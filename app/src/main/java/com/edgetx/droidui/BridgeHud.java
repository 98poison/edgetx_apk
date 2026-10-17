package com.edgetx.droidui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * The channel readout, drawn over whatever else is on screen.
 *
 * <p>Opened by holding the return button, or from the bridge screen. It shows
 * what the firmware is being told, live, without the app having to be in front -
 * which is the point: the useful moment to see channel 9 sitting at the wrong
 * value is while another app is open and the radio is being flown, not after
 * coming back here.
 *
 * <h3>Why it needs a permission, and why that is acceptable here</h3>
 *
 * <p>An overlay needs SYSTEM_ALERT_WINDOW, which on this remote has no runtime
 * dialog. That was the objection to the old floating gear, and it still holds -
 * but the difference is that here the permission is needed only for something
 * the user asked for by pressing a button, and where it is missing nothing else
 * is lost. The old gear was the app's only way to reach its own settings, so
 * without the appop the app had no settings at all.
 *
 * <p>{@link #canDraw(Context)} is checked before the window is added and the
 * reason is shown, rather than adding the view and letting the system fail
 * silently - an overlay that is added without the permission does not appear and
 * reports nothing, which is the same failure the old gear had.
 */
final class BridgeHud {

    private static final String TAG = "EdgeTXUI";

    private static final String PREFS = "hud";
    private static final String KEY_X = "x";
    private static final String KEY_Y = "y";

    /** Fast enough to look continuous, slow enough not to be the thing that drains the battery. */
    private static final long REFRESH_MS = 100;

    /**
     * The channel table costs an HTTP request, so it is asked for five times less
     * often than the firmware snapshot.
     *
     * <p>Two channels a second is enough to watch a value move and slow enough
     * that rcbridge's web server - a single goroutine per request on a handheld -
     * is not being asked to open twenty connections a second for a readout on the
     * other side of the same device.
     */
    private static final long CHANNEL_REFRESH_MS = 500;

    private static WindowManager windowManager;
    private static WindowManager.LayoutParams params;
    private static TextView readout;
    private static boolean toastShown;

    private static final Handler handler = new Handler(Looper.getMainLooper());

    /**
     * Repaints from the firmware snapshot.
     *
     * <p>One JNI call, so 10 a second is affordable and the values move smoothly.
     */
    private static final Runnable tick = new Runnable() {
        @Override
        public void run() {
            paint();
            handler.postDelayed(this, REFRESH_MS);
        }
    };

    /**
     * Repaints the channel rows, slower and off the main thread.
     *
     * <p>Separate from {@link #tick} because the two halves have nothing in
     * common: the firmware snapshot is a JNI call into this process, and the
     * channel table is an HTTP request to rcbridge. Asking ten times a second
     * meant ten connections a second to a server on the same device, and doing it
     * on the main thread meant each one blocked the UI for up to the timeout - so
     * the readout worked once and then appeared frozen. That is what "it shows
     * once and then stops" looked like.
     */
    private static final Runnable chanTick = new Runnable() {
        @Override
        public void run() {
            if (readout == null) return;
            RcBridgeClient.requestChannels(new RcBridgeClient.ChannelCallback() {
                @Override
                public void onChannels(String text) {
                    // Dropped if the readout went away while the request was out;
                    // paint() reads whatever is current at that moment.
                    sChannels = text == null ? "" : text;
                    paint();
                }
            });
            handler.postDelayed(this, CHANNEL_REFRESH_MS);
        }
    };

    /** The last channel table, fetched asynchronously. Empty until the first one lands. */
    private static volatile String sChannels = "";

    /** Drawn on every tick; both halves come from wherever they last were. */
    private static void paint() {
        if (readout == null) return;
        try {
            final String ch = sChannels;
            readout.setText(RcBridgeClient.snapshotText() + (ch.isEmpty() ? "" : "\n" + ch));
        } catch (Throwable t) {
            readout.setText("读取失败: " + t.getClass().getSimpleName());
        }
    }

    private BridgeHud() {}

    /** Whether the overlay can be shown, and if not, what to do about it. */
    static boolean canDraw(Context context) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context);
    }

    static String permissionHint(Context context) {
        return "缺少悬浮窗权限。\n电脑上执行一次：\nadb shell appops set "
                + context.getPackageName() + " SYSTEM_ALERT_WINDOW allow";
    }

    /** Adds or removes the window. Returns false if the permission is missing. */
    static boolean toggle(Context context) {
        if (readout != null) {
            wanted = false;
            hide();
            return true;
        }
        return show(context);
    }

    private static boolean show(Context context) {
        if (readout != null) return true;
        if (!canDraw(context)) {
            if (!toastShown) {
                toastShown = true;
                toast(context, permissionHint(context));
            }
            Log.w(TAG, "hud: no overlay permission, so nothing was shown");
            return false;
        }

        try {
            // The application context throughout, not the one that was passed in.
            // The readout outlives whatever opened it - that is the whole point -
            // and a view built on an Activity keeps that Activity, its window and
            // its view tree alive for as long as the view is in the window manager.
            // Since nothing here needs an Activity, there is nothing to lose.
            final Context app = context.getApplicationContext();
            windowManager = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
            if (windowManager == null) return false;

            wanted = true;
            readout = build(app);
            params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                            : WindowManager.LayoutParams.TYPE_PHONE,
                    // Not focusable, so every key press still reaches whatever is
                    // in front - on a radio that is the difference between the
                    // readout being useful and the readout costing you the
                    // remote.
                    //
                    // Deliberately NOT not-touchable, which is what makes it
                    // draggable: a window only intercepts touches inside its own
                    // bounds, and this one is WRAP_CONTENT, so it covers its text
                    // and nothing else. Everything beside it passes through to the
                    // app underneath exactly as before. The earlier version set
                    // NOT_TOUCHABLE to be safe and paid for it by being stuck
                    // wherever it first appeared.
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.START;
            // Dragging is set on the view in build(), which has the window's own
            // params; doing it here would need them threaded through show().

            final android.content.SharedPreferences prefs =
                    app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            // Kept off the very edge: a window pinned to x=0 is half off-screen
            // once the display's rounded corner is accounted for, and there is
            // nothing to drag it back in with.
            final int inset = dp(app, 12);
            params.x = prefs.getInt(KEY_X, inset);
            params.y = prefs.getInt(KEY_Y, 3 * inset);
            clamp(params, app);

            windowManager.addView(readout, params);
            Log.i(TAG, "hud: the channel readout is up");
            handler.post(tick);
            handler.post(chanTick);
            return true;
        } catch (Throwable t) {
            // Adding an overlay without the permission throws on some versions and
            // silently does nothing on others; both land here, and both mean the
            // same thing to the user.
            readout = null;
            Log.w(TAG, "hud: could not be added", t);
            toast(context, permissionHint(context));
            return false;
        }
    }

    private static void hide() {
        handler.removeCallbacks(tick);
        handler.removeCallbacks(chanTick);
        final TextView v = readout;
        readout = null;
        if (v == null || windowManager == null) return;
        try {
            windowManager.removeView(v);
        } catch (Throwable t) {
            Log.w(TAG, "hud: could not be removed", t);
        }
    }

    /**
     * Brings the readout back only if it was up before something took it down.
     *
     * <p>Used by the bridge screen, which takes it down while it is in front - the
     * readout sits in the top-left corner, which is where that screen's title and
     * tabs are, and being a separate window it draws over them.
     *
     * <p>Conditional rather than unconditional, so that hiding the readout in
     * order to open the settings does not quietly re-open it again on the way
     * back out. Tapping the readout hides it for good as far as this is
     * concerned; only the button on the settings page brings it back, and that is
     * the one that says so.
     *
     * @param wantedNow false to take it down, true to put it back if it was up.
     */
    static void restore(Context context, boolean wantedNow) {
        if (wantedNow) {
            if (wanted) {
                show(context);
            }
        } else {
            // remember whether it was up, so restore(true) can put it back
            if (readout != null) {
                wanted = true;
                hide();
            }
        }
    }

    /**
     * Whether the readout was up the last time it was hidden.
     *
     * <p>Kept rather than read from the window, because {@link #hide} clears the
     * window before anything could ask.
     */
    private static boolean wanted;

    static boolean isShowing() {
        return readout != null;
    }

    /**
 * Lets the readout be dragged, and remembers where it was put.
 *
 * <p>Both HUD and badge use this. It is written once here rather than twice
 * because they are the same gesture over the same window manager, and the part
 * that matters - the drag threshold, and not treating a release as a click - is
 * easy to get subtly wrong in a copy.
 *
 * <p>A press that does not move is a click and goes through to the view's own
 * listener; a press that does move is a drag and is consumed. Without the
 * threshold, every drag would also fire the click, which on the badge means
 * opening a page the user was trying to move the icon away from.
 *
 * <p>Coordinates are clamped so the window cannot be dragged off screen, where
 * there would be no way to get it back: there is no launcher entry for an
 * overlay, and no gesture that reaches a window the user cannot see.
 */
static void makeDraggable(final Context app, final View handle,
        final WindowManager.LayoutParams lp, final String prefsName) {
        final WindowManager wm =
                (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
        if (wm == null || lp == null) return;

        final android.content.SharedPreferences prefs =
                app.getSharedPreferences(prefsName, Context.MODE_PRIVATE);

        handle.setOnTouchListener(new View.OnTouchListener() {
            private float downRawX, downRawY;
            private int startX, startY;
            private boolean dragging;

            @Override
            public boolean onTouch(View view, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawX = event.getRawX();
                        downRawY = event.getRawY();
                        startX = lp.x;
                        startY = lp.y;
                        dragging = false;
                        return true;

                    case MotionEvent.ACTION_MOVE: {
                        final int dx = (int) (event.getRawX() - downRawX);
                        final int dy = (int) (event.getRawY() - downRawY);
                        if (!dragging && Math.abs(dx) + Math.abs(dy) < dp(app, 6)) {
                            return true;
                        }
                        dragging = true;
                        lp.x = clamp(app, lp, startX + dx, true);
                        lp.y = clamp(app, lp, startY + dy, false);
                        try {
                            wm.updateViewLayout(handle, lp);
                        } catch (Throwable ignored) {
                            // The window has gone; nothing left to move.
                        }
                        return true;
                    }

                    case MotionEvent.ACTION_UP:
                        if (dragging) {
                            prefs.edit().putInt(KEY_X, lp.x).putInt(KEY_Y, lp.y).apply();
                            return true;
                        }
                        // Not a drag, so it was a tap: hand it to the view's own
                        // listener. Doing this by hand rather than returning false
                        // is what makes the badge both draggable and tappable.
                        view.performClick();
                        return true;

                    default:
                        return false;
                }
            }
        });
    }

    /**
     * Keeps a coordinate inside the display, allowing the window most of the way
     * past an edge but not all of it.
     *
     * <p>Not clamped to zero, because a window pushed entirely beyond an edge
     * cannot be brought back: an overlay has no launcher entry and no gesture
     * reaches something the user cannot see. Letting it overhang keeps an edge of
     * it always there to grab.
     */
    private static int clamp(Context app, WindowManager.LayoutParams lp, int value,
            boolean horizontal) {
        final android.util.DisplayMetrics m = app.getResources().getDisplayMetrics();
        final int extent = horizontal ? m.widthPixels : m.heightPixels;
        final int size = horizontal ? lp.width : lp.height;
        return Math.max(-size + dp(app, 40), Math.min(value, extent - dp(app, 20)));
    }

    /** Clamps a stored position before the window is added. */
    static void clamp(WindowManager.LayoutParams lp, android.content.Context app) {
        if (lp == null) return;
        lp.x = clamp(app, lp, lp.x, true);
        lp.y = clamp(app, lp, lp.y, false);
    }
/**
     * The readout itself.
     *
     * <p>A monospaced TextView rather than a grid of bars: the values are numbers
     * and the useful comparison is between two of them in a column, which text in
     * one block does better than shapes would. A bar per channel would look
     * better and be harder to read a number off.
     */
    @SuppressLint("SetTextI18n")
    private static TextView build(Context context) {
        final TextView text = new TextView(context);
        text.setTypeface(Typeface.MONOSPACE);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        text.setTextColor(Color.WHITE);
        text.setPadding(dp(context, 8), dp(context, 6), dp(context, 8), dp(context, 6));
        // A dark card behind the text: this floats over other apps, which may be
        // any colour at all, and white-on-white is unreadable.
        text.setBackgroundColor(0xCC000000);
        text.setText(RcBridgeClient.snapshotText());
        // Tapping it hides it. It is up to be glanced at, and the way back is one
        // tap in the settings page - so the gesture that dismisses it is the one
        // the hand is already making when it decides it has read enough.
        //
        // Dragging is on the same view; see makeDraggable. The two coexist because
        // the drag handler only calls performClick when the press did not move.
        text.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                wanted = false;
                hide();
            }
        });
        makeDraggable(context, text, params, PREFS);
        return text;
    }

    private static int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static void toast(Context context, String text) {
        try {
            Toast.makeText(context, text, Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            Log.w(TAG, "hud toast: " + text, t);
        }
    }
}
