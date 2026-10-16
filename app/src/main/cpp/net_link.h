// Network output of the RC's controls.
//
// EdgeTX already drives the RF module over USB serial (see module_serial.h).
// This module is the *second* output: the same numbers, on the network. It is
// optional and off by default - nothing is sent, and no socket is opened, until
// a config file enables it (see RcNetLink.java).
//
// Two independent streams, each on its own UDP port:
//
//   JSON   a text snapshot of the raw controls, plus one line per discrete
//          event. This is "every button and stick", before the mixer: the four
//          stick axes, both dials, the scroll wheel, all ten switches, the EdgeTX
//          keys and the named events (5-way directions, go-home, rotary). A
//          ground station, a logger or a second screen reads it straight off the
//          wire - see tools/netdump.py in the repo.
//
//   CRSF   the bytes the firmware's own module driver produces, mirrored
//          verbatim. Whatever the model selected (CRSF, GHST, SBUS, MULTI, ...)
//          is what goes out, so a host that speaks the module's protocol sees
//          exactly what the RF module sees - the 16 mixed channels, in CRSF's
//          0x16 frames. This is the cheapest way to get *channel* data off the
//          radio and needs no knowledge of the protocol here at all.
//
// Both are fire-and-forget UDP: a lost datagram is a lost update, never a
// stall. The sender thread owns the socket and a bounded queue, and drops the
// oldest frame when the link cannot keep up, so a slow or unreachable host can
// never slow the mixer down.
//
// Threading: configure() is called from Java before init(); the on*() hooks run
// on the mixer task, the link thread and Java's callback threads, and are safe
// from all of them (atomics + one mutex).
#pragma once

#include <cstdint>

namespace net_link {

// Stream selection bits for configure(). They can be combined: a build that
// wants the raw controls on one machine and the mixed channels on another sets
// both.
enum Stream {
    kJson = 1 << 0,   // raw control snapshot + events, on `port`
    kCrsf = 1 << 1,   // the module driver's own bytes, on `crsfPort`
};

// Stores the configuration. Must be called before init(); calling it again while
// the link is up only updates the stored values and the rate - the sockets and the
// destination are fixed at init() time, so a new host or port needs a stop()/init()
// pair to take effect.
//
// `host` may be an IPv4 literal or a name (it is resolved once, in init()).
// `mode` is a bitmask of Stream values. `rateHz` is how often the JSON snapshot
// is emitted (1..200); events are sent as they happen, regardless of the rate.
void configure(bool enabled, const char* host, uint16_t port, uint16_t crsfPort, int mode,
               int rateHz);

// Starts the sender thread. Idempotent. Does nothing when the configuration
// has the link disabled.
void init();

// Stops the sender thread and closes the socket. Idempotent.
void stop();

// ---------------------------------------------------------------- hooks ----

// One analog channel, 0..4096, centred on 2048 (see simu_host.h:
// pushAnalog). Channels: 0 LH, 1 LV, 2 RV, 3 RH, 4 P1, 5 P2, 6 SL1, 7 SL2.
// `channel` is a raw EdgeTX channel index here, not a stick axis id.
void onAnalog(uint8_t channel, uint16_t value);

// One switch position, board table order (0 = SA .. 9 = SJ); state is
// <0 up, 0 middle, >0 down.
void onSwitch(uint8_t index, int8_t state);

// One EdgeTX key edge (radio/src/hal/key_driver.h EnumKeys).
void onKey(uint8_t key, bool down);

// The rotary encoder moved `steps` detents (positive = clockwise).
void onRotary(int32_t steps);

// A named, momentary control that has no key or switch of its own - the 5-way's
// directions, the go-home button. The name is copied, so a literal is fine.
void onNamedEvent(const char* name);

// The firmware's module driver produced `len` bytes for the RF module. Mirrored
// when Stream::kCrsf is configured, and ignored otherwise.
void onModuleTx(const uint8_t* data, uint32_t len);

// ---------------------------------------------------------------- status ---

// { sentFrames, droppedFrames, sentBytes, enabled, mode } - one call for the
// periodic log line.
void stats(uint32_t* sentFrames, uint32_t* droppedFrames, uint32_t* sentBytes, bool* enabled,
           int* mode);

}  // namespace net_link
