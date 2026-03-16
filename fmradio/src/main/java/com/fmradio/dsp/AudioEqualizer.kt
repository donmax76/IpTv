package com.fmradio.dsp

import kotlin.math.*

/**
 * Simple 2-band equalizer (Bass/Treble) using biquad shelving filters.
 * Bass: low-shelf at 300 Hz
 * Treble: high-shelf at 3000 Hz
 *
 * Gain range: -10 dB to +10 dB (0 = flat)
 */
class AudioEqualizer(private val sampleRate: Int = 48000) {

    // Bass biquad coefficients
    private var bassB0 = 1f; private var bassB1 = 0f; private var bassB2 = 0f
    private var bassA1 = 0f; private var bassA2 = 0f
    // Bass filter state
    private var bassX1 = 0f; private var bassX2 = 0f
    private var bassY1 = 0f; private var bassY2 = 0f

    // Treble biquad coefficients
    private var trebB0 = 1f; private var trebB1 = 0f; private var trebB2 = 0f
    private var trebA1 = 0f; private var trebA2 = 0f
    // Treble filter state
    private var trebX1 = 0f; private var trebX2 = 0f
    private var trebY1 = 0f; private var trebY2 = 0f

    // Current gain settings in dB
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

    /**
     * Compute biquad shelving filter coefficients.
     * Robert Bristow-Johnson's Audio EQ Cookbook formulas.
     */
    private fun computeShelfCoeffs(freq: Float, gainDb: Float, isLowShelf: Boolean): FloatArray {
        if (abs(gainDb) < 0.1f) {
            // Flat: passthrough
            return floatArrayOf(1f, 0f, 0f, 0f, 0f)
        }

        val A = 10f.pow(gainDb / 40f)  // sqrt of linear gain
        val w0 = 2f * PI.toFloat() * freq / sampleRate
        val cosW0 = cos(w0)
        val sinW0 = sin(w0)
        val S = 1f  // Shelf slope
        val alpha = sinW0 / 2f * sqrt((A + 1f / A) * (1f / S - 1f) + 2f)
        val sqrtA2alpha = 2f * sqrt(A) * alpha

        val b0: Float
        val b1: Float
        val b2: Float
        val a0: Float
        val a1: Float
        val a2: Float

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

        // Normalize by a0
        return floatArrayOf(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
    }

    /**
     * Process audio samples in-place through bass and treble filters.
     */
    fun process(samples: ShortArray): ShortArray {
        if (abs(bassGainDb) < 0.1f && abs(trebleGainDb) < 0.1f) {
            return samples // Bypass when flat
        }

        val out = ShortArray(samples.size)
        for (i in samples.indices) {
            var x = samples[i].toFloat() / 32767f

            // Bass biquad
            var y = bassB0 * x + bassB1 * bassX1 + bassB2 * bassX2 - bassA1 * bassY1 - bassA2 * bassY2
            bassX2 = bassX1; bassX1 = x
            bassY2 = bassY1; bassY1 = y
            x = y

            // Treble biquad
            y = trebB0 * x + trebB1 * trebX1 + trebB2 * trebX2 - trebA1 * trebY1 - trebA2 * trebY2
            trebX2 = trebX1; trebX1 = x
            trebY2 = trebY1; trebY1 = y

            out[i] = (y * 32767f).coerceIn(-32767f, 32767f).toInt().toShort()
        }
        return out
    }

    fun reset() {
        bassX1 = 0f; bassX2 = 0f; bassY1 = 0f; bassY2 = 0f
        trebX1 = 0f; trebX2 = 0f; trebY1 = 0f; trebY2 = 0f
    }
}
