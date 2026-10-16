#!/usr/bin/env python3
"""Receive the RC's controls from an EdgeTX-on-Android radio running the net_link output.

The radio sends two independent UDP streams (see app/src/main/cpp/net_link.h and
RcNetLink.java). This tool listens to both and prints what arrives:

  * the control stream (JSON, default port 14560): a periodic snapshot of the raw
    controls plus one line per discrete event. This is every stick, dial, switch and
    button, before the mixer.
  * the module stream (default port 14561): the bytes the firmware's own module driver
    sent to the RF module, mirrored verbatim. The default decoder understands CRSF's
    0x16 RC-channel frames - the 16 mixed channels.

Optionally, the decoded channels can be re-emitted as MAVLink RC_CHANNELS_OVERRIDE
datagrams, which is what a flight controller (or a SITL) listens for:

    python tools/netdump.py --mavlink 192.168.42.1:14550

Usage:

    python tools/netdump.py                      # both streams, physical channel values
    python tools/netdump.py --no-json            # module frames only
    python tools/netdump.py --raw                # dump module frames as hex
    python tools/netdump.py --quiet              # no snapshot spam, events only

No third-party packages are needed.
"""

from __future__ import annotations

import argparse
import json
import socket
import struct
import sys
import threading
import time

# --------------------------------------------------------------------------- CRSF --

CRSF_ADDRESSES = {0xC8, 0xEA, 0xEE}
CRSF_FRAMETYPE_RC_CHANNELS_PACKED = 0x16
CRSF_NUM_CHANNELS = 16
CRSF_PAYLOAD_LEN = 22  # 16 * 11 bits

# CRC-8/DVB-S2, the polynomial CRSF uses.
_CRC8_POLY = 0xD5
_CRC8_TABLE = []
for _i in range(256):
    _crc = _i
    for _ in range(8):
        _crc = ((_crc << 1) ^ _CRC8_POLY) & 0xFF if _crc & 0x80 else (_crc << 1) & 0xFF
    _CRC8_TABLE.append(_crc)


def crc8(data: bytes) -> int:
    crc = 0
    for byte in data:
        crc = _CRC8_TABLE[crc ^ byte]
    return crc


def crsf_channels(frame: bytes):
    """The 16 channels of one CRSF frame, or None when it is not a valid 0x16 frame.

    `frame` is the bytes the radio's module driver produced, address byte included.
    """
    if len(frame) < 4 or frame[0] not in CRSF_ADDRESSES:
        return None
    if frame[2] != CRSF_FRAMETYPE_RC_CHANNELS_PACKED:
        return None

    length = frame[1]
    if length < 2 or len(frame) < length + 2:
        return None

    body = frame[1:length + 1]           # length, type, payload
    if crc8(body) != frame[length + 1]:
        return None                      # keep going: a mismatched frame is not fatal

    payload = frame[3:length + 1]
    if len(payload) < CRSF_PAYLOAD_LEN:
        return None

    bits = int.from_bytes(payload[:CRSF_PAYLOAD_LEN], "little")
    return [(bits >> (11 * i)) & 0x7FF for i in range(CRSF_NUM_CHANNELS)]


def crsf_to_us(value: int) -> int:
    """One 11-bit CRSF channel as a servo pulse in microseconds."""
    return (value - 992) * 5 // 8 + 1500


def scan_frames(buffer: bytearray):
    """Pulls whole CRSF frames out of a byte stream, in place; yields them."""
    frames = []
    i = 0
    while i + 2 <= len(buffer):
        if buffer[i] not in CRSF_ADDRESSES:
            i += 1
            continue
        length = buffer[i + 1]
        total = length + 2
        if i + total > len(buffer):
            break                        # wait for the rest of the frame
        frames.append(bytes(buffer[i:i + total]))
        i += total
    del buffer[:i]
    return frames


# ------------------------------------------------------------------------ MAVLink --

MAVLINK_STX_V2 = 0xFD
MAVLINK_MSG_ID_RC_CHANNELS_OVERRIDE = 70
MAVLINK_CRC_EXTRA = {MAVLINK_MSG_ID_RC_CHANNELS_OVERRIDE: 124}


def _x25_crc(data: bytes) -> int:
    crc = 0xFFFF
    for byte in data:
        tmp = byte ^ (crc & 0xFF)
        tmp ^= (tmp << 4) & 0xFF
        crc = ((crc >> 8) ^ (tmp << 8) ^ (tmp << 3) ^ (tmp >> 4)) & 0xFFFF
    return crc


class MavlinkRcSender:
    """Emits MAVLink v2 RC_CHANNELS_OVERRIDE, one datagram per update."""

    def __init__(self, host: str, port: int, sysid: int = 255, compid: int = 0):
        self._addr = (host, port)
        self._socket = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self._sysid = sysid
        self._compid = compid
        self._seq = 0

    def send(self, channels) -> None:
        values = [min(65535, max(0, crsf_to_us(c))) for c in channels[:18]]
        values += [0] * (18 - len(values))

        # MAVLink packs a struct's fields in size order (largest first), not definition
        # order, so the wire layout is chan1..chan8 (16 bytes), target_system,
        # target_component, then the chan9..chan18 extensions (20 bytes) - 38 in all.
        payload = struct.pack("<8HBB10H", *values[:8], 0, 0, *values[8:18])

        # MAVLink v2 header, without the STX byte: the checksum covers this plus the
        # payload plus the message's CRC_EXTRA byte.
        header = struct.pack(
            "<BBBBBB", len(payload), 0, 0, self._seq, self._sysid, self._compid
        ) + MAVLINK_MSG_ID_RC_CHANNELS_OVERRIDE.to_bytes(3, "little")
        self._seq = (self._seq + 1) & 0xFF

        crc = _x25_crc(header + payload + bytes([MAVLINK_CRC_EXTRA[
            MAVLINK_MSG_ID_RC_CHANNELS_OVERRIDE]]))
        frame = bytes([MAVLINK_STX_V2]) + header + payload + struct.pack("<H", crc)

        try:
            self._socket.sendto(frame, self._addr)
        except OSError as exc:
            print(f"mavlink: send failed: {exc}", file=sys.stderr)


# --------------------------------------------------------------------------- stats --


class Stats:
    def __init__(self):
        self.lock = threading.Lock()
        self.json_packets = 0
        self.json_snapshots = 0
        self.json_events = 0
        self.crsf_frames = 0
        self.crsf_bytes = 0

    def bump(self, **kwargs):
        with self.lock:
            for key, value in kwargs.items():
                setattr(self, key, getattr(self, key) + value)

    def snapshot(self):
        with self.lock:
            return (self.json_packets, self.json_snapshots, self.json_events,
                    self.crsf_frames, self.crsf_bytes)


# ------------------------------------------------------------------------ printing --


def format_snapshot(payload: dict) -> str:
    """One snapshot as the raw controls, names and all."""
    analog = payload.get("st", [])
    switches = payload.get("sw", [])
    names = ["LH", "LV", "RV", "RH", "P1", "P2", "SL1", "SL2"]
    positions = {1: "down", 0: "mid", -1: "up"}

    sticks = "  ".join(
        f"{names[i] if i < len(names) else i}={v}" for i, v in enumerate(analog))
    sw = " ".join(
        f"S{i}{positions.get(s, s)}" for i, s in enumerate(switches))
    keys = payload.get("k", 0)
    rotary = payload.get("rot", 0)
    return f"t={payload.get('t', 0):>8}ms  {sticks}  | {sw} | keys=0x{keys:02X} rot={rotary}"


def format_event(payload: dict) -> str:
    event = payload.get("ev", "?")
    if event == "switch":
        return f"switch {payload.get('i')} -> {payload.get('v')}"
    if event == "key":
        return f"key {payload.get('k')} {'down' if payload.get('d') else 'up'}"
    if event == "rotary":
        return f"rotary {payload.get('v'):+d}"
    return event


# ------------------------------------------------------------------------ receiver --


def json_listener(port: int, args, stats: Stats, stop: threading.Event) -> None:
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind((args.bind, port))
    sock.settimeout(0.5)
    print(f"listening for controls on {args.bind}:{port}")

    while not stop.is_set():
        try:
            data, _ = sock.recvfrom(65535)
        except socket.timeout:
            continue
        except OSError:
            break

        stats.bump(json_packets=1)
        for raw_line in data.decode("utf-8", "replace").splitlines():
            raw_line = raw_line.strip()
            if not raw_line:
                continue
            try:
                payload = json.loads(raw_line)
            except json.JSONDecodeError:
                if args.raw:
                    print(f"  ! unparsable: {raw_line}")
                continue

            if "st" in payload:            # a periodic snapshot
                stats.bump(json_snapshots=1)
                if not args.quiet:
                    print("  " + format_snapshot(payload), flush=True)
            else:                          # a discrete event
                stats.bump(json_events=1)
                print(f"  event: {format_event(payload)}", flush=True)

    sock.close()


def crsf_listener(port: int, args, stats: Stats, stop: threading.Event, mav) -> None:
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind((args.bind, port))
    sock.settimeout(0.5)
    print(f"listening for module frames on {args.bind}:{port}")

    buffer = bytearray()
    last_print = 0.0

    while not stop.is_set():
        try:
            data, _ = sock.recvfrom(65535)
        except socket.timeout:
            continue
        except OSError:
            break

        stats.bump(crsf_frames=1, crsf_bytes=len(data))

        if args.raw:
            print("  " + " ".join(f"{b:02X}" for b in data), flush=True)

        buffer += data
        for frame in scan_frames(buffer):
            channels = crsf_channels(frame)
            if channels is None:
                if args.raw:
                    print(f"  (not a 0x16 channel frame: {frame[:4].hex()}...)")
                continue

            if mav is not None:
                mav.send(channels)

            now = time.monotonic()
            if not args.quiet and now - last_print >= 1.0 / max(1, min(args.print_hz, 20)):
                last_print = now
                us = " ".join(f"{crsf_to_us(c):4d}" for c in channels)
                print(f"  channels (us): {us}", flush=True)

    sock.close()


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Receive the RC's controls from an EdgeTX-on-Android radio.")
    parser.add_argument("--bind", default="0.0.0.0", help="local address (default all)")
    parser.add_argument("--json-port", type=int, default=14560,
                        help="control stream port (default 14560)")
    parser.add_argument("--crsf-port", type=int, default=14561,
                        help="module stream port (default 14561)")
    parser.add_argument("--no-json", action="store_true", help="ignore the control stream")
    parser.add_argument("--no-crsf", action="store_true", help="ignore the module stream")
    parser.add_argument("--quiet", action="store_true",
                        help="no per-snapshot / per-frame spam, discrete events only")
    parser.add_argument("--raw", action="store_true",
                        help="also dump raw module frames and unparsable lines as hex")
    parser.add_argument("--print-hz", type=float, default=5.0,
                        help="how often to print module channels (default 5)")
    parser.add_argument("--mavlink", metavar="HOST:PORT", default=None,
                        help="re-emit the decoded channels as MAVLink "
                             "RC_CHANNELS_OVERRIDE, e.g. 192.168.42.1:14550")
    parser.add_argument("--duration", type=float, default=0.0,
                        help="stop after this many seconds (0 = run until Ctrl-C)")
    args = parser.parse_args()

    mav = None
    if args.mavlink:
        try:
            host, _, port = args.mavlink.rpartition(":")
            mav = MavlinkRcSender(host, int(port))
            print(f"re-emitting MAVLink RC_CHANNELS_OVERRIDE to {host}:{port}")
        except ValueError:
            print(f"--mavlink wants HOST:PORT, got {args.mavlink!r}", file=sys.stderr)
            return 2

    stats = Stats()
    stop = threading.Event()
    threads = []

    if not args.no_json:
        threads.append(threading.Thread(
            target=json_listener, args=(args.json_port, args, stats, stop), daemon=True))
    if not args.no_crsf:
        threads.append(threading.Thread(
            target=crsf_listener, args=(args.crsf_port, args, stats, stop, mav), daemon=True))

    if not threads:
        print("nothing to do: both streams disabled", file=sys.stderr)
        return 2

    for thread in threads:
        thread.start()

    try:
        if args.duration > 0:
            stop.wait(args.duration)
        else:
            while True:
                stop.wait(1.0)
    except KeyboardInterrupt:
        print()
    finally:
        stop.set()
        for thread in threads:
            thread.join(timeout=2.0)

    packets, snapshots, events, frames, nbytes = stats.snapshot()
    print(f"received: {packets} control datagram(s) ({snapshots} snapshot(s), "
          f"{events} event(s)), {frames} module frame(s), {nbytes} byte(s)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
