package com.edgetx.droidui;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The bridge's own screen: settings and diagnostics, inside the app.
 *
 * <p>Public, and it has to be. Android instantiates an Activity reflectively
 * through {@link android.app.AppComponentFactory}, and {@code Class.newInstance}
 * cannot reach a package-private class: the launch dies with
 * {@code IllegalAccessException: ... is not accessible from AppComponentFactory}
 * and the process restarts, which looks like the app relaunching itself rather
 * than an accessibility error. Every other class in this package is
 * package-private. This one cannot be.
 *
 * <p>Replaces a floating gear that lived in its own window. That needed the
 * "display over other apps" permission, which on this remote can only be granted
 * over adb - so the one screen that explains what the app is doing was itself
 * the one screen that silently did not appear, and a user who had not run that
 * command had a working remote and no visible way to find out what anything was
 * configured to do.
 *
 * <p>Two pages rather than one long scroll. Settings and diagnostics are not the
 * same task and are not used at the same moment - you configure the destination
 * once, then go back to watching numbers while someone flies - and combining them
 * would make the second one a scrolling exercise on a five-inch screen.
 *
 * <p>Built in code, like the rest of this app, which has no layout resources at
 * all.
 */
/**
 * The bridge's own screen: settings and diagnostics, inside the app.
 *
 * <p>Public, and it has to be. Android instantiates an Activity reflectively
 * through {@link android.app.AppComponentFactory}, and {@code Class.newInstance}
 * cannot reach a package-private class: the launch dies with
 * {@code IllegalAccessException: ... is not accessible from
 * AppComponentFactory} and the process is restarted, which looks like the app
 * relaunching itself rather than an accessibility error. Every other class in
 * this package is package-private. This one cannot be.
 *
 * <p>Replaces a floating gear that lived in its own window. That needed the
 * "display over other apps" permission, which on this remote can only be granted
 * over adb - so the one screen that explains what the app is doing was itself
 * the one screen that silently did not appear, and a user who had not run that
 * command had a working remote and no visible way to find out what anything was
 * configured to do.
 *
 * <p>Two pages rather than one long scroll. Settings and diagnostics are not the
 * same task and are not used at the same moment - you configure the destination
 * once, then go back to watching numbers while someone flies - and combining them
 * would make the second one a scrolling exercise on a five-inch screen.
 *
 * <p>Built in code, like the rest of this app, which has no layout resources at
 * all.
 */
public final class BridgeActivity extends Activity {

    /**
     * rcbridge's own web interface, which is where the live half of the routing
     * table comes from.
     *
     * <p>Loopback rather than the outbound host, and that is not a convenience:
     * the data needed here is read <em>from</em> rcbridge, so it has to come from
     * somewhere. The outbound target is a machine on the network and is a
     * one-way flow of this app's controls.
     */
    private static final String RCBRIDGE_ROUTES = "http://127.0.0.1:8080/api/routes";

    private static final int PAGE_SETTINGS = 0;
    private static final int PAGE_DIAGNOSTICS = 1;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private Pager pages;
    private Button tabSettings;
    private Button tabDiagnostics;
    private TextView summaryView;
    private TextView tableView;

    /** Repaints the diagnostics table once a second, only while it is visible. */
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (PAGE_DIAGNOSTICS == pages.current()) {
                refreshDiagnostics();
            }
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // The panel reads and writes the collector's stored target, and the
        // collector is created by the Application. Without a context here it has
        // no way to reach either.
        Collector.attachContext(this);

        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(0x12, 0x14, 0x18));
        root.addView(buildTitleBar());
        root.addView(buildTabBar());

        pages = new Pager(this);
        root.addView(pages, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        pages.add(NetSettingsOverlay.build(this, new Runnable() {
            @Override
            public void run() {
                finish();
            }
        }));
        pages.add(scroller(buildDiagnostics()));

        setContentView(root);
        showPage(PAGE_SETTINGS);
    }

    /**
     * Title and back button in one row.
     *
     * <p>The back arrow is Android's own, drawn as a text glyph. It is here rather
     * than left to the system because this activity is started from a button
     * inside another activity and has to look like part of the same app: a
     * hardware/gesture back that silently does nothing is worse than a visible
     * one that works.
     */
    private LinearLayout buildTitleBar() {
        final LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(4), dp(8), dp(16), dp(4));

        final Button back = new Button(this);
        back.setText("‹");
        back.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        back.setBackgroundColor(Color.TRANSPARENT);
        back.setMinWidth(dp(40));
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        bar.addView(back);

        final TextView name = new TextView(this);
        name.setText("EdgeTX Bridge");
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setTextColor(Color.WHITE);
        name.setPadding(dp(4), 0, 0, 0);
        bar.addView(name);
        return bar;
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(tick);
        handler.post(tick);
        // Both floating windows come down while this screen is up, and the readout
        // is the one that matters: it is a WRAP_CONTENT window in the top-left
        // corner, which is exactly where this screen's title and its two tabs are,
        // so it landed on top of them and hid the whole header. Separate windows
        // are composited above the activity's, so nothing inside the page can be
        // laid out around one.
        //
        // The channel window is the same story, and it is the one the settings page
        // can switch on while it is up: it comes back on the way out, over the
        // firmware, rather than landing on top of the page that just turned it on.
        BridgeHud.restore(this, false);
        ChannelHud.setVisible(false);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(tick);
        BridgeHud.restore(this, true);
        // And the channel window back, for the same reason: the switch on this page
        // may have just turned it on, and the firmware is what it is there to sit
        // over.
        ChannelHud.setVisible(true);
    }

    @Override
    protected void onDestroy() {
        // The settings page's own timer re-posts itself once a second and holds
        // the whole page - every field, every checkbox - so it has to be stopped
        // explicitly. Nothing else in this activity would notice: the page is
        // simply gone, and the runnable carries on rewriting text into views that
        // are no longer on screen.
        NetSettingsOverlay.stop();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ chrome ---

    private LinearLayout buildTabBar() {
        final LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(dp(8), 0, dp(8), dp(6));

        tabSettings = tab("网络转发", PAGE_SETTINGS);
        tabDiagnostics = tab("路由与延迟", PAGE_DIAGNOSTICS);
        bar.addView(tabSettings, tabParams());
        bar.addView(tabDiagnostics, tabParams());
        return bar;
    }

    private Button tab(String text, final int page) {
        final Button view = new Button(this);
        view.setText(text);
        view.setAllCaps(false);
        view.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showPage(page);
            }
        });
        return view;
    }

    private LinearLayout.LayoutParams tabParams() {
        final LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        p.setMargins(dp(3), 0, dp(3), 0);
        return p;
    }

    private void showPage(int page) {
        pages.show(page);

        // The two tabs are highlighted rather than hidden, because both are
        // always available and a page that is not on screen is not one that is
        // not there. Hiding would make it ambiguous which is showing.
        final boolean onSettings = page == PAGE_SETTINGS;
        tabSettings.setAlpha(onSettings ? 1f : 0.55f);
        tabDiagnostics.setAlpha(onSettings ? 0.55f : 1f);

        if (page == PAGE_DIAGNOSTICS) {
            refreshDiagnostics();
        }
    }

    private ScrollView scroller(View content) {
        final ScrollView view = new ScrollView(this);
        view.addView(content);
        return view;
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
    }

    // ------------------------------------------------------------------- page ---

    /**
     * The diagnostics page: every control, where it lands, who is feeding it now,
     * and how long it took.
     *
     * <p>Built once and repainted in place. Rebuilding the table every second
     * would put a fresh set of views on screen every second, which reads as a
     * flicker and makes the text impossible to select if a finger is anywhere
     * near it.
     */
    private View buildDiagnostics() {
        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(4), dp(12), dp(24));

        // How to get here, at the top, because there is no button to click.
        //
        // The firmware is drawn on a SurfaceView with setZOrderOnTop, so a button
        // added to this activity's window sits underneath it and cannot be seen.
        // The entry point is the double-tap on a custom button instead, and that
        // is only discoverable if something on screen says so.
        final TextView howTo = line(12, 0xFF90, 0xC0, 0xFF, BridgeRoutes.HOW_TO_GET_HERE);
        howTo.setPadding(0, 0, 0, dp(6));
        root.addView(howTo);

        summaryView = line(13, Color.WHITE, "正在读取 rcbridge …");
        summaryView.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(summaryView);

        tableView = line(12, Color.WHITE, "");
        root.addView(tableView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(caveat(BridgeRoutes.CAVEAT_SOURCE));
        root.addView(caveat(BridgeRoutes.CAVEAT_LATENCY));
        root.addView(caveat(BridgeRoutes.CAVEAT_ROUNDTRIP));
        return root;
    }

    private TextView caveat(String text) {
        final TextView view = line(12, 0xFF90, 0xA0, 0xB0, text);
        view.setPadding(0, dp(10), 0, 0);
        return view;
    }

    private TextView line(int sp, int color, String text) {
        final TextView view = new TextView(this);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        view.setText(text);
        return view;
    }

    private TextView line(int sp, int r, int g, int b, String text) {
        return line(sp, Color.rgb(r, g, b), text);
    }

    // -------------------------------------------------------------- diagnostics ---

    private void refreshDiagnostics() {
        // Off the main thread. The timeout below is reached on every refresh
        // whenever rcbridge is not running, so doing this inline would stall the
        // page for up to a second, once a second, in exactly the situation the
        // page exists to explain.
        if (fetchInFlight) return;
        fetchInFlight = true;

        BridgeHttp.getAsync(RCBRIDGE_ROUTES, 800, new BridgeHttp.Callback() {
            @Override
            public void onBody(String body) {
                fetchInFlight = false;
                // The page may have been left while the request was in flight.
                if (summaryView == null || tableView == null) return;
                if (body == null) {
                    summaryView.setTextColor(0xFFFF8080);
                    summaryView.setText("读不到 rcbridge");
                    tableView.setText("连不上 " + RCBRIDGE_ROUTES + "\n\n"
                            + "rcbridge 没在跑。启动命令：\n"
                            + "  adb shell /data/local/tmp/rcbridge"
                            + " -c /data/local/tmp/rcbridge.conf\n\n"
                            + "没有它，摇杆和按键就没人读，固件也收不到东西。");
                    return;
                }
                try {
                    summaryView.setTextColor(Color.WHITE);
                    render(new JSONObject(body));
                } catch (Throwable t) {
                    summaryView.setTextColor(0xFFFF8080);
                    summaryView.setText("rcbridge 返回的不是 JSON");
                    tableView.setText(body);
                }
            }
        });
    }

    /**
     * True while a request is out.
     *
     * <p>Without this, a slow rcbridge would have a request in flight for most
     * of a second and the timer would add another one every second, and they
     * would arrive out of order - so the page could show an older reading after a
     * newer one and sit that way until the next refresh.
     */
    private boolean fetchInFlight;

    /**
     * Draws the table.
     *
     * <p>Two halves per row. The left is the design - which switch or channel a
     * control is bound to - and the right is this run - which reader actually
     * won, and how long it took. They are shown together rather than merged
     * because they can disagree, and that disagreement is the interesting thing:
     * a switch row reading "—" in the source column is a control nobody is
     * feeding, which no amount of reading the code would have revealed.
     */
    private void render(JSONObject json) {
        final String owner = json.optString("axes_owner", "");
        final StringBuilder head = new StringBuilder();
        head.append("模拟量来源: ").append(owner.isEmpty() ? "未知" : owner);
        if (owner.isEmpty()) {
            head.append(" (没有 reader 认领)");
        } else {
            head.append("  ·  已持续 ").append(json.optLong("axes_owner_secs")).append(" 秒");
        }

        final JSONObject rtt = json.optJSONObject("round_trip_us");
        if (rtt != null && rtt.optInt("samples") > 0) {
            head.append("\n回环 (发送→固件收到): ")
                    .append(us(rtt.optLong("median_us")))
                    .append("  最低 ").append(us(rtt.optLong("min_us")))
                    .append("  最高 ").append(us(rtt.optLong("max_us")));
        } else {
            head.append("\n回环: 没有收到过固件的确认");
        }
        summaryView.setText(head.toString());

        final JSONArray buttons = json.optJSONArray("buttons");
        final StringBuilder body = new StringBuilder();
        body.append(String.format(java.util.Locale.ROOT, "%-10s %-8s %-7s %6s %8s%n",
                "控制", "输出", "来源", "按压", "延迟"));

        String lastGroup = "";
        for (BridgeRoutes.Output o : BridgeRoutes.OUTPUTS) {
            if (!o.group.equals(lastGroup)) {
                body.append("\n").append(o.group).append("\n");
                lastGroup = o.group;
            }
            final JSONObject live = find(buttons, o.wire);
            body.append(String.format(java.util.Locale.ROOT, "%-10s %-8s %-7s %6s %8s%n",
                    o.name,
                    o.target,
                    live == null ? "—" : sourceName(live.optString("source")),
                    live == null ? "" : String.valueOf(live.optLong("presses")),
                    live == null ? "" : latency(live)));
        }
        tableView.setText(body.toString());
    }

    /** The live entry for one control, or null if it has never been pressed. */
    private JSONObject find(JSONArray buttons, String wire) {
        if (buttons == null || wire == null) return null;
        for (int i = 0; i < buttons.length(); i++) {
            final JSONObject o = buttons.optJSONObject(i);
            if (o != null && wire.equals(o.optString("name"))) {
                return o;
            }
        }
        return null;
    }

    /**
     * Latency as a median, with the worst case beside it when it is meaningfully
     * worse.
     *
     * <p>Only the median is shown on its own because the worst case is what a
     * pilot feels as a missed switch throw, and a single outlier from a GC pause
     * is not. When the two differ by more than a factor of two, the outlier is
     * printed rather than averaged away.
     */
    private String latency(JSONObject live) {
        final long med = live.optLong("latency_med_us");
        final long max = live.optLong("latency_max_us");
        if (med <= 0) return "—";
        final String base = String.format(java.util.Locale.ROOT, "%.1f", med / 1000.0);
        if (max > med * 2 && max > 0) {
            return base + " / " + String.format(java.util.Locale.ROOT, "%.0f", max / 1000.0);
        }
        return base;
    }

    private String us(long value) {
        return String.format(java.util.Locale.ROOT, "%.1f ms", value / 1000.0);
    }

    private String sourceName(String source) {
        if ("evdev".equals(source)) return "evdev";
        if ("msdk".equals(source)) return "MSDK";
        return source.isEmpty() ? "—" : source;
    }

    // -------------------------------------------------------------------- pager ---

    /**
     * A two-child pager.
     *
     * <p>{@code ViewFlipper} does this, but it animates between children and the
     * animation on a dense page is noise on a screen this small; this switches
     * instantly. Written out rather than subclassed so the children fill the
     * pager - a {@code FrameLayout} child is measured at wrap-content by default,
     * which leaves a ScrollView with nothing to scroll.
     */
    private static final class Pager extends FrameLayout {
        private int current = -1;

        Pager(android.content.Context context) {
            super(context);
        }

        void add(View child) {
            addView(child, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
        }

        int current() {
            return current;
        }

        void show(int index) {
            if (index == current) return;
            for (int i = 0; i < getChildCount(); i++) {
                getChildAt(i).setVisibility(i == index ? VISIBLE : GONE);
            }
            current = index;
        }
    }
}