package com.edgetx.droidui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
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
 * The network-forwarding page: what to send, where to send it, and how long it is taking.
 *
 * <p>This is the page as a fragment of layout rather than an activity, which is what lets it be
 * the first tab of {@link BridgeActivity} - the screen the &#9881; button in the firmware's own
 * window opens - without a second copy of every widget. The name is a leftover from when the
 * settings lived in an AlertDialog hanging off a floating gear; the gear is gone, and so is the
 * dialog, and this is a page.
 *
 * <p>Three groups, in the order they matter:
 *
 * <ul>
 *   <li><b>The link</b> - the four keys {@link RcNetLink} reads: the host, the two ports, which
 *       streams to send and at what rate. Written straight to the config file and applied to
 *       the running link, so a change takes effect on the radio without adb.</li>
 *   <li><b>The two latency figures</b> - {@link RcNetLink#latency()} for how long this radio
 *       takes to get a frame out (firmware channels to {@code sendto()}), and
 *       {@link RcNetLink#roundTrip()} for the whole trip when a host bounces the probe back.
 *       They are read twice a second while the page is up.</li>
 *   <li><b>The channel window</b> - the switch for {@link ChannelHud}, which is a floating
 *       window and deliberately not a table here: this page covers the firmware, so a table
 *       here would be a channel monitor that only works while the radio is not running.</li>
 * </ul>
 */
final class NetSettingsOverlay {

    private static final String TAG = "EdgeTXUI";

    private NetSettingsOverlay() {}

    static View build(Context context) {
        return build(context, null);
    }

    /**
     * The page.
     *
     * @param onSaved run after the settings were saved and applied. {@link BridgeActivity} uses
     *                it to go back to the firmware, which is what a save means there: the page
     *                was opened to change one thing and there is nothing else to see.
     */
    static View build(Context context, final Runnable onSaved) {
        final LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        final int pad = dp(context, 16);
        root.setPadding(pad, dp(context, 8), pad, dp(context, 24));

        final RcNetLink.Config current = RcNetLink.read(context);

        root.addView(header(context, "网络转发"));

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
                subnet.setText("填本机网段 (" + local.substring(0, lastDot + 1) + ")  ·  本机 "
                        + local);
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
        controls.setText("控制数据 (JSON) — 摇杆/开关/按键的原始值");
        controls.setChecked((current.mode & RcNetLink.STREAM_JSON) != 0);
        final CheckBox module = new CheckBox(context);
        module.setText("模块帧 (CRSF) — 混控后的 16 个通道");
        module.setChecked((current.mode & RcNetLink.STREAM_CRSF) != 0);
        root.addView(controls);
        root.addView(module);

        root.addView(label(context, "控制数据频率 (1..200 Hz)"));
        final EditText rate = numberField(context, String.valueOf(current.rate));
        root.addView(rate);

        final CheckBox echo = new CheckBox(context);
        echo.setText("回声测距：测手机 ↔ 电脑的往返延迟");
        echo.setChecked(current.echo);
        root.addView(echo);

        final TextView echoHint = new TextView(context);
        echoHint.setText("打开后，电脑上要跑 tools\\echo-udp.ps1 把探针弹回来；"
                + "没跑就只有单向的发送延迟。");
        echoHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        echoHint.setTextColor(Color.argb(200, 0x90, 0x90, 0x90));
        echoHint.setPadding(0, 0, 0, dp(context, 8));
        root.addView(echoHint);

        final Button save = new Button(context);
        save.setText("保存并应用");
        root.addView(save);

        root.addView(divider(context));

        // ------------------------------------------------------------- the readouts ---

        root.addView(header(context, "状态与延迟"));
        final TextView status = new TextView(context);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        status.setTypeface(Typeface.MONOSPACE);
        root.addView(status);

        final TextView latency = new TextView(context);
        latency.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        latency.setTypeface(Typeface.MONOSPACE);
        latency.setPadding(0, dp(context, 6), 0, dp(context, 8));
        root.addView(latency);

        final TextView latencyHint = new TextView(context);
        latencyHint.setText("通道延迟 = 固件把 16 个通道交给模块驱动 → UDP 发出。"
                + "控制延迟 = 摇杆/旋钮动了 → 快照发到电脑。两者都不含空中和电脑那一段，"
                + "那一段看「往返」。");
        latencyHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        latencyHint.setTextColor(Color.argb(200, 0x90, 0x90, 0x90));
        root.addView(latencyHint);

        root.addView(divider(context));

        // ------------------------------------------------------------ the channel window ---

        root.addView(header(context, "通道浮窗"));
        final CheckBox channels = new CheckBox(context);
        channels.setText("显示通道浮窗（16 通道，0–100%）");
        channels.setChecked(ChannelHud.isEnabled(context));
        channels.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton button, boolean checked) {
                ChannelHud.setEnabled(button.getContext(), checked);
                toast(button.getContext(), checked
                        ? "通道浮窗已打开：回到遥控界面就能看到，拖动可移动，点一下隐藏"
                        : "通道浮窗已关闭");
            }
        });
        root.addView(channels);

        final TextView channelHint = new TextView(context);
        channelHint.setText("浮窗显示的是固件混控之后的通道值，也就是 RF 模块正在收到的值"
                + "（50% = 中位）。开关状态会记住，重启 App 后自己回来。");
        channelHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        channelHint.setTextColor(Color.argb(200, 0x90, 0x90, 0x90));
        root.addView(channelHint);

        save.setOnClickListener(new View.OnClickListener() {
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
                next.echo = echo.isChecked();
                if (next.enabled && next.mode == 0) {
                    toast(context, "至少选一种发送内容");
                    return;
                }

                final String error = RcNetLink.save(context, next);
                if (error != null) {
                    toast(context, error);
                    return;
                }
                Log.i(TAG, "net page: saved " + address + ":" + portValue + " / " + crsfValue
                        + " mode " + next.mode + " at " + rateValue + " Hz, enabled="
                        + next.enabled + ", echo=" + next.echo);
                toast(context, next.enabled
                        ? "已保存并立即生效 → " + address
                        : "已保存：网络转发关闭");
                if (onSaved != null) {
                    onSaved.run();
                }
            }
        });

        final ScrollView scroller = new ScrollView(context);
        scroller.addView(root);

        // The readouts are only worth refreshing while somebody is looking at them. Tying the
        // timer to being attached rather than to an activity's lifecycle is what lets the same
        // page be a tab here and, later, a screen of its own without either one having to
        // remember to stop it - and it is why stop() below has nothing left to do.
        scroller.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            private final Handler handler = new Handler(Looper.getMainLooper());
            private final Runnable tick = new Runnable() {
                @Override
                public void run() {
                    status.setText(statusLine());
                    latency.setText(latencyLines());
                    handler.postDelayed(this, 500);
                }
            };

            @Override
            public void onViewAttachedToWindow(View view) {
                handler.post(tick);
            }

            @Override
            public void onViewDetachedFromWindow(View view) {
                handler.removeCallbacks(tick);
            }
        });
        return scroller;
    }

    /**
     * Kept for {@link BridgeActivity}, which called this on the way out.
     *
     * <p>It used to stop the page's own timer, which re-posted itself once a second and held
     * every field alive after the page was gone. The timer is now tied to the page being
     * attached to a window, so it stops by itself; this is kept rather than removed so the
     * caller does not have to know that.
     */
    static void stop() {
        // Nothing to do: see the attach-state listener in build().
    }

    // -------------------------------------------------------------------- readouts ---

    /** {@code 本机 192.168.1.22 · 转发已启用 · 已发 12345 帧, 丢弃 0, 1.2 MB} */
    private static String statusLine() {
        final int[] stats = RcNetLink.stats();
        final String local = localIpv4();
        return String.format(Locale.ROOT, "本机 %s · %s · 已发 %d 帧, 丢弃 %d, %s",
                local == null ? "无网络" : local,
                stats != null && stats.length > 3 && stats[3] != 0 ? "转发已启用" : "转发未启用",
                stats != null && stats.length > 0 ? stats[0] : 0,
                stats != null && stats.length > 1 ? stats[1] : 0,
                stats != null && stats.length > 2 ? bytes(stats[2]) : "0 B");
    }

    /**
     * The two latency figures and the round trip.
     *
     * <p>All of them are microseconds on the wire and read as milliseconds here, which is the
     * unit the numbers are argued about in: the reference the RF side is judged against is one
     * 100 Hz frame, 10 ms.
     */
    private static String latencyLines() {
        final StringBuilder text = new StringBuilder();
        final int[] latency = RcNetLink.latency();
        if (latency == null || latency.length < 10) {
            text.append("延迟: 读不到（native 未就绪）");
        } else if (latency[9] == 0) {
            text.append("延迟: 还没有发过通道帧（「模块帧」选了吗？）");
        } else {
            text.append(String.format(Locale.ROOT, "通道  p50 %s / p95 %s / 最大 %s   (n=%d)",
                    ms(latency[0]), ms(latency[1]), ms(latency[2]), latency[9]));
        }
        if (latency != null && latency.length >= 10) {
            text.append('\n').append(String.format(Locale.ROOT,
                    "控制  p50 %s / p95 %s / 最大 %s", ms(latency[3]), ms(latency[4]),
                    ms(latency[5])));
            text.append('\n').append(String.format(Locale.ROOT,
                    "发出  p50 %s / p95 %s / 最大 %s", ms(latency[6]), ms(latency[7]),
                    ms(latency[8])));
        }

        final int[] rtt = RcNetLink.roundTrip();
        if (rtt == null || rtt.length < 5) {
            text.append("\n往返: 读不到");
        } else if (rtt[4] == 0) {
            text.append("\n往返: 没有回包（echo 关着，或电脑上没跑 echo-udp.ps1）");
        } else {
            text.append('\n').append(String.format(Locale.ROOT,
                    "往返  当前 %s / 最小 %s / 平均 %s / 最大 %s   (n=%d)",
                    ms(rtt[0]), ms(rtt[1]), ms(rtt[2]), ms(rtt[3]), rtt[4]));
        }
        return text.toString();
    }

    /** Microseconds as milliseconds. */
    private static String ms(int microseconds) {
        if (microseconds <= 0) return "-";
        return String.format(Locale.ROOT, "%.2f ms", microseconds / 1000.0);
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
            Log.w(TAG, "net page: could not read the local address", t);
        }
        return null;
    }

    // ------------------------------------------------------------------ widgets ---

    private static TextView header(Context context, String text) {
        final TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setTextColor(Color.WHITE);
        view.setPadding(0, dp(context, 10), 0, dp(context, 4));
        return view;
    }

    private static TextView label(Context context, String text) {
        final TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        view.setTextColor(Color.argb(230, 0xD0, 0xD0, 0xD0));
        view.setPadding(0, dp(context, 10), 0, dp(context, 2));
        return view;
    }

    private static View divider(Context context) {
        final View line = new View(context);
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(context, 1)));
        params.topMargin = dp(context, 14);
        line.setLayoutParams(params);
        line.setBackgroundColor(Color.argb(60, 0xFF, 0xFF, 0xFF));
        return line;
    }

    private static EditText field(Context context, String value, int inputType) {
        final EditText view = new EditText(context);
        view.setInputType(inputType);
        view.setSingleLine(true);
        view.setText(value);
        view.setTextColor(Color.WHITE);
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
            Log.w(TAG, "net page: " + text, t);
        }
    }

    private static int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }
}
