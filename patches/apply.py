#!/usr/bin/env python3
"""Wire the Android host gaps into a cpp2go/edgetx_esp32 checkout.

The app is newer than the public fork, so the fork is missing host entry points
the app needs (and, without the aux-serial bridge, the RF module would receive
nothing).  This script:

  1. drops patches/edgetx_android_extras.cpp into radio/src/targets/simu/,
  2. builds it into the Android library in that directory's CMakeLists.txt,
  3. routes the firmware's audio (simuQueueAudio) through the new queue,
  4. makes simuStart() honour simuSetSplashStartup(),
  5. routes the firmware's aux-serial TX to the app's sink (RF module).

Every edit uses an exact anchor and fails loudly if it is not found, so a
changed upstream cannot silently produce a broken library.

Usage:  python patches/apply.py /path/to/edgetx_esp32
"""

import pathlib
import shutil
import sys


def replace_once(text, old, new, what):
    count = text.count(old)
    if count == 0:
        raise SystemExit(
            "anchor not found while patching %s:\n---\n%s\n---" % (what, old)
        )
    if count > 1:
        raise SystemExit("anchor is not unique in %s (%d matches)" % (what, count))
    return text.replace(old, new)


def patch(path, edits):
    """Apply (old, new, what) edits to `path`, normalising line endings."""
    text = path.read_text(encoding="utf-8", errors="surrogateescape")
    text = text.replace("\r\n", "\n")
    for old, new, what in edits:
        text = replace_once(text, old, new, what)
    path.write_text(text, encoding="utf-8", errors="surrogateescape")


def main():
    root = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "edgetx").resolve()
    simu = root / "radio" / "targets" / "simu"
    if not simu.is_dir():
        raise SystemExit("not an EdgeTX checkout: %s" % simu)

    here = pathlib.Path(__file__).resolve().parent

    # 1. the shim itself
    shutil.copy(here / "edgetx_android_extras.cpp", simu / "android_extras.cpp")

    # 2. compile it into the Android library
    patch(simu / "CMakeLists.txt", [(
        "add_library(edgetx_simu_core SHARED ${SIMU_SRC} android_host.cpp)",
        "add_library(edgetx_simu_core SHARED ${SIMU_SRC} android_host.cpp android_extras.cpp)",
        "CMakeLists.txt",
    )])

    # 3. audio: android_host.cpp's simuQueueAudio() forwards into the queue
    patch(simu / "android_host.cpp", [(
        "void simuQueueAudio(const uint8_t* buf, uint32_t len)\n"
        "{\n"
        "  (void)buf;\n"
        "  (void)len;\n"
        "}",
        "void androidQueueAudio(const uint8_t* buf, uint32_t len);  // android_extras.cpp\n"
        "\n"
        "void simuQueueAudio(const uint8_t* buf, uint32_t len)\n"
        "{\n"
        "  androidQueueAudio(buf, len);\n"
        "}",
        "android_host.cpp (simuQueueAudio)",
    )])

    # 4. + 5. splash and the aux-serial bridge, both in simulib.cpp
    patch(simu / "simulib.cpp", [
        (
            "  startOptions = (tests ? 0 : OPENTX_START_NO_SPLASH | "
            "OPENTX_START_NO_CALIBRATION | OPENTX_START_NO_CHECKS);",
            "  extern bool simuAndroidSplashRequested();  // android_extras.cpp\n"
            "  if (simuAndroidSplashRequested())\n"
            "    startOptions = OPENTX_START_NO_CALIBRATION;   // splash + startup checks, no wizard\n"
            "  else\n"
            "    startOptions = (tests ? 0 : OPENTX_START_NO_SPLASH | "
            "OPENTX_START_NO_CALIBRATION | OPENTX_START_NO_CHECKS);",
            "simulib.cpp (startOptions)",
        ),
        (
            "void simuAuxSerialStart(uint8_t, uint32_t, uint8_t) {}\n"
            "void simuAuxSerialStop(uint8_t) {}\n"
            "void simuAuxSerialSetBaudrate(uint8_t, uint32_t) {}\n"
            "void simuAuxSerialSendBuffer(uint8_t, const uint8_t*, uint32_t) {}",
            "void androidAuxSerialStart(uint8_t, uint32_t, uint8_t);      // android_extras.cpp\n"
            "void androidAuxSerialStop(uint8_t);\n"
            "void androidAuxSerialSetBaudrate(uint8_t, uint32_t);\n"
            "void androidAuxSerialSendBuffer(uint8_t, const uint8_t*, uint32_t);\n"
            "void simuAuxSerialStart(uint8_t p, uint32_t b, uint8_t e) { androidAuxSerialStart(p, b, e); }\n"
            "void simuAuxSerialStop(uint8_t p) { androidAuxSerialStop(p); }\n"
            "void simuAuxSerialSetBaudrate(uint8_t p, uint32_t b) { androidAuxSerialSetBaudrate(p, b); }\n"
            "void simuAuxSerialSendBuffer(uint8_t p, const uint8_t* d, uint32_t l) { androidAuxSerialSendBuffer(p, d, l); }",
            "simulib.cpp (aux serial stubs)",
        ),
    ])

    print("patched %s for the Android host" % root)


if __name__ == "__main__":
    main()
