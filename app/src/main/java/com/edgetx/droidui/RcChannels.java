package com.edgetx.droidui;

/**
 * The 16 mixed channel outputs, read straight out of the firmware.
 *
 * <p>These are the channels as the RF module is being fed - after the mixer, the limits and
 * the trims - not the sticks. The difference matters as soon as a model mixes anything: an app
 * that displayed its own copy of the stick positions would call the same number "CH1" as the
 * mixer does while showing something else entirely.
 *
 * <p>They are also the one part of the firmware the app cannot compute for itself, which is why
 * this goes through the library's own {@code simuCopyChannelOutputs()} (see
 * {@code cpp/channels.cpp}) rather than any bookkeeping here.
 *
 * <p>Units: the mixer's own, {@code -1024..1024} for full deflection - {@link #percent(int)}
 * turns that into the 0..100 the pages show.
 */
final class RcChannels {

    /** Channels the module protocol carries. */
    static final int COUNT = 16;

    /** Mixer units for full deflection. */
    static final int FULL_SCALE = 1024;

    private RcChannels() {}

    static {
        System.loadLibrary("edgetx_ui");
    }

    /**
     * Fills {@code out} with the current channel values and answers how many were written
     * (0 when the array is empty).
     */
    static int read(int[] out) {
        if (out == null || out.length == 0) return 0;
        try {
            return nativeChannels(out);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** A convenience array of the right size, for a caller that wants all 16. */
    static int[] sample() {
        final int[] values = new int[COUNT];
        read(values);
        return values;
    }

    /**
     * The channel as a percentage, 0..100 with <b>50 at centre</b> - the scale the pages and the
     * floating window show, because it is the one that reads the same whatever a channel is
     * doing (a switch at -100% is 0, a stick at centre is 50, a stick hard over is 100).
     */
    static int percent(int value) {
        int percent = 50 + Math.round(value * 50f / FULL_SCALE);
        if (percent < 0) percent = 0;
        if (percent > 100) percent = 100;
        return percent;
    }

    // cpp/channels.cpp
    private static native int nativeChannels(int[] out);
}
