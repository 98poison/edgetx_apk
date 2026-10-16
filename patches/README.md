# EdgeTX patches for the Android port

The APK links the real EdgeTX firmware, built as
`libedgetx-st16mk3-simulator.so` from a **patched EdgeTX tree**. That tree is the
public fork **`cpp2go/edgetx_esp32`** (the author of `cpp2go/edgetx_apk`), which
already carries the port's own changes (an `ANDROID` branch in
`radio/src/targets/simu/CMakeLists.txt` and `radio/src/targets/simu/android_host.cpp`).

## Why `apply.py` exists

The fork's `main` was last pushed **2026-09-15**; the app's source is dated
**2026-09-26**. The app therefore calls host entry points the public fork never
got. Some of them are declared `weak` (battery, GPS, module type, switches) and
the app copes without them, but these are **not** weak and would stop the library
from loading at all:

| Symbol | Why it is needed |
|--------|------------------|
| `simuSetSplashStartup()` | keeps EdgeTX's splash + startup checks |
| `simuTouchCancel()` | releases a stuck touch when the window detaches |
| `edgetxAndroidTakeAudio()` / `edgetxAndroidAudioSampleRate()` / `…WrittenBytes()` / `…DroppedBytes()` | the audio queue RcAudio drains into an AudioTrack |
| `edgetxAndroidSetAuxSerialSink()` | **the RF module path** — the fork's aux-serial TX currently goes to empty stubs |

`patches/edgetx_android_extras.cpp` supplies all of these, and `apply.py` wires
it into the fork (compiles it, forwards `simuQueueAudio` into the queue, makes
`simuStart()` honour the splash request, and routes the aux-serial bytes to the
app's sink).

## Usage

```bash
git clone --recursive https://github.com/cpp2go/edgetx_esp32 edgetx
python patches/apply.py edgetx
# then build the APK with -Pedgetx.dir=$PWD/edgetx
```

In CI, `edgetx_mode = source` does exactly this. See `../BUILD_APK_CI.md`.

## Caveats

* These additions are **not** the author's original code; they are a
  compatibility shim reconstructed from the app's `app/src/main/cpp/simu_host.h`.
  The build may need iteration.
* The battery, GPS, module-type and switch entry points are intentionally left
  out (the app declares them `weak`). If you have the author's real fork, use it
  instead and drop this directory.
