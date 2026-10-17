// The 16 mixed channel outputs, for the monitor and the floating window.
//
// The firmware owns the mixer and never hands the result back: the numbers the
// RF module is being fed are reachable only through the library's own
// simuCopyChannelOutputs(). Going through it is the whole point - an app that
// read its own copy of the sticks would show the stick positions, not the
// channels, and the two differ for every model that mixes anything.

#include <jni.h>

#include "simu_host.h"

namespace {

// The module protocol carries 16 channels; EdgeTX itself has room for 32, and
// the library clamps to that, but nothing past 16 is ever sent anywhere.
constexpr int kChannels = 16;

}  // namespace

// int RcChannels.read(int[] out): fills up to out.length channels and answers how
// many were written (0 when the array is empty). Values are the mixer's own
// units, +-1024 for full deflection - see simu_host.h for the scale.
extern "C" JNIEXPORT jint JNICALL
Java_com_edgetx_droidui_RcChannels_nativeChannels(JNIEnv* env, jclass, jintArray out) {
    if (out == nullptr) return 0;

    jsize length = env->GetArrayLength(out);
    if (length <= 0) return 0;
    if (length > kChannels) length = kChannels;

    int16_t values[kChannels] = {0};
    const int written = simuCopyChannelOutputs(values, static_cast<unsigned char>(length));
    const jsize count = (written > 0 && written < length) ? written : length;
    if (count <= 0) return 0;

    jint converted[kChannels];
    for (jsize i = 0; i < count; ++i) converted[i] = values[i];
    env->SetIntArrayRegion(out, 0, count, converted);
    return count;
}
