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
    // 24 taps: ~85% CPU savings vs 128 taps. Transition band is wider but acceptable
    // for FM (adjacent channel at ±200 kHz, our cutoff at 120 kHz).
    private val ifLpfOrder = 24
    private val ifLpfCoeffs: FloatArray
    // Double-buffer trick: size 2×N, write to both halves, filter without modulo
    private var ifBufI = FloatArray(ifLpfOrder * 2)
    private var ifBufQ = FloatArray(ifLpfOrder * 2)
    private var ifBufIdx = 0

    // Audio low-pass filters — separate for L+R (mono) and L-R (stereo difference)
    // 16 taps: sufficient for 15 kHz cutoff at 192 kHz. Audio content is < 15 kHz
    // after stereo decoding, so wider transition band only lets in noise above Nyquist.
    private val audioLpfOrder = 16
    private val audioLpfCoeffs: FloatArray
    // Double-buffer trick: eliminates modulo in filter inner loop
    private var monoLpfBuf = FloatArray(audioLpfOrder * 2)    // L+R channel
    private var monoLpfIdx = 0
    private var diffLpfBuf = FloatArray(audioLpfOrder * 2)    // L-R channel
    private var diffLpfIdx = 0

    private var stage1Counter = 0
    private var stage2Counter = 0

    // Wideband output for RDS — includes buffer, count, pilot phase (zero-copy)
    var widebandListener: ((FloatArray, Int, Double) -> Unit)? = null

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

    // Real-time signal strength in dB (exposed for UI)
    @Volatile
    var currentSignalStrengthDb = -100f
        private set
    private var signalPowerAcc = 0.0
    private var signalPowerCount = 0
    private val signalPowerWindow = intermediateRate / 3  // ~333ms update rate (smooth for driving)

    // Squelch based on signal quality — tuned for mobile/driving stability
    private var signalQualityAcc = 0.0
    private var signalQualityCount = 0
    private var squelchOpen = true  // Start OPEN so user hears audio immediately
    private var squelchLevel = 1f   // Start at full level — squelch closes if no signal
    private val squelchAttack = 0.015f   // ~65ms to open (smooth fade-in, no clicks)
    private val squelchRelease = 0.005f  // ~200ms to close (slow fade-out for driving)
    private val squelchOpenThreshold = 0.03f   // Modulation level to OPEN squelch
    private val squelchCloseThreshold = 0.008f // Modulation level to CLOSE squelch (hysteresis gap)

    // Warmup: discard first N intermediate samples to flush stale filter state
    private var warmupSamples = 0
    private val warmupThreshold = intermediateRate / 10  // 100ms warmup (was 500ms)

    // Guard flag: set during reset to prevent concurrent demodulate() access
    @Volatile
    private var resetting = false

    // Crossfade for seamless muting during frequency change
    private var muteRamp = 0.5f  // Start at 50% to avoid complete silence on startup
    private val muteRampUp = 0.02f   // ~50 audio samples to reach full volume
    private val muteRampDown = 0.05f  // ~20 audio samples to mute

    // Pre-allocated wideband buffer for RDS (zero-copy to listener)
    // Max input: 32768 bytes = 16384 IQ → 2730 intermediate samples
    private val wbBuf = FloatArray(6000)

    // ========== rtl_fm LUT atan2 (for maximum performance) ==========
    private val atanLutSize = 131072
    private val atanLutCoef = 8
    private val atanLut: IntArray

    init {
        // De-emphasis: 50µs time constant (Europe/Russia standard)
        val tau = 50e-6f
        val dt = 1f / audioSampleRate
        deEmphasisAlpha = dt / (tau + dt)

        // IF filter: 120 kHz cutoff
        ifLpfCoeffs = designLowPassFilter(ifLpfOrder, 120000f / inputSampleRate)
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

        // Build rtl_fm-style atan2 lookup table for fast integer FM demod
        atanLut = IntArray(atanLutSize + 1)
        for (i in 0..atanLutSize) {
            atanLut[i] = (atan(i.toDouble() / (1 shl atanLutCoef)) / PI * (1 shl 14)).toInt()
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
     * @param iqData Raw IQ bytes from USB
     * @param outBuf Pre-allocated output buffer for stereo PCM samples
     * @return Number of samples written to outBuf
     */
    fun demodulate(iqData: ByteArray, outBuf: ShortArray): Int {
        if (resetting) return 0
        val numIqSamples = iqData.size / 2
        var audioCount = 0

        val wbListener = widebandListener
        val widebandBuf = if (wbListener != null) wbBuf else null
        var wbCount = 0

        for (i in 0 until numIqSamples) {
            var iSample = (iqData[i * 2].toInt() and 0xFF) / 127.5f - 1f
            var qSample = (iqData[i * 2 + 1].toInt() and 0xFF) / 127.5f - 1f

            // DC removal (IIR high-pass)
            dcI = dcAlpha * dcI + (1 - dcAlpha) * iSample
            dcQ = dcAlpha * dcQ + (1 - dcAlpha) * qSample
            iSample -= dcI
            qSample -= dcQ

            // Store in IF filter double-buffer (write to both halves)
            ifBufI[ifBufIdx] = iSample
            ifBufI[ifBufIdx + ifLpfOrder] = iSample
            ifBufQ[ifBufIdx] = qSample
            ifBufQ[ifBufIdx + ifLpfOrder] = qSample
            ifBufIdx = (ifBufIdx + 1) % ifLpfOrder

            // Stage 1 decimation: 1152 kHz → 192 kHz
            stage1Counter++
            if (stage1Counter < stage1Decimation) continue
            stage1Counter = 0

            // Apply IF bandpass filter — no modulo in inner loop (double-buffer trick)
            var filtI = 0f
            var filtQ = 0f
            val ifBase = ifBufIdx  // after increment, this points to oldest; newest = ifBase + N - 1
            for (j in 0 until ifLpfOrder) {
                val pos = ifBase + ifLpfOrder - 1 - j
                filtI += ifBufI[pos] * ifLpfCoeffs[j]
                filtQ += ifBufQ[pos] * ifLpfCoeffs[j]
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

            // ===== Signal strength measurement (real-time, for UI) =====
            val iqPower = (filtI * filtI + filtQ * filtQ).toDouble()
            signalPowerAcc += iqPower
            signalPowerCount++
            if (signalPowerCount >= signalPowerWindow) {
                val avgPower = signalPowerAcc / signalPowerCount
                currentSignalStrengthDb = (10 * kotlin.math.log10(avgPower + 1e-10)).toFloat()
                signalPowerAcc = 0.0
                signalPowerCount = 0
            }

            // ===== Signal quality for squelch (wide window + hysteresis for driving) =====
            val absBaseband = abs(rawBaseband)
            signalQualityAcc += absBaseband
            signalQualityCount++
            if (signalQualityCount >= intermediateRate / 4) {  // ~50ms window (was 12ms)
                val avgModulation = signalQualityAcc / signalQualityCount
                // Hysteresis: different thresholds for open vs close prevents chattering
                squelchOpen = if (squelchOpen) {
                    avgModulation > squelchCloseThreshold && avgModulation < 3.0
                } else {
                    avgModulation > squelchOpenThreshold && avgModulation < 3.0
                }
                signalQualityAcc = 0.0
                signalQualityCount = 0
            }
            if (squelchOpen && squelchLevel < 1f) {
                squelchLevel = (squelchLevel + squelchAttack).coerceAtMost(1f)
            } else if (!squelchOpen && squelchLevel > 0f) {
                squelchLevel = (squelchLevel - squelchRelease).coerceAtLeast(0f)
            }

            // Wideband output for RDS decoder
            if (widebandBuf != null && wbCount < wbBuf.size) {
                widebandBuf[wbCount++] = rawBaseband
            }

            // ===== Stereo decoding (SDR++ broadcast_fm.h approach) =====
            // Scale baseband for audio path
            val baseband = rawBaseband * fmGain

            // L+R (mono) = baseband directly (0-15 kHz already)
            val mono = baseband

            // L-R = baseband × 2×cos(2×pilotPhase) — PLL-locked 38 kHz demod
            // The 38 kHz subcarrier is exactly 2× the 19 kHz pilot
            val stereoCarrier = cos(2.0 * pilotNcoPhase).toFloat()
            val diff = baseband * stereoCarrier * 2f  // ×2 for DSB-SC amplitude recovery

            // Feed into separate audio LPF double-buffers
            monoLpfBuf[monoLpfIdx] = mono
            monoLpfBuf[monoLpfIdx + audioLpfOrder] = mono
            diffLpfBuf[diffLpfIdx] = diff
            diffLpfBuf[diffLpfIdx + audioLpfOrder] = diff
            monoLpfIdx = (monoLpfIdx + 1) % audioLpfOrder
            diffLpfIdx = (diffLpfIdx + 1) % audioLpfOrder

            // Stage 2 decimation: 192 kHz → 48 kHz
            stage2Counter++
            if (stage2Counter < stage2Decimation) continue
            stage2Counter = 0

            // Apply audio LPF — no modulo in inner loop (double-buffer trick)
            var filtMono = 0f
            var filtDiff = 0f
            val monoBase = monoLpfIdx
            val diffBase = diffLpfIdx
            for (j in 0 until audioLpfOrder) {
                val mPos = monoBase + audioLpfOrder - 1 - j
                val dPos = diffBase + audioLpfOrder - 1 - j
                filtMono += monoLpfBuf[mPos] * audioLpfCoeffs[j]
                filtDiff += diffLpfBuf[dPos] * audioLpfCoeffs[j]
            }

            // Stereo matrix: L = (L+R + L-R) / 2, R = (L+R - L-R) / 2
            val left: Float
            val right: Float
            if (isStereo) {
                left = (filtMono + filtDiff) * 0.5f
                right = (filtMono - filtDiff) * 0.5f
            } else {
                left = filtMono
                right = filtMono
            }

            // De-emphasis filter (50µs) — separate state for L and R
            deEmphasisStateL += deEmphasisAlpha * (left - deEmphasisStateL)
            deEmphasisStateR += deEmphasisAlpha * (right - deEmphasisStateR)

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

            if (audioCount + 1 < outBuf.size) {
                outBuf[audioCount++] = sampleL.toShort()
                outBuf[audioCount++] = sampleR.toShort()
            }
        }

        // Send wideband data to RDS with current pilot phase (zero-copy: pass count)
        if (wbListener != null && wbCount > 0) {
            wbListener.invoke(widebandBuf!!, wbCount, pilotNcoPhase)
        }

        return audioCount
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
        resetting = true
        prevI = 0f; prevQ = 0f; deEmphasisStateL = 0f; deEmphasisStateR = 0f
        dcI = 0f; dcQ = 0f
        ifBufI = FloatArray(ifLpfOrder * 2); ifBufQ = FloatArray(ifLpfOrder * 2); ifBufIdx = 0
        monoLpfBuf = FloatArray(audioLpfOrder * 2); monoLpfIdx = 0
        diffLpfBuf = FloatArray(audioLpfOrder * 2); diffLpfIdx = 0
        stage1Counter = 0; stage2Counter = 0
        pilotNcoPhase = 0.0
        pilotNcoFreq = 2.0 * PI * 19000.0 / intermediateRate
        for (i in pilotBpfState.indices) pilotBpfState[i] = 0.0
        pilotStrength = 0f; pilotStrengthAcc = 0f; pilotStrengthCount = 0
        isStereo = false
        currentSignalStrengthDb = -100f; signalPowerAcc = 0.0; signalPowerCount = 0
        signalQualityAcc = 0.0; signalQualityCount = 0
        squelchOpen = true; squelchLevel = 1f
        warmupSamples = 0
        muteRamp = 0.5f  // Start at 50%, ramp up quickly
        resetting = false
    }
}
