package com.edgetx.droidui;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Reads the config file that turns on the optional network output of the RC's controls
 * (native side: {@code net_link.cpp}), and hands it to the firmware before the link
 * starts.
 *
 * <p>EdgeTX already drives the RF module over USB serial (see {@link RcModuleSerial}).
 * This is the second output: the same controls, on the network, so a ground station, a
 * logger or a second screen can read them. It is off unless a file asks for it - with no
 * file, no socket is ever opened and nothing is sent.
 *
 * <p>The file is {@code netlink.conf}, next to the {@code hid_probe} trigger file in the
 * app's external files directory (the same place {@link RcRawJoystick} looks - a path
 * that needs no permission and can be written over adb):
 *
 * <pre>
 *   adb push netlink.conf /sdcard/Android/data/com.edgetx.droidui/files/netlink.conf
 * </pre>
 *
 * <p>Format: one {@code key = value} per line, {@code #} starts a comment. Every key is
 * optional; the defaults are what a quick test wants.
 *
 * <pre>
 *   enabled   = true|false     off by default; false is the same as deleting the file
 *   host      = 192.168.1.50   where to send; an IPv4 address or a name
 *   port      = 14560          the control stream (JSON), see below
 *   crsf_port = 14561          the module stream (raw module-protocol bytes)
 *   mode      = json,crsf      which streams to send: json, crsf, both, or a bitmask
 *   rate      = 50             control snapshots per second, 1..200
 * </pre>
 *
 * <p>The two streams are independent and can be combined:
 *
 * <ul>
 *   <li><b>json</b> - a text snapshot of the raw controls ({@code {"t":..,"st":[..8
 *       channels..],"sw":[..10 switches..],"k":..,"rot":..}}) {@code rate} times a second,
 *       plus one line per discrete event (a key edge, a switch change, a rotary step, a
 *       named button): every stick, dial, switch and button, before the mixer.</li>
 *   <li><b>crsf</b> - the bytes the firmware's own module driver produced, mirrored
 *       verbatim. Whatever the model selected (CRSF, GHST, SBUS, MULTI, ...) is what goes
 *       out, so a host that speaks the module's protocol sees exactly what the RF module
 *       sees - the 16 mixed channels, in CRSF's 0x16 frames.</li>
 * </ul>
 *
 * <p>There is a receiver for both in the repository: {@code tools/netdump.py}.
 */
final class RcNetLink {

    private static final String TAG = "EdgeTXUI";

    /** The config file, in the app's external files directory. */
    private static final String CONFIG = "netlink.conf";

    /** Defaults for the keys the file may leave out. */
    private static final int DEFAULT_PORT = 14560;
    private static final int DEFAULT_CRSF_PORT = 14561;
    private static final int DEFAULT_RATE = 50;

    /** Stream bits, matching net_link.h Stream { kJson = 1 << 0, kCrsf = 1 << 1 }. */
    static final int STREAM_JSON = 1 << 0;
    static final int STREAM_CRSF = 1 << 1;

    private static boolean sConfigured;

    private RcNetLink() {}

    static {
        System.loadLibrary("edgetx_ui");
    }

    /**
     * Called from {@link EdgeTxApplication#startRuntime()} before {@link RcLinkService},
     * so the configuration is in place by the time {@code link_start()} calls
     * {@code net_link::init()}. Idempotent.
     */
    static synchronized void start(Context context) {
        if (sConfigured) return;
        sConfigured = true;
        apply(read(context), false);
    }

    /** The file itself - the settings overlay writes it, this class reads it. */
    static File configFile(Context context) {
        try {
            return new File(context.getExternalFilesDir(null), CONFIG);
        } catch (Throwable t) {
            Log.w(TAG, "net: no external files directory, the network output stays off");
            return null;
        }
    }

    /**
     * The file's settings with the defaults filled in, never null. Reading is separate from
     * applying so the settings overlay can show the current values without touching the link.
     */
    static Config read(Context context) {
        final Config config = new Config();
        final File file = configFile(context);
        if (file == null || !file.isFile()) {
            // Nothing to read: the native default is "off", so this is not even a call.
            if (file != null) {
                Log.i(TAG, "net: no " + CONFIG + " in " + file.getParent()
                        + ", the network output stays off");
            }
            return config;
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            int number = 0;
            while ((line = reader.readLine()) != null) {
                ++number;
                parseLine(config, line, number);
            }
        } catch (Throwable t) {
            Log.w(TAG, "net: cannot read " + file, t);
        }
        return config;
    }

    /**
     * Writes the settings back and puts them into the running link - the gear's "save".
     *
     * <p>The destination is fixed when the native link starts (see net_link.h), so a changed
     * host or port needs the stop/init pair behind {@link #nativeReconfigure}; that is also
     * what makes a change take effect without restarting the app.
     *
     * @return null when it worked, otherwise a message for the user
     */
    static synchronized String save(Context context, Config config) {
        final File file = configFile(context);
        if (file == null) return "没有外部存储目录，无法保存";
        try {
            write(file, config);
        } catch (Throwable t) {
            Log.w(TAG, "net: cannot write " + file, t);
            return "写入失败: " + t;
        }
        sConfigured = true;
        apply(config, true);
        return null;
    }

    /** Written in exactly the shape {@link #parseLine} reads. */
    private static void write(File file, Config config) throws IOException {
        final String mode = config.mode == (STREAM_JSON | STREAM_CRSF) ? "both"
                : (config.mode == STREAM_CRSF ? "crsf" : "json");
        final StringBuilder text = new StringBuilder();
        text.append("# Written by the settings gear on the radio. Every key is documented in\n")
                .append("# RcNetLink.java and every one of them is optional except host.\n")
                .append("enabled=").append(config.enabled).append('\n')
                .append("host=").append(config.host == null ? "" : config.host).append('\n')
                .append("port=").append(config.port).append('\n')
                .append("crsf_port=").append(config.crsfPort).append('\n')
                .append("mode=").append(mode).append('\n')
                .append("rate=").append(config.rate).append('\n');
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(file)) {
            out.write(text.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * Puts a configuration into the native link, logging what came of it.
     *
     * @param live true for a change made while the link is up (stop + init), false for the one
     *        read at startup, which is stored before the firmware's own {@code init()}
     */
    private static void apply(Config config, boolean live) {
        if (!config.enabled || config.host == null || config.host.isEmpty() || config.mode == 0) {
            // Off, and saying which of the three ways it got there.
            if (!config.enabled) {
                Log.i(TAG, "net: " + CONFIG + " says enabled=false, the network output stays off");
            } else if (config.host == null || config.host.isEmpty()) {
                Log.w(TAG, "net: " + CONFIG + " has no host=, the network output stays off");
            } else {
                Log.w(TAG, "net: " + CONFIG + " selects no stream, the network output stays off");
            }
            if (live) nativeReconfigure(false, "", config.port, config.crsfPort, 0, config.rate);
            return;
        }

        if (live) {
            nativeReconfigure(true, config.host, config.port, config.crsfPort, config.mode,
                    config.rate);
        } else {
            nativeConfigure(true, config.host, config.port, config.crsfPort, config.mode,
                    config.rate);
        }

        Log.i(TAG, "net: " + describe(config) + " -> " + config.host + " (controls :"
                + config.port + ", module :" + config.crsfPort + "), " + config.rate + " Hz"
                + (live ? " (applied now)" : ""));
    }

    /** { sentFrames, droppedFrames, sentBytes, enabled, mode } - for a status log. */
    static int[] stats() {
        return nativeStats();
    }

    private static String describe(Config config) {
        if (config.mode == (STREAM_JSON | STREAM_CRSF)) return "sending controls + module frames";
        if (config.mode == STREAM_CRSF) return "sending module frames";
        return "sending controls";
    }

    /** One {@code key = value} line. Unreadable values keep the default and are logged. */
    private static void parseLine(Config config, String raw, int number) {
        final String line = stripComment(raw).trim();
        if (line.isEmpty()) return;

        final int eq = line.indexOf('=');
        if (eq < 0) {
            Log.w(TAG, "net: " + CONFIG + ":" + number + ": no '=' in \"" + line + "\"");
            return;
        }

        final String key = line.substring(0, eq).trim().toLowerCase(java.util.Locale.ROOT);
        final String value = line.substring(eq + 1).trim();

        try {
            switch (key) {
                case "enabled":
                    config.enabled = parseBoolean(value);
                    break;
                case "host":
                    if (!value.isEmpty()) config.host = value;
                    break;
                case "port":
                    config.port = parsePort(value);
                    break;
                case "crsf_port":
                    config.crsfPort = parsePort(value);
                    break;
                case "mode":
                    config.mode = parseMode(value);
                    break;
                case "rate":
                    config.rate = parseRate(value);
                    break;
                default:
                    Log.w(TAG, "net: " + CONFIG + ":" + number + ": unknown key \"" + key + "\"");
                    break;
            }
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "net: " + CONFIG + ":" + number + ": " + e.getMessage());
        }
    }

    private static String stripComment(String line) {
        final int hash = line.indexOf('#');
        return hash < 0 ? line : line.substring(0, hash);
    }

    private static boolean parseBoolean(String value) {
        final String text = value.toLowerCase(java.util.Locale.ROOT);
        if (text.equals("1") || text.equals("true") || text.equals("yes") || text.equals("on")) {
            return true;
        }
        if (text.equals("0") || text.equals("false") || text.equals("no") || text.equals("off")) {
            return false;
        }
        throw new IllegalArgumentException("\"" + value + "\" is not a yes/no value");
    }

    private static int parsePort(String value) {
        final int port = Integer.parseInt(value);
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port " + port + " is out of range");
        }
        return port;
    }

    private static int parseRate(String value) {
        final int rate = Integer.parseInt(value);
        if (rate < 1 || rate > 200) {
            throw new IllegalArgumentException("rate " + rate + " is outside 1..200");
        }
        return rate;
    }

    /** {@code json}, {@code crsf}, {@code both}, or a numeric bitmask (1 json, 2 crsf). */
    private static int parseMode(String value) {
        final String text = value.toLowerCase(java.util.Locale.ROOT).replace(",", "+");
        if (text.matches("\\d+")) {
            final int bits = Integer.parseInt(text);
            if (bits < 1 || bits > (STREAM_JSON | STREAM_CRSF)) {
                throw new IllegalArgumentException("mode " + bits + " is not a stream bitmask");
            }
            return bits;
        }

        int mode = 0;
        for (String part : text.split("\\+")) {
            switch (part.trim()) {
                case "": break;
                case "json":
                case "controls":
                    mode |= STREAM_JSON;
                    break;
                case "crsf":
                case "module":
                case "raw":
                    mode |= STREAM_CRSF;
                    break;
                case "both":
                case "all":
                    mode |= STREAM_JSON | STREAM_CRSF;
                    break;
                default:
                    throw new IllegalArgumentException("\"" + part + "\" is not a stream");
            }
        }
        return mode;
    }

    /**
     * The working set of parsed values, defaults filled in. Package private, with package
     * private fields, because the settings overlay shows and edits exactly these.
     */
    static final class Config {
        boolean enabled = false;
        String host = null;
        int port = DEFAULT_PORT;
        int crsfPort = DEFAULT_CRSF_PORT;
        int mode = STREAM_JSON | STREAM_CRSF;
        int rate = DEFAULT_RATE;
    }

    // Configured from Java, owned by net_link.cpp. See net_link.h.
    private static native void nativeConfigure(boolean enabled, String host, int port,
            int crsfPort, int mode, int rateHz);

    /**
     * Same, but for a link that is already running: net_link::stop() + configure() + init(),
     * which is what a changed host or port needs (the destination is resolved once, in init()).
     */
    private static native void nativeReconfigure(boolean enabled, String host, int port,
            int crsfPort, int mode, int rateHz);

    private static native int[] nativeStats();
}
