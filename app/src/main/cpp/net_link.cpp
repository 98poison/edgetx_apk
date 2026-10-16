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
// goes out on one must never be written to the other.
struct QueuedFrame {
    std::vector<uint8_t> bytes;
    bool crsf;
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

bool jsonEnabled() { return (g_mode.load(std::memory_order_relaxed) & net_link::kJson) != 0; }
bool crsfEnabled() { return (g_mode.load(std::memory_order_relaxed) & net_link::kCrsf) != 0; }

// Queues one datagram. Drops the oldest when the queue is full.
void enqueue(std::vector<uint8_t>&& frame, bool crsf) {
    if (frame.empty()) return;
    {
        std::lock_guard<std::mutex> lock(g_queueMutex);
        if (g_queue.size() >= kMaxQueue) {
            g_queue.pop_front();
            g_droppedFrames.fetch_add(1, std::memory_order_relaxed);
        }
        g_queue.push_back(QueuedFrame{std::move(frame), crsf});
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
    const ssize_t n = sendto(socketFd, frame.data(), frame.size(), 0,
                             reinterpret_cast<const sockaddr*>(&destination), sizeof(destination));
    if (n > 0) {
        g_sentFrames.fetch_add(1, std::memory_order_relaxed);
        g_sentBytes.fetch_add(static_cast<uint32_t>(n), std::memory_order_relaxed);
    }
}

// Sends one queued datagram on the stream it belongs to. The sockets are only
// ever touched here, on the sender thread.
void route(const QueuedFrame& frame) {
    if (frame.crsf)
        sendOn(g_crsfSocket.load(), g_crsfAddr, frame.bytes);
    else
        sendOn(g_jsonSocket.load(), g_jsonAddr, frame.bytes);
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

void senderLoop() {
    uint32_t nextSnapshotAt = 0;
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

        if (jsonEnabled()) {
            const uint32_t now = static_cast<uint32_t>(now_ms());
            if (now >= nextSnapshotAt) {
                const uint32_t period = rate > 0 ? static_cast<uint32_t>(1000 / rate) : 0;
                nextSnapshotAt = now + (period ? period : 1000);
                sendSnapshot();
            }
        }
    }
}

// Sends the batch that is still queued after the thread was asked to stop, so
// the last events are not silently dropped on shutdown.
void flushQueue() {
    std::deque<QueuedFrame> batch;
    {
        std::lock_guard<std::mutex> lock(g_queueMutex);
        batch.swap(g_queue);
    }
    for (const QueuedFrame& frame : batch) route(frame);
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
               int rateHz) {
    std::lock_guard<std::mutex> lock(g_configMutex);
    g_host = host != nullptr ? host : "";
    g_port = port;
    g_crsfPort = crsfPort;
    g_mode.store(mode, std::memory_order_relaxed);
    g_rateHz.store(rateHz > 0 ? rateHz : 50, std::memory_order_relaxed);
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
    g_thread = std::thread(senderLoop);
    LOGI("net: sending %s to %s (controls :%u, module :%u), %d Hz",
         (jsonEnabled() && crsfEnabled()) ? "controls + module frames"
                                          : (crsfEnabled() ? "module frames" : "controls"),
         host.c_str(), port, crsfPort, g_rateHz.load());
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
    if (channel < kAnalogCount) g_analog[channel].store(value, std::memory_order_relaxed);
}

void onSwitch(uint8_t index, int8_t state) {
    if (index >= kSwitchCount) return;
    const int8_t previous = g_switch[index].exchange(state, std::memory_order_relaxed);
    if (previous == state) return;
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
    if (!jsonEnabled() || g_jsonSocket.load() < 0) return;

    char line[96];
    std::snprintf(line, sizeof(line), "{\"t\":%llu,\"ev\":\"key\",\"k\":%u,\"d\":%d}\n",
                  static_cast<unsigned long long>(now_ms()), static_cast<unsigned>(key),
                  down ? 1 : 0);
    enqueueJson(line);
}

void onRotary(int32_t steps) {
    g_rotaryTotal.fetch_add(steps, std::memory_order_relaxed);
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

}  // namespace net_link

// --------------------------------------------------------------------- JNI --
// Called from RcNetLink.java.

extern "C" JNIEXPORT void JNICALL
Java_com_edgetx_droidui_RcNetLink_nativeConfigure(JNIEnv* env, jclass, jboolean enabled,
                                                  jstring host, jint port, jint crsfPort,
                                                  jint mode, jint rateHz) {
    const char* text = nullptr;
    if (host != nullptr) text = env->GetStringUTFChars(host, nullptr);
    net_link::configure(enabled != JNI_FALSE, text, static_cast<uint16_t>(port),
                        static_cast<uint16_t>(crsfPort), mode, rateHz);
    if (text != nullptr) env->ReleaseStringUTFChars(host, text);
}

// The settings gear changed the destination while the link was up. configure() alone only
// stores the new values - the sockets and the resolved address are fixed by init() - so this
// stops the link and starts it again on the new ones. stop() is idempotent, and init() starts
// nothing while the configuration is disabled, which is how the gear turns the output off.
extern "C" JNIEXPORT void JNICALL
Java_com_edgetx_droidui_RcNetLink_nativeReconfigure(JNIEnv* env, jclass, jboolean enabled,
                                                    jstring host, jint port, jint crsfPort,
                                                    jint mode, jint rateHz) {
    const char* text = nullptr;
    if (host != nullptr) text = env->GetStringUTFChars(host, nullptr);
    net_link::stop();
    net_link::configure(enabled != JNI_FALSE, text, static_cast<uint16_t>(port),
                        static_cast<uint16_t>(crsfPort), mode, rateHz);
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
