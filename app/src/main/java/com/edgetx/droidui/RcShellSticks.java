package com.edgetx.droidui;

import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;
import android.view.KeyEvent;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import rikka.shizuku.Shizuku;

/**
 * The RC's four sticks, two dials and its five-way, read from the kernel's own joystick node.
 *
 * <p><b>Which of the three routes this is.</b> The same hardware offers three, all three were
 * measured on the RC Plus 2 (2026-10-17):
 *
 * <pre>
 *   DJI SDK (DjiMsdkBridge)        8-14 new values/s    70-125 ms, gaps to 280 ms
 *   /dev/input/event4 (this one)   ~50 reports/s        ~20 ms
 *   USB reports (RcRawJoystick)    398 reports/s        2.5 ms
 * </pre>
 *
 * <p>The USB route is the fastest and it is what the app used until this reader was added: it
 * takes the interface away from the kernel with a forced claim. That claim has two costs that
 * the numbers above do not show. It detaches the kernel's HID driver, so
 * {@code /dev/input/event4} stays in the file system but stops producing events altogether
 * (measured: 0 events in 25 s while the app held the interface, and events again the moment the
 * app was force-stopped), and while it is held nothing else can read the device either - not
 * the RC's own service, which is what turns the return button into the tap the app sees, and
 * not the user's own bridge app. This reader needs no claim: the node is {@code root:input
 * 0660} and no app permission grants that group, but the {@code shell} user is in it
 * ({@code groups=...,1004(input)}), so one {@code cat /dev/input/event4} run through Shizuku is
 * enough - the same route {@link RcShellKeys} uses for the shoulders, and one node more.
 *
 * <p><b>How the two readers are kept apart.</b> {@link #ownsInputs()} says the axes are coming
 * from here, and {@link RcRawJoystick#ownsInputs()} returns true for either reader, so the SDK's
 * older copies stay suppressed and the USB reader leaves the interface alone while this one is
 * up (see {@link #takesOverAxes(long)}); without Shizuku this reader never starts and the USB
 * path is what it always was. The two can therefore not fight over the device, which is what
 * the kernel driver would otherwise lose.
 *
 * <p><b>The node</b> is the remote's {@code DJI embedded joystick} (it is on USB as
 * {@code 2ca3:1501 "DJI Virtual Joystick"}). A capture of it while every control was moved
 * gives, in {@code struct input_event} terms:
 *
 * <pre>
 *   EV_ABS ABS_X   (0)    -32767..32767   left stick horizontal   -> stick 0
 *   EV_ABS ABS_Y   (1)    -32768..32766   left stick vertical     -> stick 1
 *   EV_ABS ABS_RX  (3)    -32767..32767   right stick horizontal  -> stick 2
 *   EV_ABS ABS_RY  (4)    -32768..32766   right stick vertical    -> stick 3
 *   EV_ABS ABS_Z   (2)         0..254     left dial               -> dial 0
 *   EV_ABS ABS_RZ  (5)         0..254     right dial              -> dial 1
 *   EV_ABS ABS_HAT0X (16)     -1/0/1      five-way left/right
 *   EV_ABS ABS_HAT0Y (17)     -1/0/1      five-way up/down  (-1 is up)
 *   EV_KEY BTN_THUMBL (317)      0/1      five-way centre press
 *   EV_KEY KEY_BACK (158)        0/1      the return button (RTN)
 *   EV_KEY BTN_TL (310), BTN_TR (311)     the record and the photo button
 * </pre>
 *
 * <p>Every one of those was mapped by pressing the buttons one at a time and watching the
 * stream, so the names are measurements, not guesses.
 *
 * <p>The controls are handed to the firmware through the entry points the SDK's own callbacks
 * use - {@link DjiMsdkBridge#nativeOnDjiStick}, {@link DjiMsdkBridge#nativeOnDjiDial},
 * {@link DjiMsdkBridge#onRawFiveWay} - so the trim mode, the key map and the five-way's
 * centring rules apply unchanged. The two buttons that Android also delivers (the return button
 * as {@code KEY_BACK}, the five-way's centre as {@code BTN_THUMBL}) go through
 * {@link RcShellKeys#nativeOnNodeKey} instead, which is the path that runs Android's key map and
 * lets {@code joystick.cpp}'s {@code noteNodeEdge()} drop whichever of the two copies arrives
 * second. The record and the photo button are deliberately not delivered from here: the SDK
 * reports both itself, and the second copy would only be a second press.
 *
 * <p>The <b>scroll wheel</b> is not in this node at all; it keeps coming from the SDK.
 *
 * <p><b>Readings that are not a change.</b> The node only emits an event when a value moves, so
 * it is silent while the sticks sit still - the 50 reports/s above is what it does while one is
 * being moved. That is also why "nothing arrived" cannot be used to decide whether the node is
 * alive, and why {@link #sLive} follows the {@code cat} process instead of the stream.
 *
 * <p>The axes are scaled into the numbers {@code nativeOnDjiStick} already expects - the SDK's
 * own, full scale 660, which is what the USB reports were normalised to as well. Whether a
 * direction comes out backwards on this remote is settled against the SDK in the verbose log
 * ({@link #logComparison}), with {@link #AXIS_SIGN} to flip one if it turns out reversed.
 *
 * <p>Without Shizuku - not installed, not running, or permission not granted - nothing here runs
 * and the sticks keep coming from the SDK and the USB reader, exactly as before.
 */
final class RcShellSticks {

    private static final String TAG = "EdgeTXUI";

    /** The remote's joystick node (see the class comment for what it carries). */
    private static final String NODE = "/dev/input/event4";

    /** struct input_event, 64-bit: timeval(16) + __u16 type + __u16 code + __s32 value. */
    private static final int EVENT_BYTES = 24;
    private static final int TYPE_AT = 16;
    private static final int CODE_AT = 18;
    private static final int VALUE_AT = 20;
    private static final int EV_KEY = 0x01;
    private static final int EV_ABS = 0x03;

    /** evdev codes on this node, named (see the capture in the class comment). */
    private static final int ABS_X = 0;
    private static final int ABS_Y = 1;
    private static final int ABS_Z = 2;
    private static final int ABS_RX = 3;
    private static final int ABS_RY = 4;
    private static final int ABS_RZ = 5;
    private static final int ABS_HAT0X = 16;
    private static final int ABS_HAT0Y = 17;
    private static final int BTN_THUMBL = 317;
    private static final int KEY_BACK = 158;

    /** The four sticks and the two dials, in the order the firmware numbers them. */
    private static final int[] AXIS_CODE = {ABS_X, ABS_Y, ABS_RX, ABS_RY, ABS_Z, ABS_RZ};
    private static final int STICK_COUNT = 4;
    private static final int AXIS_COUNT = AXIS_CODE.length;

    /**
     * What nativeOnDjiStick/nativeOnDjiDial call "full deflection": the SDK's own number and
     * the one the USB reports are normalised to as well, so all three routes agree.
     */
    private static final int SDK_FULL_SCALE = 660;

    /** The node's own ranges: the sticks are signed 16-bit, the dials 0..254 with 127 centre. */
    private static final int NODE_STICK_FULL_SCALE = 32767;
    private static final int NODE_DIAL_CENTRE = 127;
    private static final int NODE_DIAL_FULL_SCALE = 127;

    /** Sign of each of the six axes, in {@link #AXIS_CODE} order. */
    private static final int[] AXIS_SIGN = {1, 1, 1, 1, 1, 1};

    private static final String[] AXIS_NAMES = {"LH", "LV", "RH", "RV", "dialL", "dialR"};

    /**
     * The same permission {@link RcShellKeys} asks for, and the same request code: Shizuku's
     * permission is per app, not per node, so one grant covers both readers.
     */
    private static final int REQUEST_CODE = 0x4B31;
    private static final long RETRY_MS = 5000;

    /** How long start() waits for a Shizuku that is neither installed nor running. */
    private static final long BINDER_WAIT_MS = 4000;

    private static final Object LOCK = new Object();
    private static final AtomicBoolean sAskedPermission = new AtomicBoolean();
    private static Context sContext;
    private static Process sProcess;
    private static boolean sReading;

    /**
     * True while {@code cat /dev/input/event4} is running, i.e. while this reader is the one
     * feeding the axes. Follows the process, not the stream: the node sends nothing while the
     * sticks are still, so an empty read means "nothing moved", never "this is not working".
     */
    private static volatile boolean sLive;

    /** True while Shizuku might still turn up - what {@link #takesOverAxes(long)} waits on. */
    private static volatile boolean sShizukuPossible;

    /** Set once the answer is a definite no, so the USB reader stops waiting for us. */
    private static volatile boolean sDenied;

    /** The last value pushed for each axis, and whether one ever was. */
    private static final int[] sValue = new int[AXIS_COUNT];
    private static final boolean[] sSeen = new boolean[AXIS_COUNT];

    /** The five-way's two hat axes, as they were last read: -1, 0 or 1. */
    private static int sHatX;
    private static int sHatY;

    private static boolean sHatSeen;

    /** One warning per kind, not one per frame. */
    private static boolean sWarnedAxes;
    private static boolean sWarnedButtons;

    private static long sLastCompareMs;

    private static final Shizuku.OnRequestPermissionResultListener PERMISSION_RESULT =
            new Shizuku.OnRequestPermissionResultListener() {
                @Override
                public void onRequestPermissionResult(int requestCode, int grantResult) {
                    if (requestCode != REQUEST_CODE) return;
                    if (grantResult == PackageManager.PERMISSION_GRANTED) {
                        Log.i(TAG, "shizuku: granted - the sticks and dials now also come from "
                                + NODE);
                        startReader();
                    } else {
                        sDenied = true;
                        Log.w(TAG, "shizuku: permission denied; the sticks keep coming from the "
                                + "SDK and the USB reader");
                    }
                }
            };

    private static final Shizuku.OnBinderReceivedListener BINDER_UP =
            new Shizuku.OnBinderReceivedListener() {
                @Override
                public void onBinderReceived() {
                    Log.i(TAG, "shizuku: server appeared");
                    sShizukuPossible = true;
                    requestOrRead();
                }
            };

    private static final Shizuku.OnBinderDeadListener BINDER_DOWN =
            new Shizuku.OnBinderDeadListener() {
                @Override
                public void onBinderDead() {
                    // Says so to the USB reader too: with the server gone this reader is not
                    // coming back on its own, and the fallback should not keep waiting for it.
                    sShizukuPossible = false;
                    Log.w(TAG, "shizuku: server went away; the sticks fall back to the SDK");
                }
            };

    private RcShellSticks() {
    }

    /** Called once from EdgeTxApplication, next to the shoulder button reader. */
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
            Log.i(TAG, "shizuku: not available (" + t.getClass().getSimpleName()
                    + "); the sticks come from the SDK and the USB reader");
            return;
        }

        waitForBinder();
    }

    /**
     * The binder is handed to the provider a moment after the process starts, so the first look
     * almost always fails. This waits for it briefly; a binder that turns up later still reaches
     * the sticky listener above, which is why giving up here only means "do not hold the USB
     * reader back any longer".
     */
    private static void waitForBinder() {
        final Thread thread = new Thread(() -> {
            final long deadline = System.currentTimeMillis() + BINDER_WAIT_MS;
            while (System.currentTimeMillis() < deadline) {
                try {
                    if (Shizuku.pingBinder()) {
                        Log.i(TAG, "shizuku: server version " + Shizuku.getVersion());
                        sShizukuPossible = true;
                        requestOrRead();
                        return;
                    }
                } catch (Throwable t) {
                    // still not there; the next round tries again
                }
                try {
                    Thread.sleep(250);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "rc-shizuku-sticks-wait");
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
        final Thread thread = new Thread(RcShellSticks::readLoop, "rc-node-sticks");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Whether the axes are coming from the node at this moment. Read by
     * {@link RcRawJoystick#ownsInputs()} and by the USB reader before it takes the interface.
     */
    static boolean ownsInputs() {
        return sLive;
    }

    /**
     * Whether the USB reader has to leave the joystick's interface alone.
     *
     * <p>While Shizuku might still turn up this answers yes: the USB reader waits (up to
     * {@code timeoutMs}) rather than taking the device away from a reader that is about to start,
     * because that claim would leave the node silent and empty the SDK fallback both. A definite
     * no - Shizuku is not there, or the permission was refused - answers no straight away, and
     * then the USB route is exactly what it was before this class existed.
     */
    static boolean takesOverAxes(long timeoutMs) {
        final long deadline = System.currentTimeMillis() + Math.max(0, timeoutMs);
        while (true) {
            if (sLive) return true;
            if (sDenied) return false;
            if (!sShizukuPossible) return false;
            if (System.currentTimeMillis() >= deadline) return sLive;
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                return sLive;
            }
        }
    }

    /**
     * Keeps {@code cat /dev/input/event4} running. The process ends by itself only if the node
     * disappears or Shizuku restarts, and a fresh start picks it up again - axes that are read
     * by neither this nor the SDK are worth a log line every few seconds, not a silent stop.
     */
    private static void readLoop() {
        while (true) {
            Process process = null;
            try {
                process = newShellProcess(new String[]{"cat", NODE});
                sProcess = process;
                sLive = true;
                Log.i(TAG, "shizuku: reading the sticks and dials from " + NODE);

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
                sLive = false;
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
     * Runs one command as the shell user. {@code Shizuku.newProcess} is the only route to that
     * which does not mean binding a user service, but it is private in API 13: it was public in
     * 12 and is scheduled to disappear in 14, and 13.1.5 is what build.gradle.kts pins. It is a
     * plain private method of a class inside this APK, not a hidden platform API, so reflection
     * reaches it - and if Shizuku ever drops it, this throws and the reader retries, which is
     * the same as Shizuku not being there.
     */
    private static Process newShellProcess(String[] command) throws Exception {
        final Method newProcess = Shizuku.class.getDeclaredMethod("newProcess",
                String[].class, String[].class, String.class);
        newProcess.setAccessible(true);
        return (Process) newProcess.invoke(null, command, null, null);
    }

    /**
     * Turns the node's events into firmware input, one 24-byte record at a time. The sticks and
     * dials are pushed per event - an event only exists because the value moved - and the
     * five-way's directions are levels, so only a change of direction is a press, exactly as in
     * {@link RcRawJoystick}'s frame decoder.
     */
    private static void parse(InputStream in) throws Exception {
        final byte[] buffer = new byte[EVENT_BYTES * 64];
        int held = 0;

        while (true) {
            final int read = in.read(buffer, held, buffer.length - held);
            if (read <= 0) return;
            held += read;

            int at = 0;
            while (held - at >= EVENT_BYTES) {
                final int code = u16(buffer, at + CODE_AT);
                final int value = s32(buffer, at + VALUE_AT);
                switch (u16(buffer, at + TYPE_AT)) {
                    case EV_ABS:
                        axis(code, value);
                        break;
                    case EV_KEY:
                        if (code == BTN_THUMBL) {
                            // Android delivers this one too (as AKEYCODE_BUTTON_THUMBL,
                            // and the DJI SDK a third time), so it goes through the
                            // node-edge path instead of the five-way's: joystick.cpp's
                            // noteNodeEdge() drops whichever copy arrives second, and
                            // handleKeyCode() maps it to the same ENTER the SDK path
                            // would have produced.
                            nodeKey(KeyEvent.KEYCODE_BUTTON_THUMBL, value != 0);
                        } else if (code == KEY_BACK) {
                            // The return button. Android reads the same edge off this node
                            // and delivers it as BACK as well, so this is another one the
                            // node-edge path has to own - and unlike the RC service's tap it
                            // also works when the UI is gone.
                            nodeKey(KeyEvent.KEYCODE_BACK, value != 0);
                        }
                        break;
                    default:
                        // EV_SYN and everything else carry nothing this reader needs
                        break;
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

            logComparison();
        }
    }

    /** One absolute axis event: one of the six, or one of the five-way's two directions. */
    private static void axis(int code, int value) {
        for (int index = 0; index < AXIS_COUNT; index++) {
            if (AXIS_CODE[index] != code) continue;
            final int scaled = index < STICK_COUNT
                    ? (value * SDK_FULL_SCALE) / NODE_STICK_FULL_SCALE * AXIS_SIGN[index]
                    : ((value - NODE_DIAL_CENTRE) * SDK_FULL_SCALE)
                            / NODE_DIAL_FULL_SCALE * AXIS_SIGN[index];
            if (sSeen[index] && scaled == sValue[index]) {
                return;
            }
            sSeen[index] = true;
            sValue[index] = scaled;
            try {
                if (index < STICK_COUNT) {
                    DjiMsdkBridge.nativeOnDjiStick(index, scaled);
                } else {
                    DjiMsdkBridge.nativeOnDjiDial(index - STICK_COUNT, scaled);
                }
            } catch (Throwable t) {
                if (!sWarnedAxes) {
                    sWarnedAxes = true;
                    Log.w(TAG, "node joystick: the axes could not be handed to the firmware", t);
                }
            }
            return;
        }

        if (code != ABS_HAT0X && code != ABS_HAT0Y) {
            return;
        }
        if (!sHatSeen) {
            sHatSeen = true;
            sHatX = 0;
            sHatY = 0;
        }
        if (code == ABS_HAT0X) {
            if (value == sHatX) return;
            sHatX = value;
            if (value < 0) {
                fiveWay(DjiMsdkBridge.BTN_LEFT);
            } else if (value > 0) {
                fiveWay(DjiMsdkBridge.BTN_RIGHT);
            }
        } else {
            if (value == sHatY) return;
            sHatY = value;
            // Linux hat convention: -1 is up.
            if (value < 0) {
                fiveWay(DjiMsdkBridge.BTN_UP);
            } else if (value > 0) {
                fiveWay(DjiMsdkBridge.BTN_DOWN);
            }
        }
    }

    private static void fiveWay(int buttonId) {
        try {
            DjiMsdkBridge.onRawFiveWay(buttonId);
        } catch (Throwable t) {
            if (!sWarnedButtons) {
                sWarnedButtons = true;
                Log.w(TAG, "node joystick: the five-way could not be delivered", t);
            }
        }
    }

    /**
     * One key edge from the node, through the same handler Android's key events go through: the
     * key map runs, and the native side remembers the edge so its own copy of the same press -
     * Android's, or the SDK's - is dropped instead of acting twice.
     */
    private static void nodeKey(int keycode, boolean down) {
        try {
            RcShellKeys.nativeOnNodeKey(keycode, down);
        } catch (Throwable t) {
            if (!sWarnedButtons) {
                sWarnedButtons = true;
                Log.w(TAG, "node joystick: a key from the node could not be delivered", t);
            }
        }
    }

    /**
     * Once a second, and only with the {@code hid_probe} file in place, the same six axes as the
     * SDK last reported them next to the node's own numbers: the two must agree in sign, and if
     * one does not, {@link #AXIS_SIGN} is what has to flip. This is the only way to tell a
     * reversed axis from a working one short of flying it.
     */
    private static void logComparison() {
        if (!RcRawJoystick.verbose()) {
            return;
        }
        final long now = System.currentTimeMillis();
        if (now - sLastCompareMs < 1000) {
            return;
        }
        sLastCompareMs = now;

        final StringBuilder sdk = new StringBuilder();
        final StringBuilder node = new StringBuilder();
        for (int index = 0; index < AXIS_COUNT; index++) {
            if (index > 0) {
                sdk.append(' ');
                node.append(' ');
            }
            sdk.append(AXIS_NAMES[index]).append('=').append(RcRawJoystick.sdkValue(index));
            node.append(AXIS_NAMES[index]).append('=').append(sValue[index]);
        }
        Log.i(TAG, "node joystick: sdk " + sdk + " | node " + node);
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
}
