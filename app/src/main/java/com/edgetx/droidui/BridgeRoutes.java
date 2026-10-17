package com.edgetx.droidui;

import java.util.ArrayList;
import java.util.List;

/**
 * Where every control goes, and which reader is actually feeding it.
 *
 * <p>This is the answer to the question the rest of the program makes hard to
 * answer by looking at it: a button is not "wired to switch H", it is read from
 * one of two places by one of two readers and lands on one of two kinds of
 * output, and any of the three can be different from what the code suggests.
 *
 * <p>The design and the runtime are kept apart deliberately. {@link #OUTPUTS} is
 * the design - the switch table and the channel map, both fixed at build time.
 * The reader that actually won is not in here at all; it comes from rcbridge's
 * {@code /api/routes}, because it is a property of this run rather than of this
 * build. Both halves are needed: the design alone does not say whether event4 was
 * present, and the runtime alone does not say what the control is for.
 */
final class BridgeRoutes {

    private BridgeRoutes() {}

    /** One control's fixed half: what it is called, and where it lands. */
    static final class Output {
        /** What the user sees. */
        final String name;
        /**
         * The name rcbridge uses for the same control, or null when rcbridge has
         * no entry for it.
         *
         * <p>The two programs name the same controls differently - this one says
         * 快门 where rcbridge says "shutter" - so they are joined here explicitly.
         * Matching on a prefix or a normalised form would be shorter and would
         * silently stop matching the first time either name changed, which is why
         * it is written out.
         */
        final String wire;
        /** Which block of the table this belongs to. */
        final String group;
        /** The switch letter, the key name, or the channel. */
        final String target;
        /** "switch", "key" or "channel" - what kind of thing it drives. */
        final String kind;
        /** The Linux keycode, or -1 when only the SDK publishes it. */
        final int code;
        /** Why this control is read the way it is. */
        final String note;

        Output(String group, String name, String wire, String kind, String target,
               int code, String note) {
            this.group = group;
            this.name = name;
            this.wire = wire;
            this.kind = kind;
            this.target = target;
            this.code = code;
            this.note = note;
        }
    }

    // Switch slots, matching rc_bridge.cpp's kSwA..kSwJ. Kept as letters because
    // that is what the firmware's own switch names are, and a number here would
    // need translating every time somebody reads a log.
    private static final String SW_GO_HOME = "SA";
    private static final String SW_FMODE = "SB";
    private static final String SW_RECORD = "SC";
    private static final String SW_PAUSE = "SD";
    private static final String SW_SHUTTER = "SE";
    private static final String SW_C123 = "SF";
    private static final String SW_R1 = "SG";
    private static final String SW_R2 = "SH";
    private static final String SW_R3 = "SI";
    private static final String SW_C4 = "SJ";

    // Key numbers, matching EnumKeys in the firmware.
    private static final String KEY_SYS = "SYS";
    private static final String KEY_MODEL = "MDL";
    private static final String KEY_TELE = "TELE";
    private static final String KEY_EXIT = "EXIT";

    // Linux keycodes, matching rcbridge's built-in table. Both numbers, not
    // names, because the table is keyed by number and a name would have to be
    // resolved somewhere that could disagree with it.
    private static final int KEY_F1 = 59, KEY_F2 = 60, KEY_F3 = 61;
    private static final int KEY_F4 = 62, KEY_F5 = 63, KEY_F6 = 64;
    private static final int KEY_BACK = 158;
    private static final int BTN_TL = 310, BTN_TR = 311;

    /**
     * Every control, in the order a person would go through them.
     *
     * <p>Grouped the way the remote is laid out, top row first. A table sorted
     * alphabetically or by bit number makes the reader hunt for the button they
     * are looking at, and the whole point of this page is to be read at a glance
     * while something is not working.
     */
    static final List<Output> OUTPUTS = new ArrayList<Output>() {{
        // The wire names are rcbridge's, not ours: "record", "shutter", "pause",
        // "gohome", "C1".. "C4", "L1".. "L3", "R1".. "R3", "RTN", and the axis
        // names "LH", "LV", "RH", "RV", "dialL", "dialR", "hatX", "hatY", "wheel".
        // Anything rcbridge does not publish - the flight-mode switch and the
        // camera buttons it only sees through the SDK - has a wire name too,
        // because the SDK path fills the same table.
        add(new Output("肩部 / 上排", "L1", "L1", "key", KEY_SYS, KEY_F1,
                "gpio-keys 节点 · evdev 唯一来源"));
        add(new Output("肩部 / 上排", "L2", "L2", "key", KEY_MODEL, KEY_F2,
                "gpio-keys 节点 · evdev 唯一来源"));
        add(new Output("肩部 / 上排", "L3", "L3", "key", KEY_TELE, KEY_F3,
                "gpio-keys 节点 · evdev 唯一来源"));
        add(new Output("肩部 / 上排", "R1", "R1", "switch", SW_R1, KEY_F4, "瞬时开关"));
        add(new Output("肩部 / 上排", "R2", "R2", "switch", SW_R2, KEY_F5, "瞬时开关"));
        add(new Output("肩部 / 上排", "R3", "R3", "switch", SW_R3, KEY_F6, "瞬时开关"));

        add(new Output("相机 / 云台", "快门", "shutter", "switch", SW_SHUTTER, BTN_TR,
                "evdev 读 311 · MSDK 也报 · 走仲裁"));
        add(new Output("相机 / 云台", "录像", "record", "switch", SW_RECORD, BTN_TL,
                "evdev 读 310 · MSDK 也报 · 走仲裁"));
        add(new Output("相机 / 云台", "飞行模式", "fmode", "switch", SW_FMODE, -1,
                "三档 · 直连开关位置"));
        add(new Output("相机 / 云台", "急停", "pause", "switch", SW_PAUSE, -1,
                "SystemUI 在内核之上吃掉了 · 只有 MSDK"));
        add(new Output("相机 / 云台", "返航", "gohome", "switch", SW_GO_HOME, -1,
                "SystemUI 在内核之上吃掉了 · 只有 MSDK"));

        add(new Output("自定义", "C1", "C1", "switch", SW_C123, -1,
                "SystemUI 吃掉了 · 只有 MSDK · 与 C2/C3 共用 SF"));
        add(new Output("自定义", "C2", "C2", "switch", SW_C123, -1, "同上"));
        add(new Output("自定义", "C3", "C3", "switch", SW_C123, -1, "同上"));
        add(new Output("自定义", "C4", "C4", "switch", SW_C4, -1,
                "MSDK 的 authLed 键 · 没有 evdev 对应"));

        add(new Output("导航", "返回", "RTN", "key", KEY_EXIT, KEY_BACK,
                "内核只报 8 ms 的短按 · 靠长按触发 EdgeTX 手势"));
        add(new Output("导航", "5-way 左右", "hatX", "channel", "CH7", -1,
                "event4 · ABS_HAT0X"));
        add(new Output("导航", "5-way 上下", "hatY", "channel", "CH8", -1,
                "event4 · ABS_HAT0Y"));

        add(new Output("摇杆", "左摇杆横", "LH", "channel", "CH4 RUD", -1, "event4 · ABS_X"));
        add(new Output("摇杆", "左摇杆纵", "LV", "channel", "CH3 THR", -1, "event4 · ABS_Y"));
        add(new Output("摇杆", "右摇杆横", "RH", "channel", "CH1 AIL", -1, "event4 · ABS_RX"));
        add(new Output("摇杆", "右摇杆纵", "RV", "channel", "CH2 ELE", -1, "event4 · ABS_RY"));
        add(new Output("旋钮 / 滚轮", "左旋钮", "dialL", "channel", "CH5", -1, "event4 · ABS_Z"));
        add(new Output("旋钮 / 滚轮", "右旋钮", "dialR", "channel", "CH6", -1, "event4 · ABS_RZ"));
        add(new Output("旋钮 / 滚轮", "滚轮", "wheel", "channel", "CH18", -1,
                "只有 MSDK · evdev 没有这个轴"));
    }};

    /**
     * How to reach this screen, shown at the top of it.
     *
     * <p>Only the floating icon. There was a second way in - a double-tap on one
     * of the remote's custom buttons - and it is gone. The tap interval a hand
     * actually produced on this remote was 463 ms against a 400 ms window, missing
     * by 63 ms, which from the user's side is indistinguishable from a gesture
     * that does not exist; and the SDK's CustomButton1..3 do not line up with the
     * case's C1..C3 in the order the code assumed, so which button carried it was
     * a guess. Neither is something a user can see or work around, and an entry
     * point that silently fails is worse than not having one.
     *
     * <p>The button drawn into the firmware's own window cannot be seen either: the
     * firmware is rendered on a NativeSurfaceView with setZOrderOnTop, so that
     * surface is composited above the activity's window and everything in the view
     * hierarchy is underneath it. It is added, the log says so, and it is
     * invisible.
     */
    static final String HOW_TO_GET_HERE =
            "怎么进这个页面：点屏幕左上角的 ⚙ 图标。\n"
                    + "那个图标可以拖到顺手的位置，长按可以藏起来；"
                    + "藏了之后用下面「显示左上角的入口图标」这个按钮找回来。\n"
                    + "固件界面里那个「桥接」按钮被 SurfaceView 挡住了，看不见，别找它。";

    static final String CAVEAT_SOURCE =
            "「来源」是本次运行真正赢得仲裁的那个 reader，不是设计值。"
                    + "event4 出现或被别的 app 抢走时这一列会变。";

    static final String CAVEAT_LATENCY =
            "延迟单位 ms，格式为「中位 / 最坏」。只读得出 evdev→读取→发出的这一段——"
                    + "按键在内核里被按下到 rcbridge 读到，中间没有别的环节。";

    static final String CAVEAT_ROUNDTRIP =
            "回环是 rcbridge 发出到固件确认收到，含 UDP 往返和固件处理。"
                    + "两段相加才是按键到模型动作的总延迟。";
}
