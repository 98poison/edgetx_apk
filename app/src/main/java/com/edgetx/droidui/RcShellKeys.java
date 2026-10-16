package com.edgetx.droidui;

import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import rikka.shizuku.Shizuku;

/**
 * The RC's six shoulder buttons (L1..L3 / R1..R3), read from the kernel's own input
 * node instead of from Android's input queue.
 *
 * <p><b>Why this exists.</b> Android only delivers key events to the window that has
 * focus, so the shoulders stop moving their switches the moment the UI is gone: the
 * app swiped away still runs the firmware (RcLinkService), but there is no window
 * left to receive keys. The kernel's node keeps reporting them regardless.
 *
 * <p>The node is {@code root:input 0660} and no app permission grants the
 * {@code input} group, but the {@code shell} user is in it - measured on the RC Plus 2
 * ({@code groups=...,1004(input)}). Shizuku is a running shell-uid server, so running one
 * command as that shell is the only route to the node that needs no root: this class runs
 * {@code cat /dev/input/event3} as that shell and parses the event stream (see
 * {@link #newShellProcess}).
 *
 * <p><b>The node</b> is the remote's {@code gpio-keys} device. It declares exactly
 * seven key codes, of which the first six are the shoulders:
 *
 * <pre>
 *   0x3b..0x40  KEY_F1..KEY_F6   the shoulders, in the order L1 L2 L3 R1 R2 R3
 *   0x73        KEY_VOLUMEUP     the volume rocker - not read here
 * </pre>
 *
 * <p><b>The stream</b> is the kernel's {@code struct input_event}, 24 bytes on this
 * 64-bit kernel: a 16-byte {@code timeval}, then {@code __u16 type}, {@code __u16 code}
 * and {@code __s32 value}, all little-endian. {@code cat} rather than {@code getevent}
 * because {@code getevent} prints through stdio, which block-buffers on a pipe and would
 * hold a button press back until 4 KB of them had accumulated; {@code cat} writes each
 * read straight out.
 *
 * <p>Both this and Android's own path are live while a window exists, so the same press
 * is seen twice; the native side drops whichever copy arrives second (see
 * {@code noteNodeEdge} in joystick.cpp). Without Shizuku - not installed, not running,
 * or permission not granted - nothing here runs and the shoulders simply keep coming
 * from Android, which is what they did before.
 */
final class RcShellKeys {

    private static final String TAG = "EdgeTXUI";

    /** The remote's gpio-keys node (see the class comment for what it carries). */
    private static final String NODE = "/dev/input/event3";

    /** evdev KEY_F1 (0x3b) and Android's KEYCODE_F1: the two ends of the mapping. */
    private static final int EVDEV_F1 = 59;
    private static final int ANDROID_F1 = 131;
    private static final int SHOULDER_KEYS = 6;

    /** struct input_event, 64-bit: timeval(16) + __u16 type + __u16 code + __s32 value. */
    private static final int EVENT_BYTES = 24;
    private static final int TYPE_AT = 16;
    private static final int CODE_AT = 18;
    private static final int VALUE_AT = 20;
    private static final int EV_KEY = 0x01;
    private static final int VALUE_UP = 0;
    private static final int VALUE_DOWN = 1;

    private static final int REQUEST_CODE = 0x4B31;
    private static final long RETRY_MS = 5000;

    private static final Object LOCK = new Object();
    private static final AtomicBoolean sAskedPermission = new AtomicBoolean();
    private static Context sContext;
    private static Process sProcess;
    private static boolean sReading;

    private static final Shizuku.OnRequestPermissionResultListener PERMISSION_RESULT =
            new Shizuku.OnRequestPermissionResultListener() {
                @Override
                public void onRequestPermissionResult(int requestCode, int grantResult) {
                    if (requestCode != REQUEST_CODE) return;
                    if (grantResult == PackageManager.PERMISSION_GRANTED) {
                        Log.i(TAG, "shizuku: granted - the shoulder buttons now also come from "
                                + NODE);
                        startReader();
                    } else {
                        Log.w(TAG, "shizuku: permission denied; L1..R3 keep coming from Android "
                                + "only, so they stop when the UI is gone");
                    }
                }
            };

    private static final Shizuku.OnBinderReceivedListener BINDER_UP =
            new Shizuku.OnBinderReceivedListener() {
                @Override
                public void onBinderReceived() {
                    Log.i(TAG, "shizuku: server appeared");
                    requestOrRead();
                }
            };

    private static final Shizuku.OnBinderDeadListener BINDER_DOWN =
            new Shizuku.OnBinderDeadListener() {
                @Override
                public void onBinderDead() {
                    Log.w(TAG, "shizuku: server went away; the shoulders fall back to Android");
                }
            };

    private RcShellKeys() {
    }

    /** Called once from EdgeTxApplication, next to the raw joystick reader. */
    static void start(Context context) {
        synchronized (LOCK) {
            if (sContext != null) return;
            sContext = context.getApplicationContext();
        }

        try {
            Shizuku.addRequestPermissionResultListener(PERMISSION_RESULT);
            // Sticky rather than the plain one: whether the binder is already there by the
            // time this runs is a race with how fast the provider was created, and the
            // plain listener only fires for a binder that arrives afterwards.
            Shizuku.addBinderReceivedListenerSticky(BINDER_UP);
            Shizuku.addBinderDeadListener(BINDER_DOWN);
        } catch (Throwable t) {
            // Nothing about Shizuku is essential: without it the buttons behave as they
            // did before this class existed.
            Log.i(TAG, "shizuku: not available (" + t.getClass().getSimpleName()
                    + "); the shoulder buttons come from Android");
            return;
        }

        waitForBinder();
    }

    /**
     * Shizuku's binder is handed to the provider a moment *after* the process starts
     * (measured: the provider initialises, and a tenth of a second later the service is
     * still not there), so the first look almost always fails. This waits for it, and the
     * sticky listener above catches a binder that arrives even later.
     */
    private static void waitForBinder() {
        final Thread thread = new Thread(() -> {
            for (int attempt = 0; attempt < 60; attempt++) {
                try {
                    if (Shizuku.pingBinder()) {
                        Log.i(TAG, "shizuku: server version " + Shizuku.getVersion());
                        requestOrRead();
                        return;
                    }
                } catch (Throwable t) {
                    // still not there; the next round tries again
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    return;
                }
            }
            Log.i(TAG, "shizuku: no server after 60 s; the shoulder buttons come from Android "
                    + "(they keep working while a window exists)");
        }, "rc-shizuku-wait");
        thread.setDaemon(true);
        thread.start();
    }

    private static void requestOrRead() {
        try {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                startReader();
                return;
            }
            if (sAskedPermission.compareAndSet(false, true)) {
                Log.i(TAG, "shizuku: asking for permission to read " + NODE);
                Shizuku.requestPermission(REQUEST_CODE);
            }
        } catch (Throwable t) {
            Log.w(TAG, "shizuku: could not ask for the permission", t);
        }
    }

    private static void startReader() {
        synchronized (LOCK) {
            if (sReading) return;
            sReading = true;
        }
        final Thread thread = new Thread(RcShellKeys::readLoop, "rc-node-keys");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Keeps {@code cat /dev/input/event3} running. The process ends by itself only if
     * the node disappears or Shizuku restarts, and a fresh start picks it up again - a
     * button that is read by neither this nor Android is worth a log line every few
     * seconds, not a silent stop.
     */
    private static void readLoop() {
        while (true) {
            Process process = null;
            try {
                process = newShellProcess(new String[]{"cat", NODE});
                sProcess = process;
                Log.i(TAG, "shizuku: reading the shoulder buttons from " + NODE);

                parse(process.getInputStream());

                final int exit = process.waitFor();
                final String error = drain(process.getErrorStream());
                Log.w(TAG, "shizuku: the node reader ended (exit " + exit
                        + (error.isEmpty() ? "" : ", " + error) + "), restarting in "
                        + (RETRY_MS / 1000) + " s");
            } catch (Throwable t) {
                Log.w(TAG, "shizuku: reading " + NODE + " failed ("
                        + t.getClass().getSimpleName() + ": " + t.getMessage()
                        + "), restarting in " + (RETRY_MS / 1000) + " s");
            } finally {
                sProcess = null;
                if (process != null) {
                    try {
                        process.destroy();
                    } catch (Throwable ignored) {
                        // already gone
                    }
                }
            }

            try {
                Thread.sleep(RETRY_MS);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /**
     * Runs one command as the shell user. {@code Shizuku.newProcess} is the only route to
     * that which does not mean binding a user service, but it is private in API 13: it was
     * public in 12 and is scheduled to disappear in 14, and 13.1.5 is what build.gradle.kts
     * pins. It is a plain private method of a class inside this APK, not a hidden platform
     * API, so reflection reaches it - and if Shizuku ever drops it, this throws and the
     * reader retries, which is the same as Shizuku not being there.
     */
    private static Process newShellProcess(String[] command) throws Exception {
        final Method newProcess = Shizuku.class.getDeclaredMethod("newProcess",
                String[].class, String[].class, String.class);
        newProcess.setAccessible(true);
        return (Process) newProcess.invoke(null, command, null, null);
    }

    /** Feeds every key edge in the stream to the firmware, one 24-byte record at a time. */
    private static void parse(InputStream in) throws Exception {
        final byte[] buffer = new byte[EVENT_BYTES * 64];
        int held = 0;

        while (true) {
            final int read = in.read(buffer, held, buffer.length - held);
            if (read <= 0) return;
            held += read;

            int at = 0;
            while (held - at >= EVENT_BYTES) {
                if (u16(buffer, at + TYPE_AT) == EV_KEY) {
                    final int value = s32(buffer, at + VALUE_AT);
                    if (value == VALUE_DOWN || value == VALUE_UP) {
                        final int index = u16(buffer, at + CODE_AT) - EVDEV_F1;
                        if (index >= 0 && index < SHOULDER_KEYS) {
                            nativeOnNodeKey(ANDROID_F1 + index, value == VALUE_DOWN);
                        }
                    }
                }
                at += EVENT_BYTES;
            }

            if (at > 0) {
                System.arraycopy(buffer, at, buffer, 0, held - at);
                held -= at;
            }
            if (held == buffer.length) {
                Log.w(TAG, "shizuku: dropping a torn input event batch");
                held = 0;
            }
        }
    }

    private static String drain(InputStream in) {
        try {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final byte[] chunk = new byte[256];
            for (int read; (read = in.read(chunk)) > 0; ) {
                out.write(chunk, 0, read);
                if (out.size() > 512) break;
            }
            return out.toString("UTF-8").trim();
        } catch (Throwable t) {
            return "";
        }
    }

    private static int u16(byte[] bytes, int at) {
        return (bytes[at] & 0xFF) | ((bytes[at + 1] & 0xFF) << 8);
    }

    private static int s32(byte[] bytes, int at) {
        return (bytes[at] & 0xFF)
                | ((bytes[at + 1] & 0xFF) << 8)
                | ((bytes[at + 2] & 0xFF) << 16)
                | (bytes[at + 3] << 24);
    }

    /** joystick.cpp: the same handler Android's key events go through. */
    private static native void nativeOnNodeKey(int keycode, boolean down);
}
