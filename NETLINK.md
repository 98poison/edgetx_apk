# Network output of the RC's controls

EdgeTX already sends the mixed channels out through the RF module over USB serial
(see `module_serial.cpp`). This is a **second, independent output**: the same
controls, on the network, so a ground station, a logger, a companion computer or a
second screen can read them without a receiver and without the RF link.

It is off by default. With no config file, **no socket is opened and nothing is
sent** — the code is inert.

## What is sent

Two streams, each on its own UDP port, each independently selectable:

| Stream | Port | What |
|--------|------|------|
| `json` | 14560 | A text snapshot of the **raw controls** — the four stick axes, both dials, both sliders, all ten switches, the held-key bitmask and the rotary counter — sent `rate` times a second, **plus one line per discrete event**: a key edge, a switch change, a rotary step, or a named button (5-way directions, go-home, …). This is "every button and stick", *before* the mixer. |
| `crsf` | 14561 | The bytes the firmware's own **module driver** produced, mirrored **verbatim**. Whatever protocol the model selected (CRSF, GHST, SBUS, MULTI, …) is what goes out, so a host that speaks the module's protocol sees exactly what the RF module sees — with CRSF, the 16 mixed channels in `0x16` frames. |

Both are fire-and-forget UDP: a lost datagram is a lost update, never a stall. A
bounded queue on the sender side **drops the oldest frame** when the link cannot
keep up, so a slow or unreachable host can never slow the mixer down.

## Turning it on

Write `netlink.conf` into the app's external files directory — the same place the
`hid_probe` trigger file lives, which needs no permission and can be written over
adb:

```powershell
adb shell "printf 'enabled=true\nhost=192.168.1.50\nmode=json,crsf\n' > /sdcard/Android/data/com.edgetx.droidui/files/netlink.conf"
adb shell am force-stop com.edgetx.droidui   # then launch the app again
```

The file is read once, at start-up, before the firmware boots. Restart the app to
pick up a change.

### File format

One `key = value` per line; `#` starts a comment. Every key is optional.

| Key | Default | Meaning |
|-----|---------|---------|
| `enabled` | `false` | Master switch. `false` is the same as deleting the file. |
| `host` | — | Where to send: an IPv4 address or a host name (resolved once, at start). |
| `port` | `14560` | The `json` (control) stream port. |
| `crsf_port` | `14561` | The `crsf` (module) stream port. |
| `mode` | `json,crsf` | Which streams to send: `json`, `crsf`, `both`, or a bitmask (`1` = json, `2` = crsf). |
| `rate` | `50` | `json` snapshots per second, `1`..`200`. Events are sent as they happen, regardless. |

Example:

```
# Send everything to my ground station.
enabled   = true
host      = 192.168.1.50
port      = 14560
crsf_port = 14561
mode      = both
rate      = 50
```

The host may equally be the phone/PC running the receiver, a broadcast address
(`255.255.255.255`), or a subnet broadcast (`192.168.1.255`).

## Reading it

`tools/netdump.py` listens to both streams and prints what arrives:

```powershell
python tools/netdump.py                         # both streams, physical channel values
python tools/netdump.py --no-json               # module frames only
python tools/netdump.py --raw                   # dump module frames as hex
python tools/netdump.py --quiet                 # no snapshot spam, events only
python tools/netdump.py --mavlink 192.168.42.1:14550
```

The `--mavlink` option decodes the CRSF channel frames and **re-emits them as
MAVLink `RC_CHANNELS_OVERRIDE`** datagrams — this is what a flight controller (or
a SITL) listens for, and it is the same idea as the `dji-rc-plus-mavlink` project,
but it runs on the host so the radio only ever sends its own native data.

Sample output:

```
listening for controls on 0.0.0.0:14560
listening for module frames on 0.0.0.0:14561
  t=   12340ms  LH=2048  LV=2048  RV=2048  RH=2048  P1=2048  P2=2048  SL1=2048  SL2=2048  | S0mid S1mid S2mid ... | keys=0x00 rot=0
  event: key 2 down
  event: switch 1 -> 1
  channels (us): 1500 1500 1500 1500 1500 1500 1500 1500 1500 1500 1500 1500 1500 1500 1500 1500
```

### The JSON lines

A periodic snapshot:

```json
{"t":12340,"st":[2048,2048,2048,2048,2048,2048,2048,2048],"sw":[-1,0,0,0,0,0,0,0,0,0],"k":0,"rot":0}
```

* `t` — milliseconds since the native module started.
* `st` — the eight analog channels, `0`..`4096`, centred on `2048`, in EdgeTX
  channel order: `LH, LV, RV, RH, P1, P2, SL1, SL2`.
* `sw` — the ten switches by board index (`0` = SA … `9` = SJ); `<0` up, `0`
  middle, `>0` down.
* `k` — the held EdgeTX keys as a bitmask (bit *n* = key *n*, `EnumKeys` order).
* `rot` — the accumulated rotary-encoder detents.

A discrete event, one line each:

```json
{"t":12345,"ev":"key","k":2,"d":1}       key 2 went down
{"t":12346,"ev":"switch","i":1,"v":1}    switch 1 -> down
{"t":12347,"ev":"rotary","v":1}          one detent clockwise
{"t":12360,"ev":"dpad_up"}               a named button with no key of its own
```

Named events so far: `dpad_up`, `dpad_down`, `dpad_left`, `dpad_right`,
`dpad_center`, `go_home`.

## How it is wired in

The native module is `app/src/main/cpp/net_link.{h,cpp}`; the Java side that reads
the config and hands it over is `app/src/main/java/com/edgetx/droidui/RcNetLink.java`.

```
RcNetLink.java  (reads netlink.conf, calls nativeConfigure)
        │
        ▼
net_link::init()  ──┬── sender thread: own socket + bounded queue
                    │
   hooks ───────────┘
     joystick.cpp:  set_key      -> net_link::onKey        (every key edge)
                    push_analog  -> net_link::onAnalog     (sticks, dials, wheel)
                    requestSwitch-> net_link::onSwitch     (every switch change)
                    requestRotary-> net_link::onRotary     (5-way up/down, encoder)
                    nativeOnDji* -> net_link::onNamedEvent (5-way, go-home)
     module_serial.cpp: sinkSend  -> net_link::onModuleTx   (the RF module's bytes)
```

`RcNetLink.start()` runs in `EdgeTxApplication.startRuntime()` **before**
`RcLinkService.start()`, so the configuration is in place by the time
`link_start()` calls `net_link::init()`. `net_link::stop()` runs in
`link_stop()`, after the firmware, so the last module frames are still mirrored.

The hooks only touch atomics and a single mutex-guarded queue; the socket is owned
by one sender thread. The mixer task never blocks on the network.

## Log lines

The link heartbeat (every 10 s, tag `EdgeTXNet`) reports the network output when
it is on:

```
net: sending controls + module to 192.168.1.50 (controls :14560, module :14561), 50 Hz
net: controls + module, 4213 frame(s) 109538 byte(s) sent, 0 dropped
net: stopped (sent 9042 frame(s), 235012 byte(s), dropped 12)
```

`dropped` climbing means the host is not reading fast enough (or the link is
congested); the radio is unaffected either way.

## Permissions

None are needed beyond what the app already declares: `INTERNET` and
`ACCESS_NETWORK_STATE` are in the manifest (they were already there for the DJI
SDK). UDP sending needs nothing else.
