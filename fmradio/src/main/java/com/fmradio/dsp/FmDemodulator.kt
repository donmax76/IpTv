package com.fmradio.dsp

import kotlin.math.*

/**
 * High-quality FM demodulation pipeline based on SDR++/rtl_fm/librtlsdr.
 *
 * IQ (960 kHz) → DC removal → IF LPF → Decimate /5 → FM discriminator (192 kHz)
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
        // 960 kHz: the BYD DiLink USB host can't sustain 1.152 MHz (2.304 MB/s)
        // and lost ~1.8% of samples — clicks in audio, broken RDS sync.
        // 960 kHz = 1.92 MB/s with headroom; intermediate stays 192 kHz.
        const val RECOMMENDED_SAMPLE_RATE = 960000

        // Channel filter for measureChannelRatioDb: +/-80 kHz at 960 kHz.
        private const val CHAN_TAPS = 33
        private val chanCoeffs: FloatArray = FloatArray(CHAN_TAPS).also { c ->
            val fc = 80_000.0 / RECOMMENDED_SAMPLE_RATE
            val mid = CHAN_TAPS / 2
            var sum = 0.0
            for (n in 0 until CHAN_TAPS) {
                val k = n - mid
                val sinc = if (k == 0) 2.0 * fc
                           else kotlin.math.sin(2.0 * Math.PI * fc * k) / (Math.PI * k)
                val w = 0.54 - 0.46 * kotlin.math.cos(2.0 * Math.PI * n / (CHAN_TAPS - 1))
                val v = sinc * w
                c[n] = v.toFloat()
                sum += v
            }
            if (sum != 0.0) for (n in 0 until CHAN_TAPS) c[n] = (c[n] / sum).toFloat()
        }
    }

    /**
     * How much of the tuner's window is the station at its centre, in dB.
     *
     * measureSignalStrength below totals the power of the WHOLE 960 kHz window,
     * and that is what the scan and the seek were both deciding on. It cannot
     * tell one frequency from another: the window spans nine channels of the
     * 100 kHz grid, so nearly the same stations are inside it wherever you tune
     * and the total barely moves. It is also proportional to the tuner gain, so
     * a fixed threshold on it means something different at every gain setting.
     *
     * This measures the power inside +/-80 kHz of the tuned frequency — where a
     * zero-IF tuner puts the wanted station — as a share of the whole window.
     * Being a ratio it does not depend on the gain at all:
     *
     *   empty channel   ~ -7.8 dB   (the band's share of the window, noise only)
     *   station present ~ -2 dB     (the carrier fills its own channel)
     */
    /**
     * [0] = power inside +/-80 kHz of the tuned frequency, in dB.
     * [1] = that power as a share of the whole window, in dB.
     *
     * Both come from one pass because a sweep runs this at every step.
     */
    fun measureChannel(iqData: ByteArray, decimation: Int = 8): FloatArray {
        val numSamples = iqData.size / 2
        if (numSamples < CHAN_TAPS * 4) return floatArrayOf(-100f, -100f)

        var totalPower = 0.0
        var bandPower = 0.0
        var bandCount = 0
        val histI = FloatArray(CHAN_TAPS)
        val histQ = FloatArray(CHAN_TAPS)
        var hist = 0
        var phase = 0

        for (n in 0 until numSamples) {
            val i = (iqData[n * 2].toInt() and 0xFF) / 127.5f - 1f
            val q = (iqData[n * 2 + 1].toInt() and 0xFF) / 127.5f - 1f
            totalPower += (i * i + q * q).toDouble()

            histI[hist] = i
            histQ[hist] = q
            hist = if (hist + 1 >= CHAN_TAPS) 0 else hist + 1

            if (++phase >= decimation) {
                phase = 0
                if (n >= CHAN_TAPS) {
                    var accI = 0f
                    var accQ = 0f
                    var h = hist
                    for (t in 0 until CHAN_TAPS) {
                        accI += histI[h] * chanCoeffs[t]
                        accQ += histQ[h] * chanCoeffs[t]
                        h = if (h + 1 >= CHAN_TAPS) 0 else h + 1
                    }
                    bandPower += (accI * accI + accQ * accQ).toDouble()
                    bandCount++
                }
            }
        }
        if (bandCount == 0 || totalPower <= 0.0) return floatArrayOf(-100f, -100f)
        val band = bandPower / bandCount
        val total = totalPower / numSamples
        return floatArrayOf(
            (10.0 * log10(band + 1e-12)).toFloat(),
            (10.0 * log10(band / total + 1e-12)).toFloat())
    }

    /** Just the share — kept for the seek, which only needs that test. */
    fun measureChannelRatioDb(iqData: ByteArray, decimation: Int = 8): Float =
        measureChannel(iqData, decimation)[1]

    private val intermediateRate: Int = 192000
    private val stage1Decimation = inputSampleRate / intermediateRate  // 5 at 960 kHz
    private val stage2Decimation: Int = intermediateRate / audioSampleRate  // 4

    // DC removal (IIR high-pass)
    // Use faster alpha for quicker convergence on frequency change
    private var dcI = 0f
    private var dcQ = 0f
    private val dcAlpha = 0.999995f  // ~0.9 Hz cutoff — matches actual C++ DSP value

    // FM discriminator state
    private var prevI = 0f
    private var prevQ = 0f

    // FM deviation gain — converts atan2 output to proper audio level.
    // 100% modulation (±75 kHz) maps to ~±0.82 — close to the soft-clip knee at 0.8.
    // The soft-clip limiter handles peaks above this safely, so we don't waste 12 dB
    // Matches C++ DSP value. Below soft-clip knee (0.80) for clean audio.
    private val fmGain = (intermediateRate.toFloat() / (2f * PI.toFloat() * 75000f)) * 0.75f

    // De-emphasis filter (50µs time constant for Europe/Russia)
    private var deEmphasisStateL = 0f
    private var deEmphasisStateR = 0f
    private val deEmphasisAlpha: Float

    // IF low-pass filter (before stage 1 decimation).
    // 64 taps with Blackman-Harris: ~95 dB stopband, ~45 kHz transition band at
    // 1.152 MHz fs. Cutoff 120 kHz so the full ±100 kHz FM-broadcast spectrum
    // (Carson) passes flat — no peak clipping → no demodulation distortion.
    // Modest CPU bump over the historical 48 taps; safe on mid-range phones.
    private val ifLpfOrder = 64
    private val ifLpfCoeffs: FloatArray
    // Double-buffer trick: size 2×N, write to both halves, filter without modulo
    private var ifBufI = FloatArray(ifLpfOrder * 2)
    private var ifBufQ = FloatArray(ifLpfOrder * 2)
    private var ifBufIdx = 0

    // Audio low-pass filters — separate for L+R (mono) and L-R (stereo difference).
    // 32 taps: 15 kHz cutoff at 192 kHz with ~90 dB stopband — clean anti-alias
    // before /4 decimation to 48 kHz. Lower CPU than longer filters; the 19 kHz
    // pilot residue is well below the noise floor of broadcast FM at this length.
    private val audioLpfOrder = 32
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
    private val pilotLoopBw = 2.0 * PI * 1.0 / intermediateRate  // 1 Hz bandwidth — stable PLL tracking
    private val pilotAlpha: Double
    private val pilotBeta: Double

    private var pilotStrength = 0f
    private var pilotStrengthAcc = 0f
    private var pilotStrengthCount = 0
    private val pilotDetectWindow = intermediateRate / 4

    // Pilot PLL frequency bounds (prevent runaway on noise)
    private val pilotFreqMin = 2.0 * PI * 18500.0 / intermediateRate
    private val pilotFreqMax = 2.0 * PI * 19500.0 / intermediateRate

    // Stereo detection with hysteresis + smooth blend.
    // Thresholds are tuned for the pilot BPF (Q=80 @ 19 kHz) output on top
    // of the raw atan2 baseband. Pure noise gives ~0.008-0.010 squared-mean,
    // a real broadcast pilot (10% AM modulation) gives ~0.025-0.035. Use
    // 0.020 lock / 0.012 unlock to keep the gap clean and avoid false stereo
    // on dead frequencies.
    private val stereoLockThreshold = 0.016f     // match C++ — FC0013 weak pilot
    private val stereoUnlockThreshold = 0.006f   // match C++ — wide hysteresis
    private var stereoBlend = 0f                // 0 = mono, 1 = full stereo
    private val stereoBlendAttack = 0.0003f     // match C++ — 70ms to stereo
    private val stereoBlendRelease = 0.0001f    // match C++ — 210ms to mono

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
    // Squelch ramp rates (per intermediate sample at 192 kHz)
    private val squelchAttack = 1f / (0.1f * intermediateRate)   // 100ms to open (smooth fade-in)
    private val squelchRelease = 1f / (0.3f * intermediateRate)  // 300ms to close (gradual for driving)
    private val squelchOpenThreshold = 0.03f   // Modulation level to OPEN squelch
    private val squelchCloseThreshold = 0.008f // Modulation level to CLOSE squelch (hysteresis gap)

    // Warmup: discard first N intermediate samples to flush stale filter state
    private var warmupSamples = 0
    private val warmupThreshold = intermediateRate / 10  // 100ms warmup (was 500ms)

    // Synchronization lock for reset vs demodulate thread safety
    private val dspLock = java.util.concurrent.locks.ReentrantLock()

    // Crossfade for seamless muting during frequency change
    private var muteRamp = 0f  // Start muted to avoid noise burst on startup
    private val muteRampUp = 1f / (0.05f * audioSampleRate)    // 50ms fade-in
    private val muteRampDown = 1f / (0.02f * audioSampleRate)  // 20ms fade-out

    // Pre-allocated wideband buffer for RDS (zero-copy to listener)
    // Max input: 32768 bytes = 16384 IQ → 2730 intermediate samples
    private val wbBuf = FloatArray(6000)
    // Pilot NCO phase at wbBuf[0] — captured on the first wideband sample
    // of each call so the RDS decoder can reconstruct the 57 kHz carrier
    // starting at the correct phase for that buffer.
    private var wbStartPilotPhase = 0.0

    init {
        // De-emphasis: 50µs time constant (Europe/Russia standard)
        val tau = 50e-6f
        val dt = 1f / audioSampleRate
        deEmphasisAlpha = dt / (tau + dt)

        // IF filter: 120 kHz cutoff. Wide enough to pass full FM broadcast spectrum
        // (Carson ≈ ±90 kHz incl. RDS at ±57 kHz) flat — no edge clipping.
        ifLpfCoeffs = designLowPassFilter(ifLpfOrder, 90000f / inputSampleRate)
        // Audio filter: 15 kHz cutoff — standard FM mono audio
        audioLpfCoeffs = designLowPassFilter(audioLpfOrder, 15000f / intermediateRate)

        // Design 19 kHz pilot bandpass biquad (Q=80 for narrow extraction)
        val w0 = 2.0 * PI * 19000.0 / intermediateRate
        val bpfQ = 80.0  // Q=80 — fast pilot lock without ringing, stable on transients
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
    /**
     * Cubic soft-clip limiter. Linear up to |x|≈0.8, smoothly saturates toward ±1.
     * Prevents audible hard-clip artifacts on loud modulation peaks while preserving
     * transient detail within the linear region.
     */
    private fun softClip(x: Float): Float {
        val ax = abs(x)
        if (ax <= 0.8f) return x                    // transparent linear region
        val sign = if (x >= 0f) 1f else -1f
        // Smooth asymptotic knee above 0.8: y = 0.8 + 0.2 * (1 - 1/(1 + 5*(|x|-0.8)))
        val t = (ax - 0.8f) * 5f                    // 0 at threshold, grows unbounded
        val compressed = t / (1f + t)               // 0..1 smooth saturation
        return sign * (0.8f + 0.2f * compressed).coerceAtMost(0.9999695f)
    }

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
        if (!dspLock.tryLock()) return 0
        try { return demodulateInternal(iqData, outBuf) } finally { dspLock.unlock() }
    }

    private fun demodulateInternal(iqData: ByteArray, outBuf: ShortArray): Int {
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

            // Stage 1 decimation: input rate → 192 kHz
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
                pilotNcoFreq = (pilotNcoFreq + pilotBeta * pilotError).coerceIn(pilotFreqMin, pilotFreqMax)
                pilotNcoPhase += pilotNcoFreq + pilotAlpha * pilotError
                if (pilotNcoPhase > 2 * PI) pilotNcoPhase -= 2 * PI
                if (pilotNcoPhase < 0) pilotNcoPhase += 2 * PI
                continue
            }

            // ===== Pilot PLL: lock to 19 kHz pilot tone =====
            // Pre-update NCO phase corresponds to THIS sample (the detector
            // compares the current sample against it); the post-update phase is
            // one NCO step (~71° at 38 kHz) ahead — using it rotated the
            // recovered stereo constellation almost onto the orthogonal axis,
            // which swapped left/right channels (verified by simulation).
            val pilotPhaseThisSample = pilotNcoPhase
            val pilotSig = pilotBpf(rawBaseband.toDouble())
            val pilotError = pilotSig * cos(pilotNcoPhase)
            pilotNcoFreq = (pilotNcoFreq + pilotBeta * pilotError).coerceIn(pilotFreqMin, pilotFreqMax)
            pilotNcoPhase += pilotNcoFreq + pilotAlpha * pilotError
            if (pilotNcoPhase > 2 * PI) pilotNcoPhase -= 2 * PI
            if (pilotNcoPhase < 0) pilotNcoPhase += 2 * PI

            // Pilot strength measurement with hysteresis
            pilotStrengthAcc += (pilotSig * pilotSig).toFloat()
            pilotStrengthCount++
            if (pilotStrengthCount >= pilotDetectWindow) {
                pilotStrength = pilotStrengthAcc / pilotStrengthCount
                isStereo = if (isStereo) {
                    pilotStrength > stereoUnlockThreshold  // Stay stereo until weak
                } else {
                    pilotStrength > stereoLockThreshold     // Need strong pilot to engage
                }
                pilotStrengthAcc = 0f
                pilotStrengthCount = 0
            }

            // Smooth stereo blend (prevents pops on stereo/mono transitions)
            if (isStereo && stereoBlend < 1f) {
                stereoBlend = (stereoBlend + stereoBlendAttack).coerceAtMost(1f)
            } else if (!isStereo && stereoBlend > 0f) {
                stereoBlend = (stereoBlend - stereoBlendRelease).coerceAtLeast(0f)
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
            if (signalQualityCount >= intermediateRate / 2) {  // ~500ms window for driving stability
                val avgModulation = signalQualityAcc / signalQualityCount
                // Hysteresis: different thresholds for open vs close prevents chattering
                squelchOpen = if (squelchOpen) {
                    avgModulation > squelchCloseThreshold && avgModulation < 1.2
                } else {
                    avgModulation > squelchOpenThreshold && avgModulation < 1.2
                }
                signalQualityAcc = 0.0
                signalQualityCount = 0
            }
            if (squelchOpen && squelchLevel < 1f) {
                squelchLevel = (squelchLevel + squelchAttack).coerceAtMost(1f)
            } else if (!squelchOpen && squelchLevel > 0f) {
                squelchLevel = (squelchLevel - squelchRelease).coerceAtLeast(0f)
            }

            // Wideband output for RDS decoder — capture pilot phase at sample 0
            // so the RDS carrier reconstruction aligns with the buffer start.
            if (widebandBuf != null) {
                if (wbCount == 0) wbStartPilotPhase = pilotPhaseThisSample
                widebandBuf[wbCount++] = rawBaseband
                // Flush when full and keep collecting — the old behavior
                // silently DROPPED everything past 6000 samples (~31 ms),
                // so any caller feeding larger IQ chunks lost most of the
                // RDS bitstream and block sync could never hold. The RDS
                // carrier NCO is phase-continuous, so mid-call flushes are
                // seamless.
                if (wbCount == wbBuf.size) {
                    wbListener?.invoke(widebandBuf, wbCount, wbStartPilotPhase)
                    wbCount = 0
                }
            }

            // ===== Stereo decoding (SDR++ broadcast_fm.h approach) =====
            // Scale baseband for audio path
            val baseband = rawBaseband * fmGain

            // L+R (mono) = baseband directly (0-15 kHz already)
            val mono = baseband

            // L-R = baseband × 2×sin(2×pilotPhase) — PLL-locked 38 kHz demod.
            // The detector locks sin(φ) in phase with the pilot; per the FM
            // stereo standard the 38 kHz subcarrier is sin(2φ), and the phase
            // must be the pre-update one (see pilotPhaseThisSample above).
            val stereoCarrier = sin(2.0 * pilotPhaseThisSample).toFloat()
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

            // Stereo matrix with smooth blend (prevents pops on stereo/mono switch)
            // blend=0: mono (L=R=filtMono), blend=1: full stereo
            val diffGain = stereoBlend * 0.7f  // match C++ native DSP
            val left = filtMono + filtDiff * diffGain
            val right = filtMono - filtDiff * diffGain

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

            // Scale to 16-bit PCM with soft-clip limiter (eliminates harsh hard-clip artifacts
            // on deep-modulated peaks). Uses a cubic soft-knee that is transparent below
            // 0.8 full-scale and rolls off smoothly toward ±1.0.
            val gain = muteRamp * 28000f  // match C++ native DSP
            val sampleL = (softClip(outL * gain / 32767f) * 32767f).toInt()
            val sampleR = (softClip(outR * gain / 32767f) * 32767f).toInt()

            if (audioCount + 1 < outBuf.size) {
                outBuf[audioCount++] = sampleL.toShort()
                outBuf[audioCount++] = sampleR.toShort()
            }
        }

        // Send wideband data to RDS with the phase captured at wbBuf[0]
        // (not the current end-of-buffer phase, which introduced a constant
        // per-buffer rotation and broke DBPSK decoding across boundaries).
        if (wbListener != null && wbCount > 0) {
            wbListener.invoke(widebandBuf!!, wbCount, wbStartPilotPhase)
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
        dspLock.lock()
        try {
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
            stereoBlend = 0f
            isStereo = false
            currentSignalStrengthDb = -100f; signalPowerAcc = 0.0; signalPowerCount = 0
            signalQualityAcc = 0.0; signalQualityCount = 0
            squelchOpen = true; squelchLevel = 1f
            warmupSamples = 0
            muteRamp = 0f  // Start muted, ramp up after warmup
        } finally {
            dspLock.unlock()
        }
    }
}
