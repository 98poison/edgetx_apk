// Injection of rcbridge's control state into the running EdgeTX firmware.
//
// This is the inbound counterpart to net_link.cpp, which mirrors the RC's
// controls *out* over the network. Together they make the EdgeTX firmware a
// participant in the bridge rather than only an observer of it:
//
//   rcbridge  --RCB1/UDP-->  this module  -->  simu::pushAnalog
//   (evdev                        |            simu::setSwitch
//    + MSDK)                      |            simu::setKey
//                                 |            simu::rotaryEncoderEvent
//                                 v
//                          EdgeTX mixer  -->  module_serial.cpp  -->  RF module
//
// Why the state goes through the firmware rather than straight to the module
// port is the whole reason this path is worth having. The firmware applies
// everything between the sticks and the module - mixes, expo, differential on
// the ailerons, the model's own channel assignments, logical switches, the
// safety settings on an arm switch - and it applies them the way the operator
// configured them. A frame generator (see rcbridge's module_proto.go) sends the
// same numbers but none of that, which means every receiver gets a model that
// behaves slightly differently from what the radio shows.
//
// The mapping into the firmware's own inputs is the one the app already uses for
// the DJI SDK path (DjiMsdkBridge -> joystick.cpp), deliberately. Two sets of
// switch slots cannot exist on one board, and having the direct-SDK path and the
// rcbridge path fight over SA..SJ would make the radio's behaviour depend on
// which app happened to be running. Sharing the table means the radio behaves
// identically whether the buttons arrived through the SDK or over the network,
// and the existing mixing, model storage and telemetry keep working untouched.
//
// Threading: the socket is read and the packet decoded on this module's own
// thread, and nothing there touches the firmware. A decoded packet goes into a
// single-slot mailbox and joystick::tick() - which runs on the link thread -
// drains it. That is the discipline joystick.cpp already follows for every other
// input, and for the same reason: the firmware samples its analog, switch and key
// state from its own thread, so a value written from an unrelated thread can be
// missed entirely, and a key pressed and released inside one call is never
// observed at all.
//
// The mailbox is latest-wins rather than a queue. rcbridge sends at 100 Hz and
// the link thread runs at a similar rate, so a backlog would consist entirely of
// stale states - and an older packet is worth less than none, because applying
// it would move a stick backwards.
#pragma once

#include <cstdint>

namespace rc_bridge {

// Stores the configuration. Must be called before init().
//
// `port` is the local UDP port to listen on; 0 disables the module entirely,
// which is the default - a build with no configuration must not open a socket.
// `sysid` and `compid` are accepted so a future authenticated or filtered mode
// has a home, and so the acknowledgement carries something identifying.
void configure(bool enabled, uint16_t port, uint8_t sysid, uint8_t compid);

// Starts the listener thread. Idempotent. Does nothing when disabled.
void init();

// Stops the listener thread and closes the socket. Idempotent.
void stop();

// Queue one decoded RCB1 packet for application.
//
// This only *decodes and stores*: the firmware is touched from tick(), on the
// link thread. Exposed so the Java layer, or a test, can inject a state without
// going through a socket - which is what makes the whole mapping testable
// without a radio and without rcbridge running.
void onPacket(const uint8_t* data, uint32_t len);

// Apply whatever onPacket most recently queued. Called from joystick::tick().
//
// Kept separate from onPacket on purpose, so there is exactly one place in this
// module that writes to the firmware and exactly one thread it can be called from.
void tick();

// ---------------------------------------------------------------- status ---

// { received, applied, bad, enabled, port } - one call for the periodic log line.
void stats(uint64_t* received, uint64_t* applied, uint64_t* bad, bool* enabled, uint16_t* port);

}  // namespace rc_bridge