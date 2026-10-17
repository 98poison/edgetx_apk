package com.edgetx.droidui;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.Arrays;

import rikka.shizuku.Shizuku;

/**
 * Starts rcbridge from inside the app, as the shell user, through Shizuku.
 *
 * <h3>Why this exists</h3>
 *
 * <p>rcbridge reads the remote's input nodes out of {@code /dev/input}, and
 * {@code /dev/input/event*} is {@code crw-rw---- root input} - so reading it takes
 * membership of the {@code input} group. This app does not have it:
 *
 * <pre>Groups: 3001 3002 3003 9997 20150 50150      &lt;- no 1004(input)</pre>
 *
 * <p>Shizuku is a running shell-uid server, so a process it starts has exactly the
 * groups {@code adb shell} has, including {@code input}. That is why
 * {@link RcShellKeys} reads the shoulder buttons this way already, and why this is
 * not a new dependency.
 *
 * <h3>Why not just the USB joystick</h3>
 *
 * <p>It was tried and it works: 398 reports a second, every control, no node
 * involved. The problem is what it costs other programs. Claiming the USB joystick
 * interface makes the kernel node "DJI embedded joystick" disappear from
 * {@code /proc/bus/input/devices}, and measured over one session it did not come
 * back when this app was killed - so anything else on the remote that reads
 * {@code /dev/input} stops working while this app runs, permanently, until a
 * reboot.
 *
 * <p>So: this app takes the node, and lets go of the USB interface.
 *
 * <h3>What it does not do</h3>
 *
 * <p>It does not bind a user service, and it does not need rcbridge to be pushed
 * over adb first - the binary is copied out of the APK's own assets on first run.
 * There is no daemon left behind either: the process is a child of this app's
 * Shizuku session and stops when the app does.
 */
final class RcBridgeLauncher {

    private static final String TAG = "EdgeTXUI";

    /** Where the binary is staged. Shell can write here, and it is not world-visible. */
    private static final String BIN_DIR = "/data/local/tmp";
    private static final String BIN = BIN_DIR + "/rcbridge";
    private static final String CONF = BIN_DIR + "/rcbridge.conf";

    private static final String ASSET = "rcbridge-linux-arm64";

    private static final String DEFAULT_CONF = "enabled=true\nport=14571\n";

    private static Process sProcess;

    private RcBridgeLauncher() {}

    /** Whether Shizuku is installed, running and permitted. */
    static boolean canStart() {
        try {
            return Shizuku.pingBinder() && Shizuku.checkSelfPermission() == 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Makes sure rcbridge is running, copying the binary out if it is not there.
     *
     * <p>Safe to call on every resume. Does nothing if it is already up, which is
     * the common case, and is the whole reason the process is tracked here rather
     * than started blind.
     */
    static synchronized boolean ensureRunning(Context context) {
        if (isRunning()) return true;

        if (!canStart()) {
            Log.w(TAG, "launcher: Shizuku is not available, so rcbridge cannot be "
                    + "started from here. It has to be run over adb: "
                    + "adb shell " + BIN + " -c " + CONF);
            return false;
        }

        try {
            ensureBinary(context);
        } catch (Throwable t) {
            Log.w(TAG, "launcher: could not stage the rcbridge binary", t);
            return false;
        }
        return start();
    }

    /** Whether our process is the one still running. */
    static synchronized boolean isRunning() {
        return sProcess != null && sProcess.isAlive();
    }

    private static synchronized boolean start() {
        try {
            final Process p = runAsShell(new String[]{
                    BIN, "-c", CONF,
            }, BIN_DIR);
            if (p == null) {
                Log.w(TAG, "launcher: the shell process would not start");
                return false;
            }
            sProcess = p;
            // Drain both streams. A process whose pipes fill up blocks on its next
            // write, and rcbridge logs on every button press.
            drain(p.getInputStream(), false);
            drain(p.getErrorStream(), true);
            // No pid: Process.pid() is API 26 and this builds against 24, and the
            // process object is the handle that matters - isRunning() asks it
            // directly. The pid would be nice in a bug report and is not worth a
            // reflective call on a path that runs at every launch.
            Log.i(TAG, "launcher: rcbridge started");
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "launcher: could not start rcbridge", t);
            return false;
        }
    }

    /** Stops it, for the settings page's button and for a clean shutdown. */
    static synchronized void stop() {
        final Process p = sProcess;
        sProcess = null;
        if (p == null) return;
        try {
            p.destroy();
            Log.i(TAG, "launcher: rcbridge stopped");
        } catch (Throwable t) {
            Log.w(TAG, "launcher: could not stop rcbridge", t);
        }
    }

    /**
     * Copies the binary out of the APK on first run.
     *
     * <p>Through the app's own shell process rather than by opening the asset
     * directly, because {@link #runAsShell} is the only way to write somewhere
     * this app cannot write itself.
     *
     * <p>Skipped when the file is already there and the right size, so this costs
     * one {@code ls} per launch rather than 6 MB of copying.
     */
    private static void ensureBinary(Context context) throws Exception {
        final Process check = runAsShell(new String[]{
                "sh", "-c", "stat -c %s " + BIN + " 2>/dev/null || echo 0",
        }, BIN_DIR);
        if (check == null) return;
        final String size = drainToString(check);
        if (size.trim().equals("0")) {
            Log.i(TAG, "launcher: staging " + ASSET + " to " + BIN);
            copyAsset(context, ASSET, BIN);
        }
        runAsShell(new String[]{"sh", "-c", "chmod 755 " + BIN + " && echo ok"}, BIN_DIR)
                .waitFor();

        // The config is written by the settings page through RcNetLink; this only
        // makes sure the file exists so a first launch has something to read.
        final Process conf = runAsShell(new String[]{
                "sh", "-c", "test -f " + CONF + " || (echo '" + DEFAULT_CONF + "' > " + CONF + ")",
        }, BIN_DIR);
        if (conf != null) conf.waitFor();
    }

    private static void copyAsset(Context context, String asset, String dest)
            throws Exception {
        // Read the asset here, write through the shell process: the APK's assets
        // are readable by this app and the destination is not writable by it.
        final InputStream in = context.getAssets().open(asset);
        final java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        final byte[] chunk = new byte[64 * 1024];
        int n;
        while ((n = in.read(chunk)) > 0) {
            buf.write(chunk, 0, n);
        }
        in.close();

        final Process p = runAsShell(new String[]{
                "sh", "-c", "cat > " + dest,
        }, BIN_DIR);
        if (p == null) throw new IllegalStateException("no shell process");
        final OutputStream out = p.getOutputStream();
        out.write(buf.toByteArray());
        out.flush();
        out.close();
        p.waitFor();
    }

    /**
     * Runs a command as the shell user.
     *
     * <p>Reflection on purpose: {@code Shizuku.newProcess} is private in API 13 and
     * scheduled to disappear in 14. It is reached here rather than bound as a user
     * service because a user service outlives this app and would keep rcbridge -
     * and the input node it holds - alive after the last window closes, which is
     * the same problem in a different shape. When the method goes, this returns
     * null and the app says so, instead of crashing on a NoSuchMethodException.
     */
    private static Process runAsShell(String[] command, String dir) {
        try {
            final Method m = Shizuku.class.getDeclaredMethod(
                    "newProcess", String[].class, String[].class, String.class);
            m.setAccessible(true);
            final String[] env = {"PATH=/system/bin:/system/xbin:/vendor/bin"};
            return (Process) m.invoke(null, command, env, dir);
        } catch (Throwable t) {
            Log.w(TAG, "launcher: Shizuku.newProcess is unavailable: " + t);
            return null;
        }
    }

    /**
     * Forwards a child stream into this app's log, line by line.
     *
     * <p>It used to discard it, on the grounds that rcbridge's own output is already in
     * logcat when it is started over adb. That is not true of the case that matters: when
     * this launcher starts it, adb is not involved and these are the only words rcbridge
     * says. They are also the only place a reader failure is visible at all - the Go side
     * reports "node opened" and "no events for N seconds" nowhere else, and a reader that
     * opens a node which never delivers looks exactly like a healthy one from the outside.
     *
     * <p>Read on its own daemon thread so a full pipe cannot block rcbridge, and truncated
     * so one runaway line cannot flood logcat.
     */
    private static void drain(final InputStream in, final boolean isErr) {
        if (in == null) return;
        final Thread t = new Thread(() -> {
            final java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8),
                    4096);
            try {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.isEmpty()) continue;
                    if (line.length() > 500) {
                        line = line.substring(0, 500) + " ...[truncated]";
                    }
                    if (isErr) {
                        Log.w(TAG, "rcbridge| " + line);
                    } else {
                        Log.i(TAG, "rcbridge| " + line);
                    }
                }
            } catch (Throwable ignored) {
                // The stream closes when rcbridge exits, which is not an error here: the
                // process it belonged to is gone and start() is the only thing that cares.
            }
        }, isErr ? "rcbridge-err" : "rcbridge-out");
        t.setDaemon(true);
        t.start();
    }

    private static String drainToString(Process p) throws Exception {
        final java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        final InputStream in = p.getInputStream();
        final byte[] buf = new byte[256];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        p.waitFor();
        return b.toString("UTF-8");
    }
}