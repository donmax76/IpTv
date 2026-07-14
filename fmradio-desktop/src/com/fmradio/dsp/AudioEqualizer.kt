package com.fmradio.dsp

import kotlin.math.*

/**
 * Stereo 2-band equalizer (Bass/Treble) using biquad shelving filters.
 * Bass: low-shelf at 300 Hz
 * Treble: high-shelf at 3000 Hz
 * Processes interleaved stereo samples (L,R,L,R,...) with independent L/R filter states.
 *
 * Gain range: -10 dB to +10 dB (0 = flat)
 */
class AudioEqualizer(private val sampleRate: Int = 48000) {

    // Bass biquad coefficients (shared for L/R — same filter design)
    private var bassB0 = 1f; private var bassB1 = 0f; private var bassB2 = 0f
    private var bassA1 = 0f; private var bassA2 = 0f
    // Bass filter state — separate for Left and Right channels
    private var bassLX1 = 0f; private var bassLX2 = 0f; private var bassLY1 = 0f; private var bassLY2 = 0f
    private var bassRX1 = 0f; private var bassRX2 = 0f; private var bassRY1 = 0f; private var bassRY2 = 0f

    // Treble biquad coefficients
    private var trebB0 = 1f; private var trebB1 = 0f; private var trebB2 = 0f
    private var trebA1 = 0f; private var trebA2 = 0f
    // Treble filter state — separate for Left and Right channels
    private var trebLX1 = 0f; private var trebLX2 = 0f; private var trebLY1 = 0f; private var trebLY2 = 0f
    private var trebRX1 = 0f; private var trebRX2 = 0f; private var trebRY1 = 0f; private var trebRY2 = 0f

    var bassGainDb: Float = 0f
        set(value) {
            field = value.coerceIn(-10f, 10f)
            computeBassCoeffs()
        }

    var trebleGainDb: Float = 0f
        set(value) {
            field = value.coerceIn(-10f, 10f)
            computeTrebleCoeffs()
        }

    init {
        computeBassCoeffs()
        computeTrebleCoeffs()
    }

    private fun computeBassCoeffs() {
        computeShelfCoeffs(300f, bassGainDb, isLowShelf = true).let { (b0, b1, b2, a1, a2) ->
            bassB0 = b0; bassB1 = b1; bassB2 = b2; bassA1 = a1; bassA2 = a2
        }
    }

    private fun computeTrebleCoeffs() {
        computeShelfCoeffs(3000f, trebleGainDb, isLowShelf = false).let { (b0, b1, b2, a1, a2) ->
            trebB0 = b0; trebB1 = b1; trebB2 = b2; trebA1 = a1; trebA2 = a2
        }
    }

    private fun computeShelfCoeffs(freq: Float, gainDb: Float, isLowShelf: Boolean): FloatArray {
        if (abs(gainDb) < 0.1f) {
            return floatArrayOf(1f, 0f, 0f, 0f, 0f)
        }
        val A = 10f.pow(gainDb / 40f)
        val w0 = 2f * PI.toFloat() * freq / sampleRate
        val cosW0 = cos(w0)
        val sinW0 = sin(w0)
        val S = 1f
        val alpha = sinW0 / 2f * sqrt((A + 1f / A) * (1f / S - 1f) + 2f)
        val sqrtA2alpha = 2f * sqrt(A) * alpha

        val b0: Float; val b1: Float; val b2: Float
        val a0: Float; val a1: Float; val a2: Float

        if (isLowShelf) {
            b0 = A * ((A + 1) - (A - 1) * cosW0 + sqrtA2alpha)
            b1 = 2 * A * ((A - 1) - (A + 1) * cosW0)
            b2 = A * ((A + 1) - (A - 1) * cosW0 - sqrtA2alpha)
            a0 = (A + 1) + (A - 1) * cosW0 + sqrtA2alpha
            a1 = -2 * ((A - 1) + (A + 1) * cosW0)
            a2 = (A + 1) + (A - 1) * cosW0 - sqrtA2alpha
        } else {
            b0 = A * ((A + 1) + (A - 1) * cosW0 + sqrtA2alpha)
            b1 = -2 * A * ((A - 1) + (A + 1) * cosW0)
            b2 = A * ((A + 1) + (A - 1) * cosW0 - sqrtA2alpha)
            a0 = (A + 1) - (A - 1) * cosW0 + sqrtA2alpha
            a1 = 2 * ((A - 1) - (A + 1) * cosW0)
            a2 = (A + 1) - (A - 1) * cosW0 - sqrtA2alpha
        }
        return floatArrayOf(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
    }

    /**
     * Process interleaved stereo samples (L,R,L,R,...) through bass and treble filters.
     * Uses separate filter states for left and right channels to prevent crosstalk.
     */
    fun process(samples: ShortArray): ShortArray {
        if (abs(bassGainDb) < 0.1f && abs(trebleGainDb) < 0.1f) {
            return samples
        }

        val out = ShortArray(samples.size)
        var i = 0
        while (i < samples.size - 1) {
            // Left channel (even indices)
            var xL = samples[i].toFloat() / 32767f
            var yL = bassB0 * xL + bassB1 * bassLX1 + bassB2 * bassLX2 - bassA1 * bassLY1 - bassA2 * bassLY2
            bassLX2 = bassLX1; bassLX1 = xL; bassLY2 = bassLY1; bassLY1 = yL
            xL = yL
            yL = trebB0 * xL + trebB1 * trebLX1 + trebB2 * trebLX2 - trebA1 * trebLY1 - trebA2 * trebLY2
            trebLX2 = trebLX1; trebLX1 = xL; trebLY2 = trebLY1; trebLY1 = yL
            out[i] = (yL * 32767f).coerceIn(-32767f, 32767f).toInt().toShort()

            // Right channel (odd indices)
            var xR = samples[i + 1].toFloat() / 32767f
            var yR = bassB0 * xR + bassB1 * bassRX1 + bassB2 * bassRX2 - bassA1 * bassRY1 - bassA2 * bassRY2
            bassRX2 = bassRX1; bassRX1 = xR; bassRY2 = bassRY1; bassRY1 = yR
            xR = yR
            yR = trebB0 * xR + trebB1 * trebRX1 + trebB2 * trebRX2 - trebA1 * trebRY1 - trebA2 * trebRY2
            trebRX2 = trebRX1; trebRX1 = xR; trebRY2 = trebRY1; trebRY1 = yR
            out[i + 1] = (yR * 32767f).coerceIn(-32767f, 32767f).toInt().toShort()

            i += 2
        }
        return out
    }

    fun reset() {
        bassLX1 = 0f; bassLX2 = 0f; bassLY1 = 0f; bassLY2 = 0f
        bassRX1 = 0f; bassRX2 = 0f; bassRY1 = 0f; bassRY2 = 0f
        trebLX1 = 0f; trebLX2 = 0f; trebLY1 = 0f; trebLY2 = 0f
        trebRX1 = 0f; trebRX2 = 0f; trebRY1 = 0f; trebRY2 = 0f
    }
}
