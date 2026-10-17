package com.edgetx.droidui

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.min

/**
 * The RCB1 packet, in Kotlin.
 *
 * Deliberately mirrors rcbridge's proto.go field for field and offset for
 * offset. There are three implementations of this format - that one, this one and
 * rc_bridge.cpp in the EdgeTX app - and none of the three compilers can see the
 * other two, so the offsets are pinned by a test on the Go side (wire_test.go)
 * that fails if this file and proto.go disagree.
 *
 * That test exists because both of the other implementations had the same bug
 * during development: a padding word after the `fmode` byte, which shifted
 * `buttons` from offset 32 to 36. The symptom is not a link that fails to come
 * up - it is a receiver that decodes a well-formed 48 bytes into a stick that
 * reads slightly wrong, which is far harder to trace.
 */
object Proto {

    /** The size of every packet. Fixed by the format. */
    const val SIZE = 48

    /** "RCB1", read as little-endian so it matches proto.go's constant. */
    const val MAGIC = 0x31424352

    /** Sent by the collector app. */
    const val SRC_MSDK = 1

    /** The packet carries stick/dial values. */
    const val FLAG_AXES = 1

    /** The packet carries the button bits. */
    const val FLAG_BUTTONS = 2

    /**
     * The SDK's stick readings span -660..660 at full deflection, measured on the
     * device. Everything is a fraction of this rather than of 1000, so that a
     * stick that reports 655 is recognised as full travel rather than as a
     * reading that needs clamping.
     */
    const val DJI_RANGE = 660f

    /**
     * The default dead zone, as a fraction of full travel.
     *
     * A gimbal that is at rest still reports a few counts of jitter, and turning
     * that into channel movement makes a parked model twitch. Applied here rather
     * than in rcbridge so both readers dead-zone the same way - a stick that is
     * quiet from the kernel and noisy from the SDK would otherwise sit on opposite
     * sides of the model gate.
     */
    const val DEFAULT_DEAD_ZONE = 0.03f

    /**
     * One SDK stick reading to the wire format's -1000..1000.
     *
     * The dead zone is subtracted from the travel rather than applied as a
     * separate clamp, so a stick resting 2% inside the dead zone reads as a
     * smooth 0 at the centre and full travel is still reached at 100%. Clamping
     * instead would leave a flat step at the edge of the zone.
     */
    @JvmStatic
    fun scale(raw: Int, deadZoneFraction: Float = DEFAULT_DEAD_ZONE): Int {
        val n = raw / DJI_RANGE
        val f = if (abs(n) < deadZoneFraction) {
            0f
        } else {
            val sign = if (n < 0f) -1f else 1f
            val m = (abs(n) - deadZoneFraction) / (1f - deadZoneFraction)
            sign * min(m, 1f)
        }
        return (f * 1000f).toInt().coerceIn(-1000, 1000)
    }

    /**
     * Builds a packet.
     *
     * `buf` is reused so the 100 Hz send loop does not allocate 100 times a
     * second; on a phone that also has a radio link and a DJI SDK running, a
     * steady allocation here is worth avoiding.
     *
     * The offsets below are the format, not a detail of this code. Every field's
     * position is asserted by a unit test against the same table, because a
     * stray padding word here does not fail to compile and does not fail to run
     * - it writes a well-formed 48 bytes that the receiver decodes into
     * plausible-looking rubbish.
     */
    @JvmStatic
    fun encode(
        buf: ByteArray,
        seq: Long,
        tMillis: Long,
        sticks: IntArray,
        dials: IntArray,
        wheel: Int,
        five: Int,
        fmode: Int,
        buttons: Long,
        hasAxes: Boolean,
        hasButtons: Boolean,
    ): ByteArray {
        val b = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        b.clear()

        b.putInt(MAGIC)                    // 0..3
        b.putInt(seq.toInt())              // 4..7
        b.putInt(tMillis.toInt())          // 8..11
        b.put(SRC_MSDK.toByte())           // 12
        var flags = 0
        if (hasAxes) flags = flags or FLAG_AXES
        if (hasButtons) flags = flags or FLAG_BUTTONS
        b.put(flags.toByte())              // 13
        b.putShort(0)                      // 14..15 reserved

        for (i in 0 until 4) b.putShort(sticks[i].toShort())   // 16..23
        for (i in 0 until 2) b.putShort(dials[i].toShort())    // 24..27
        b.putShort(wheel.toShort())        // 28..29
        b.put(five.toByte())               // 30
        b.put(fmode.toByte())              // 31
        // No padding here: five + fmode already end at 32, which is where the
        // uint64 below belongs and is 8-byte aligned on purpose.
        b.putLong(buttons)                 // 32..39
        b.putInt(1)                        // 40..43 sourceMask: msdk is live
        b.putInt(0)                        // 44..47 reserved
        return buf
    }

    /** One log line, so every caller does not have to import Log itself. */
    @JvmStatic
    fun logError(what: String, t: Throwable) {
        Log.w(TAG, what, t)
    }

    private const val TAG = "RcCollector"

    /**
     * Button bits, in the order proto.go numbers them.
     *
     * The order is the format, not a preference: it is fixed by rcbridge's
     * state.go and by the Go side's own tests. The face buttons come first
     * because that is the order the evdev key codes were identified in, and the
     * shoulder buttons after.
     *
     * Written as plain values rather than `1L shl N`. A `shl` expression here
     * compiled to a ConstantValue of zero for every entry - which is not a
     * compile error, is invisible on screen, and means no button is ever
     * transmitted - so the bits are spelled out and a test can read them.
     */
    object Btn {
        const val SHUTTER = 1L
        const val RECORD = 2L
        const val GO_HOME = 4L
        const val PAUSE = 8L
        const val C1 = 16L
        const val C2 = 32L
        const val C3 = 64L
        const val C4 = 128L
        const val L1 = 256L
        const val L2 = 512L
        const val L3 = 1024L
        const val R1 = 2048L
        const val R2 = 4096L
        const val R3 = 8192L
        const val RTN = 16384L

        // There is deliberately no takeoff bit here.
        //
        // A separate one was added and then removed: the DJI key
        // KeyRCAuthLedButtonDown is already bound to C4 above, and the round lit
        // button it names is on the RC Pro, not on this remote - DjiMsdkBridge
        // says so in as many words ("The auth-led button is the round one on an
        // RC Pro"). A second bit fed by the same key would have been the same
        // duplicate the switch table already had, one layer down.

        /**
         * The name of one button bit, for the channel readout.
         *
         * The inverse of this table, and it lives here rather than at the call
         * site because the call site has a bitmask and this is what knows what
         * the bits mean. A second copy would drift the first time a button is
         * added, and it would drift silently - the readout would print a name
         * that does not match the button.
         *
         * Returns "?" for a bit that is not a button. A malformed mask should
         * look odd on the readout, not throw on the UI thread ten times a second.
         */
        @JvmStatic
        fun nameOf(bit: Long): String = when (bit) {
            SHUTTER -> "快门"
            RECORD -> "录像"
            GO_HOME -> "返航"
            PAUSE -> "急停"
            C1 -> "C1"
            C2 -> "C2"
            C3 -> "C3"
            C4 -> "C4"
            L1 -> "L1"
            L2 -> "L2"
            L3 -> "L3"
            R1 -> "R1"
            R2 -> "R2"
            R3 -> "R3"
            RTN -> "返回"
            else -> "?"
        }
    }

    /** 5-way bits, matching proto.go. */
    object Pad {
        const val UP = 1
        const val DOWN = 2
        const val LEFT = 4
        const val RIGHT = 8
        const val CENTRE = 16
    }
}