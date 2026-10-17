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

    /**
     * What the firmware was last told: 24 longs, see {@link #snapshotText()}.
     *
     * Polled by the readout rather than pushed, because the readout repaints on
     * the UI thread and a push per packet would be 100 posts a second to a view
     * that draws at 16.
     */
    private static native long[] nativeStatus();

    /**
     * The readout's text: the four sticks, the two dials, the switches and the
     * buttons, as the firmware sees them.
     *
     * <p>Built as one monospaced block so columns line up. Everything here is
     * already in the firmware's units - sticks and dials have been through
     * toAnalog by the time they are stored - because what is being shown is what
     * the model will see, not what arrived on the wire.
     */
    static String snapshotText() {
        final long[] s = nativeStatus();
        if (s == null || s.length < 24) {
            return "固件没有在跑\n(rcbridge → 本机 的通道输入没开)";
        }

        final StringBuilder b = new StringBuilder(320);
        b.append("固件").append(s[23] != 0 ? " ●" : " ○").append("  包 ").append(s[21]);

        b.append("\n摇  LH").append(pad(centre(s[0])))
                .append(" LV").append(pad(centre(s[1])));
        b.append("\n    RH").append(pad(centre(s[2])))
                .append(" RV").append(pad(centre(s[3])));
        b.append("\n旋钮 P1").append(pad(centre(s[4])))
                .append(" P2").append(pad(centre(s[5])));
        b.append("\n5way").append(pad(String.valueOf(s[6])))
                .append(" 飞行").append(pad(String.valueOf(s[7])));

        b.append("\nSA").append(pad(pos(s[10])))
                .append(" SB").append(pad(pos(s[11])))
                .append(" SC").append(pad(pos(s[12])));
        b.append("\nSD").append(pad(pos(s[13])))
                .append(" SE").append(pad(pos(s[14])))
                .append(" SF").append(pad(pos(s[15])));
        b.append("\nSG").append(pad(pos(s[16])))
                .append(" SH").append(pad(pos(s[17])))
                .append(" SI").append(pad(pos(s[18])))
                .append(" SJ").append(pad(pos(s[19])));

        final long mask = (s[8] << 32) | (s[9] & 0xFFFFFFFFL);
        final StringBuilder held = new StringBuilder();
        for (int i = 0; i < 15; i++) {
            if ((mask & (1L << i)) != 0) {
                if (held.length() > 0) held.append(' ');
                held.append(Proto.Btn.nameOf(1L << i));
            }
        }
        b.append("\n按下 ").append(held.length() == 0 ? "无" : held);
        return b.toString();
    }

    /**
     * Every output channel at its current value, from rcbridge.
     *
     * <p>Comes over HTTP rather than through the native snapshot, because the
     * channel map is rcbridge's configuration and duplicating it on the app side
     * would be a second thing to keep in step - the app would label channel 9 as
     * whatever it thought, while rcbridge was sending something else under that
     * number, and there would be nothing to notice the disagreement.
     *
     * <p>All of them, including the ones at rest. A list that only contains what
     * has been touched answers "what have I pressed", which is not a question worth
     * asking while flying.
     */
    /** Receives the rendered channel rows on the main thread. */
    interface ChannelCallback {
        void onChannels(String text);
    }

    /**
     * Fetches the channel table without blocking the caller.
     *
     * <p>Off the main thread because it is an HTTP request, and every failure -
     * rcbridge not running, the socket refused, the timeout - costs up to the full
     * timeout. On the main thread that is a frozen screen several times a second,
     * which is what made the readout look like it worked once and then stopped.
     */
    static void requestChannels(final ChannelCallback callback) {
        BridgeHttp.getAsync(ChannelsUrl(), 700, new BridgeHttp.Callback() {
            @Override
            public void onBody(String body) {
                callback.onChannels(renderChannels(body));
            }
        });
    }

    private static String renderChannels(String json) {
        if (json == null) {
            return "通道: 连不上 rcbridge";
        }
        try {
            final org.json.JSONArray ch = new org.json.JSONObject(json)
                    .optJSONArray("channels");
            if (ch == null || ch.length() == 0) {
                return "通道: rcbridge 没有返回通道表";
            }
            final StringBuilder b = new StringBuilder(240);
            for (int i = 0; i < ch.length(); i++) {
                final org.json.JSONObject c = ch.getJSONObject(i);
                // Two per line: one per line makes the readout taller than the
                // screen it is meant to sit over, which is its whole problem.
                if (i > 0 && i % 2 == 0) {
                    b.append('\n');
                } else if (i > 0) {
                    b.append("   ");
                }
                b.append(String.format(java.util.Locale.ROOT, "CH%-2d %5d",
                        c.optInt("channel"), c.optInt("value")));
            }
            return "通道\n" + b;
        } catch (Throwable t) {
            return "通道: " + t.getClass().getSimpleName();
        }
    }

    private static String ChannelsUrl() {
        return "http://127.0.0.1:8080/api/routes";
    }

    /** An analog reading, centred on 2048 and shown as a signed offset. */
    private static String centre(long raw) {
        final long v = raw - 2048;
        return (v >= 0 ? " +" : "") + v;
    }

    /** A switch position: three positions on a TX switch are -1, 0 and +1. */
    private static String pos(long v) {
        return v > 0 ? " 低" : v < 0 ? " 高" : " 中";
    }

    private static String pad(String s) {
        final StringBuilder b = new StringBuilder(s);
        while (b.length() < 6) b.append(' ');
        return b.toString();
    }

    /** Whether the listener is currently open, from the native side's own count. */
    static boolean isRunning() {
        try {
            long[] s = nativeStats();
            return s != null && s.length >= 4 && s[3] != 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** The configured port, so the settings panel can show it. */
    static int port() {
        return sConf == null ? 14571 : sConf.port;
    }

    /**
     * Turns the listener on or off while the app runs.
     *
     * <p>Used by the settings panel, so the bridge can be switched without
     * restarting EdgeTX and without the activity being recreated - which would
     * restart the firmware link, and that is a much bigger interruption than
     * turning a socket off.
     *
     * <p>Port 0 disables the listener entirely, which is what "off" means: the
     * socket is closed rather than merely ignored, so nothing is received and
     * nothing is applied.
     */
    static void setRunning(boolean on, int port) {
        try {
            nativeStart(on, port, sConf == null ? 1 : sConf.sysid,
                    sConf == null ? 1 : sConf.compid);
            Log.i(TAG, "bridge: " + (on ? "listening on port " + port : "stopped"));
        } catch (Throwable t) {
            Log.w(TAG, "bridge: could not switch the listener", t);
        }
    }

    /** {@code 收 12345 包 / 接受 12340 / 拒绝 5} */
    static String statusLine() {
        try {
            long[] s = nativeStats();
            if (s == null || s.length < 5) return "状态不可用";
            return String.format(java.util.Locale.ROOT,
                    "收到 %d 包 · 接受 %d · 拒绝 %d · %s",
                    s[0], s[1], s[2], s[3] != 0 ? ("监听端口 " + s[4]) : "未监听");
        } catch (Throwable t) {
            return "状态不可用";
        }
    }

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
    /**
     * Whether rcbridge is feeding this app, and therefore owns the EdgeTX switches.
     *
     * <p>Two writers for SA..SJ is not a redundancy, it is a conflict. This app's
     * DJI listener drove them from its own table - takeoff onto SA, C1 onto SG, C2
     * onto SH - while rcbridge's stream drives the same slots with different
     * meanings: go-home on SA, R1 on SG, R2 on SH. Whichever wrote last won, so
     * the same press produced different switch positions depending on timing.
     *
     * <p>rcbridge's table is the one that has the real routes, because it is the
     * one that saw the press: it merges evdev against this app's own readings and
     * it knows which reader won. So when rcbridge is up it writes the switches and
     * this app stops.
     *
     * <p>Not a setting: it follows whether packets have actually arrived, so the
     * app keeps working the way it always did on a radio where rcbridge is not
     * running, and there is no flag that can be left set after it stops.
     */
    static boolean bridgeFeedsUs() {
        try {
            final long[] s = nativeStats();
            return s != null && s.length > 0 && s[0] > 0;
        } catch (Throwable t) {
            return false;
        }
    }

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
// ---------------------------------------------------------------------------
// The MSDK collector, as a static facade.
//
// The settings panel that drives it is an overlay window, so it has no Activity
// and no instance to hand; these are the four calls it needs, each holding the
// holder's bridge.
//
// They live here rather than in RcBridge.kt as a companion object because that
// file is a straight copy of the standalone collector app's, and keeping it a
// copy is worth more than avoiding eight lines of forwarding: the two can be
// diffed against each other, and the collector can still be lifted out into its
// own app if the EdgeTX build is ever dropped.
final class Collector {



    private static com.edgetx.droidui.RcBridge bridge(android.content.Context c) {
        return com.edgetx.droidui.RcBridgeHolder.INSTANCE.get(c);
    }

    static void setTarget(String address) {
        // The holder needs a Context; the panel always has one, and this is the
        // only place the address changes, so it is passed down from there.
        setTargetWith(sContext, address);
    }

    private static android.content.Context sContext;

    static void attachContext(android.content.Context c) {
        sContext = c.getApplicationContext();
    }

    /**
     * Creates the collector and starts it reading, without opening anything.
     *
     * Called from Application.onCreate so the buttons are live from launch. The
     * socket stays closed until a target has been stored and Start pressed in the
     * panel; this only makes the key listeners live, which is what the RC's own
     * buttons need in order to be reported at all.
     *
     * Safe to call more than once - the holder keeps one instance.
     */
    static void startup() {
        if (sContext == null) return;
        bridge(sContext);
    }

    private static void setTargetWith(android.content.Context c, String address) {
        if (c == null) return;
        bridge(c).setTarget(address);
    }

    static String target() {
        return sContext == null ? "" : bridge(sContext).target();
    }

    static void startSending() {
        if (sContext != null) bridge(sContext).startSending();
    }

    static void stopSending() {
        if (sContext != null) bridge(sContext).stopSending();
    }

    static boolean isSending() {
        return sContext != null && bridge(sContext).isSending();
    }

    static boolean isRegistered() {
        return sContext != null && bridge(sContext).isRegistered();
    }

    static long packetsSent() {
        return sContext == null ? 0 : bridge(sContext).packetsSent();
    }

    static boolean sendAxes() {
        return sContext != null && bridge(sContext).sendAxes();
    }

    static void setSendAxes(boolean on) {
        if (sContext != null) bridge(sContext).setSendAxes(on);
    }

    static String heldText() {
        return sContext == null ? "-" : bridge(sContext).heldText();
    }
}
