package com.fmradio.dsp

import com.fmradio.ui.DesktopLog
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Automatic gain control for the tuner — the desktop counterpart of the loop
 * the Android build runs in FmRadioService.startGainControl.
 *
 * What the desktop did before: R820T/R828D were pinned at maximum gain and
 * every other tuner was handed to librtlsdr's automatic mode. Neither is a
 * gain control for FM.
 *
 *  - Maximum gain is 49.6 dB on an R820T. A local transmitter drives the
 *    RTL2832U's 8-bit converter into its end stops, and clipped samples reach
 *    the discriminator as harsh, crackly distortion on a station that is clean
 *    everywhere else.
 *  - The automatic mode is the tuner's own hardware AGC. It is designed for
 *    television, settles over many seconds, and equalises everything it sees —
 *    which also destroys the level comparison a station scan depends on.
 *
 * So close the loop in software instead, off the measured converter loading,
 * and do it for every tuner: what matters is the ADC level, and every tuner
 * reports a discrete gain table that can be stepped through.
 *
 * The level is measured exactly as the Android DSP measures it — RMS of the
 * raw IQ magnitude, per axis, against full scale — so the same target means
 * the same thing on both builds and a field log from one can be read against
 * the other. A healthy RTL2832U input sits near a quarter of full scale: far
 * enough above the quantisation floor to be irrelevant, with headroom left for
 * FM's peaks and for a strong neighbouring channel.
 *
 * One decision every 200 ms tracks fading at driving speed while staying far
 * slower than programme modulation, so the loop cannot pump on the audio.
 */
class TunerAgc(gains: IntArray, private val apply: (Int) -> Unit) {

    companion object {
        /** Middle of the dead zone — what the proportional correction aims at. */
        const val ADC_RMS_TARGET = 0.27f
        const val ADC_RMS_HIGH = 0.34f
        const val ADC_RMS_LOW = 0.20f
        /** Any sustained clipping at all is already audible on FM. */
        const val CLIP_LIMIT_PCT = 0.02f

        private const val DECISION_MS = 200L
        /**
         * Ignore one decision after a change so the loop does not react to its
         * own move before the level has settled. One is enough because the
         * corrections are proportional and therefore few.
         */
        private const val SETTLE_DECISIONS = 1
        /** Log a steady-state line every ~10 s so field logs show the level. */
        private const val LOG_EVERY = 50
        /**
         * Where the loop starts, as a fraction of the tuner's range. Mid-scale
         * rather than maximum so a strong station is not grossly overloaded for
         * the first seconds after tuning in.
         */
        private const val START_FRACTION = 0.65
        /** Biggest single correction, in dB — the same span as Android's ±8 steps. */
        private const val MAX_CORRECTION_DB = 16.0
    }

    /** Supported gains in tenths of a dB, ascending. */
    private val table = gains.distinct().sorted().toIntArray()

    /** False when the tuner reports no usable gain table; caller falls back. */
    val isUsable: Boolean get() = table.size >= 2

    private var index = -1
    private var acc = 0.0
    private var count = 0L
    private var clipCount = 0L
    private var subsample = 0
    private var nextDecisionMs = 0L
    private var settle = 0
    private var quietDecisions = 0

    var gainTenths = 0; private set
    var rms = 0f; private set
    var clipPct = 0f; private set

    fun start() {
        if (!isUsable) return
        acc = 0.0; count = 0L; clipCount = 0L; subsample = 0
        settle = SETTLE_DECISIONS
        quietDecisions = 0
        nextDecisionMs = System.currentTimeMillis() + DECISION_MS
        val span = table.last() - table.first()
        index = nearest(table.first() + (span * START_FRACTION).roundToInt())
        gainTenths = table[index]
        apply(gainTenths)
        DesktopLog.log("AGC: %d steps, %.1f..%.1f dB, starting at %.1f dB"
            .format(table.size, table.first() / 10.0, table.last() / 10.0, gainTenths / 10.0))
    }

    /**
     * Feed one USB block. Metering is subsampled to every fourth IQ pair: the
     * result is averaged over 200 ms anyway, so a quarter of the samples is a
     * statistically identical measurement for a quarter of the work.
     */
    fun feed(iq: ByteArray) {
        if (index < 0) return
        var i = 0
        val n = iq.size - 1
        while (i < n) {
            if ((subsample++ and 3) == 0) {
                val rawI = iq[i].toInt() and 0xFF
                val rawQ = iq[i + 1].toInt() and 0xFF
                val fi = rawI / 127.5 - 1.0
                val fq = rawQ / 127.5 - 1.0
                acc += fi * fi + fq * fq
                count++
                if (rawI <= 1 || rawI >= 254 || rawQ <= 1 || rawQ >= 254) clipCount++
            }
            i += 2
        }
        val now = System.currentTimeMillis()
        if (now < nextDecisionMs) return
        nextDecisionMs = now + DECISION_MS
        decide()
    }

    private fun decide() {
        if (count == 0L) return
        rms = sqrt(acc / count / 2.0).toFloat()
        clipPct = (100.0 * clipCount / count).toFloat()
        acc = 0.0; count = 0L; clipCount = 0L

        if (settle > 0) { settle--; return }

        // Correct proportionally: turn the level error straight into decibels
        // and move the gain by that much. One notch at a time is fine once
        // settled but hopeless from a cold start, which is where the audible
        // problem is — a loud burst of noise on tuning in that then clears up.
        var newIndex = index
        var down = false
        if (clipPct > CLIP_LIMIT_PCT) {
            // Clipping compresses the reading, so rms understates how far out
            // we are. Move decisively rather than proportionally.
            val db = (clipPct / CLIP_LIMIT_PCT).toDouble().coerceIn(2.0, 6.0) * 2.0
            newIndex = nearest(table[index] - (db * 10).roundToInt())
            down = true
        } else if (rms > ADC_RMS_HIGH || rms < ADC_RMS_LOW) {
            val errDb = (20.0 * log10(rms / ADC_RMS_TARGET.toDouble()))
                .coerceIn(-MAX_CORRECTION_DB, MAX_CORRECTION_DB)
            newIndex = nearest(table[index] - (errDb * 10).roundToInt())
            down = errDb > 0
        } else {
            if (++quietDecisions >= LOG_EVERY) {
                quietDecisions = 0
                DesktopLog.log("AGC steady: %.1f dB, rms=%.3f clip=%.3f%%"
                    .format(gainTenths / 10.0, rms, clipPct))
            }
            return
        }

        // A coarse gain table can round the correction back to where we
        // already are, which would leave the loop stuck overloaded. Guarantee
        // at least one notch in the direction the error asks for.
        if (newIndex == index) newIndex = if (down) index - 1 else index + 1
        newIndex = newIndex.coerceIn(0, table.size - 1)
        if (newIndex == index) return

        index = newIndex
        gainTenths = table[index]
        apply(gainTenths)
        settle = SETTLE_DECISIONS
        quietDecisions = 0
        DesktopLog.log("AGC: gain -> %.1f dB (rms=%.3f clip=%.3f%%)"
            .format(gainTenths / 10.0, rms, clipPct))
    }

    private fun nearest(tenths: Int): Int {
        var best = 0
        var bestDiff = Int.MAX_VALUE
        for (i in table.indices) {
            val d = kotlin.math.abs(table[i] - tenths)
            if (d < bestDiff) { bestDiff = d; best = i }
        }
        return best
    }

    /** One-line summary for the periodic health log. */
    fun report(): String =
        if (index < 0) "off" else "%.1fdB rms=%.3f clip=%.3f%%".format(gainTenths / 10.0, rms, clipPct)
}
