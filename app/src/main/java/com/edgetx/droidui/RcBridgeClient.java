package com.edgetx.droidui;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Locale;
import java.util.Properties;

/**
 * Receives control state from rcbridge and feeds it into the running EdgeTX firmware.
 *
 * <p>This is the inbound half of the bridge. Its counterpart is the app's own
 * outbound {@code net_link.cpp}, which mirrors the controls out over the network;
 * together they mean the EdgeTX firmware is a participant in the bridge rather
 * than only an observer of it.
 *
 * <pre>
 *   rcbridge --RCB1/UDP--> rc_bridge.cpp --> simu::pushAnalog / setSwitch / requestKey
 *   (evdev + MSDK)                              |
 *                                              v
 *                                    EdgeTX mixer --> module_serial.cpp --> RF module
 * </pre>
 *
 * <p>Going through the firmware rather than straight to the module port is the
 * whole point: the firmware applies the model's mixes, expo, differentials,
 * logical switches and safety settings between the sticks and the module, so a
 * receiver sees exactly what the operator configured in the model, and the radio
 * behaves like a radio rather than like a frame generator.
 *
 * <h3>Off by default</h3>
 *
 * <p>With no config file nothing is opened and no socket exists - the code is
 * inert. See {@code RcNetLink} for why that matters in an app that ships on
 * somebody's radio.
 *
 * <h3>Configuration</h3>
 *
 * <p>{@code rcbridge.conf} in the app's external files directory, the same place
 * the {@code hid_probe} trigger file lives (so it needs no permission and can be
 * written over adb):
 *
 * <pre>
 *   adb shell "printf 'enabled=true\nport=14571\n' \
 *       &gt; /sdcard/Android/data/com.edgetx.droidui/files/rcbridge.conf"
 *   adb shell am force-stop com.edgetx.droidui
 * </pre>
 *
 * <p>The port is the one {@code rcbridge.conf} on the radio sends to, set there
 * as {@code edgetx.targets = ["127.0.0.1:14571"]}.
 */
final class RcBridgeClient {

    private static final String TAG = "EdgeTXUI";

    /** One key = value per line; '#' starts a comment. Every key is optional. */
    private static final String CONF = "rcbridge.conf";

    /** Resolved configuration, read once by start(). Null until then. */
    private static Conf sConf;

    private static boolean sStarted;

    /** What {@code rcbridge.conf} says. Absent keys keep their defaults. */
    private static final class Conf {
        boolean enabled = false;
        int port = 14571;
        int sysid = 1;
        int compid = 1;
    }

    private RcBridgeClient() {}

    /** libedgetx_ui is loaded by NativeActivity; ART only looks up a native
     *  method in the library registered against the declaring class's loader,
     *  so without this the call below dies with UnsatisfiedLinkError. */
    static {
        try {
            System.loadLibrary("edgetx_ui");
        } catch (Throwable t) {
            Log.w(TAG, "bridge: could not load edgetx_ui", t);
        }
    }

    private static native boolean nativeStart(boolean enabled, int port, int sysid, int compid);

    private static native void nativeStop();

    /** { received, accepted, rejected, enabled, port } */
    private static native long[] nativeStats();

    // ------------------------------------------------------------ lifecycle --

    /**
     * Starts the listener.
     *
     * <p>Called from {@link EdgeTxApplication} before the link is started, so the
     * configuration is in place by the time {@code link_start()} brings the
     * firmware up. Stopping it happens in {@code link_stop()}, after the firmware,
     * so the last injected state is still applied when it goes.
     */
    static void start(Context context) {
        if (sStarted) {
            return;
        }
        sStarted = true;

        // Read here rather than in a static initialiser, which is not an option:
        // the file lives in the app's external directory, so the path is not
        // known until a context exists, and a static block would run before the
        // application could hand one over.
        Conf c = new Conf();
        try {
            Properties p = load(context);
            c.enabled = boolProp(p, "enabled", false);
            c.port = intProp(p, "port", c.port);
            c.sysid = intProp(p, "sysid", c.sysid);
            c.compid = intProp(p, "compid", c.compid);
        } catch (Throwable t) {
            Log.i(TAG, "bridge: no usable configuration, staying inert: " + t);
        }
        sConf = c;

        if (!c.enabled) {
            Log.i(TAG, "bridge: not enabled in " + CONF + ", no socket opened");
            return;
        }

        try {
            nativeStart(true, c.port, c.sysid, c.compid);
            Log.i(TAG, "bridge: started on port " + c.port);
        } catch (Throwable t) {
            Log.w(TAG, "bridge: could not start", t);
        }
    }

    static void stop() {
        if (!sStarted) {
            return;
        }
        sStarted = false;
        try {
            nativeStop();
        } catch (Throwable t) {
            Log.w(TAG, "bridge: stop failed", t);
        }
    }

    /**
     * One log line, for the periodic status report.
     *
     * <p>Reads the receiver's own count of packets, not a tick, so a line that
     * says "0 received" means rcbridge is not sending - which is the question the
     * log exists to answer, and the one that is otherwise hardest: UDP reports no
     * error when nothing is listening on the other end.
     */
    static void logStatus() {
        try {
            long[] s = nativeStats();
            if (s == null || s.length < 5) {
                return;
            }
            Log.i(TAG, "bridge: " + (s[3] != 0 ? "listening on port " + s[4] : "disabled")
                    + ", " + s[0] + " packet(s) received, " + s[1] + " accepted, "
                    + s[2] + " rejected");
            if (s[3] != 0 && s[0] == 0) {
                Log.w(TAG, "bridge: listening but nothing has arrived - check that rcbridge "
                        + "is running and that its edgetx.targets points here");
            }
        } catch (Throwable t) {
            Log.w(TAG, "bridge: status unavailable", t);
        }
    }

    // --------------------------------------------------------------- config --

    private static Properties load(Context context) throws Exception {
        if (context == null) {
            throw new IllegalStateException("no context");
        }
        File f = new File(context.getExternalFilesDir(null), CONF);
        Properties p = new Properties();
        if (!f.isFile()) {
            return p;   // absent is the normal case, not an error
        }
        try (InputStream in = new FileInputStream(f)) {
            p.load(in);
        }
        return p;
    }

    private static boolean boolProp(Properties p, String key, boolean fallback) {
        String v = p.getProperty(key);
        return v == null ? fallback : Boolean.parseBoolean(v.trim());
    }

    private static int intProp(Properties p, String key, int fallback) {
        String v = p.getProperty(key);
        if (v == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            Log.w(TAG, "bridge: " + key + "=\"" + v + "\" is not a number, using " + fallback);
            return fallback;
        }
    }
}