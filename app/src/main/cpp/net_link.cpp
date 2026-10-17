// See net_link.h for the architecture.
//
// Threading: everything the on*() hooks touch is an atomic or a short critical
// section, so the mixer task can call them without ever blocking on the
// network. The socket itself is owned by a single sender thread, which also
// produces the periodic JSON snapshot, so no caller ever waits on sendto().

#include "net_link.h"

#include <android/log.h>
#include <jni.h>

#include <arpa/inet.h>
#include <netdb.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <unistd.h>

#include <atomic>
#include <algorithm>
#include <chrono>
#include <condition_variable>
#include <cstdio>
#include <cstring>
#include <deque>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#define LOG_TAG "EdgeTXNet"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// How many datagrams may wait for the sender. A CRSF frame is ~26 bytes and
// goes out every few ms; the JSON snapshot is a couple of hundred bytes. This
// is over a second of backlog - enough to ride out a scheduling hitch, small
// enough that a host that stopped reading cannot grow the heap.
constexpr size_t kMaxQueue = 512;

// The control state the snapshot is built from. Written by the hooks, read by
// the sender thread.
constexpr uint8_t kAnalogCount = 8;
constexpr uint8_t kSwitchCount = 10;

std::atomic<uint16_t> g_analog[kAnalogCount];
std::atomic<int8_t> g_switch[kSwitchCount];
std::atomic<uint32_t> g_keysDown{0};
std::atomic<int32_t> g_rotaryTotal{0};

// ---------------------------------------------------------------- config ---

std::mutex g_configMutex;
std::string g_host;
uint16_t g_port = 0;
uint16_t g_crsfPort = 0;
std::atomic<int> g_mode{0};
std::atomic<bool> g_enabled{false};
std::atomic<int> g_rateHz{50};

// ---------------------------------------------------------------- sender ---

std::thread g_thread;
std::atomic<bool> g_stop{false};

// A datagram waiting for the sender thread, tagged with the stream it belongs
// to: the two streams are separate transports on separate ports, so a frame that
// goes out on one must never be written to the other. `stampUs` is when the
// value it carries became available - see the latency block above.
struct QueuedFrame {
    std::vector<uint8_t> bytes;
    bool crsf;
    uint64_t stampUs;
};

std::mutex g_queueMutex;
std::condition_variable g_queueCv;
std::deque<QueuedFrame> g_queue;

std::atomic<uint32_t> g_sentFrames{0};
std::atomic<uint32_t> g_droppedFrames{0};
std::atomic<uint32_t> g_sentBytes{0};

// The sockets are opened in init() and closed in stop(). They are read from the
// hook paths (onModuleTx, the event hooks) as a "is there anything to send to"
// check, so they are atomic: a plain int would be a data race against init().
std::atomic<int> g_jsonSocket{-1};
std::atomic<int> g_crsfSocket{-1};
sockaddr_in g_jsonAddr{};
sockaddr_in g_crsfAddr{};

// Wall-clock milliseconds since this module started, for the "t" field.
const std::chrono::steady_clock::time_point g_startedAt = std::chrono::steady_clock::now();

uint64_t now_ms() {
    using namespace std::chrono;
    return static_cast<uint64_t>(
        duration_cast<milliseconds>(steady_clock::now() - g_startedAt).count());
}

// Microseconds since this module started: the resolution the latency figures
// need (a datagram's whole device-side hop is well under a millisecond when
// nothing is wrong).
uint64_t now_us() {
    using namespace std::chrono;
    return static_cast<uint64_t>(
        duration_cast<microseconds>(steady_clock::now() - g_startedAt).count());
}

// ------------------------------------------------------------- latency ------
//
// What the settings page shows as "send latency". Every datagram is stamped
// twice - when the value it carries became available, and when sendto()
// returned - and the difference is the whole hop that happens on this device:
// the wait in the queue plus the kernel's own work. Air time, whatever is in
// between and the host are on top of that and are not measurable from here
// (that is what the echo probe is for).
//
// The two streams are kept apart because they carry different things:
//
//   channel  the module frame, i.e. the 16 *mixed* channels, mirrored from the
//            firmware's own driver. Stamped in onModuleTx(), so the number is
//            "the mixer produced a frame -> the bytes are on the wire".
//   control  the raw-control JSON snapshot. Stamped at the stick/dial change it
//            carries, so the number is "a stick moved -> the host has been
//            told", which is the one to compare with the RF module's own
//            stick-to-frame figure.
constexpr size_t kLatencySamples = 256;

struct LatencyRing {
    uint32_t us[kLatencySamples]{};   // microseconds
    uint32_t maxUs = 0;
    uint32_t at = 0;
    uint32_t count = 0;
};

std::mutex g_latencyMutex;
LatencyRing g_channelLatency;   // the module stream
LatencyRing g_controlLatency;   // the control stream
LatencyRing g_sendLatency;      // sendto() on its own

void recordLatency(LatencyRing* ring, uint32_t us) {
    std::lock_guard<std::mutex> lock(g_latencyMutex);
    ring->us[ring->at] = us;
    ring->at = (ring->at + 1) % kLatencySamples;
    if (ring->count < kLatencySamples) ++ring->count;
    if (us > ring->maxUs) ring->maxUs = us;
}

// One percentile, in microseconds, over the samples collected so far. Copies
// the ring and sorts it, so it is called from the status call (a couple of
// times a second) and never from the send path. Caller holds g_latencyMutex.
uint32_t percentileLocked(const LatencyRing& ring, int percent) {
    if (ring.count == 0) return 0;
    std::vector<uint32_t> values(ring.us, ring.us + ring.count);
    std::sort(values.begin(), values.end());
    const size_t index = (values.size() - 1) * static_cast<size_t>(percent) / 100;
    return values[index];
}

// When a raw control last changed, for the control-stream number. A snapshot
// taken while nothing has moved carries stale data and must not be counted, or
// an idle radio would report a latency of seconds.
std::atomic<uint64_t> g_lastControlChangeUs{0};

// ---------------------------------------------------------------- echo -----
//
// The only thing here that reads from the socket. A "PROBE <seq> <ms>" datagram
// goes out on the control socket (or the module one, when only that is
// configured) twice a second; a host that echoes sends the bytes straight back,
// and the difference between the millisecond field - this device's own clock, so
// no clock sync is involved - and now is the round trip.
constexpr uint32_t kProbeIntervalMs = 500;

std::atomic<bool> g_echoEnabled{false};
std::atomic<uint32_t> g_probeSeq{0};

std::mutex g_rttMutex;
uint32_t g_rttLastUs = 0;
uint32_t g_rttMinUs = 0;
uint32_t g_rttMaxUs = 0;
uint64_t g_rttSumUs = 0;
uint32_t g_rttReplies = 0;

void recordRtt(uint32_t us) {
    std::lock_guard<std::mutex> lock(g_rttMutex);
    g_rttLastUs = us;
    if (g_rttReplies == 0 || us < g_rttMinUs) g_rttMinUs = us;
    if (us > g_rttMaxUs) g_rttMaxUs = us;
    g_rttSumUs += us;
    ++g_rttReplies;
}

bool jsonEnabled() { return (g_mode.load(std::memory_order_relaxed) & net_link::kJson) != 0; }
bool crsfEnabled() { return (g_mode.load(std::memory_order_relaxed) & net_link::kCrsf) != 0; }

// Queues one datagram. Drops the oldest when the queue is full.
void enqueue(std::vector<uint8_t>&& frame, bool crsf) {
    if (frame.empty()) return;
    {
        const uint64_t stampUs = now_us();
        std::lock_guard<std::mutex> lock(g_queueMutex);
        if (g_queue.size() >= kMaxQueue) {
            g_queue.pop_front();
            g_droppedFrames.fetch_add(1, std::memory_order_relaxed);
        }
        g_queue.push_back(QueuedFrame{std::move(frame), crsf, stampUs});
    }
    g_queueCv.notify_one();
}

// A line for the JSON stream, newline terminated.
void enqueueJson(const std::string& line) {
    std::vector<uint8_t> frame(line.begin(), line.end());
    enqueue(std::move(frame), false);
}

void sendOn(int socketFd, const sockaddr_in& destination, const std::vector<uint8_t>& frame) {
    if (socketFd < 0) return;
    const uint64_t before = now_us();
    const ssize_t n = sendto(socketFd, frame.data(), frame.size(), 0,
                             reinterpret_cast<const sockaddr*>(&destination), sizeof(destination));
    const uint64_t after = now_us();
    recordLatency(&g_sendLatency, static_cast<uint32_t>(after - before));
    if (n > 0) {
        g_sentFrames.fetch_add(1, std::memory_order_relaxed);
        g_sentBytes.fetch_add(static_cast<uint32_t>(n), std::memory_order_relaxed);
    }
}

// Sends one queued datagram on the stream it belongs to. The sockets are only
// ever touched here, on the sender thread.
void route(const QueuedFrame& frame) {
    if (frame.crsf) {
        sendOn(g_crsfSocket.load(), g_crsfAddr, frame.bytes);
        // This is the number the settings page calls the channel latency: the
        // firmware's own driver produced the 16 mixed channels a moment ago
        // (onModuleTx) and they are on the wire now.
        const uint64_t now = now_us();
        if (now >= frame.stampUs) {
            recordLatency(&g_channelLatency, static_cast<uint32_t>(now - frame.stampUs));
        }
    } else {
        sendOn(g_jsonSocket.load(), g_jsonAddr, frame.bytes);
    }
}

// The periodic whole-state line: every stick, dial, slider, switch and
// currently held key.
void sendSnapshot() {
    char line[512];
    int at = std::snprintf(line, sizeof(line), "{\"t\":%llu,\"st\":[",
                           static_cast<unsigned long long>(now_ms()));
    for (uint8_t i = 0; i < kAnalogCount && at > 0 && at < static_cast<int>(sizeof(line)); ++i) {
        at += std::snprintf(line + at, sizeof(line) - static_cast<size_t>(at), "%s%u",
                            i ? "," : "", static_cast<unsigned>(g_analog[i].load()));
    }
    if (at > 0 && at < static_cast<int>(sizeof(line))) {
        at += std::snprintf(line + at, sizeof(line) - static_cast<size_t>(at), "],\"sw\":[");
    }
    for (uint8_t i = 0; i < kSwitchCount && at > 0 && at < static_cast<int>(sizeof(line)); ++i) {
        at += std::snprintf(line + at, sizeof(line) - static_cast<size_t>(at), "%s%d",
                            i ? "," : "", static_cast<int>(g_switch[i].load()));
    }
    if (at > 0 && at < static_cast<int>(sizeof(line))) {
        std::snprintf(line + at, sizeof(line) - static_cast<size_t>(at), "],\"k\":%u,\"rot\":%d}\n",
                      static_cast<unsigned>(g_keysDown.load()),
                      static_cast<int>(g_rotaryTotal.load()));
    }
    enqueueJson(line);

    // "A stick moved -> the host has been told": the snapshot carries whatever
    // the hooks last pushed, so the age of that value is the whole point. A
    // radio nobody is touching repeats stale data forever, which would read as
    // a latency of seconds, so anything older than a quarter of a second is
    // left out of the sample set.
    const uint64_t now = now_us();
    const uint64_t changedUs = g_lastControlChangeUs.load(std::memory_order_relaxed);
    if (changedUs != 0 && now >= changedUs && now - changedUs <= 250000ull) {
        recordLatency(&g_controlLatency, static_cast<uint32_t>(now - changedUs));
    }
}

bool resolve(const std::string& host, uint16_t port, sockaddr_in* out) {
    if (host.empty() || port == 0 || out == nullptr) return false;

    addrinfo hints{};
    hints.ai_family = AF_INET;
    hints.ai_socktype = SOCK_DGRAM;

    addrinfo* result = nullptr;
    const std::string portText = std::to_string(port);
    const int rc = getaddrinfo(host.c_str(), portText.c_str(), &hints, &result);
    if (rc != 0 || result == nullptr) {
        LOGE("cannot resolve %s: %s", host.c_str(), gai_strerror(rc));
        return false;
    }

    std::memcpy(out, result->ai_addr, sizeof(sockaddr_in));
    out->sin_port = htons(port);
    freeaddrinfo(result);
    return true;
}

// Routes everything queued right now. Used after building a snapshot, so it goes
// out at once instead of waiting for the next pass - waiting would add a whole
// interval to that stream's latency.
void flushNow() {
    std::deque<QueuedFrame> batch;
    {
        std::lock_guard<std::mutex> lock(g_queueMutex);
        batch.swap(g_queue);
    }
    for (const QueuedFrame& frame : batch) route(frame);
}

// The probe the host is asked to bounce back. The payload carries this device's
// own microsecond clock, so the reply needs no agreement between the two clocks
// at all: it measures the round trip, not the offset.
void sendProbe() {
    int fd = g_jsonSocket.load();
    const sockaddr_in* destination = &g_jsonAddr;
    if (fd < 0) {
        fd = g_crsfSocket.load();
        destination = &g_crsfAddr;
    }
    if (fd < 0) return;

    char line[64];
    const int len = std::snprintf(line, sizeof(line), "PROBE %u %llu\n",
                                  static_cast<unsigned>(g_probeSeq.fetch_add(1)),
                                  static_cast<unsigned long long>(now_us()));
    if (len <= 0) return;
    sendto(fd, line, static_cast<size_t>(len), 0,
           reinterpret_cast<const sockaddr*>(destination), sizeof(*destination));
}

// Reads back whatever arrived on the socket. Non-blocking and called once per
// pass (10 ms at 100 Hz): the sender thread must never wait on anything, and the
// reply to a probe is simply still in the socket buffer when the next pass gets
// there.
void drainReplies() {
    for (int i = 0; i < 16; ++i) {
        int fd = g_jsonSocket.load();
        if (fd < 0) fd = g_crsfSocket.load();
        if (fd < 0) return;

        char buffer[256];
        sockaddr_in from{};
        socklen_t fromLen = sizeof(from);
        const ssize_t n = recvfrom(fd, buffer, sizeof(buffer) - 1, MSG_DONTWAIT,
                                   reinterpret_cast<sockaddr*>(&from), &fromLen);
        if (n <= 0) return;
        buffer[n] = '\0';

        unsigned seq = 0;
        unsigned long long sentUs = 0;
        if (std::sscanf(buffer, "PROBE %u %llu", &seq, &sentUs) != 2) continue;

        const uint64_t now = now_us();
        if (now < sentUs) continue;
        const uint64_t rtt = now - sentUs;
        if (rtt > 60000000ull) continue;   // a minute: a stale reply, not a round trip
        recordRtt(static_cast<uint32_t>(rtt));
    }
}

void senderLoop() {
    uint32_t nextSnapshotAt = 0;
    uint32_t nextProbeAt = 0;
    if (jsonEnabled()) {
        const int rate = g_rateHz.load(std::memory_order_relaxed);
        if (rate > 0) sendSnapshot();
    }

    while (!g_stop.load(std::memory_order_relaxed)) {
        const int rate = g_rateHz.load(std::memory_order_relaxed);
        const auto interval = rate > 0 ? std::chrono::milliseconds(1000 / rate)
                                       : std::chrono::milliseconds(200);

        std::deque<QueuedFrame> batch;
        {
            std::unique_lock<std::mutex> lock(g_queueMutex);
            g_queueCv.wait_for(lock, interval, [] { return !g_queue.empty() || g_stop.load(); });
            batch.swap(g_queue);
        }

        for (const QueuedFrame& frame : batch) route(frame);

        uint32_t now = static_cast<uint32_t>(now_ms());
        if (jsonEnabled() && now >= nextSnapshotAt) {
            const uint32_t period = rate > 0 ? static_cast<uint32_t>(1000 / rate) : 0;
            nextSnapshotAt = now + (period ? period : 1000);
            sendSnapshot();
            flushNow();
        }

        if (g_echoEnabled.load(std::memory_order_relaxed)) {
            drainReplies();
            if (now >= nextProbeAt) {
                nextProbeAt = now + kProbeIntervalMs;
                sendProbe();
            }
        }
    }
}

// Sends the batch that is still queued after the thread was asked to stop, so
// the last events are not silently dropped on shutdown.
void flushQueue() {
    flushNow();
}

void closeSockets() {
    const int jsonFd = g_jsonSocket.exchange(-1);
    if (jsonFd >= 0) close(jsonFd);

    const int crsfFd = g_crsfSocket.exchange(-1);
    if (crsfFd >= 0) close(crsfFd);
}

}  // namespace

namespace net_link {

void configure(bool enabled, const char* host, uint16_t port, uint16_t crsfPort, int mode,
               int rateHz, bool echo) {
    std::lock_guard<std::mutex> lock(g_configMutex);
    g_host = host != nullptr ? host : "";
    g_port = port;
    g_crsfPort = crsfPort;
    g_mode.store(mode, std::memory_order_relaxed);
    g_rateHz.store(rateHz > 0 ? rateHz : 50, std::memory_order_relaxed);
    g_echoEnabled.store(echo, std::memory_order_relaxed);
    g_enabled.store(enabled, std::memory_order_relaxed);
}

void init() {
    if (!g_enabled.load(std::memory_order_relaxed)) {
        LOGI("net: disabled (no config, or enabled=false)");
        return;
    }
    if (g_thread.joinable()) return;   // idempotent

    std::string host;
    uint16_t port = 0;
    uint16_t crsfPort = 0;
    {
        std::lock_guard<std::mutex> lock(g_configMutex);
        host = g_host;
        port = g_port;
        crsfPort = g_crsfPort;
    }

    if (jsonEnabled()) {
        if (resolve(host, port, &g_jsonAddr)) {
            const int fd = socket(AF_INET, SOCK_DGRAM, 0);
            if (fd < 0) {
                LOGW("net: no socket for the control stream");
            } else {
                g_jsonSocket.store(fd);
            }
        }
    }
    if (crsfEnabled()) {
        if (resolve(host, crsfPort, &g_crsfAddr)) {
            const int fd = socket(AF_INET, SOCK_DGRAM, 0);
            if (fd < 0) {
                LOGW("net: no socket for the module stream");
            } else {
                g_crsfSocket.store(fd);
            }
        }
    }

    if (g_jsonSocket.load() < 0 && g_crsfSocket.load() < 0) {
        LOGE("net: nothing to send to (%s:%u / %s:%u)", host.c_str(), port, host.c_str(),
             crsfPort);
        return;
    }

    // Seed the snapshot with centred sticks: until a channel is pushed for the first
    // time the array would otherwise read 0 (stick hard over), and the very first
    // snapshot would look like a full-deflection input. The firmware's own channels are
    // untouched - this is only what goes out on the wire.
    for (uint8_t i = 0; i < kAnalogCount; ++i) {
        g_analog[i].store(2048, std::memory_order_relaxed);
    }

    g_stop.store(false, std::memory_order_relaxed);

    // A new destination means the old numbers describe a link that no longer
    // exists, and a link that was just started has not had time to collect any.
    {
        std::lock_guard<std::mutex> lock(g_latencyMutex);
        g_channelLatency = LatencyRing{};
        g_controlLatency = LatencyRing{};
        g_sendLatency = LatencyRing{};
    }
    {
        std::lock_guard<std::mutex> lock(g_rttMutex);
        g_rttLastUs = 0;
        g_rttMinUs = 0;
        g_rttMaxUs = 0;
        g_rttSumUs = 0;
        g_rttReplies = 0;
    }
    g_lastControlChangeUs.store(0, std::memory_order_relaxed);

    g_thread = std::thread(senderLoop);
    LOGI("net: sending %s to %s (controls :%u, module :%u), %d Hz%s",
         (jsonEnabled() && crsfEnabled()) ? "controls + module frames"
                                          : (crsfEnabled() ? "module frames" : "controls"),
         host.c_str(), port, crsfPort, g_rateHz.load(),
         g_echoEnabled.load(std::memory_order_relaxed)
                 ? ", echo probe on (round-trip to the host)"
                 : "");
}

void stop() {
    if (!g_thread.joinable()) {
        closeSockets();
        return;
    }
    g_stop.store(true, std::memory_order_relaxed);
    g_queueCv.notify_all();
    g_thread.join();
    flushQueue();
    closeSockets();
    LOGI("net: stopped (sent %u frame(s), %u byte(s), dropped %u)",
         g_sentFrames.load(), g_sentBytes.load(), g_droppedFrames.load());
}

void onAnalog(uint8_t channel, uint16_t value) {
    if (channel >= kAnalogCount) return;
    const uint16_t previous = g_analog[channel].exchange(value, std::memory_order_relaxed);
    if (previous == value) return;
    g_lastControlChangeUs.store(now_us(), std::memory_order_relaxed);
}

void onSwitch(uint8_t index, int8_t state) {
    if (index >= kSwitchCount) return;
    const int8_t previous = g_switch[index].exchange(state, std::memory_order_relaxed);
    if (previous == state) return;
    g_lastControlChangeUs.store(now_us(), std::memory_order_relaxed);
    if (!jsonEnabled() || g_jsonSocket.load() < 0) return;

    char line[96];
    std::snprintf(line, sizeof(line), "{\"t\":%llu,\"ev\":\"switch\",\"i\":%u,\"v\":%d}\n",
                  static_cast<unsigned long long>(now_ms()), static_cast<unsigned>(index),
                  static_cast<int>(state));
    enqueueJson(line);
}

void onKey(uint8_t key, bool down) {
    if (key >= 32) return;
    const uint32_t bit = 1u << key;
    if (down) {
        g_keysDown.fetch_or(bit, std::memory_order_relaxed);
    } else {
        g_keysDown.fetch_and(~bit, std::memory_order_relaxed);
    }
    g_lastControlChangeUs.store(now_us(), std::memory_order_relaxed);
    if (!jsonEnabled() || g_jsonSocket.load() < 0) return;

    char line[96];
    std::snprintf(line, sizeof(line), "{\"t\":%llu,\"ev\":\"key\",\"k\":%u,\"d\":%d}\n",
                  static_cast<unsigned long long>(now_ms()), static_cast<unsigned>(key),
                  down ? 1 : 0);
    enqueueJson(line);
}

void onRotary(int32_t steps) {
    g_rotaryTotal.fetch_add(steps, std::memory_order_relaxed);
    g_lastControlChangeUs.store(now_us(), std::memory_order_relaxed);
    if (!jsonEnabled() || g_jsonSocket.load() < 0) return;

    char line[96];
    std::snprintf(line, sizeof(line), "{\"t\":%llu,\"ev\":\"rotary\",\"v\":%d}\n",
                  static_cast<unsigned long long>(now_ms()), static_cast<int>(steps));
    enqueueJson(line);
}

void onNamedEvent(const char* name) {
    if (name == nullptr || name[0] == '\0') return;
    if (!jsonEnabled() || g_jsonSocket.load() < 0) return;

    char line[128];
    std::snprintf(line, sizeof(line), "{\"t\":%llu,\"ev\":\"%s\"}\n",
                  static_cast<unsigned long long>(now_ms()), name);
    enqueueJson(line);
}

void onModuleTx(const uint8_t* data, uint32_t len) {
    if (data == nullptr || len == 0) return;
    if (!crsfEnabled() || g_crsfSocket.load() < 0) return;
    enqueue(std::vector<uint8_t>(data, data + len), true);
}

void stats(uint32_t* sentFrames, uint32_t* droppedFrames, uint32_t* sentBytes, bool* enabled,
           int* mode) {
    if (sentFrames != nullptr) *sentFrames = g_sentFrames.load();
    if (droppedFrames != nullptr) *droppedFrames = g_droppedFrames.load();
    if (sentBytes != nullptr) *sentBytes = g_sentBytes.load();
    if (enabled != nullptr) *enabled = g_enabled.load();
    if (mode != nullptr) *mode = g_mode.load();
}

void latency(uint32_t* out10) {
    if (out10 == nullptr) return;
    std::lock_guard<std::mutex> lock(g_latencyMutex);
    out10[0] = percentileLocked(g_channelLatency, 50);
    out10[1] = percentileLocked(g_channelLatency, 95);
    out10[2] = g_channelLatency.maxUs;
    out10[3] = percentileLocked(g_controlLatency, 50);
    out10[4] = percentileLocked(g_controlLatency, 95);
    out10[5] = g_controlLatency.maxUs;
    out10[6] = percentileLocked(g_sendLatency, 50);
    out10[7] = percentileLocked(g_sendLatency, 95);
    out10[8] = g_sendLatency.maxUs;
    out10[9] = g_channelLatency.count;
}

void roundTrip(uint32_t* last, uint32_t* minimum, uint32_t* average, uint32_t* maximum,
               uint32_t* replies) {
    std::lock_guard<std::mutex> lock(g_rttMutex);
    if (last != nullptr) *last = g_rttLastUs;
    if (minimum != nullptr) *minimum = g_rttMinUs;
    if (average != nullptr) {
        *average = g_rttReplies > 0 ? static_cast<uint32_t>(g_rttSumUs / g_rttReplies) : 0;
    }
    if (maximum != nullptr) *maximum = g_rttMaxUs;
    if (replies != nullptr) *replies = g_rttReplies;
}

}  // namespace net_link

// --------------------------------------------------------------------- JNI --
// Called from RcNetLink.java.

extern "C" JNIEXPORT void JNICALL
Java_com_edgetx_droidui_RcNetLink_nativeConfigure(JNIEnv* env, jclass, jboolean enabled,
                                                  jstring host, jint port, jint crsfPort,
                                                  jint mode, jint rateHz, jboolean echo) {
    const char* text = nullptr;
    if (host != nullptr) text = env->GetStringUTFChars(host, nullptr);
    net_link::configure(enabled != JNI_FALSE, text, static_cast<uint16_t>(port),
                        static_cast<uint16_t>(crsfPort), mode, rateHz, echo != JNI_FALSE);
    if (text != nullptr) env->ReleaseStringUTFChars(host, text);
}

// The settings gear changed the destination while the link was up. configure() alone only
// stores the new values - the sockets and the resolved address are fixed by init() - so this
// stops the link and starts it again on the new ones. stop() is idempotent, and init() starts
// nothing while the configuration is disabled, which is how the gear turns the output off.
extern "C" JNIEXPORT void JNICALL
Java_com_edgetx_droidui_RcNetLink_nativeReconfigure(JNIEnv* env, jclass, jboolean enabled,
                                                    jstring host, jint port, jint crsfPort,
                                                    jint mode, jint rateHz, jboolean echo) {
    const char* text = nullptr;
    if (host != nullptr) text = env->GetStringUTFChars(host, nullptr);
    net_link::stop();
    net_link::configure(enabled != JNI_FALSE, text, static_cast<uint16_t>(port),
                        static_cast<uint16_t>(crsfPort), mode, rateHz, echo != JNI_FALSE);
    if (text != nullptr) env->ReleaseStringUTFChars(host, text);
    net_link::init();
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_edgetx_droidui_RcNetLink_nativeStats(JNIEnv* env, jclass) {
    uint32_t sent = 0, dropped = 0, bytes = 0;
    bool enabled = false;
    int mode = 0;
    net_link::stats(&sent, &dropped, &bytes, &enabled, &mode);

    const jint values[5] = {
        static_cast<jint>(sent),
        static_cast<jint>(dropped),
        static_cast<jint>(bytes),
        enabled ? 1 : 0,
        static_cast<jint>(mode),
    };
    jintArray out = env->NewIntArray(5);
    if (out != nullptr) {
        env->SetIntArrayRegion(out, 0, 5, values);
    }
    return out;
}

// { channel p50, channel p95, channel max, control p50, control p95, control max,
//   sendto p50, sendto p95, sendto max, channel samples } - all microseconds except the last.
extern "C" JNIEXPORT jintArray JNICALL
Java_com_edgetx_droidui_RcNetLink_nativeLatency(JNIEnv* env, jclass) {
    uint32_t values[10] = {0};
    net_link::latency(values);

    jint out[10];
    for (int i = 0; i < 10; ++i) {
        // A microsecond figure never reaches the top of the range; a saturated
        // cast keeps a nonsense value from turning negative on the Java side.
        out[i] = values[i] > 0x7fffffffu ? 0x7fffffff : static_cast<jint>(values[i]);
    }
    jintArray array = env->NewIntArray(10);
    if (array != nullptr) {
        env->SetIntArrayRegion(array, 0, 10, out);
    }
    return array;
}

// { last, min, average, max, replies } - microseconds, from the echo probe.
extern "C" JNIEXPORT jintArray JNICALL
Java_com_edgetx_droidui_RcNetLink_nativeRoundTrip(JNIEnv* env, jclass) {
    uint32_t last = 0, minimum = 0, average = 0, maximum = 0, replies = 0;
    net_link::roundTrip(&last, &minimum, &average, &maximum, &replies);

    const jint values[5] = {
        static_cast<jint>(last),
        static_cast<jint>(minimum),
        static_cast<jint>(average),
        static_cast<jint>(maximum),
        static_cast<jint>(replies),
    };
    jintArray out = env->NewIntArray(5);
    if (out != nullptr) {
        env->SetIntArrayRegion(out, 0, 5, values);
    }
    return out;
}
