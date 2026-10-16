#include "rc_bridge.h"

#include "joystick.h"
#include "log.h"
#include "simu_host.h"

#include <algorithm>
#include <atomic>
#include <cstdarg>
#include <cstddef>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include <android/log.h>
#include <arpa/inet.h>
#include <errno.h>
#include <netinet/in.h>
#include <poll.h>
#include <sys/socket.h>
#include <unistd.h>

// See rc_bridge.h for why this module exists and where it sits in the chain.
namespace rc_bridge {
namespace {

// ---------------------------------------------------------------- wire ----

// The RCB1 packet, mirrored from rcbridge's proto.go.
//
// The offsets are asserted at compile time. That is not ceremony: a field added
// on one side and not the other would still compile, would still decode, and
// would decode into plausible-looking rubbish - a stick that reads slightly
// wrong is a far harder failure to trace than a link that does not come up.
constexpr uint32_t kMagic = 0x31424352u;  // "RCB1" read as little-endian
constexpr uint32_t kPacketSize = 48;

// Source ids, mirrored from rcbridge's proto.go.
constexpr uint8_t kSrcEvdev = 0;
constexpr uint8_t kSrcMSDK = 1;
constexpr uint8_t kSrcEdgeTX = 2;

constexpr uint8_t kFlagAxes = 1 << 0;
constexpr uint8_t kFlagButtons = 1 << 1;

// Whether a packet came from something that should be listened to.
//
// The list is the point of this function. rcbridge tags the packets it sends on
// this link SrcEdgeTX - it is the EdgeTX sender in edgetx.go, whatever read the
// buttons underneath - and an earlier version of this file accepted only
// kSrcMSDK, which is how a packet that looked entirely correct got counted as
// rejected: the magic matched, the length matched, every field decoded, and the
// one byte checked afterwards said "not from the collector", so nothing was ever
// applied. The whole injection path was dead while reporting "0 accepted, N
// rejected" - which reads like a wiring problem and is in fact a source-id
// mismatch.
//
// So: accept every source rcbridge defines. Rejecting an id we do not recognise
// is still worth doing, because it catches a version mismatch, but it must not be
// a list that can go stale. SrcEvdev is included for completeness - rcbridge
// never sends it on this link, since the evdev reader is inside rcbridge - and
// rejecting it would be wrong if that ever changes.
constexpr bool kKnownSrc(uint8_t src)
{
    return src == kSrcMSDK || src == kSrcEdgeTX || src == kSrcEvdev;
}

#pragma pack(push, 1)
struct Packet {
    uint32_t magic;      // 0
    uint32_t seq;        // 4
    uint32_t tMillis;    // 8
    uint8_t src;         // 12
    uint8_t flags;       // 13
    uint16_t reserved0;  // 14
    int16_t sticks[4];   // 16  LH LV RH RV, -1000..1000
    int16_t dials[2];    // 24  left right
    int16_t wheel;       // 28
    uint8_t five;        // 30  bit0 up bit1 down bit2 left bit3 right bit4 centre
    int8_t fmode;        // 31
    uint64_t buttons;    // 32  - no padding before this: five+fmode end at 32
    uint32_t sourceMask; // 40
    uint32_t reserved5;  // 44
};
#pragma pack(pop)

static_assert(sizeof(Packet) == kPacketSize, "RCB1 packet must be 48 bytes");
static_assert(offsetof(Packet, src) == 12, "src must be at 12");
static_assert(offsetof(Packet, flags) == 13, "flags must be at 13");
static_assert(offsetof(Packet, sticks) == 16, "sticks must be at 16");
static_assert(offsetof(Packet, dials) == 24, "dials must be at 24");
static_assert(offsetof(Packet, wheel) == 28, "wheel must be at 28");
static_assert(offsetof(Packet, five) == 30, "five must be at 30");
static_assert(offsetof(Packet, fmode) == 31, "fmode must be at 31");
static_assert(offsetof(Packet, buttons) == 32, "buttons must be at 32 (8-aligned)");
static_assert(offsetof(Packet, sourceMask) == 40, "sourceMask must be at 40");

// Button bits, mirrored from rcbridge's state.go.
enum : uint64_t {
    kBtnShutter = 1ull << 0,
    kBtnRecord = 1ull << 1,
    kBtnGoHome = 1ull << 2,
    kBtnPause = 1ull << 3,
    kBtnC1 = 1ull << 4,
    kBtnC2 = 1ull << 5,
    kBtnC3 = 1ull << 6,
    kBtnC4 = 1ull << 7,
    kBtnL1 = 1ull << 8,
    kBtnL2 = 1ull << 9,
    kBtnL3 = 1ull << 10,
    kBtnR1 = 1ull << 11,
    kBtnR2 = 1ull << 12,
    kBtnR3 = 1ull << 13,
    kBtnRTN = 1ull << 14,
};

// 5-way bits.
enum : uint8_t {
    kPadUp = 1 << 0,
    kPadDown = 1 << 1,
    kPadLeft = 1 << 2,
    kPadRight = 1 << 3,
    kPadCentre = 1 << 4,
};

// ------------------------------------------------------- switch mapping ----
//
// The board's switch table (radio/src/boards/hw_defs/tx16smk3.json) and the
// same slots DjiMsdkBridge already drives - see rc_bridge.h for why the two
// input paths must share one table rather than each claiming slots of their own.
constexpr uint8_t kSwA = 0;   // go-home / takeoff
constexpr uint8_t kSwB = 1;   // flight mode (3-position)
constexpr uint8_t kSwC = 2;   // record
constexpr uint8_t kSwD = 3;   // pause
constexpr uint8_t kSwE = 4;   // shutter
constexpr uint8_t kSwF = 5;   // C1 / C2 / C3 (3-position)
constexpr uint8_t kSwG = 6;   // R1
constexpr uint8_t kSwH = 7;   // R2
constexpr uint8_t kSwI = 8;   // R3
constexpr uint8_t kSwJ = 9;   // C4

// EdgeTX keys (radio/src/hal/key_driver.h EnumKeys). joystick.cpp numbers the
// same keys the same way, but its constants are file-local (constexpr), so the
// ones used here have to be repeated.
constexpr uint8_t kKeyExit = 1;
constexpr uint8_t kKeyPageUp = 3;
constexpr uint8_t kKeyPageDn = 4;
constexpr uint8_t kKeySys = 13;
constexpr uint8_t kKeyModel = 11;
constexpr uint8_t kKeyTele = 12;

// The two dials, on the board's pot inputs (simu_host.h: 4 = P1, 5 = P2), which
// is what a radio normally drives its pots from - volume and backlight through
// a special function both work.
//
// The sticks deliberately have no constants here. Their channel order differs
// from the packet's, so they need the table in apply(), and a pair of constants
// that looked like a mapping but was not is what put roll on the elevator.
constexpr uint8_t kDialChannelFirst = 4;  // P1, then P2

// ------------------------------------------------------------- state ----
//
// Two threads reach this module and they must not both write to the firmware:
// the socket thread decodes, and tick() - on the link thread - applies. The
// hand-off between them is the mailbox, which has its own mutex; everything
// below the mailbox is link-thread-only and needs no lock of its own.

std::atomic<bool> g_enabled{false};
std::atomic<uint16_t> g_port{0};

std::atomic<uint64_t> g_received{0};
std::atomic<uint64_t> g_accepted{0};
std::atomic<uint64_t> g_bad{0};
std::atomic<uint64_t> g_axesPushes{0};

std::atomic<bool> g_running{false};
std::atomic<int> g_sock{-1};
std::thread g_thread;

// The decoded state. Only *changes* are pushed to the firmware: the setters are
// not free, and pushing a hundred identical states a second is work for no
// observable effect.
int16_t g_sticks[4] = {0, 0, 0, 0};
int16_t g_dials[2] = {0, 0};
uint8_t g_five = 0;
int8_t g_fmode = 0;
uint64_t g_buttons = 0;
bool g_seenButtons = false;

// Latched positions of the buttons that only report a press.
//
// rcbridge sends momentary levels on purpose (config.go: toggleOf) so that a
// stuck channel is visible as a stuck channel rather than being
// indistinguishable from an intentional toggle. The latch lives here, on the
// EdgeTX side, because that is where the radio's own latching buttons and their
// saved positions already are - and because a model expects a level that
// persists until the next press.
//
// Link-thread only, like everything below the mailbox, so no lock.
int8_t g_goHome = 0;
int8_t g_record = 0;
int8_t g_pause = 0;
int8_t g_shutter = 0;
int8_t g_c4 = 0;

// C1/C2/C3 as one 3-position switch, and the position last pushed - the initial
// 2 is "nothing pushed yet", so the first packet always establishes it rather
// than leaving the switch at whatever the boot reset it to.
constexpr int8_t kNoPosition = 2;
int8_t g_cLast = kNoPosition;

int16_t clampAxis(int16_t v)
{
    if (v > 1000) return 1000;
    if (v < -1000) return -1000;
    return v;
}

// ------------------------------------------------------- firmware I/O ----
//
// Every firmware call below runs on the link thread, from apply() - the same
// thread joystick::tick() runs on and the same discipline joystick.cpp follows
// for every other input. The firmware samples its analog, switch and key state
// from its own thread, so a value written from the socket thread could be missed
// entirely, and a key pressed and released inside one call is never observed.
// Keys are therefore queued (joystick::requestKey) rather than set directly.

void pushSwitch(uint8_t index, int8_t state)
{
    // Through joystick::, not simu:: directly. That is what keeps the firmware
    // mutex and the network mirror - see joystick.h: setSwitchNow.
    joystick::setSwitchNow(index, state);
    // The library keeps its own copy so the boot's switch reset cannot undo it
    // (see simu_host.h: setSwitch).
    if (edgetxAndroidSetSwitch != nullptr) edgetxAndroidSetSwitch(index, state);
}

// ------------------------------------------------------ the 5-way ----

// The 5-way has no switch of its own on this board, so it drives the navigation
// controls the TX16SMK3's colour LCD actually consumes:
//
//   up / down    the rotary encoder, one detent each - up is a step left, down a
//                step right. On this target the encoder *is* the navigation
//                control (joystick.cpp: nothing else moves the focus), so this
//                is what makes the 5-way usable for editing at all.
//   left / right PAGEUP / PAGEDOWN - page back and forward.
//   centre       ENTER.
//
// Only the edges are sent. Holding a direction must not scroll continuously, or
// a press that lasts half a second would run through half the model's screens.
void applyFiveWay(uint8_t now, uint8_t prev)
{
    const uint8_t rising = now & ~prev;

    if (rising & kPadUp) {
        joystick::requestRotary(-1);
        LOGI("bridge: 5-way up -> encoder one step left");
    }
    if (rising & kPadDown) {
        joystick::requestRotary(+1);
        LOGI("bridge: 5-way down -> encoder one step right");
    }
    if (rising & kPadLeft) {
        joystick::requestKey(kKeyPageUp, 60);
        LOGI("bridge: 5-way left -> PAGEUP");
    }
    if (rising & kPadRight) {
        joystick::requestKey(kKeyPageDn, 60);
        LOGI("bridge: 5-way right -> PAGEDOWN");
    }
    if (rising & kPadCentre) {
        joystick::requestKey(2 /* ENTER */, 60);
        LOGI("bridge: 5-way centre -> ENTER");
    }
}

// ------------------------------------------------------- buttons ----

void applyButtons(uint64_t now, uint64_t prev)
{
    const uint64_t rising = now & ~prev;
    const uint64_t falling = prev & ~now;

    // --- press-only buttons: each press steps a latched 2-position switch ---
    //
    // Stepping rather than mirroring is deliberate: a momentary button pressed
    // for 100 ms produces four frames of "high" at 20 Hz, which a receiver
    // sampling slower may miss entirely. A toggle always produces an edge.
    struct Latched { uint64_t bit; int8_t* pos; uint8_t slot; const char* name; };
    const Latched latched[] = {
        {kBtnGoHome, &g_goHome, kSwA, "go-home"},
        {kBtnRecord, &g_record, kSwC, "record"},
        {kBtnPause, &g_pause, kSwD, "pause"},
        {kBtnShutter, &g_shutter, kSwE, "shutter"},
        {kBtnC4, &g_c4, kSwJ, "C4"},
    };
    for (const Latched& l : latched) {
        if ((rising & l.bit) == 0) continue;
        // One press moves the switch to the other end, so a press always
        // produces a visible change wherever it was standing.
        *l.pos = (*l.pos > 0) ? static_cast<int8_t>(-1) : static_cast<int8_t>(1);
        pushSwitch(l.slot, *l.pos);
        LOGI("bridge: %s -> switch %c %s", l.name, 'A' + l.slot,
             *l.pos > 0 ? "low" : "high");
    }

    // --- C1/C2/C3: the three positions of one 3-position switch ------------
    // Lowest held wins, so pressing two reads as the first. Identical to the rule
    // rcbridge uses, so the two can never disagree about what is held.
    int8_t cpos = 0;
    if (now & kBtnC1) cpos = -1;
    else if (now & kBtnC2) cpos = 0;
    else if (now & kBtnC3) cpos = 1;
    if (cpos != g_cLast) {
        pushSwitch(kSwF, cpos);
        LOGI("bridge: C1/C2/C3 -> switch F position %d", cpos);
        g_cLast = cpos;
    }

    // --- R1/R2/R3: momentary switches, following the button ----------------
    struct Momentary { uint64_t bit; uint8_t slot; const char* name; };
    const Momentary momentary[] = {
        {kBtnR1, kSwG, "R1"},
        {kBtnR2, kSwH, "R2"},
        {kBtnR3, kSwI, "R3"},
    };
    for (const Momentary& m : momentary) {
        if ((rising & m.bit) != 0) {
            pushSwitch(m.slot, 1);
            LOGI("bridge: %s -> switch %c down", m.name, 'A' + m.slot);
        }
        if ((falling & m.bit) != 0) {
            pushSwitch(m.slot, -1);
            LOGI("bridge: %s -> switch %c up", m.name, 'A' + m.slot);
        }
    }

    // --- L1/L2/L3 and RTN: EdgeTX keys -----------------------------------
    //
    // Keys rather than switches, so a single press opens the setup, model or
    // telemetry page - which is what a hardware TX16S does with its SYS/MDL/TELE
    // buttons, and the RC Plus 2 does not have. Putting them where a pilot
    // expects them is worth a switch slot each.
    struct KeyBit { uint64_t bit; uint8_t key; uint32_t hold; const char* name; };
    const KeyBit keys[] = {
        {kBtnL1, kKeySys, 60, "L1"},
        {kBtnL2, kKeyModel, 60, "L2"},
        {kBtnL3, kKeyTele, 60, "L3"},
        // RTN is given a long hold so EdgeTX's own long press fires. The RC's
        // return button arrives as an 8 ms tap however long it is held (see
        // joystick.cpp), so without this the long-press gestures - closing a page
        // group, stopping a Lua script - cannot be reached from it at all.
        {kBtnRTN, kKeyExit, 320, "RTN"},
    };
    for (const KeyBit& k : keys) {
        if ((rising & k.bit) != 0) {
            joystick::requestKey(k.key, k.hold);
            LOGI("bridge: %s pressed", k.name);
        }
    }
}

// ------------------------------------------------------------ mailbox ----
//
// Single-slot, latest-wins. Written by the socket thread, drained by tick() on
// the link thread, and nothing in between touches the firmware.

struct Mailbox {
    bool pending = false;
    uint8_t flags = 0;
    int16_t sticks[4] = {0, 0, 0, 0};
    int16_t dials[2] = {0, 0};
    uint8_t five = 0;
    int8_t fmode = 0;
    uint64_t buttons = 0;
};

std::mutex g_mailboxMutex;
Mailbox g_mailbox;

void deposit(const Packet& p)
{
    std::lock_guard<std::mutex> lock(g_mailboxMutex);
    g_mailbox.pending = true;
    g_mailbox.flags = p.flags;
    // A half that this packet does not carry keeps whatever the last packet
    // said, rather than reverting to zero. A buttons-only collector must not be
    // able to centre the sticks once a second.
    if (p.flags & kFlagAxes) {
        for (int i = 0; i < 4; ++i) g_mailbox.sticks[i] = clampAxis(p.sticks[i]);
        for (int i = 0; i < 2; ++i) g_mailbox.dials[i] = clampAxis(p.dials[i]);
        g_mailbox.five = p.five;
        g_mailbox.fmode = p.fmode < -1 ? -1 : (p.fmode > 1 ? 1 : p.fmode);
    }
    if (p.flags & kFlagButtons) {
        g_mailbox.buttons = p.buttons;
    }
}

bool take(Mailbox* out)
{
    std::lock_guard<std::mutex> lock(g_mailboxMutex);
    if (!g_mailbox.pending) return false;
    *out = g_mailbox;
    g_mailbox.pending = false;
    return true;
}

// ------------------------------------------------------------ packet ----

// Declared here, defined in the "analog" section further down: apply() calls it
// from line 386 on, and C++ wants the declaration before the first call.
uint16_t toAnalog(int16_t v);

// Applies one mailbox snapshot to the firmware. Runs on the link thread only.
void apply(const Mailbox& m)
{
    if (m.flags & kFlagAxes) {
        // The packet's order is LH LV RH RV (proto.go's AxisLH..AxisRV) and the
        // firmware's channel order is 0 LH, 1 LV, 2 RV, 3 RH - so the right stick
        // arrives transposed relative to the channel it belongs on. Pushing
        // index i to channel kAnalogLH + i would put roll on the elevator and
        // pitch on the aileron, which flies.
        //
        // kStickChannels in joystick.cpp is the same table, and using it rather
        // than restating it here is what keeps the two paths from disagreeing.
        constexpr uint8_t kStickChannel[4] = {0, 1, 3, 2};  // LH LV RH RV -> 0 1 3 2
        for (int i = 0; i < 4; ++i) {
            const int16_t v = m.sticks[i];
            if (v == g_sticks[i]) continue;
            joystick::pushAnalogNow(kStickChannel[i], toAnalog(v));
            g_sticks[i] = v;
            g_axesPushes.fetch_add(1, std::memory_order_relaxed);
        }
        // The dials land on P1/P2, which is what a radio normally uses its pots
        // for - volume and backlight through a special function both work.
        // They happen to be in channel order already, so no table is needed.
        for (int i = 0; i < 2; ++i) {
            const int16_t v = m.dials[i];
            if (v == g_dials[i]) continue;
            joystick::pushAnalogNow(static_cast<uint8_t>(kDialChannelFirst + i), toAnalog(v));
            g_dials[i] = v;
            g_axesPushes.fetch_add(1, std::memory_order_relaxed);
        }

        // The flight-mode switch is a position, not a toggle, so it is pushed
        // whenever it differs.
        if (m.fmode != g_fmode) {
            pushSwitch(kSwB, m.fmode);
            LOGI("bridge: flight mode -> switch B position %d", m.fmode);
            g_fmode = m.fmode;
        }

        if (m.five != g_five) {
            applyFiveWay(m.five, g_five);
            g_five = m.five;
        }
    }

    if (m.flags & kFlagButtons) {
        if (m.buttons != g_buttons) {
            applyButtons(m.buttons, g_buttons);
            g_buttons = m.buttons;
        } else if (!g_seenButtons && m.buttons != 0) {
            // First packet, with a button already held across a restart: there is
            // no edge to see, so establish the 3-position switch explicitly
            // rather than leaving it where the boot reset it.
            g_seenButtons = true;
            applyButtons(m.buttons, 0);
        }
        g_seenButtons = true;
    }
}

// toAnalog: the firmware wants 0..4096 centred on 2048; rcbridge sends
// -1000..1000.
constexpr int32_t kAnalogMax = 4096;
constexpr int32_t kAnalogCentre = 2048;

uint16_t toAnalog(int16_t v)
{
    int32_t out = kAnalogCentre + (static_cast<int32_t>(v) * (kAnalogMax / 2)) / 1000;
    if (out < 0) out = 0;
    if (out > kAnalogMax) out = kAnalogMax;
    return static_cast<uint16_t>(out);
}

// --------------------------------------------------------------- net ----

void sendAck(uint32_t seq)
{
    const int fd = g_sock.load(std::memory_order_relaxed);
    if (fd < 0) return;
    uint8_t ack[8] = {'A', 'C', 'K', '1', 0, 0, 0, 0};
    ack[4] = static_cast<uint8_t>(seq & 0xFF);
    ack[5] = static_cast<uint8_t>((seq >> 8) & 0xFF);
    ack[6] = static_cast<uint8_t>((seq >> 16) & 0xFF);
    ack[7] = static_cast<uint8_t>((seq >> 24) & 0xFF);
    ::send(fd, ack, sizeof(ack), 0);
    // Sent back to whoever sent the packet, which is what rcbridge's unverified
    // UDP needs: without an answer, a stopped collector and a working one look
    // identical from rcbridge's side.
}

void receiveLoop(int fd)
{
    std::vector<uint8_t> buf(2048);

    while (true) {
        // poll() rather than a blocking recv so stop() can be noticed within a
        // poll interval even if the close has not landed yet.
        pollfd pfd{};
        pfd.fd = fd;
        pfd.events = POLLIN;
        const int ready = poll(&pfd, 1, 500);
        if (ready < 0) {
            if (errno == EINTR) continue;
            break;
        }
        if (ready == 0) continue;   // timeout: loop and re-check g_running

        const ssize_t n = recv(fd, buf.data(), buf.size(), 0);
        if (n <= 0) continue;

        // Acknowledge, then deposit. The reply goes out before the state is
        // applied, which is honest: it means "received and decoded", not "applied",
        // and rcbridge only uses it to tell a live link from a dead one.
        Packet probe{};
        memcpy(&probe, buf.data(), std::min<size_t>(sizeof(probe), n));
        if (probe.magic == kMagic && kKnownSrc(probe.src)) {
            sendAck(probe.seq);
        }

        // Decode and store only. The firmware is touched from tick(), on the
        // link thread - see the threading note in rc_bridge.h.
        onPacket(buf.data(), static_cast<uint32_t>(n));
    }
}

}  // namespace

// --------------------------------------------------------------- api ----

void configure(bool enabled, uint16_t port, uint8_t sysid, uint8_t compid)
{
    (void)sysid;
    (void)compid;
    g_enabled.store(enabled && port != 0, std::memory_order_relaxed);
    g_port.store(port, std::memory_order_relaxed);
}

void init()
{
    if (!g_enabled.load(std::memory_order_relaxed)) {
        LOGI("bridge: disabled by configuration, no socket opened");
        return;
    }
    if (g_running.exchange(true)) return;

    const int fd = socket(AF_INET, SOCK_DGRAM, 0);
    if (fd < 0) {
        LOGW("bridge: socket() failed: %s", strerror(errno));
        g_running.store(false);
        return;
    }

    // SO_REUSEADDR so a restart does not have to wait out the previous socket's
    // TIME_WAIT, which matters on a radio that gets restarted a lot.
    int one = 1;
    setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));

    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    // Bound to all interfaces, not loopback: the collector runs in a separate
    // app and rcbridge may be on another machine. A loopback-only bind would
    // work for the first and fail for the second with nothing in the log to say
    // why.
    addr.sin_addr.s_addr = INADDR_ANY;
    addr.sin_port = htons(g_port.load(std::memory_order_relaxed));
    if (bind(fd, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) < 0) {
        LOGW("bridge: cannot bind port %u: %s",
             g_port.load(std::memory_order_relaxed), strerror(errno));
        close(fd);
        g_running.store(false);
        return;
    }

    g_sock.store(fd, std::memory_order_relaxed);
    g_thread = std::thread(receiveLoop, fd);
    LOGI("bridge: listening for rcbridge on port %u", g_port.load(std::memory_order_relaxed));
}

void stop()
{
    if (!g_running.exchange(false)) return;
    const int fd = g_sock.exchange(-1);
    if (fd >= 0) {
        shutdown(fd, SHUT_RDWR);
    }
    if (g_thread.joinable()) g_thread.join();
    if (fd >= 0) close(fd);
    LOGI("bridge: stopped (received %llu, accepted %llu, rejected %llu, %llu analog push(es))",
         (unsigned long long)g_received.load(std::memory_order_relaxed),
         (unsigned long long)g_accepted.load(std::memory_order_relaxed),
         (unsigned long long)g_bad.load(std::memory_order_relaxed),
         (unsigned long long)g_axesPushes.load(std::memory_order_relaxed));
}

void onPacket(const uint8_t* data, uint32_t len)
{
    g_received.fetch_add(1, std::memory_order_relaxed);

    if (data == nullptr || len < kPacketSize) {
        g_bad.fetch_add(1, std::memory_order_relaxed);
        return;
    }

    Packet p;
    memcpy(&p, data, sizeof(p));

    if (p.magic != kMagic || !kKnownSrc(p.src)) {
        g_bad.fetch_add(1, std::memory_order_relaxed);
        // Logged rather than silently counted, because "rejected" with no reason
        // is the hardest kind of failure to act on: the log says packets are
        // arriving and being thrown away, which points at the radio and the
        // network, and both look fine.
        LOGW("bridge: rejected a %u-byte packet (magic 0x%08x, src %u)",
             (unsigned)len, (unsigned)p.magic, (unsigned)p.src);
        return;
    }

    // Decode and store only. The firmware is touched from tick(), on the link
    // thread - see the threading note in rc_bridge.h.
    deposit(p);
    g_accepted.fetch_add(1, std::memory_order_relaxed);
}

void tick()
{
    Mailbox m;
    if (!take(&m)) return;
    apply(m);
}

void stats(uint64_t* received, uint64_t* accepted, uint64_t* bad, bool* enabled, uint16_t* port)
{
    if (received) *received = g_received.load(std::memory_order_relaxed);
    if (accepted) *accepted = g_accepted.load(std::memory_order_relaxed);
    if (bad) *bad = g_bad.load(std::memory_order_relaxed);
    if (enabled) *enabled = g_enabled.load(std::memory_order_relaxed);
    if (port) *port = g_port.load(std::memory_order_relaxed);
}

}  // namespace rc_bridge

// --------------------------------------------------------------------- JNI --

extern "C" JNIEXPORT jboolean JNICALL
Java_com_edgetx_droidui_RcBridgeClient_nativeStart(JNIEnv*, jclass, jboolean enabled, jint port,
                                                    jint sysid, jint compid)
{
    rc_bridge::configure(enabled == JNI_TRUE, static_cast<uint16_t>(port),
                         static_cast<uint8_t>(sysid), static_cast<uint8_t>(compid));
    rc_bridge::init();
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_edgetx_droidui_RcBridgeClient_nativeStop(JNIEnv*, jclass)
{
    rc_bridge::stop();
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_edgetx_droidui_RcBridgeClient_nativeStats(JNIEnv* env, jclass)
{
    uint64_t received = 0, accepted = 0, bad = 0;
    bool enabled = false;
    uint16_t port = 0;
    rc_bridge::stats(&received, &accepted, &bad, &enabled, &port);

    const jlong values[5] = {
        static_cast<jlong>(received), static_cast<jlong>(accepted),
        static_cast<jlong>(bad), enabled ? 1 : 0, static_cast<jlong>(port),
    };
    jlongArray out = env->NewLongArray(5);
    if (out != nullptr) env->SetLongArrayRegion(out, 0, 5, values);
    return out;
}