/*
 * Android host entry points the app (cpp2go/edgetx_apk) uses but the public
 * EdgeTX tree (cpp2go/edgetx_esp32) does not provide.
 *
 * The fork's `main` was last pushed 2026-09-15; the app's source is dated
 * 2026-09-26 and drives a host API that was never published.  Without these
 * definitions libedgetx_ui.so either fails to link or - because Android binds
 * symbols eagerly - fails to load, so they are the minimum needed to build and
 * run the app against the public fork.
 *
 * What is supplied here:
 *   simuSetSplashStartup()          keep EdgeTX's splash and startup checks
 *   simuTouchCancel()               release a stuck touch when the window detaches
 *   edgetxAndroidTakeAudio()        drain the firmware's PCM into AudioTrack
 *   edgetxAndroidAudioSampleRate()  the rate and the counters RcAudio logs
 *   edgetxAndroidSetAuxSerialSink() bridge the module's serial port (RF module)
 *
 * The battery / GPS / module-type / switch entry points are declared `weak` by
 * the app, so they are intentionally omitted: the app checks for them and
 * degrades gracefully when they are missing.
 *
 * `patches/apply.py` wires this file into the fork and routes the firmware's
 * audio and aux-serial calls through it.  See patches/README.md.
 *
 * License GPLv2 (same as EdgeTX).
 */

#include <cstdint>
#include <cstring>
#include <mutex>
#include <vector>

// ----------------------------------------------------------------- splash ---
// simulib.cpp's patched simuStart() reads this to decide whether to keep the
// splash.  The app calls simuSetSplashStartup() before simuStart(false).
static volatile bool s_splashRequested = false;

void simuSetSplashStartup() { s_splashRequested = true; }
bool simuAndroidSplashRequested() { return s_splashRequested; }

// ------------------------------------------------------------------ touch ---
// simulib.cpp only has simuTouchDown()/simuTouchUp(); releasing the touch is
// exactly what "cancel" means here.
void simuTouchUp();
void simuTouchCancel() { simuTouchUp(); }

// ------------------------------------------------------------------ audio ---
// android_host.cpp's simuQueueAudio() forwards here; the app drains the queue
// with edgetxAndroidTakeAudio() into an AudioTrack (see RcAudio.java).  The
// format is the firmware's own: signed 16-bit mono at AUDIO_SAMPLE_RATE.
namespace {

constexpr size_t kMaxQueuedBytes = 1u << 18;   // 256 KB ~ 4 s at 32 kHz/16-bit

std::mutex g_audioMutex;
std::vector<uint8_t> g_audio;
size_t g_audioHead = 0;                        // consumed prefix
uint32_t g_audioWritten = 0;
uint32_t g_audioDropped = 0;

}  // namespace

void androidQueueAudio(const uint8_t* buf, uint32_t len)
{
  if (buf == nullptr || len == 0) return;

  std::lock_guard<std::mutex> lock(g_audioMutex);

  // Drop the consumed prefix once it dominates the buffer.
  if (g_audioHead > 0 &&
      (g_audioHead == g_audio.size() || g_audioHead > (1u << 16))) {
    g_audio.erase(g_audio.begin(),
                  g_audio.begin() + static_cast<std::ptrdiff_t>(g_audioHead));
    g_audioHead = 0;
  }

  const size_t pending = g_audio.size() - g_audioHead;
  if (pending + len > kMaxQueuedBytes) {
    g_audioDropped += len;
    const size_t room = kMaxQueuedBytes - pending;
    if (room == 0) return;
    len = static_cast<uint32_t>(room);
  }

  g_audio.insert(g_audio.end(), buf, buf + len);
  g_audioWritten += len;
}

uint32_t edgetxAndroidTakeAudio(uint8_t* dst, uint32_t maxLen)
{
  if (dst == nullptr || maxLen == 0) return 0;

  std::lock_guard<std::mutex> lock(g_audioMutex);
  const size_t pending = g_audio.size() - g_audioHead;
  const size_t take = pending < maxLen ? pending : maxLen;
  if (take > 0) {
    std::memcpy(dst, g_audio.data() + g_audioHead, take);
    g_audioHead += take;
  }
  return static_cast<uint32_t>(take);
}

// radio/src/hal/audio_driver.h: #define AUDIO_SAMPLE_RATE 32000
uint32_t edgetxAndroidAudioSampleRate() { return 32000; }

uint32_t edgetxAndroidAudioWrittenBytes()
{
  std::lock_guard<std::mutex> lock(g_audioMutex);
  return g_audioWritten;
}

uint32_t edgetxAndroidAudioDroppedBytes()
{
  std::lock_guard<std::mutex> lock(g_audioMutex);
  return g_audioDropped;
}

// ------------------------------------------------- module serial  bridge ---
// The firmware's aux-serial driver (simulib.cpp host_drv_*) calls the patched
// simuAuxSerial*() forwards, which land here; the app's module_serial.cpp
// registers the sink and puts the bytes on the USB serial port the RF module
// hangs off.  The struct layout must match edgetxAndroidSerialSink in the app's
// app/src/main/cpp/simu_host.h.
struct edgetxAndroidSerialSink {
  void (*start)(uint8_t port_nr, uint32_t baudrate, uint8_t encoding);
  void (*stop)(uint8_t port_nr);
  void (*setBaudrate)(uint8_t port_nr, uint32_t baudrate);
  void (*send)(uint8_t port_nr, const uint8_t* data, uint32_t len);
};

static const edgetxAndroidSerialSink* s_serialSink = nullptr;

void edgetxAndroidSetAuxSerialSink(const edgetxAndroidSerialSink* sink)
{
  s_serialSink = sink;
}

void androidAuxSerialStart(uint8_t port, uint32_t baudrate, uint8_t encoding)
{
  if (s_serialSink && s_serialSink->start) s_serialSink->start(port, baudrate, encoding);
}

void androidAuxSerialStop(uint8_t port)
{
  if (s_serialSink && s_serialSink->stop) s_serialSink->stop(port);
}

void androidAuxSerialSetBaudrate(uint8_t port, uint32_t baudrate)
{
  if (s_serialSink && s_serialSink->setBaudrate) s_serialSink->setBaudrate(port, baudrate);
}

void androidAuxSerialSendBuffer(uint8_t port, const uint8_t* data, uint32_t len)
{
  if (s_serialSink && s_serialSink->send && data && len > 0)
    s_serialSink->send(port, data, len);
}
