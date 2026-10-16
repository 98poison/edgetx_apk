package com.edgetx.droidui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The settings gear: one small translucent disc in the corner of the screen that opens the
 * network-forwarding settings, so the destination address can be changed on the radio itself.
 *
 * <p>The alternative is pushing {@code netlink.conf} over adb, which is fine for the first
 * setup and wrong for every one after it - the address of the machine that is listening
 * changes, and so does the port someone wants to try. The radio has no app list to launch a
 * settings activity from (the launcher is DJI's) and EdgeTX's own menu lives inside a prebuilt
 * library, so the way in is an overlay window drawn over the firmware's screen.
 *
 * <p>An overlay needs the "display over other apps" permission (SYSTEM_ALERT_WINDOW, an appop
 * on Android 6 and later). Android will not show a runtime dialog for it, so without it the
 * gear simply does not appear - the log line and the toast say the one command that fixes it:
 * {@code adb shell appops set com.edgetx.droidui SYSTEM_ALERT_WINDOW allow}.
 *
 * <p>What it edits is exactly the file {@link RcNetLink} reads, and saving goes through
 * {@link RcNetLink#save}, which also restarts the link - a new host or port cannot take effect
 * any other way (the sockets are opened once, at init()).
 */
final class NetSettingsOverlay {

    private static final String TAG = "EdgeTXUI";

    /** Where the gear's own position is kept, so it stays where it was dragged to. */
    private static final String PREFS = "gear";
    private static final String KEY_X = "x";
    private static final String KEY_Y = "y";

    /** The disc: small, translucent, and out of the way until it is touched. */
    private static final int SIZE_DP = 46;
    private static final int ALPHA = 130;
    private static final int INSET_DP = 24;

    private static View sGear;
    private static WindowManager sWindowManager;
    private static WindowManager.LayoutParams sParams;
    private static boolean sToldAboutPermission;

    private NetSettingsOverlay() {}

    /**
     * Puts the gear on the screen, once per process. Called from {@link EdgeTxActivity}, which
     * is where the window it draws into comes from.
     */
    static void attach(Activity activity) {
        if (sGear != null) return;

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    && !Settings.canDrawOverlays(activity)) {
                if (!sToldAboutPermission) {
                    sToldAboutPermission = true;
                    Log.i(TAG, "gear: no 'display over other apps' permission, so the settings "
                            + "gear is not shown. Grant it once with: adb shell appops set "
                            + activity.getPackageName() + " SYSTEM_ALERT_WINDOW allow");
                    Toast.makeText(activity,
                            "缺少悬浮窗权限，设置齿轮没有显示。\n电脑上执行一次：\nadb shell appops set "
                                    + activity.getPackageName() + " SYSTEM_ALERT_WINDOW allow",
                            Toast.LENGTH_LONG).show();
                }
                return;
            }

            sWindowManager = (WindowManager) activity.getSystemService(Context.WINDOW_SERVICE);
            if (sWindowManager == null) return;

            final TextView gear = new TextView(activity);
            gear.setText("⚙");
            gear.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
            gear.setTextColor(Color.WHITE);
            gear.setGravity(Gravity.CENTER);
            gear.setAlpha(0.85f);
            final GradientDrawable disc = new GradientDrawable();
            disc.setShape(GradientDrawable.OVAL);
            disc.setColor(Color.argb(ALPHA, 0x18, 0x18, 0x18));
            disc.setStroke(dp(activity, 1), Color.argb(170, 0xFF, 0xFF, 0xFF));
            gear.setBackground(disc);

            final SharedPreferences prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            sParams = new WindowManager.LayoutParams(
                    dp(activity, SIZE_DP), dp(activity, SIZE_DP),
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                            : WindowManager.LayoutParams.TYPE_PHONE,
                    // Not focusable so the firmware keeps every key press; the disc only wants
                    // touches, and a touch on it does not reach the firmware anyway.
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    android.graphics.PixelFormat.TRANSLUCENT);
            sParams.gravity = Gravity.TOP | Gravity.START;
            sParams.x = prefs.getInt(KEY_X, dp(activity, INSET_DP));
            sParams.y = prefs.getInt(KEY_Y, dp(activity, 3 * INSET_DP));

            gear.setOnTouchListener(new View.OnTouchListener() {
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
                            if (!moved && Math.abs(dx) + Math.abs(dy) < dp(view.getContext(), 10)) {
                                return true;
                            }
                            moved = true;
                            sParams.x = startX + dx;
                            sParams.y = startY + dy;
                            try {
                                sWindowManager.updateViewLayout(sGear, sParams);
                            } catch (Throwable ignored) {
                                // Window gone (the app is going away): nothing to move.
                            }
                            return true;
                        }
                        case MotionEvent.ACTION_UP:
                            if (moved) {
                                prefs.edit().putInt(KEY_X, sParams.x).putInt(KEY_Y, sParams.y)
                                        .apply();
                            } else {
                                show(view.getContext());
                            }
                            return true;
                        default:
                            return false;
                    }
                }
            });

            sWindowManager.addView(gear, sParams);
            sGear = gear;
            Log.i(TAG, "gear: the network settings button is up (tap it to change the address)");
        } catch (Throwable t) {
            // Never let a convenience take the app down; everything about the app works
            // without the gear, and netlink.conf can still be pushed over adb.
            Log.w(TAG, "gear: could not be added", t);
        }
    }

    /** Takes it down again, so it does not float over whatever starts next. */
    static void detach() {
        final View gear = sGear;
        sGear = null;
        if (gear == null || sWindowManager == null) return;
        try {
            sWindowManager.removeView(gear);
        } catch (Throwable t) {
            Log.w(TAG, "gear: could not be removed", t);
        }
    }

    /** Hides it without forgetting the position - used when the app goes to the background. */
    static void setVisible(boolean visible) {
        if (sGear != null) {
            sGear.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    // --------------------------------------------------------------- the dialog ---

    /** The settings themselves: the same keys netlink.conf has, with what they do now. */
    private static void show(Context context) {
        final RcNetLink.Config current = RcNetLink.read(context);
        final LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        final int pad = dp(context, 16);
        root.setPadding(pad, dp(context, 4), pad, 0);

        final CheckBox enabled = new CheckBox(context);
        enabled.setText("启用网络转发");
        enabled.setChecked(current.enabled);
        root.addView(enabled);

        root.addView(label(context, "目标地址（电脑的 IP）"));
        final EditText host = field(context, current.host == null ? "" : current.host,
                InputType.TYPE_CLASS_PHONE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        root.addView(host);

        // Typing a whole address on a radio is the annoying part, so offer the one thing that
        // is known for certain: this radio's own subnet, which the machine to send to is on.
        final String local = localIpv4();
        if (local != null) {
            final int lastDot = local.lastIndexOf('.');
            if (lastDot > 0) {
                final TextView subnet = new TextView(context);
                subnet.setText("填本机网段 (" + local.substring(0, lastDot + 1) + ")  ·  本机 " + local);
                subnet.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                subnet.setPadding(0, dp(context, 4), 0, dp(context, 8));
                subnet.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        host.setText(local.substring(0, lastDot + 1));
                        host.setSelection(host.getText().length());
                    }
                });
                root.addView(subnet);
            }
        }

        final LinearLayout ports = new LinearLayout(context);
        ports.setOrientation(LinearLayout.HORIZONTAL);
        final EditText port = numberField(context, String.valueOf(current.port));
        final EditText crsfPort = numberField(context, String.valueOf(current.crsfPort));
        ports.addView(titled(context, "控制端口 (JSON)", port));
        ports.addView(titled(context, "模块端口 (CRSF)", crsfPort));
        root.addView(ports);

        root.addView(label(context, "发送内容"));
        final CheckBox controls = new CheckBox(context);
        controls.setText("控制数据 (JSON)");
        controls.setChecked((current.mode & RcNetLink.STREAM_JSON) != 0);
        final CheckBox module = new CheckBox(context);
        module.setText("模块帧 (CRSF)");
        module.setChecked((current.mode & RcNetLink.STREAM_CRSF) != 0);
        root.addView(controls);
        root.addView(module);

        root.addView(label(context, "控制数据频率 (1..200 Hz)"));
        final EditText rate = numberField(context, String.valueOf(current.rate));
        root.addView(rate);

        final TextView status = new TextView(context);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        status.setPadding(0, dp(context, 10), 0, dp(context, 4));
        status.setText(statusLine(context));
        root.addView(status);

        final ScrollView scroller = new ScrollView(context);
        scroller.addView(root);

        final Handler handler = new Handler(Looper.getMainLooper());
        final Runnable tick = new Runnable() {
            @Override
            public void run() {
                status.setText(statusLine(context));
                handler.postDelayed(this, 1000);
            }
        };

        final AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle("网络转发")
                .setView(scroller)
                .setPositiveButton("保存并应用", null)
                .setNegativeButton("关闭", null)
                .create();

        dialog.setOnShowListener(new android.content.DialogInterface.OnShowListener() {
            @Override
            public void onShow(android.content.DialogInterface which) {
                handler.post(tick);
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                        .setOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View view) {
                                final String address = String.valueOf(host.getText()).trim();
                                if (address.isEmpty()) {
                                    toast(context, "目标地址不能为空");
                                    return;
                                }
                                final int portValue = number(context, port, 1, 65535, "控制端口");
                                if (portValue < 0) return;
                                final int crsfValue = number(context, crsfPort, 1, 65535, "模块端口");
                                if (crsfValue < 0) return;
                                final int rateValue = number(context, rate, 1, 200, "频率");
                                if (rateValue < 0) return;

                                final RcNetLink.Config next = new RcNetLink.Config();
                                next.enabled = enabled.isChecked();
                                next.host = address;
                                next.port = portValue;
                                next.crsfPort = crsfValue;
                                next.mode = (controls.isChecked() ? RcNetLink.STREAM_JSON : 0)
                                        | (module.isChecked() ? RcNetLink.STREAM_CRSF : 0);
                                next.rate = rateValue;
                                if (next.enabled && next.mode == 0) {
                                    toast(context, "至少选一种发送内容");
                                    return;
                                }

                                final String error = RcNetLink.save(context, next);
                                if (error != null) {
                                    toast(context, error);
                                    return;
                                }
                                Log.i(TAG, "gear: saved " + address + ":" + portValue + " / "
                                        + crsfValue + " mode " + next.mode + " at "
                                        + rateValue + " Hz, enabled=" + next.enabled);
                                toast(context, next.enabled
                                        ? "已保存并立即生效 → " + address
                                        : "已保存：网络转发关闭");
                                dialog.dismiss();
                            }
                        });
            }
        });
        dialog.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(android.content.DialogInterface which) {
                handler.removeCallbacks(tick);
            }
        });

        try {
            dialog.getWindow().setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        } catch (Throwable ignored) {
            // Before show() there may be no window yet; the default is fine.
        }
        dialog.show();
    }

    /** {@code 本机 192.168.1.22 · 已启用 · 已发 12345 帧 / 丢 0 / 1.2 MB} */
    private static String statusLine(Context context) {
        final int[] stats = RcNetLink.stats();
        final String local = localIpv4();
        return String.format(Locale.ROOT, "本机 %s · %s · 已发 %d 帧, 丢弃 %d, %s",
                local == null ? "无网络" : local,
                stats != null && stats.length > 3 && stats[3] != 0 ? "转发已启用" : "转发未启用",
                stats != null && stats.length > 0 ? stats[0] : 0,
                stats != null && stats.length > 1 ? stats[1] : 0,
                stats != null && stats.length > 2 ? bytes(stats[2]) : "0 B");
    }

    private static String bytes(int value) {
        if (value < 1024) return value + " B";
        if (value < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB", value / 1024.0);
        return String.format(Locale.ROOT, "%.1f MB", value / (1024.0 * 1024.0));
    }

    /**
     * This radio's own IPv4 address, which is the one thing about the network that is known
     * without asking: the machine to send to is on the same subnet.
     */
    private static String localIpv4() {
        try {
            final List<NetworkInterface> interfaces =
                    Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface iface : interfaces) {
                if (iface.isLoopback() || !iface.isUp()) continue;
                for (InetAddress address : Collections.list(iface.getInetAddresses())) {
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()
                            && address.isSiteLocalAddress()) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "gear: could not read the local address", t);
        }
        return null;
    }

    // ------------------------------------------------------------------ widgets ---

    private static TextView label(Context context, String text) {
        final TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        view.setPadding(0, dp(context, 10), 0, dp(context, 2));
        return view;
    }

    private static EditText field(Context context, String value, int inputType) {
        final EditText view = new EditText(context);
        view.setInputType(inputType);
        view.setSingleLine(true);
        view.setText(value);
        view.setSelection(view.getText().length());
        return view;
    }

    private static EditText numberField(Context context, String value) {
        return field(context, value, InputType.TYPE_CLASS_NUMBER);
    }

    private static LinearLayout titled(Context context, String title, View view) {
        final LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(0, 0, dp(context, 12), 0);
        column.addView(label(context, title));
        column.addView(view);
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        column.setLayoutParams(params);
        return column;
    }

    /** A number from a field, or -1 with a toast when it is not one. */
    private static int number(Context context, EditText field, int min, int max, String what) {
        final int value;
        try {
            value = Integer.parseInt(String.valueOf(field.getText()).trim());
        } catch (NumberFormatException e) {
            toast(context, what + " 不是一个数字");
            return -1;
        }
        if (value < min || value > max) {
            toast(context, what + " 需要在 " + min + ".." + max + " 之间");
            return -1;
        }
        return value;
    }

    private static void toast(Context context, String text) {
        try {
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Log.w(TAG, "gear: " + text, t);
        }
    }

    private static int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }
}
