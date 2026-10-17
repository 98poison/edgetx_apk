package com.edgetx.droidui;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
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

import java.util.Locale;

/**
 * The channel monitor: a floating window with all 16 mixed channels as 0..100%, switched on and
 * off from the network-forwarding page.
 *
 * <p>It is deliberately not part of that page. The point of a channel monitor is to be readable
 * <em>while the radio is on screen</em> - a page has to be opened and the firmware stops drawing
 * behind it - so the switch on the page turns this window on and leaves it there.
 *
 * <p>The values come from the firmware's own mixer outputs through {@link RcChannels}, so what is
 * on the window is what the RF module is being fed. They are read ten times a second: fast enough
 * to watch a stick move, slow enough to be invisible in the profile.
 *
 * <p>An overlay window needs the "display over other apps" permission, the same one the settings
 * gear needs - granted once with {@code adb shell appops set com.edgetx.droidui
 * SYSTEM_ALERT_WINDOW allow}. Drag it to move it, tap it to hide it (which is the same as turning
 * the switch off on the page: the state is one preference, {@code channels/show}).
 */
final class ChannelHud {

    private static final String TAG = "EdgeTXUI";

    private static final String PREFS = "channels";
    private static final String KEY_SHOW = "show";
    private static final String KEY_X = "x";
    private static final String KEY_Y = "y";

    /** How often the values are read, in milliseconds. */
    private static final long TICK_MS = 100;

    /** Characters in a row's bar. */
    private static final int BAR = 14;

    private static View sPanel;
    private static WindowManager sWindowManager;
    private static WindowManager.LayoutParams sParams;
    private static TextView[] sRows;
    private static Handler sHandler;
    private static Runnable sTick;
    private static boolean sToldAboutPermission;

    /** Whether the window should be hidden while it exists - see {@link #setVisible}. */
    private static boolean sHidden;

    private ChannelHud() {}

    // ------------------------------------------------------------------ state ---

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Whether the switch on the page is on. */
    static boolean isEnabled(Context context) {
        return prefs(context).getBoolean(KEY_SHOW, false);
    }

    /** Flicks the switch, from the page or from a tap on the window itself. */
    static void setEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_SHOW, enabled).apply();
        if (enabled) {
            show(context);
        } else {
            remove();
        }
    }

    /**
     * Puts the window back if the switch is on. Called from {@link EdgeTxActivity} at start-up,
     * so the monitor comes back with the app rather than only when the page is opened.
     */
    static void restore(Activity activity) {
        if (!isEnabled(activity)) return;
        show(activity);
    }

    /**
     * Hides it without forgetting the switch - used when something else is in front, such as the
     * settings page (it starts in the top-left corner, which is where that page's own content is,
     * and a separate window is composited above the activity's, so nothing inside the page can be
     * laid out around it).
     *
     * <p>Remembered as well as applied, because the switch on the page can be flicked while the
     * page is up: the window is then created hidden and appears on the way back out, rather than
     * landing on top of the page that just turned it on.
     */
    static void setVisible(boolean visible) {
        sHidden = !visible;
        if (sPanel != null) {
            sPanel.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    // ------------------------------------------------------------------ window ---

    private static void show(Context context) {
        if (sPanel != null) {
            sPanel.setVisibility(sHidden ? View.GONE : View.VISIBLE);
            startTicking();
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    && !Settings.canDrawOverlays(context)) {
                if (!sToldAboutPermission) {
                    sToldAboutPermission = true;
                    Log.i(TAG, "channels: no 'display over other apps' permission, so the channel "
                            + "window is not shown. Grant it once with: adb shell appops set "
                            + context.getPackageName() + " SYSTEM_ALERT_WINDOW allow");
                    Toast.makeText(context,
                            "缺少悬浮窗权限，通道浮窗没有显示。\n电脑上执行一次：\nadb shell appops set "
                                    + context.getPackageName() + " SYSTEM_ALERT_WINDOW allow",
                            Toast.LENGTH_LONG).show();
                }
                return;
            }

            sWindowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            if (sWindowManager == null) return;

            final SharedPreferences prefs = prefs(context);
            sParams = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                            : WindowManager.LayoutParams.TYPE_PHONE,
                    // Not focusable, so the firmware keeps every key press; only
                    // touches that land on the window itself are taken.
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    android.graphics.PixelFormat.TRANSLUCENT);
            sParams.gravity = Gravity.TOP | Gravity.START;
            sParams.x = prefs.getInt(KEY_X, dp(context, 8));
            sParams.y = prefs.getInt(KEY_Y, dp(context, 8));

            final LinearLayout panel = buildPanel(context);
            panel.setOnTouchListener(new View.OnTouchListener() {
                private float downX;
                private float downY;
                private int startX;
                private int startY;
                private boolean moved;

                @Override
                public boolean onTouch(View view, MotionEvent event) {
                    switch (event.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            downX = event.getRawX();
                            downY = event.getRawY();
                            startX = sParams.x;
                            startY = sParams.y;
                            moved = false;
                            return true;
                        case MotionEvent.ACTION_MOVE: {
                            final int dx = (int) (event.getRawX() - downX);
                            final int dy = (int) (event.getRawY() - downY);
                            if (!moved && Math.abs(dx) + Math.abs(dy) < dp(view.getContext(), 12)) {
                                return true;
                            }
                            moved = true;
                            sParams.x = startX + dx;
                            sParams.y = startY + dy;
                            try {
                                sWindowManager.updateViewLayout(sPanel, sParams);
                            } catch (Throwable ignored) {
                                // Window gone: nothing to move.
                            }
                            return true;
                        }
                        case MotionEvent.ACTION_UP:
                            if (moved) {
                                prefs.edit().putInt(KEY_X, sParams.x).putInt(KEY_Y, sParams.y)
                                        .apply();
                            } else {
                                // A tap hides it, and says so on the page: one switch,
                                // two places to flick it.
                                Log.i(TAG, "channels: hidden by a tap on the window");
                                setEnabled(view.getContext(), false);
                            }
                            return true;
                        default:
                            return false;
                    }
                }
            });

            sWindowManager.addView(panel, sParams);
            sPanel = panel;
            if (sHidden) panel.setVisibility(View.GONE);
            startTicking();
            Log.i(TAG, "channels: the channel window is up (16 channels, 0..100%, 50 = centre)");
        } catch (Throwable t) {
            // A monitor that cannot be drawn must not take the app down.
            Log.w(TAG, "channels: could not be added", t);
        }
    }

    private static LinearLayout buildPanel(Context context) {
        final LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        final int pad = dp(context, 10);
        panel.setPadding(pad, dp(context, 6), pad, dp(context, 8));

        final GradientDrawable background = new GradientDrawable();
        background.setColor(Color.argb(204, 0, 0, 0));
        background.setCornerRadius(dp(context, 8));
        background.setStroke(dp(context, 1), Color.argb(150, 0xFF, 0xFF, 0xFF));
        panel.setBackground(background);

        final TextView title = new TextView(context);
        title.setText("通道 0–100%（50 = 中位）· 点一下隐藏");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        title.setTextColor(Color.argb(200, 0xC0, 0xE0, 0xFF));
        title.setPadding(0, 0, 0, dp(context, 4));
        panel.addView(title);

        final LinearLayout columns = new LinearLayout(context);
        columns.setOrientation(LinearLayout.HORIZONTAL);
        final LinearLayout left = new LinearLayout(context);
        left.setOrientation(LinearLayout.VERTICAL);
        final LinearLayout right = new LinearLayout(context);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setPadding(dp(context, 12), 0, 0, 0);
        columns.addView(left);
        columns.addView(right);
        panel.addView(columns);

        sRows = new TextView[RcChannels.COUNT];
        for (int i = 0; i < RcChannels.COUNT; ++i) {
            final TextView row = new TextView(context);
            // Monospace, so the numbers and the bars line up column by column and
            // a moving stick is read by position rather than by re-reading.
            row.setTypeface(android.graphics.Typeface.MONOSPACE);
            row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            row.setTextColor(Color.WHITE);
            row.setIncludeFontPadding(false);
            sRows[i] = row;
            (i < RcChannels.COUNT / 2 ? left : right).addView(row);
        }
        return panel;
    }

    private static void remove() {
        stopTicking();
        final View panel = sPanel;
        sPanel = null;
        sRows = null;
        if (panel == null || sWindowManager == null) return;
        try {
            sWindowManager.removeView(panel);
        } catch (Throwable t) {
            Log.w(TAG, "channels: could not be removed", t);
        }
    }

    // ------------------------------------------------------------------- values ---

    private static void startTicking() {
        if (sHandler == null) sHandler = new Handler(Looper.getMainLooper());
        if (sTick != null) return;
        sTick = new Runnable() {
            @Override
            public void run() {
                refresh();
                if (sTick != null) sHandler.postDelayed(this, TICK_MS);
            }
        };
        sHandler.post(sTick);
    }

    private static void stopTicking() {
        if (sHandler != null && sTick != null) sHandler.removeCallbacks(sTick);
        sTick = null;
    }

    private static void refresh() {
        final TextView[] rows = sRows;
        if (rows == null) return;
        final int[] values = new int[RcChannels.COUNT];
        final int count = RcChannels.read(values);
        for (int i = 0; i < rows.length; ++i) {
            if (rows[i] == null) continue;
            if (i >= count) {
                rows[i].setText(String.format(Locale.ROOT, "CH%02d   --", i + 1));
                continue;
            }
            final int percent = RcChannels.percent(values[i]);
            rows[i].setText(String.format(Locale.ROOT, "CH%02d %3d%% %s", i + 1, percent,
                    bar(percent)));
        }
    }

    /** {@code 50%} becomes seven filled blocks out of {@link #BAR}. */
    private static String bar(int percent) {
        final int filled = percent * BAR / 100;
        final StringBuilder text = new StringBuilder(BAR);
        for (int i = 0; i < BAR; ++i) {
            text.append(i < filled ? '\u2588' : '\u00b7');
        }
        return text.toString();
    }

    private static int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }
}
