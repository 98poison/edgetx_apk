package com.edgetx.droidui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * The always-present entry to the bridge's screen.
 *
 * <h3>Why this is a window and not a view</h3>
 *
 * <p>It started as a {@link android.widget.Button} added to the firmware
 * activity's own content view, which is the obvious thing to do and does not
 * work. The firmware is rendered on a NativeSurfaceView with setZOrderOnTop, so
 * that surface is composited by SurfaceFlinger <em>above</em> the activity's
 * window. The button was added, the log said so, uiautomator could not find it,
 * and it was never on screen. Demoting the surface with setZOrderMediaOverlay
 * is the documented way out and is in {@link EdgeTxActivity}, but it is a fight
 * with the native side's own z-order, which this app does not own, and it has
 * not been shown to leave the firmware drawing correctly.
 *
 * <p>A separate window is not layered into the app's window at all, so nothing
 * the activity does to its own hierarchy can put it underneath.
 *
 * <h3>Why the button is here at all</h3>
 *
 * <p>Because the other two ways in both failed on this hardware. The gesture -
 * double-tapping a custom button - is genuinely awkward to hit: the measured gap
 * between two taps of a real double-tap on this remote was 463 ms, and the
 * button's own press length makes the second tap easy to miss. And a gesture
 * nobody has been told about is one nobody uses.
 *
 * <p>So this is the primary entry, and the gesture stays as a shortcut for people
 * who use it. It costs a permission, which is why it is not the only one - but
 * unlike the old floating gear, this is not the app's sole way to reach its own
 * settings, so not having the permission costs one entry point rather than all
 * of them.
 *
 * <h3>Touch and focus</h3>
 *
 * <p>FLAG_NOT_FOCUSABLE, so every key press still reaches whatever is in front.
 * A system dialog with the keyboard hidden and the back key working matters here:
 * the remote is normally being used to fly something, and an overlay that ate the
 * return button would be unusable.
 */
final class BridgeBadge {

    private static final String TAG = "EdgeTXUI";

    private static final String PREFS = "badge";
    private static final String KEY_X = "x";
    private static final String KEY_Y = "y";
    private static final String KEY_HIDDEN = "hidden";

    /** Small, and dimmed. It is there to be found, not to be looked at. */
    private static final int SIZE_DP = 40;
    private static final int MARGIN_DP = 10;

    private static WindowManager windowManager;
    private static WindowManager.LayoutParams params;
    private static View view;
    private static boolean toldAboutPermission;

    private BridgeBadge() {}

    static boolean canDraw(Context context) {
        return android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M
                || android.provider.Settings.canDrawOverlays(context);
    }

    static String permissionHint(Context context) {
        return "入口图标需要悬浮窗权限。\n电脑上执行一次：\nadb shell appops set "
                + context.getPackageName() + " SYSTEM_ALERT_WINDOW allow\n\n"
                + "没有它，双击 C1/C2/C3 仍然可以打开这个页面。";
    }

    /** Adds the window, or does nothing if it is already up. */
    static void show(final Context context) {
        if (view != null) return;

        final Context app = context.getApplicationContext();
        if (hiddenByUser(app)) {
            // Said every time, not once per process. This is the state where
            // nothing appears and no log line explains it: the icon was long-pressed
            // away at some earlier point, the choice was remembered, and from then
            // on show() returned without a word. It looked exactly like the
            // permission being missing or the window failing to add.
            Log.i(TAG, "badge: not showing - it was long-pressed away and that choice "
                    + "is remembered. Long-press is how it is dismissed; the bridge "
                    + "page's \"显示左上角的入口图标\" button brings it back.");
            return;
        }

        if (!canDraw(app)) {
            if (!toldAboutPermission) {
                toldAboutPermission = true;
                Log.i(TAG, "badge: no overlay permission, so there is no entry icon. "
                        + "The C1/C2/C3 double-tap still opens the bridge screen.");
                toast(app, "没有悬浮窗权限，入口图标没显示。\n"
                        + "双击 C1/C2/C3 仍然可以打开桥接页面。");
            }
            return;
        }

        try {
            windowManager = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
            if (windowManager == null) return;

            view = build(app);
            params = new WindowManager.LayoutParams(
                    dp(app, SIZE_DP), dp(app, SIZE_DP),
                    android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O
                            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                            : WindowManager.LayoutParams.TYPE_PHONE,
                    // Not focusable: key presses belong to the app underneath.
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.START;

            final android.content.SharedPreferences prefs =
                    app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            final int margin = dp(app, MARGIN_DP);
            params.x = prefs.getInt(KEY_X, margin);
            // Below the status strip rather than in it: the remote's clock and
            // battery live there, and a badge over the clock is a badge nobody can
            // read. It is draggable, so this is only where it starts.
            params.y = prefs.getInt(KEY_Y, margin + dp(app, 28));
            BridgeHud.clamp(params, app);

            windowManager.addView(view, params);
            Log.i(TAG, "badge: the bridge entry is up");
        } catch (Throwable t) {
            view = null;
            Log.w(TAG, "badge: could not be added", t);
            toast(app, permissionHint(app));
        }
    }

    static void hide() {
        final View v = view;
        view = null;
        if (v == null || windowManager == null) return;
        try {
            windowManager.removeView(v);
            Log.i(TAG, "badge: hidden");
        } catch (Throwable t) {
            Log.w(TAG, "badge: could not be removed", t);
        }
    }

    /**
     * Hides it and remembers.
     *
     * <p>For somebody who wants the screen while the badge is in the way - which
     * is the only real objection to it. Remembered, because a badge that reappears
     * every launch after being dismissed once is worse than one that is always
     * there.
     */
    static void hideForGood(Context context) {
        hide();
        context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_HIDDEN, true).apply();
    }

    static void showAgain(Context context) {
        final android.content.SharedPreferences p =
                context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        p.edit().putBoolean(KEY_HIDDEN, false).apply();
        show(context);
    }

    static boolean isShowing() {
        return view != null;
    }

    @SuppressLint("SetTextI18n")
    private static View build(final Context context) {
        // A TextView rather than an ImageView: the project's drawable resources are
        // the firmware's and there is no icon to reuse, and a glyph is legible at
        // 40 dp where a downscaled bitmap would not be.
        final TextView badge = new TextView(context);
        badge.setText("⚙");
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        badge.setTextColor(Color.WHITE);
        badge.setGravity(Gravity.CENTER);
        badge.setBackgroundColor(0xC0202028);
        badge.setAlpha(0.8f);
        badge.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    context.startActivity(new android.content.Intent(
                            context, BridgeActivity.class)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                                    | android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP));
                } catch (Throwable t) {
                    Log.w(TAG, "badge: could not open the bridge screen", t);
                    toast(context, "打不开桥接页面");
                }
            }
        });
        // A long press is the way to get rid of it: a single tap has to open the
        // screen, and there is nowhere else on a 40 dp target for a second
        // control.
        badge.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                hideForGood(context);
                toast(context, "入口图标已隐藏。\n在桥接页面里可以再打开。");
                return true;
            }
        });
        // Draggable, because the default position is under the status strip and
        // every remote puts something there. The handler lives in BridgeHud so
        // that the two windows cannot drift apart on the drag threshold; it takes
        // this window's own params and its own preference file, because the badge
        // and the readout are moved independently and must not share a position.
        BridgeHud.makeDraggable(context, badge, params, PREFS);
        return badge;
    }

    private static boolean hiddenByUser(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_HIDDEN, false);
    }

    /** Shown while the bridge screen is on screen, so the badge is not behind it. */
    static void onBridgeScreenChanged(Context context, boolean visible) {
        if (visible) {
            hide();
        } else {
            show(context);
        }
    }

    private static int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static void toast(Context context, String text) {
        try {
            Toast.makeText(context, text, Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            Log.w(TAG, "badge toast: " + text, t);
        }
    }
}