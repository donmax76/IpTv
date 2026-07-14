package com.fmradio.dsp

import kotlin.math.*

/**
 * High-quality FM demodulation pipeline based on SDR++/rtl_fm/librtlsdr.
 *
 * IQ (1152 kHz) → DC removal → IF LPF (±120 kHz) → Decimate /6 → FM discriminator (192 kHz)
 *   → Pilot PLL (locks 19 kHz) → pilotPhase×2 = 38 kHz for stereo L-R
 *   → Wideband baseband output + pilotPhase (for RDS decoder at 192 kHz)
 *   → Stereo decode: L+R (mono LPF) and L-R (38 kHz demod + LPF)
 *   → Decimate /4 → De-emphasis (50µs) → Squelch → Stereo PCM (48 kHz)
 *
 * References:
 *   - SDR++ broadcast_fm.h: PLL-locked stereo, separate L/R filters
 *   - librtlsdr rtl_fm.c: fast_atan2, polar_discriminant, LUT atan2
 *   - osmocom rtl-sdr wiki: RTL2832U device parameters
 */
class FmDemodulator(
    private val inputSampleRate: Int = RECOMMENDED_SAMPLE_RATE,
    private val audioSampleRate: Int = 48000
) {
    companion object {
        const val RECOMMENDED_SAMPLE_RATE = 1152000
    }

    private val stage1Decimation = 6
    private val intermediateRate: Int = inputSampleRate / stage1Decimation  // 192000
    private val stage2Decimation: Int = intermediateRate / audioSampleRate  // 4

    // DC removal (IIR high-pass)
    // Use faster alpha for quicker convergence on frequency change
    private var dcI = 0f
    private var dcQ = 0f
    private val dcAlpha = 0.9995f  // ~50 Hz cutoff at 1.152 MHz — converges in ~2000 samples (~2ms)

    // FM discriminator state
    private var prevI = 0f
    private var prevQ = 0f

    // FM deviation gain — converts atan2 output to proper audio level
    // Max phase change per sample = 2π × 75000/192000 ≈ 2.454 rad
    // We want 100% modulation to map to ~±0.7 to leave headroom
    private val fmGain = (intermediateRate.toFloat() / (2f * PI.toFloat() * 75000f)) * 0.7f

    // De-emphasis filter (50µs time constant for Europe/Russia)
    private var deEmphasisStateL = 0f
    private var deEmphasisStateR = 0f
    private val deEmphasisAlpha: Float

    // IF low-pass filter (before stage 1 decimation)
    private val ifLpfOrder = 128
    private val ifLpfCoeffs: FloatArray
    private var ifBufI = FloatArray(ifLpfOrder)
    private var ifBufQ = FloatArray(ifLpfOrder)
    private var ifBufIdx = 0

    // Audio low-pass filters — separate for L+R (mono) and L-R (stereo difference)
    private val audioLpfOrder = 96
    private val audioLpfCoeffs: FloatArray
    private var monoLpfBuf = FloatArray(audioLpfOrder)    // L+R channel
    private var monoLpfIdx = 0
    private var diffLpfBuf = FloatArray(audioLpfOrder)    // L-R channel
    private var diffLpfIdx = 0

    private var stage1Counter = 0
    private var stage2Counter = 0

    // Wideband output for RDS — includes pilot phase
    var widebandListener: ((FloatArray, Double) -> Unit)? = null

    // ========== Pilot PLL (19 kHz, SDR++/gr-rds approach) ==========
    private val pilotBpfState = DoubleArray(4)
    private val pilotBpfB0: Double
    private val pilotBpfB2: Double
    private val pilotBpfA1: Double
    private val pilotBpfA2: Double

    private var pilotNcoPhase = 0.0
    private var pilotNcoFreq = 2.0 * PI * 19000.0 / intermediateRate
    private val pilotLoopBw = 2.0 * PI * 5.0 / intermediateRate
    private val pilotAlpha: Double
    private val pilotBeta: Double

    private var pilotStrength = 0f
    private var pilotStrengthAcc = 0f
    private var pilotStrengthCount = 0
    private val pilotDetectWindow = intermediateRate / 4

    @Volatile
    var isStereo = false
        private set

    @Volatile
    private var resetRequested = false

    /** Thread-safe reset: performed by the DSP thread at the next demodulate() call. */
    fun requestReset() {
        resetRequested = true
    }

    // Squelch based on signal quality — faster response
    private var signalQualityAcc = 0.0
    private var signalQualityCount = 0
    private var squelchOpen = false  // Start closed to avoid initial burst of noise
    private var squelchLevel = 0f
    private val squelchAttack = 0.03f   // ~33ms to open (smooth fade-in)
    private val squelchRelease = 0.02f  // ~50ms to close (fast mute on noise)

    // Warmup: discard first N intermediate samples to flush stale filter state
    private var warmupSamples = 0
    private val warmupThreshold = intermediateRate / 2  // 0.5s warmup for filter settling

    // Crossfade for seamless muting during frequency change
    private var muteRamp = 0f  // 0 = muted, 1 = full volume
    private val muteRampUp = 0.005f   // ~200 audio samples to reach full volume

    // 19 kHz pilot notch biquad (applied at audio rate, separate L/R state)
    private val notchB0: Float
    private val notchB1: Float
    private val notchB2: Float
    private val notchA1: Float
    private val notchA2: Float
    private var notchLX1 = 0f; private var notchLX2 = 0f
    private var notchLY1 = 0f; private var notchLY2 = 0f
    private var notchRX1 = 0f; private var notchRX2 = 0f
    private var notchRY1 = 0f; private var notchRY2 = 0f

    init {
        // De-emphasis: 50µs time constant (Europe/Russia standard)
        val tau = 50e-6f
        val dt = 1f / audioSampleRate
        deEmphasisAlpha = dt / (tau + dt)

        // IF filter: 90 kHz cutoff. Must stay below the post-decimation
        // Nyquist (192 kHz / 2 = 96 kHz) — the previous 120 kHz cutoff let
        // noise and adjacent-channel energy from 96-120 kHz alias straight
        // into the signal band after the /6 decimation.
        ifLpfCoeffs = designLowPassFilter(ifLpfOrder, 90000f / inputSampleRate)
        // Audio filter: 15 kHz cutoff — standard FM mono audio
        audioLpfCoeffs = designLowPassFilter(audioLpfOrder, 15000f / intermediateRate)

        // Design 19 kHz pilot bandpass biquad (Q=80 for narrow extraction)
        val w0 = 2.0 * PI * 19000.0 / intermediateRate
        val bpfQ = 80.0
        val bpfAlpha = sin(w0) / (2.0 * bpfQ)
        val a0 = 1.0 + bpfAlpha
        pilotBpfB0 = bpfAlpha / a0
        pilotBpfB2 = -bpfAlpha / a0
        pilotBpfA1 = (-2.0 * cos(w0)) / a0
        pilotBpfA2 = (1.0 - bpfAlpha) / a0

        // PLL gains — second-order loop, critically damped (Gardner's textbook)
        val damp = 0.707
        val bw = pilotLoopBw
        pilotAlpha = 2.0 * damp * bw
        pilotBeta = bw * bw

        // 19 kHz RBJ notch at the audio rate (pass-through if out of range)
        if (19000.0 < audioSampleRate / 2.0) {
            val nw0 = 2.0 * PI * 19000.0 / audioSampleRate
            val nAlpha = sin(nw0) / (2.0 * 8.0)  // Q = 8
            val nA0 = 1.0 + nAlpha
            notchB0 = (1.0 / nA0).toFloat()
            notchB1 = (-2.0 * cos(nw0) / nA0).toFloat()
            notchB2 = (1.0 / nA0).toFloat()
            notchA1 = (-2.0 * cos(nw0) / nA0).toFloat()
            notchA2 = ((1.0 - nAlpha) / nA0).toFloat()
        } else {
            notchB0 = 1f; notchB1 = 0f; notchB2 = 0f; notchA1 = 0f; notchA2 = 0f
        }
    }

    private fun designLowPassFilter(order: Int, normalizedCutoff: Float): FloatArray {
        val coeffs = FloatArray(order)
        val mid = order / 2
        var sum = 0f
        for (i in 0 until order) {
            val n = i - mid
            coeffs[i] = if (n == 0) {
                2 * normalizedCutoff
            } else {
                sin(2 * PI.toFloat() * normalizedCutoff * n) / (PI.toFloat() * n)
            }
            // Blackman-Harris window — ~92 dB stopband rejection
            val w = i.toFloat() / (order - 1).toFloat()
            val a0 = 0.35875f; val a1 = 0.48829f; val a2 = 0.14128f; val a3 = 0.01168f
            coeffs[i] *= a0 - a1 * cos(2 * PI.toFloat() * w) +
                    a2 * cos(4 * PI.toFloat() * w) - a3 * cos(6 * PI.toFloat() * w)
            sum += coeffs[i]
        }
        for (i in coeffs.indices) coeffs[i] /= sum
        return coeffs
    }

    /** Process pilot biquad bandpass filter — returns isolated 19 kHz pilot signal */
    private fun pilotBpf(input: Double): Double {
        val x0 = input
        val y0 = pilotBpfB0 * x0 + pilotBpfB2 * pilotBpfState[1] -
                pilotBpfA1 * pilotBpfState[2] - pilotBpfA2 * pilotBpfState[3]
        pilotBpfState[1] = pilotBpfState[0]
        pilotBpfState[0] = x0
        pilotBpfState[3] = pilotBpfState[2]
        pilotBpfState[2] = y0
        return y0
    }

    /**
     * Fast atan2 approximation — polynomial, from rtl_fm.
     * Max error < 0.005 radians. Much faster than Math.atan2.
     */
    private fun fastAtan2(y: Float, x: Float): Float {
        val absX = abs(x)
        val absY = abs(y)
        if (absX < 1e-12f && absY < 1e-12f) return 0f
        val a = minOf(absX, absY) / maxOf(absX, absY)
        val s = a * a
        var r = ((-0.0464964749f * s + 0.15931422f) * s - 0.327622764f) * s * a + a
        if (absY > absX) r = PI.toFloat() / 2f - r
        if (x < 0) r = PI.toFloat() - r
        if (y < 0) r = -r
        return r
    }

    /**
     * Demodulate raw IQ samples to stereo audio PCM (interleaved L,R,L,R...).
     * Also feeds wideband baseband to RDS decoder if listener is set.
     *
     * @return ShortArray of interleaved stereo samples (L,R,L,R,...)
     */
    fun demodulate(iqData: ByteArray): ShortArray {
        // Deferred reset: reset() reallocates filter buffers, so calling it
        // from the UI thread while this method runs on the streaming thread
        // is a data race. requestReset() defers the reset to here, where it
        // runs on the same thread as all other DSP state access.
        if (resetRequested) {
            resetRequested = false
            reset()
        }
        val numIqSamples = iqData.size / 2
        val maxAudioSamples = numIqSamples / (stage1Decimation * stage2Decimation) + 2
        // Stereo output: 2 samples per audio frame (L, R)
        val audioOut = ShortArray(maxAudioSamples * 2)
        var audioCount = 0

        val wbListener = widebandListener
        val maxWbSamples = numIqSamples / stage1Decimation + 2
        val widebandBuf = if (wbListener != null) FloatArray(maxWbSamples) else null
        var wbCount = 0
        // Pilot phase corresponding to the START of the wideband buffer —
        // the RDS decoder seeds its 57 kHz carrier from this and advances
        // forward, so passing the end-of-buffer phase would misalign it by
        // the whole buffer length.
        val pilotPhaseAtBufferStart = pilotNcoPhase

        for (i in 0 until numIqSamples) {
            var iSample = (iqData[i * 2].toInt() and 0xFF) / 127.5f - 1f
            var qSample = (iqData[i * 2 + 1].toInt() and 0xFF) / 127.5f - 1f

            // DC removal (IIR high-pass)
            dcI = dcAlpha * dcI + (1 - dcAlpha) * iSample
            dcQ = dcAlpha * dcQ + (1 - dcAlpha) * qSample
            iSample -= dcI
            qSample -= dcQ

            // Store in IF filter buffer
            ifBufI[ifBufIdx] = iSample
            ifBufQ[ifBufIdx] = qSample
            ifBufIdx = (ifBufIdx + 1) % ifLpfOrder

            // Stage 1 decimation: 1152 kHz → 192 kHz
            stage1Counter++
            if (stage1Counter < stage1Decimation) continue
            stage1Counter = 0

            // Apply IF bandpass filter
            var filtI = 0f
            var filtQ = 0f
            for (j in 0 until ifLpfOrder) {
                val idx = (ifBufIdx - 1 - j + ifLpfOrder) % ifLpfOrder
                filtI += ifBufI[idx] * ifLpfCoeffs[j]
                filtQ += ifBufQ[idx] * ifLpfCoeffs[j]
            }

            // FM discriminator: conjugate multiply + atan2 (rtl_fm / SDR++ approach)
            val realProd = filtI * prevI + filtQ * prevQ
            val imagProd = filtQ * prevI - filtI * prevQ
            prevI = filtI
            prevQ = filtQ

            val rawBaseband = fastAtan2(imagProd, realProd)

            // Warmup: skip initial samples to let filters settle
            if (warmupSamples < warmupThreshold) {
                warmupSamples++
                val pilotSig = pilotBpf(rawBaseband.toDouble())
                val pilotError = pilotSig * cos(pilotNcoPhase)
                pilotNcoFreq += pilotBeta * pilotError
                pilotNcoPhase += pilotNcoFreq + pilotAlpha * pilotError
                if (pilotNcoPhase > 2 * PI) pilotNcoPhase -= 2 * PI
                if (pilotNcoPhase < 0) pilotNcoPhase += 2 * PI
                continue
            }

            // ===== Pilot PLL: lock to 19 kHz pilot tone =====
            // The phase detector compares the CURRENT sample against the
            // pre-update NCO phase, so that phase — not the post-update one —
            // is what corresponds to this sample. It must also be the phase
            // the 38 kHz demod uses below: the post-update phase is one NCO
            // step (≈71° at 38 kHz) ahead, which used to rotate the recovered
            // constellation almost exactly onto the orthogonal axis.
            val pilotPhaseThisSample = pilotNcoPhase
            val pilotSig = pilotBpf(rawBaseband.toDouble())
            val pilotError = pilotSig * cos(pilotNcoPhase)
            pilotNcoFreq += pilotBeta * pilotError
            pilotNcoPhase += pilotNcoFreq + pilotAlpha * pilotError
            if (pilotNcoPhase > 2 * PI) pilotNcoPhase -= 2 * PI
            if (pilotNcoPhase < 0) pilotNcoPhase += 2 * PI

            // Pilot strength measurement
            pilotStrengthAcc += (pilotSig * pilotSig).toFloat()
            pilotStrengthCount++
            if (pilotStrengthCount >= pilotDetectWindow) {
                pilotStrength = pilotStrengthAcc / pilotStrengthCount
                isStereo = pilotStrength > 0.0005f
                pilotStrengthAcc = 0f
                pilotStrengthCount = 0
            }

            // ===== Signal quality for squelch =====
            val absBaseband = abs(rawBaseband)
            signalQualityAcc += absBaseband
            signalQualityCount++
            if (signalQualityCount >= intermediateRate / 16) {
                val avgModulation = signalQualityAcc / signalQualityCount
                // Pure noise gives avg |Δφ| ≈ π/2 ≈ 1.57 (uniform phase steps),
                // real FM program material stays well below ~0.9. The previous
                // upper bound of 2.0 never triggered on dead air, so the
                // squelch was effectively disabled — loud static played on
                // empty frequencies. 1.2 rejects noise with margin both ways.
                squelchOpen = avgModulation > 0.05 && avgModulation < 1.2
                signalQualityAcc = 0.0
                signalQualityCount = 0
            }
            if (squelchOpen && squelchLevel < 1f) {
                squelchLevel = (squelchLevel + squelchAttack).coerceAtMost(1f)
            } else if (!squelchOpen && squelchLevel > 0f) {
                squelchLevel = (squelchLevel - squelchRelease).coerceAtLeast(0f)
            }

            // Wideband output for RDS decoder
            if (widebandBuf != null && wbCount < widebandBuf.size) {
                widebandBuf[wbCount++] = rawBaseband
            }

            // ===== Stereo decoding (SDR++ broadcast_fm.h approach) =====
            // Scale baseband for audio path
            val baseband = rawBaseband * fmGain

            // L+R (mono) = baseband directly (0-15 kHz already)
            val mono = baseband

            // L-R = baseband × 2×sin(2×pilotPhase) — PLL-locked 38 kHz demod.
            // The detector (err = pilot × cos(φ)) locks sin(φ) in phase with
            // the 19 kHz pilot; per the FM stereo standard the 38 kHz
            // subcarrier crosses zero with positive slope together with the
            // pilot, i.e. it is sin(2φ). Verified by simulation of this exact
            // loop: sin(2φ_pre-update) recovers L-R at amplitude +1.000;
            // the previous cos(2φ_post-update) recovered -0.947 — stereo
            // played with LEFT AND RIGHT SWAPPED (plus 5% loss).
            val stereoCarrier = sin(2.0 * pilotPhaseThisSample).toFloat()
            val diff = baseband * stereoCarrier * 2f  // ×2 for DSB-SC amplitude recovery

            // Feed into separate audio LPF buffers
            monoLpfBuf[monoLpfIdx] = mono
            diffLpfBuf[diffLpfIdx] = diff
            monoLpfIdx = (monoLpfIdx + 1) % audioLpfOrder
            diffLpfIdx = (diffLpfIdx + 1) % audioLpfOrder

            // Stage 2 decimation: 192 kHz → 48 kHz
            stage2Counter++
            if (stage2Counter < stage2Decimation) continue
            stage2Counter = 0

            // Apply audio LPF to both mono and diff channels
            var filtMono = 0f
            var filtDiff = 0f
            for (j in 0 until audioLpfOrder) {
                val mIdx = (monoLpfIdx - 1 - j + audioLpfOrder) % audioLpfOrder
                val dIdx = (diffLpfIdx - 1 - j + audioLpfOrder) % audioLpfOrder
                filtMono += monoLpfBuf[mIdx] * audioLpfCoeffs[j]
                filtDiff += diffLpfBuf[dIdx] * audioLpfCoeffs[j]
            }

            // Stereo matrix. The composite carries (L+R)/2 in the mono channel
            // and (L-R)/2 on the 38 kHz subcarrier, so after demodulation:
            // L = mono + diff, R = mono - diff — with no extra 0.5 factor.
            // (Halving here would make stereo 6 dB quieter than mono and cause
            // loudness jumps whenever the pilot detector toggles.)
            val left: Float
            val right: Float
            if (isStereo) {
                left = filtMono + filtDiff
                right = filtMono - filtDiff
            } else {
                left = filtMono
                right = filtMono
            }

            // 19 kHz pilot notch — the 15 kHz audio LPF's transition band only
            // partially attenuates the pilot; this removes the residual whine.
            val nL = notchB0 * left + notchB1 * notchLX1 + notchB2 * notchLX2 -
                notchA1 * notchLY1 - notchA2 * notchLY2
            notchLX2 = notchLX1; notchLX1 = left; notchLY2 = notchLY1; notchLY1 = nL
            val nR = notchB0 * right + notchB1 * notchRX1 + notchB2 * notchRX2 -
                notchA1 * notchRY1 - notchA2 * notchRY2
            notchRX2 = notchRX1; notchRX1 = right; notchRY2 = notchRY1; notchRY1 = nR

            // De-emphasis filter (50µs) — separate state for L and R
            deEmphasisStateL += deEmphasisAlpha * (nL - deEmphasisStateL)
            deEmphasisStateR += deEmphasisAlpha * (nR - deEmphasisStateR)

            // Apply squelch with smooth level
            val outL = deEmphasisStateL * squelchLevel
            val outR = deEmphasisStateR * squelchLevel

            // Mute ramp for seamless frequency change (avoids initial burst)
            if (muteRamp < 1f) {
                muteRamp = (muteRamp + muteRampUp).coerceAtMost(1f)
            }

            // Scale to 16-bit PCM
            val gain = muteRamp * 24000f
            val sampleL = (outL * gain).toInt().coerceIn(-32767, 32767)
            val sampleR = (outR * gain).toInt().coerceIn(-32767, 32767)

            if (audioCount + 1 < audioOut.size) {
                audioOut[audioCount++] = sampleL.toShort()
                audioOut[audioCount++] = sampleR.toShort()
            }
        }

        // Send wideband data to RDS with the pilot phase at buffer start
        if (wbListener != null && wbCount > 0) {
            val buf = if (wbCount == widebandBuf!!.size) widebandBuf else widebandBuf.copyOf(wbCount)
            wbListener.invoke(buf, pilotPhaseAtBufferStart)
        }

        return if (audioCount == audioOut.size) audioOut else audioOut.copyOf(audioCount)
    }

    fun measureSignalStrength(iqData: ByteArray): Float {
        if (iqData.isEmpty()) return -100f
        var powerSum = 0.0
        val numSamples = iqData.size / 2
        for (i in 0 until numSamples) {
            val iVal = (iqData[i * 2].toInt() and 0xFF) / 127.5f - 1f
            val qVal = (iqData[i * 2 + 1].toInt() and 0xFF) / 127.5f - 1f
            powerSum += (iVal * iVal + qVal * qVal).toDouble()
        }
        val avgPower = powerSum / numSamples
        return (10 * log10(avgPower + 1e-10)).toFloat()
    }

    fun measureSignalQuality(iqData: ByteArray): Float {
        if (iqData.size < 4) return 0f
        val numSamples = iqData.size / 2
        var prevI = 0f; var prevQ = 0f
        var phaseVariance = 0.0
        var phaseMean = 0.0
        var count = 0
        for (i in 0 until numSamples) {
            val iVal = (iqData[i * 2].toInt() and 0xFF) / 127.5f - 1f
            val qVal = (iqData[i * 2 + 1].toInt() and 0xFF) / 127.5f - 1f
            if (i > 0) {
                val realProd = iVal * prevI + qVal * prevQ
                val imagProd = qVal * prevI - iVal * prevQ
                val phase = atan2(imagProd, realProd)
                phaseMean += phase
                phaseVariance += phase * phase
                count++
            }
            prevI = iVal; prevQ = qVal
        }
        if (count == 0) return 0f
        phaseMean /= count
        phaseVariance = phaseVariance / count - phaseMean * phaseMean
        return phaseVariance.toFloat()
    }

    fun reset() {
        prevI = 0f; prevQ = 0f; deEmphasisStateL = 0f; deEmphasisStateR = 0f
        dcI = 0f; dcQ = 0f
        notchLX1 = 0f; notchLX2 = 0f; notchLY1 = 0f; notchLY2 = 0f
        notchRX1 = 0f; notchRX2 = 0f; notchRY1 = 0f; notchRY2 = 0f
        ifBufI = FloatArray(ifLpfOrder); ifBufQ = FloatArray(ifLpfOrder); ifBufIdx = 0
        monoLpfBuf = FloatArray(audioLpfOrder); monoLpfIdx = 0
        diffLpfBuf = FloatArray(audioLpfOrder); diffLpfIdx = 0
        stage1Counter = 0; stage2Counter = 0
        pilotNcoPhase = 0.0
        pilotNcoFreq = 2.0 * PI * 19000.0 / intermediateRate
        for (i in pilotBpfState.indices) pilotBpfState[i] = 0.0
        pilotStrength = 0f; pilotStrengthAcc = 0f; pilotStrengthCount = 0
        isStereo = false
        signalQualityAcc = 0.0; signalQualityCount = 0
        squelchOpen = false; squelchLevel = 0f
        warmupSamples = 0
        muteRamp = 0f  // Start muted, ramp up smoothly
    }
}
