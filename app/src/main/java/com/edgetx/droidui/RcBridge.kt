package com.edgetx.droidui

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Log
import dji.sdk.keyvalue.key.DJIKeyInfo
import dji.sdk.keyvalue.key.DJIRemoteControllerKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.remotecontroller.BatteryInfo
import dji.sdk.keyvalue.value.remotecontroller.FiveDimensionPressedStatus
import dji.sdk.keyvalue.value.remotecontroller.RCFlightModeSwitch
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.common.register.DJISDKInitEvent
import dji.v5.manager.KeyManager
import dji.v5.manager.SDKManager
import dji.v5.manager.interfaces.SDKManagerCallback
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Listens to the DJI Mobile SDK and reports what it hears to rcbridge.
 *
 * A reporter and nothing more. It does no mixing, no channel mapping and no
 * protocol work beyond the 48-byte packet in [Proto]; everything else is
 * rcbridge's job. That is deliberate - it means one capture path serves the
 * EdgeTX firmware, a flight controller over MAVLink, and an RF module directly,
 * without three different notions of what a button is.
 *
 * ## Why this app exists at all
 *
 * Seven of the RC Plus 2's controls are not reachable from /dev/input at all. The
 * remote's own SystemUI runs a dpad service that consumes the kernel key events
 * for pause, go-home and C1-C4 before they are dispatched, and the pause button
 * never reaches the input stream in any form. No amount of root changes that,
 * because the event is consumed above the kernel. The SDK is the only source
 * that still sees them, which makes this app the only way they can be read.
 *
 * ## What it does *not* need to do
 *
 * The sticks, dials, wheel, 5-way, the six shoulder buttons, shutter, record and
 * RTN all come through /dev/input, and rcbridge's kernel reader gets them
 * directly at several hundred hertz with no SDK in the path. This app still
 * listens to them - partly because that is how a button can be proven missing
 * from one source and present in the other, and partly because `sendAxes` and
 * the merge arbiter make it harmless - but by default it does not transmit the
 * analog half. See [sendAxes].
 */
class RcBridge(private val ctx: Context) {

    private val prefs: SharedPreferences =
        ctx.getSharedPreferences("collector", Context.MODE_PRIVATE)

    // ---- state the SDK writes, the sender reads ----------------------------
    //
    // volatile rather than locked: the SDK's listener callbacks run on the SDK's
    // own thread and the send loop reads them at 100 Hz, and a pair of int
    // assignments cannot produce anything worse than a frame that mixes a stick
    // position from before a move with one from after it. The button bits are the
    // exception and are guarded - see setButton.

    @Volatile private var sticks = IntArray(4)
    @Volatile private var dials = IntArray(2)
    @Volatile private var wheel = 0
    @Volatile private var five = 0
    @Volatile private var fmode = 0
    @Volatile private var buttons = 0L

    // Not volatile: only the send loop and clearState read these, and the flags
    // being a frame stale is harmless.
    private var axesSeen = false
    private var buttonsSeen = false

    private val sending = AtomicBoolean(false)
    private var socket: DatagramSocket? = null
    private var target: InetAddress? = null
    private var targetPort = 14570
    private val seq = AtomicLong(0)
    private val startedAt = SystemClock.elapsedRealtime()
    private var sender: Thread? = null

    @Volatile private var registered = false
    @Volatile private var registeredText = "not registered"
    @Volatile private var lastRx = 0L
    @Volatile private var lastTx = 0L
    @Volatile private var txCount = 0L
    @Volatile private var batteryPower = 0

    // Polling for the app's own SDK registration. One waiter at a time, because
    // startSdk() is reachable from both the Application and the settings panel and
    // two pollers would each end up calling listen() - and listen() is not
    // idempotent, so every key would be subscribed twice.
    private val waitingForRegistration = AtomicBoolean(false)
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    // The owner object the SDK's listener registry is keyed on. One per bridge,
    // so cancelListen(this) removes everything this app registered and nothing
    // else - which matters because the DJI apps on a remote share that registry.
    private val keyOwner = Any()

    // A short hold, applied by the SDK's button callbacks.
    //
    // The SDK reports a press and a release that can be only a few milliseconds
    // apart, and rcbridge merges readings from two readers within a 120 ms
    // window. A tap that arrives already released can therefore fall between two
    // of rcbridge's samples and never be seen as pressed. Holding it for 250 ms
    // - which is roughly the SDK's own shortest reported press - makes the
    // release land inside the same merge window as the press.
    private val pressedByKey = HashMap<Long, Long>()

    /**
     * Whether the collector also sends the analog half.
     *
     * The DJI SDK publishes the sticks, dials and 5-way as well as the buttons,
     * so without this the collector duplicates what rcbridge's kernel reader
     * already has - two sources for one control, with rcbridge arbitrating
     * between them.
     *
     * It is off by default because on this remote the kernel is unambiguously
     * the better source. rcbridge reads /dev/input directly; the SDK's own host
     * app cannot, because those nodes are root:input mode 0660 and the only
     * Android permission that grants that group is signature-level. The kernel
     * also reports them at several hundred hertz with nothing in the path, and
     * works whether or not the SDK has finished registering.
     *
     * It exists at all because the fallback is real: if the kernel reader cannot
     * open a node, the SDK copy is the only one there will be. Turning it on
     * costs a few bytes a second, and rcbridge still prefers whichever source it
     * has, so it cannot make anything worse.
     */
    /**
     * Whether the analog half is sent to rcbridge.
     *
     * On by default, and the reason is not redundancy - it is that the primary
     * reader is not this app.
     *
     * The sticks and the dials are read by rcbridge out of /dev/input, as the
     * shell user, started from here through Shizuku (see RcBridgeLauncher). That
     * is the better path while it works: evdev carries the controls the DJI SDK
     * cannot be reached for, and it carries them at the rate the kernel publishes
     * them. But every link in that chain is outside this process - Shizuku has to
     * be running, the app has to have been granted its permission, and a
     * separate 6 MB binary has to still be alive - and if any of them is not,
     * this app goes completely deaf. A remote that cannot be flown is a much
     * worse failure than a slow stick.
     *
     * So the SDK's copy of the analog half stays on as the floor under it. It is
     * 8-14 Hz against evdev's 70 Hz, so it is worse by an order of magnitude in
     * latency and would be a poor primary - which is exactly why it is not one.
     * rcbridge's merge arbiter prefers evdev over this, so while the node is
     * there nothing changes; the SDK's frames are measured, used only if evdev
     * goes quiet, and cost a few hundred bytes a second on loopback.
     *
     * The settings page turns it off for anyone who wants strictly one source and
     * will accept the consequences.
     */
    @Volatile private var sendAxes = prefs.getBoolean(PREF_SEND_AXES, true)

    // ---- lifecycle ---------------------------------------------------------

    fun onResume() {
        if (!registered) startSdk()
    }

    fun onPause() {
        // The listeners stay registered across a pause. The SDK delivers button
        // states as levels rather than edges, so a press that happens while the
        // screen is off is still reported as held when the app comes back - and
        // cancelling would lose the edge entirely, leaving the button stuck on
        // whichever side of the press the app happened to be on.
    }

    fun close() {
        stopSending()
        try {
            KeyManager.getInstance().cancelListen(keyOwner)
        } catch (t: Throwable) {
            Log.w(TAG, "cancelListen failed", t)
        }
    }

    // ---- target ------------------------------------------------------------

    fun target(): String = prefs.getString(PREF_TARGET, DEFAULT_TARGET) ?: DEFAULT_TARGET

    fun setTarget(text: String) {
        // host:port, or just a host. The port default matches rcbridge's
        // msdk.listen default so the common case is one field, not two.
        val host = text.substringBeforeLast(':')
        targetPort = text.substringAfterLast(':', "14570").toIntOrNull() ?: 14570
        prefs.edit().putString(PREF_TARGET, text).apply()
        target = try {
            InetAddress.getByName(host)
        } catch (t: Throwable) {
            Log.w(TAG, "cannot resolve $host", t)
            null
        }
    }

    fun sendAxes(): Boolean = sendAxes

    fun setSendAxes(v: Boolean) {
        prefs.edit().putBoolean(PREF_SEND_AXES, v).apply()
        sendAxes = v
        // Dropping the flag matters: leaving it set would keep sending the
        // last-known stick positions as if they were live, after the reason to
        // send them has been turned off.
        if (!v) axesSeen = false
    }

    // ---- the sender --------------------------------------------------------

    fun isSending(): Boolean = sending.get()

    fun startSending() {
        if (target == null) setTarget(target())
        if (target == null) return
        if (!sending.compareAndSet(false, true)) return

        socket = try {
            DatagramSocket()
        } catch (t: Throwable) {
            Log.w(TAG, "cannot open a UDP socket", t)
            sending.set(false)
            return
        }
        sender = Thread({ sendLoop() }, "rcbridge-sender").apply {
            isDaemon = true
            start()
        }
        // hostAddress, not hostName. hostName is a reverse DNS lookup: it asks the
        // network for a PTR record and, with none, blocks until the resolver gives
        // up. On a loopback target - which is the normal case here, rcbridge is on
        // this same remote - there is never a PTR record, so it always blocks, and
        // on the main thread Android kills the app for it.
        Log.i(TAG, "sending to ${target!!.hostAddress}:$targetPort")
    }

    /**
     * Opens the socket off the main thread.
     *
     * <p>setTarget() resolves the host, and resolving a *name* - as opposed to an
     * address literal - is a DNS query. Called from the registration poll, which
     * runs on the main thread, that is a NetworkOnMainThreadException and the app
     * is gone: three crashes in a row, all with this same stack, before the
     * address was looked at.
     *
     * <p>Guarded by {@link #sending} rather than a separate flag, so a second call
     * while the first is still resolving does not open a second socket.
     */
    private fun startSendingAsync() {
        Thread({
            try {
                startSending()
            } catch (t: Throwable) {
                // A background thread with an uncaught exception is a crash that
                // arrives with no stack anyone is watching for.
                Log.w(TAG, "could not start sending", t)
                sending.set(false)
            }
        }, "rcbridge-collector-open").apply {
            isDaemon = true
            start()
        }
    }

    fun stopSending() {
        if (!sending.compareAndSet(true, false)) return
        sender?.join(500)
        sender = null
        try {
            socket?.close()
        } catch (_: Throwable) {
        }
        socket = null
    }

    /**
     * The 100 Hz send loop.
     *
     * A level, not an edge: the SDK's button keys are levels, and rcbridge
     * reduces them to edges itself so it can merge them with its evdev reader.
     * Sending edges from here would put that decision in two places.
     *
     * A failure sleeps 500 ms rather than retrying immediately, because a target
     * that has gone away produces the same error at 100 Hz and the log fills
     * with one line per packet while telling nobody anything new.
     */
    private fun sendLoop() {
        val buf = ByteArray(Proto.SIZE)
        val intervalMs = 10L   // 100 Hz

        while (sending.get()) {
            val addr = target
            val sock = socket
            if (addr != null && sock != null) {
                try {
                    Proto.encode(
                        buf = buf,
                        seq = seq.getAndIncrement(),
                        tMillis = SystemClock.elapsedRealtime() - startedAt,
                        sticks = sticks,
                        dials = dials,
                        wheel = wheel,
                        five = five,
                        fmode = fmode,
                        buttons = buttons,
                        hasAxes = sendAxes && axesSeen,
                        hasButtons = buttonsSeen,
                    )
                    sock.send(DatagramPacket(buf, buf.size, addr, targetPort))
                    lastTx = SystemClock.elapsedRealtime()
                    txCount++
                } catch (t: Throwable) {
                    Log.w(TAG, "send failed", t)
                    Thread.sleep(500)
                }
            }
            Thread.sleep(intervalMs)
        }
    }

    // ---- the SDK -----------------------------------------------------------

    /**
     * Starts the SDK and, once it registers, subscribes to every key.
     *
     * Every listener is registered under one owner object so that a single
     * cancelListen() in close() removes the lot.
     */
    fun startSdk() {
        if (registered) return

        // EdgeTX already registers the DJI SDK for its own input path (see
        // DjiMsdkBridge.init, which EdgeTxApplication calls before the firmware
        // starts). Calling SDKManager.init() twice is not supported and the second
        // callback never arrives, so the registration is joined rather than
        // repeated.
        //
        // Which is also why the collector lives inside this app at all: two apps
        // registering the same SDK is the situation this avoids, and it is why the
        // whole thing only needs one DJI App Key.
        try {
            if (SDKManager.getInstance().isRegistered()) {
                registered = true
                registeredText = "registered"
                listen()
                startSendingAsync()
                return
            }
        } catch (t: Throwable) {
            Log.w(TAG, "cannot ask the SDK whether it is registered; will wait for it", t)
        }

        waitForRegistration()
    }

    /**
     * Polls for the registration that DjiMsdkBridge.init is already performing.
     *
     * It used to call SDKManager.init() here instead. That cannot work from the
     * Application: DjiMsdkBridge.init runs in attachBaseContext, and the collector
     * starts two lines later in onCreate, so isRegistered() is reliably still false
     * - this is a race that loses every time, not sometimes. The second init is
     * rejected, its callback never arrives, and listen() is never called, so all 18
     * keys stay unsubscribed and the remote's buttons do nothing at all.
     *
     * The failure was easy to misread because the SDK still delivered the sticks,
     * the dials and the 5-way - those are installed on DjiMsdkBridge's own path,
     * which registration does not gate - so half the controls worked while every
     * button was dead, with nothing in the log saying why.
     *
     * So this waits instead. Registration takes 0.9 s on this remote, and the
     * timeout is far beyond anything healthy so that a genuine failure is still
     * reported rather than waited on forever.
     */
    private fun waitForRegistration() {
        if (!waitingForRegistration.compareAndSet(false, true)) return

        registeredText = "waiting for the app's SDK registration"
        val startedAt = SystemClock.elapsedRealtime()

        val poll = object : Runnable {
            override fun run() {
                val ready = try {
                    SDKManager.getInstance().isRegistered()
                } catch (t: Throwable) {
                    Log.w(TAG, "isRegistered() threw while waiting", t)
                    false
                }

                if (ready) {
                    waitingForRegistration.set(false)
                    registered = true
                    registeredText = "registered"
                    listen()

                    // Send as soon as there is something to send. Subscribing to
                    // the keys without opening the socket collects every button
                    // press on the remote and throws it away, which looks
                    // identical to a remote with no buttons.
                    //
                    // Off-thread: this poll is on the main thread, and opening the
                    // socket resolves the target host.
                    startSendingAsync()
                    return
                }

                val waited = SystemClock.elapsedRealtime() - startedAt
                if (waited >= REGISTRATION_TIMEOUT_MS) {
                    waitingForRegistration.set(false)
                    registeredText = "the app's SDK registration never completed " +
                        "(${waited / 1000} s) - no key was subscribed"
                    Log.w(TAG, registeredText)
                    return
                }
                handler.postDelayed(this, REGISTRATION_POLL_MS)
            }
        }
        handler.postDelayed(poll, REGISTRATION_POLL_MS)
    }

    fun isRegistered(): Boolean = registered

    /** Every key this app listens to. */
    private fun listen() {
        listenSticks()
        listenDials()
        listenFiveDimension()
        listenButtons()
        listenFlightMode()
        listenBattery()
    }

    /**
     * One subscription, with the failure swallowed.
     *
     * Not every remote publishes every key - the auth-led button does not exist
     * on this one, for instance - and a key that is absent is a fact about the
     * remote, not an error worth propagating. It is logged by name, because
     * "that button is not on this remote" is the answer to a question this app
     * exists to answer.
     */
    private fun <T> listenKey(name: String, info: DJIKeyInfo<T>, handler: (T?) -> Unit) {
        try {
            KeyManager.getInstance().listen(
                KeyTools.createKey(info), keyOwner,
                object : CommonCallbacks.KeyListener<T> {
                    override fun onValueChange(oldValue: T?, newValue: T?) {
                        handler(newValue)
                    }
                })
            Log.i(TAG, "listening to $name")
        } catch (t: Throwable) {
            Log.w(TAG, "$name is not available on this remote", t)
        }
    }

    private fun listenSticks() {
        // No dead zone here, unlike rcbridge's own reader. Proto.scale's default
        // is applied at the call site; these four pass 0.0f because the SDK's
        // sticks are already clean at rest and a dead zone on top of them makes
        // fine trim adjustments around centre impossible to feel.
        listenKey("stick LH", DJIRemoteControllerKey.KeyStickLeftHorizontal) {
            if (it != null) { sticks[0] = Proto.scale(it, 0f); axesSeen = true }
        }
        listenKey("stick LV", DJIRemoteControllerKey.KeyStickLeftVertical) {
            if (it != null) { sticks[1] = Proto.scale(it, 0f); axesSeen = true }
        }
        listenKey("stick RH", DJIRemoteControllerKey.KeyStickRightHorizontal) {
            if (it != null) { sticks[2] = Proto.scale(it, 0f); axesSeen = true }
        }
        listenKey("stick RV", DJIRemoteControllerKey.KeyStickRightVertical) {
            if (it != null) { sticks[3] = Proto.scale(it, 0f); axesSeen = true }
        }
    }

    private fun listenDials() {
        listenKey("left dial", DJIRemoteControllerKey.KeyLeftDial) {
            if (it != null) { dials[0] = Proto.scale(it, 0f); axesSeen = true }
        }
        listenKey("right dial", DJIRemoteControllerKey.KeyRightDial) {
            if (it != null) { dials[1] = Proto.scale(it, 0f); axesSeen = true }
        }
        // The wheel reports relative steps rather than a position, so it
        // accumulates. rcbridge turns the accumulated total into a position, and
        // this is the only place a wheel reading comes from on a remote whose
        // kernel driver does not expose one.
        listenKey("scroll wheel", DJIRemoteControllerKey.KeyScrollWheel) {
            if (it != null) { wheel += it; axesSeen = true }
        }
    }

    private fun listenFiveDimension() {
        listenKey("5-way", DJIRemoteControllerKey.KeyFiveDimensionPressedStatus) { s: FiveDimensionPressedStatus? ->
            if (s == null) return@listenKey
            var m = 0
            if (s.upwards == true) m = m or Proto.Pad.UP
            if (s.downwards == true) m = m or Proto.Pad.DOWN
            if (s.leftwards == true) m = m or Proto.Pad.LEFT
            if (s.rightwards == true) m = m or Proto.Pad.RIGHT
            if (s.middlePressed == true) m = m or Proto.Pad.CENTRE
            five = m
            axesSeen = true
            lastRx = SystemClock.elapsedRealtime()
        }
    }

    /**
     * The seven controls /dev/input does not carry.
     *
     * Each is registered separately rather than in a loop, because each has a
     * different failure mode: the SDK does not publish every one on every
     * remote, and a key that throws has to be logged by name or the set silently
     * shrinks.
     */
    private fun listenButtons() {
        listenButton("shutter", Proto.Btn.SHUTTER, DJIRemoteControllerKey.KeyShutterButtonDown)
        listenButton("record", Proto.Btn.RECORD, DJIRemoteControllerKey.KeyRecordButtonDown)
        listenButton("pause", Proto.Btn.PAUSE, DJIRemoteControllerKey.KeyPauseButtonDown)
        listenButton("C1", Proto.Btn.C1, DJIRemoteControllerKey.KeyCustomButton1Down)
        listenButton("C2", Proto.Btn.C2, DJIRemoteControllerKey.KeyCustomButton2Down)
        listenButton("C3", Proto.Btn.C3, DJIRemoteControllerKey.KeyCustomButton3Down)
        // Not on the RC Plus 2, and registered anyway so that its absence is
        // logged rather than assumed. The auth-led button is the round one on
        // an RC Pro.
        listenButton("authLed", Proto.Btn.C4, DJIRemoteControllerKey.KeyRCAuthLedButtonDown)

        // go-home is handled separately from the rest: the SDK reports it as a
        // press-and-release pair whose release can arrive before the listener is
        // fully live, so it goes through the short-hold path.
        listenKey("go-home", DJIRemoteControllerKey.KeyGoHomeButtonDown) { v: Boolean? ->
            if (v == true) {
                setButton(Proto.Btn.GO_HOME, true)
                pressedByKey[Proto.Btn.GO_HOME] = SystemClock.elapsedRealtime() + SHORT_HOLD_MS
            } else {
                pressedByKey.remove(Proto.Btn.GO_HOME)
                setButton(Proto.Btn.GO_HOME, false)
            }
        }
    }

    /**
     * One button key.
     *
     * The first callback for a key carries the current state as both old and new
     * value - it is a snapshot, not a transition - and acting on it would report
     * every button as held the instant the app starts. It is logged instead,
     * which is also how a button that is genuinely being held at start-up gets
     * noticed.
     */
    private fun listenButton(name: String, bit: Long, info: DJIKeyInfo<Boolean>) {
        try {
            KeyManager.getInstance().listen(
                KeyTools.createKey(info), keyOwner,
                object : CommonCallbacks.KeyListener<Boolean> {
                    override fun onValueChange(oldValue: Boolean?, newValue: Boolean?) {
                        if (oldValue == null && newValue != null) {
                            Log.d(TAG, "$name initial snapshot = $newValue")
                            return
                        }
                        if (newValue == null) return
                        setButton(bit, newValue)
                    }
                })
            Log.i(TAG, "listening to $name")
        } catch (t: Throwable) {
            Log.w(TAG, "$name is not available on this remote", t)
        }
    }

    private fun listenFlightMode() {
        listenKey("flight mode", DJIRemoteControllerKey.KeyFlightModeSwitchState) { v: RCFlightModeSwitch? ->
            if (v == null) return@listenKey
            // Matched on the enum, not on its name. This was originally a
            // `when (v.toString())` against "SWITCH_ONE" and friends, which
            // compiles, runs, and silently reports no flight-mode movement at all
            // if the SDK ever renames a constant - the `else` branch keeps the
            // old value and nothing says why.
            fmode = when (v) {
                RCFlightModeSwitch.SWITCH_ONE -> -1
                RCFlightModeSwitch.SWITCH_TWO -> 0
                RCFlightModeSwitch.SWITCH_THREE -> 1
                else -> fmode
            }
            axesSeen = true
            lastRx = SystemClock.elapsedRealtime()
        }
    }

    private fun listenBattery() {
        // batteryPower. There is no getVoltage() on BatteryInfo, and the field's
        // unit is not documented anywhere in the SDK: the value measured on a
        // live RC Plus 2 is 539500 with the panel showing 83%, which is neither
        // millivolts nor percent - 539500/10000 is 53.95, which looks like
        // hundredths of a percent, but that is inference from one reading and it
        // is shown as such rather than asserted.
        listenKey("battery", DJIRemoteControllerKey.KeyBatteryInfo) { v: BatteryInfo? ->
            val p = v?.batteryPower
            if (p != null) batteryPower = p
        }
    }

    // ---- button plumbing ---------------------------------------------------

    /**
     * Sets or clears one button bit.
     *
     * Synchronised because the SDK delivers button callbacks from more than one
     * thread and a read-modify-write of a 64-bit value from two of them can lose
     * a bit - which shows up as a button that occasionally will not release.
     */
    /**
     * Called when a button changes, on whichever thread the SDK delivered the
     * event on.
     *
     * This exists because the display used to refresh on a timer, and a timer is
     * the wrong clock for a button: at 2 Hz the readout is up to half a second
     * behind the press, which on a remote that is supposed to feel direct reads
     * as input lag rather than as a slow display - the same symptom, a very
     * different place to fix it.
     *
     * The analog half still refreshes on the timer, because it changes
     * continuously and there is nothing discrete to trigger on.
     */
@Volatile
    private var onButtonChanged: (() -> Unit)? = null

/** Asks for an immediate display refresh. Safe to call from any thread. */
    fun notifyChanged() {
        onButtonChanged?.invoke()
    }

    fun setOnChanged(fn: (() -> Unit)?) {
        onButtonChanged = fn
    }

    @Synchronized
    private fun setButton(bit: Long, down: Boolean) {
        val before = buttons
        buttons = if (down) buttons or bit else buttons and bit.inv()
        buttonsSeen = true
        lastRx = SystemClock.elapsedRealtime()
        if (buttons != before) onButtonChanged?.invoke()
    }

    /**
     * An Android key event, as a second source for the buttons rcbridge's kernel
     * reader already has.
     *
     * The SDK does not publish the shoulder buttons on every firmware, and on an
     * RC Pro they are silent, but the kernel reports them as ordinary key codes
     * which the Android layer surfaces as key events. So the app offers them as
     * a backup and rcbridge merges the two copies with the same 120 ms window it
     * uses everywhere else - one physical press is still one press.
     */
    fun reportKeyEvent(bit: Long, down: Boolean) {
        setButton(bit, down)
    }

    /**
     * Maps an Android key code to one of this app's button bits.
     *
     * Both namespaces, because this is the *Android* side: the kernel codes
     * (KEY_F1..F6 = 59..64, KEY_BACK = 158) as the driver reports them, and the
     * AKEYCODE_F1..F6 (131..136) and AKEYCODE_BACK (4) that the framework
     * delivers to a window. rcbridge's kernel-side table deliberately lists only
     * the first set, because a reader on /dev/input can never see the second.
     */
    fun keyCodeToButton(keyCode: Int): Long? = when (keyCode) {
        59, 131 -> Proto.Btn.L1
        60, 132 -> Proto.Btn.L2
        61, 133 -> Proto.Btn.L3
        62, 134 -> Proto.Btn.R1
        63, 135 -> Proto.Btn.R2
        64, 136 -> Proto.Btn.R3
        4, 158 -> Proto.Btn.RTN
        else -> null
    }

    /** Resets the reported state, so a stuck value can be distinguished from a real one. */
    fun clearState() {
        synchronized(this) {
            sticks = IntArray(4)
            dials = IntArray(2)
            wheel = 0
            five = 0
            fmode = 0
            buttons = 0
        }
    }

    // ---- the display -------------------------------------------------------

    fun statusText(): String {
        val sb = StringBuilder()
        sb.append(if (registered) "SDK registered" else registeredText)
        sb.append("   sending: ").append(
            // hostAddress, never hostName: this line is rebuilt once a second on the
        // main thread while the settings page is open, and hostName is a reverse
        // DNS lookup that blocks for the whole resolver timeout.
        if (isSending()) "yes -> ${target?.hostAddress}:$targetPort" else "no")
        val age = SystemClock.elapsedRealtime() - lastRx
        if (lastRx > 0L) sb.append("   last event ${age}ms ago")
        return sb.toString()
    }

    /**
     * The live readout.
     *
     * Deliberately verbose and deliberately showing raw numbers next to a bar:
     * when a stick reads wrong, the question is always whether the value never
     * arrived or arrived and was scaled badly, and this answers it on one screen
     * with no logging and no debugger.
     */
    fun detailText(): String {
        val sb = StringBuilder()
        bar(sb, "LH", sticks[0])
        bar(sb, "LV", sticks[1])
        bar(sb, "RH", sticks[2])
        bar(sb, "RV", sticks[3])
        bar(sb, "dial-L", dials[0])
        bar(sb, "dial-R", dials[1])
        sb.append(String.format("%-10s %5d%n", "wheel", wheel))
        sb.append(String.format("%-10s %s%n", "5-way", fiveToText(five)))
        sb.append(String.format("%-10s %d%n", "fmode", fmode))
        sb.append(String.format("%-10s %s%n", "battery", batteryText()))
        sb.append("held: ").append(if (buttons == 0L) "-" else heldToText(buttons))
        sb.append(String.format("%n%s", "sent $txCount packets"))
        return sb.toString()
    }

    /**
     * The battery, in the SDK's own units plus a reading of them.
     *
     * Shown as both because the unit is not documented and a bare 539500 tells
     * nobody whether the battery is flat or full. The percentage is derived, not
     * measured, and says so.
     */
    private fun batteryText(): String {
        if (batteryPower <= 0) return "-"
        return "$batteryPower raw (~$%.1f%%)".format(batteryPower / 10000.0)
    }

    /**
     * One axis, as a bar that grows outwards from the centre.
     *
     * A single bar filling from one edge is the obvious thing to write and it is
     * wrong here: at rest every axis sits at zero, so a bar that fills from the
     * left shows *nothing* for every axis at rest - which is most of the time.
     * The first version did exactly that, and because the row was formatted with
     * a padded field it rendered as a block of invisible spaces, so the screen
     * looked like it had lost three lines rather than showing a centred stick.
     *
     * The centre tick is fixed and the fill grows towards whichever end the value
     * leans to, so "centred" and "full left" and "full right" are all visibly
     * different at a glance.
     *
     * 36 characters, which is BAR_WIDTH even so the two halves are equal: at 11sp
     * monospace on a 1200px panel a 50-wide bar put the row at 68 characters and
     * it wrapped.
     */
    private fun bar(sb: StringBuilder, label: String, v: Int) {
        val half = BAR_WIDTH / 2
        val p = v * half / 1000        // -half .. +half
        val left = if (p < 0) " ".repeat(-p) + "#".repeat(half + p) else "#".repeat(half)
        val right = if (p > 0) ".".repeat(half - p) + " ".repeat(p) else ".".repeat(half)
        sb.append(String.format("%-8s %5d |%s|%s|%n", label, v, left, right))
    }

    private fun fiveToText(m: Int): String {
        if (m == 0) return "none"
        val parts = ArrayList<String>()
        if (m and 1 != 0) parts.add("up")
        if (m and 2 != 0) parts.add("down")
        if (m and 4 != 0) parts.add("left")
        if (m and 8 != 0) parts.add("right")
        if (m and 0x10 != 0) parts.add("centre")
        return parts.joinToString("+")
    }

    private fun heldToText(m: Long): String {
        val names = listOf(
            "shutter" to Proto.Btn.SHUTTER,
            "record" to Proto.Btn.RECORD,
            "gohome" to Proto.Btn.GO_HOME,
            "pause" to Proto.Btn.PAUSE,
            "C1" to Proto.Btn.C1,
            "C2" to Proto.Btn.C2,
            "C3" to Proto.Btn.C3,
            "C4" to Proto.Btn.C4,
            "L1" to Proto.Btn.L1,
            "L2" to Proto.Btn.L2,
            "L3" to Proto.Btn.L3,
            "R1" to Proto.Btn.R1,
            "R2" to Proto.Btn.R2,
            "R3" to Proto.Btn.R3,
            "RTN" to Proto.Btn.RTN,
        )
        return names.filter { m and it.second != 0L }.joinToString(" ") { it.first }
            .ifEmpty { "-" }
    }

    companion object {
        private const val TAG = "RcCollector"
        private const val PREF_TARGET = "target"
        private const val PREF_SEND_AXES = "send_axes"

        /**
         * Where the collector sends, unless the panel says otherwise.
         *
         * rcbridge listens on 0.0.0.0:14570 on this same remote, so the default is
         * a loopback address rather than an empty field. An empty target made
         * startup depend on someone opening the settings panel and pressing Start
         * before a single button did anything - the keys were subscribed, the
         * values arrived, and all of it was discarded at the socket.
         */
        private const val DEFAULT_TARGET = "127.0.0.1:14570"

        /** How often to ask whether DjiMsdkBridge.init's registration has landed. */
        private const val REGISTRATION_POLL_MS = 500L

        /**
         * How long to keep asking.
         *
         * Registration takes 0.9 s on this remote. Two minutes is not a tight
         * bound in either direction: it is long enough that a slow network
         * registration never trips it, and short enough that a failure is
         * reported rather than waited on indefinitely.
         */
        private const val REGISTRATION_TIMEOUT_MS = 120_000L

        /**
         * How long a momentary SDK button is held before being released.
         *
         * Long enough to land inside rcbridge's 120 ms merge window even if the
         * release arrives a few milliseconds after the press, and short enough
         * that a tap still reads as a tap rather than a 250 ms press.
         */
        private const val SHORT_HOLD_MS = 250L

        /** Width of the analog bars, in characters. See bar(). */
        private const val BAR_WIDTH = 36
    }

    /** Packets handed to the socket so far. Shown in the settings panel. */
    fun packetsSent(): Long = txCount

    /** Which buttons are held right now, by name; a dash when none. */
    fun heldText(): String = heldToText(buttons)
}

// ---------------------------------------------------------------------------
// A process-wide instance.
//
// The collector is driven from the settings panel, which is a static overlay
// window with no Activity to hang an instance off, so one is created here on
// first use. Created lazily rather than in a static initialiser because the SDK
// loader has to have run first, and that happens in Application.attachBaseContext.
//
// The registration itself is not started here: EdgeTxApplication already calls
// DjiMsdkBridge.init, which registers the SDK for its own input path. Calling
// init() a second time is not supported, and the SDK gives no error - the second
// callback simply never arrives, which looks exactly like a network problem. So
// this joins whatever registration the app already has.
object RcBridgeHolder {
    @Volatile
    private var instance: RcBridge? = null

    fun get(context: android.content.Context): RcBridge =
        instance ?: synchronized(this) {
            instance ?: RcBridge(context.applicationContext).also {
                instance = it
                it.startSdk()
            }
        }
}
