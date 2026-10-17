package com.edgetx.droidui;

import android.Manifest;
import android.app.NativeActivity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.Toast;

/**
 * The launcher activity: Android's {@link NativeActivity}, plus the two things that can only be
 * done from an activity - asking for a permission that needs a dialog, and telling the user what
 * a permission it cannot ask for is costing.
 *
 * <p>The app itself still runs from NativeActivity - this subclass only adds {@code onCreate},
 * {@code onResume}, {@code onPause} and {@code onRequestPermissionsResult} - so nothing about the
 * native side changes; the manifest names this class instead of {@code android.app.NativeActivity}
 * and everything after that (lib_name meta-data, the callbacks) is inherited.
 *
 * <p>The permission it asks for is the one Android 10 needs for the simulated SD card. Android 11
 * and later use "All files access", which the user grants once in system settings (see
 * {@link EdgeTxApplication}), but Android 10 has no such thing: the card in
 * {@code /storage/emulated/0/EdgeTX} is written with {@code WRITE_EXTERNAL_STORAGE} there, and a
 * runtime permission can only be requested from an activity. On the RC Pro (Android 10) that is
 * what puts the card where a file manager can reach it, the way it already is on every other
 * remote. Android 11+ returns immediately: the permission is not even declared for those versions
 * (maxSdkVersion 29 in the manifest), and cannot be requested.
 *
 * <p>The other two are one that needs a dialog ({@code UsbManager.requestPermission}, asked for
 * again below until it is granted - see {@link RcRawJoystick}) and one that cannot be asked for at
 * all: {@code READ_LOGS} is signature-level and adb-only, so all this activity can do about it is
 * say on the screen that it is missing and what that costs.
 */
public class EdgeTxActivity extends NativeActivity {

    private static final String TAG = "EdgeTXUI";

    /** Both runtime permissions this app asks for go through this one request. */
    private static final int REQUEST_PERMISSIONS = 1;

    /**
     * Id of the bridge button, so it is added once even though onCreate can run
     * again after a configuration change.
     *
     * <p>A made-up id in the app's own range, not a resource one: the project has
     * no layout resources and adding a first one for a single button would be a
     * larger change than the button.
     */
    private static final int BRIDGE_BUTTON_ID = 0x0B71D6;

    /** How often to put the USB dialog back up while the permission is missing. */
    private static final long USB_ASK_MS = 8000;

    /** The dialog right after the window appears, not one interval later. */
    private static final long USB_FIRST_ASK_MS = 2500;

    /**
     * How many times to ask before leaving it alone until the app comes to the front again. Five
     * is enough that a dialog missed once is not the end of it, and few enough that a deliberate
     * "no" is not answered with a dialog every eight seconds for as long as the UI is up.
     */
    private static final int USB_ASKS = 5;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private int usbAsks;

    /** Said once per process, so the reminder does not repeat at every resume. */
    private static boolean sToldAboutLogs;

    /**
     * Puts the USB permission dialog back up until the raw joystick is claimed.
     *
     * <p>Its reader is the only source of the 400 Hz sticks, the dials, the return button's real
     * long press, and the round and photo buttons on the RC Pro - and the permission behind it
     * exists only as a dialog: there is no settings screen for it, and the dialog is shown once
     * per request. Asking only from {@code onResume} therefore meant that a dialog dismissed
     * while the firmware was on screen never came back (the user's "the virtual joystick
     * permission only pops up when the app is installed"), and with it the reader stayed off for
     * the rest of the session.
     */
    private final Runnable usbAsk = new Runnable() {
        @Override
        public void run() {
            if (RcRawJoystick.ownsInputs()) {
                return;
            }
            if (usbAsks >= USB_ASKS) {
                Log.i(TAG, "raw joystick: still no USB permission after " + usbAsks
                        + " asks, leaving it until the app comes to the front again");
                return;
            }
            usbAsks++;
            RcRawJoystick.start(EdgeTxActivity.this);
            handler.postDelayed(this, USB_ASK_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        EdgeTxApplication.startFromActivity(this);
        super.onCreate(savedInstanceState);

        // The way in to the bridge's own screen: settings and diagnostics.
        //
        // This was a floating gear in an overlay window, which needed the
        // "display over other apps" permission. That permission is an appop with
        // no runtime dialog on this remote, so it could only be granted over adb,
        // and a user who had not run that command had a working remote and no
        // visible way to find out what anything was configured to do. A button in
        // this activity's own window needs nothing granted.
        addBridgeButton();

        // The two runtime permissions this app has, and neither one has a settings screen that
        // would do: the storage one Android 10 needs for the simulated card (see the class note),
        // and the location one the remote's own GPS is read with (see RcGps). Only what is
        // missing is asked for, so every start after the first asks for nothing.
        final java.util.List<String> wanted = new java.util.ArrayList<>(2);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    && Build.VERSION.SDK_INT < Build.VERSION_CODES.R
                    && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                            != PackageManager.PERMISSION_GRANTED) {
                wanted.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            }
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                wanted.add(Manifest.permission.ACCESS_FINE_LOCATION);
            }
        } catch (Throwable t) {
            Log.w(TAG, "could not look up the runtime permissions", t);
            return;
        }

        if (wanted.isEmpty()) {
            return;
        }
        try {
            Log.i(TAG, "asking for " + wanted);
            requestPermissions(wanted.toArray(new String[0]), REQUEST_PERMISSIONS);
        } catch (Throwable t) {
            // Never let a permission prompt take the app down: without storage the card simply
            // stays in the app's own directory, and without location the firmware shows no fix.
            Log.w(TAG, "could not ask for the runtime permissions", t);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // The raw joystick reports need a USB permission, and that one can only be granted
        // through a dialog: asked for from the Application, before any window exists, the
        // system refuses it without showing anything (measured on the RC Pro, where the
        // photo and record buttons are in those reports and nowhere else). Here the window
        // is there, so the dialog is real. The call is idempotent: with the permission
        // already held it only makes sure the reader is running.
        usbAsks = 0;
        handler.removeCallbacks(usbAsk);
        handler.postDelayed(usbAsk, USB_FIRST_ASK_MS);

        // Both are idempotent, and both need something this activity has just made sure of: a
        // window for the USB dialog, and the location permission for the remote's GPS.
        RcGps.start(this);

        // The channel window, if the switch on the settings page is on. Restored rather than
        // shown, so a switch left off stays off. Nothing takes it down when this activity
        // pauses: it is an overlay, and the moment it is worth having is while something else
        // is in front - see ChannelHud.
        ChannelHud.restore(this);

        tellAboutMissingLogAccess();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // A request made without a window is refused without a dialog, so do not ask while the
        // firmware is not the thing on screen; onResume starts the asking again.
        handler.removeCallbacks(usbAsk);
    }

    /**
     * Adds the button that opens the bridge's screen, as a child of this activity's
     * own window.
     *
     * <p>Deferred rather than done in onCreate, because this activity is a
     * NativeActivity and its native side calls setContentView() after onCreate
     * returns. setContentView empties android.R.id/content first, so a button
     * added in onCreate is silently removed a few milliseconds later - which looks
     * exactly like a button that was never added, and is why the first attempt at
     * this produced an activity with no way into it and no error anywhere.
     *
     * <p>onWindowFocusChanged is used rather than a posted runnable because it is
     * the first point at which the native content is definitely in place and the
     * window has its final size, and it fires again after a resume - so the
     * existing check below is what keeps it from being added twice.
     */
    private void addBridgeButton() {
        final android.view.ViewGroup root =
                (android.view.ViewGroup) getWindow().getDecorView()
                        .findViewById(android.R.id.content);
        if (root == null || root.findViewById(BRIDGE_BUTTON_ID) != null) return;

        // The firmware is drawn on a NativeSurfaceView, whose surface is
        // composited by SurfaceFlinger rather than drawn into the window. The
        // native side puts it on top - setZOrderOnTop - because it is the whole
        // screen. Anything added to the view hierarchy then lands on the window's
        // own surface, which is *underneath*, and is invisible even though it is
        // in the tree and the log says it was added.
        //
        // setZOrderMediaOverlay puts the surface back below the window's, which is
        // exactly where a full-screen SurfaceView wants to be anyway: it still
        // covers everything, because the window is transparent where the
        // SurfaceView's hole is punched, and Android views can now draw over it.
        demoteSurface(root);

        final Button button = new Button(this);
        button.setText("⚙ 桥接");
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        button.setAlpha(0.72f);
        button.setPadding(dp(8), dp(4), dp(8), dp(4));
        button.setBackgroundColor(0xB0000000);
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent(EdgeTxActivity.this, BridgeActivity.class));
                } catch (Throwable t) {
                    Log.w(TAG, "could not open the bridge screen", t);
                    Toast.makeText(EdgeTxActivity.this, "打不开桥接页面",
                            Toast.LENGTH_SHORT).show();
                }
            }
        });

        final android.widget.FrameLayout.LayoutParams lp =
                new android.widget.FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        android.view.Gravity.TOP | android.view.Gravity.END);
        lp.topMargin = dp(12);
        lp.rightMargin = dp(60);
        root.addView(button, lp);
        button.setId(BRIDGE_BUTTON_ID);
        Log.i(TAG, "bridge: the settings button is up");
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            addBridgeButton();
        }
    }

    /**
 * Takes the firmware's surface out from on top of the window.
 *
 * <p>Walks the tree because the surface view is created by the native side and
 * put somewhere we do not control - looking it up by name or by position would
 * both be a guess that breaks the next time the native side changes shape.
 *
 * <p>Only touches views that have it set already. Calling it unconditionally
 * would be harmless in itself but would move the surface on a build that had it
 * right, and "it used to work" is worth preserving rather than re-establishing on
 * every launch.
 *
 * <p>setZOrderOnTop is read back reflectively because getZOrderOnTop is hidden
 * API and the app has no business calling it. If the read fails the surface is
 * demoted anyway: the cost of doing it to a surface that was already below is
 * nothing visible, whereas guessing wrong about a surface that was above leaves
 * the button invisible.
 */
private void demoteSurface(ViewGroup root) {
        for (int i = 0; i < root.getChildCount(); i++) {
            final View child = root.getChildAt(i);
            if (child instanceof android.view.SurfaceView) {
                final android.view.SurfaceView sv = (android.view.SurfaceView) child;
                if (surfaceIsOnTop(sv)) {
                    sv.setZOrderOnTop(false);
                    sv.setZOrderMediaOverlay(true);
                    Log.i(TAG, "bridge: the firmware's surface was on top; moved it below "
                            + "so this activity's own views are visible");
                }
                continue;
            }
            if (child instanceof ViewGroup) {
                demoteSurface((ViewGroup) child);
            }
        }
    }

    /** Whether this surface asked to be above the window. False if it cannot be asked. */
    private boolean surfaceIsOnTop(android.view.SurfaceView sv) {
        try {
            final java.lang.reflect.Method m =
                    android.view.SurfaceView.class.getMethod("getZOrderOnTop");
            return Boolean.TRUE.equals(m.invoke(sv));
        } catch (Throwable t) {
            // Hidden API unavailable: assume it is on top and demote it. That is
            // the state this whole method exists to undo, so erring towards it is
            // the safe direction.
            return true;
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    /**
     * The one grant this app cannot ask for anywhere, said where the user can see it.
     *
     * <p>{@code READ_LOGS} is signature|privileged and adb-only ({@code pm grant}), and it is
     * stored per installation, so a reinstall takes it away again. Without it the remote's own
     * button log is unread (see {@link RcDpadLog}) and the landing, pause and C1..C3 buttons are
     * down to the DJI SDK alone - which loses them in waves while the DJI app holds the remote's
     * data channel. That is the "these switches only work sometimes" report, and until now the
     * only sign of it was a line in logcat. A toast costs nothing and is the difference between
     * a user who can fix it and one who cannot.
     */
    private void tellAboutMissingLogAccess() {
        if (sToldAboutLogs) {
            return;
        }
        try {
            if (checkSelfPermission(Manifest.permission.READ_LOGS)
                    == PackageManager.PERMISSION_GRANTED) {
                return;
            }
            sToldAboutLogs = true;
            Toast.makeText(this, "READ_LOGS not granted: landing, pause and C1..C3 will miss "
                            + "presses.\nRun once per install: adb shell pm grant "
                            + getPackageName() + " android.permission.READ_LOGS",
                    Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            Log.w(TAG, "could not tell the user about READ_LOGS", t);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQUEST_PERMISSIONS || permissions == null) {
            return;
        }
        for (int i = 0; i < permissions.length; i++) {
            final boolean granted = results != null && i < results.length
                    && results[i] == PackageManager.PERMISSION_GRANTED;
            if (Manifest.permission.WRITE_EXTERNAL_STORAGE.equals(permissions[i])) {
                // The card's root is decided when the link starts, which has already happened by
                // the time this answer arrives - so a grant takes effect on the next start, and
                // saying so is the point of this line.
                Log.i(TAG, "sdcard: WRITE_EXTERNAL_STORAGE " + (granted
                        ? "granted - the card moves to /storage/emulated/0/EdgeTX on the next start"
                        : "denied - the card stays in the app's own directory"));
            } else if (Manifest.permission.ACCESS_FINE_LOCATION.equals(permissions[i])) {
                Log.i(TAG, "gps: ACCESS_FINE_LOCATION " + (granted
                        ? "granted - the remote's own GPS is read from now on"
                        : "denied - EdgeTX's own GPS stays empty"));
                if (granted) {
                    // The listener could not start before the grant, so this is the moment.
                    RcGps.start(this);
                }
            }
        }
    }
}
