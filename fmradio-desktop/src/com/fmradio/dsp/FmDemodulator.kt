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

        // Squelch: in-band power, with hysteresis. Field measurements put real
        // stations at -8 to -12 dB and an empty frequency at -27 dB, so -22/-20
        // sits in the middle of a 15 dB gap. Power is used rather than any
        // spectral measure because a station controls what it transmits but not
        // how much of it reaches the receiver — every spectral input tried so
        // far could be fooled by a station's own content.
        private const val SQUELCH_CLOSE_DB = -22f
        private const val SQUELCH_OPEN_DB = -20f

        // Progressive stereo blend and treble roll-off, same idea as a car
        // radio: give up separation and bandwidth gradually as noise rises
        // rather than switching audibly between stereo and mono.
        // Calibrated for the 84 kHz band: a clean signal reads 0.0037,
        // C/N 14 dB reads 0.0092, C/N 5 dB reads 0.0242, empty air 0.18.
        private const val NOISE_STEREO_FULL = 0.008f
        private const val NOISE_STEREO_NONE = 0.030f
        private const val NOISE_HICUT_START = 0.014f
        private const val NOISE_HICUT_FULL = 0.038f
        const val HICUT_MAX_HZ = 15000f
        private const val HICUT_MIN_HZ = 3200f
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

    /**
     * Current pilot PLL frequency in rad/sample at [intermediateRate]. The RDS
     * decoder locks its 57 kHz carrier to 3× this value; taking the frequency
     * (not the phase) lets its NCO stay phase continuous across buffers, which
     * differential BPSK detection requires.
     */
    val pilotFreqRadPerSample: Double get() = pilotNcoFreq
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

    /**
     * Mean |dphi| per intermediate sample. This is a MODULATION level, not a
     * noise level — see the squelch below for why that distinction matters.
     * Kept for the log.
     */
    @Volatile
    var modulationLevel = 0f
        private set

    /** True while the squelch is letting audio through. */
    @Volatile
    var squelchIsOpen = false
        private set

    // ===== Reception quality, measured from ultrasonic noise =====
    //
    // Measured at 84 kHz, above everything a station transmits.
    //
    // 65 kHz was the obvious choice and it was wrong. Stations carry SCA/DARC
    // subcarriers around 67 kHz — a normal, widely used service — which lands
    // straight in that band. A field log showed the consequence plainly: a
    // station at -8.8 dB read 0.22 while a STRONGER one at -7.9 dB read 0.016,
    // so the reading was following what each station transmits rather than how
    // well it was being received. Reproduced on the bench with a synthesized
    // 67 kHz subcarrier at normal injection:
    //
    //   no subcarrier        0.0075   full stereo, 15 kHz
    //   SCA at  5%           0.0240   stereo 0.57, 12.9 kHz
    //   SCA at 10%           0.0460   MONO,        5.3 kHz
    //   SCA at 15%           0.0685   MONO,        3.2 kHz
    //   (pure noise          0.0546 — LOWER than a clean station with SCA)
    //
    // Above the subcarriers, 76 kHz is unusable too: it is the second harmonic
    // of the 38 kHz stereo subcarrier, an artefact whose level follows
    // programme loudness. 84 kHz clears both and stays inside the 90 kHz IF.
    private val nzBpB0: Double
    private val nzBpB2: Double
    private val nzBpA1: Double
    private val nzBpA2: Double
    private var nzX1 = 0.0; private var nzX2 = 0.0
    private var nzY1 = 0.0; private var nzY2 = 0.0
    private var nzAcc = 0.0
    private var nzCount = 0
    private val nzWindow = intermediateRate / 100      // 10 ms

    /** Smoothed ultrasonic noise level — lower is a better signal. */
    @Volatile
    var noiseLevel = 0f
        private set

    // In-band power, on the same scale measureSignalStrength() reports. This
    // is what the squelch runs on now: in the field it separates cleanly and
    // unambiguously — real stations sit at -8 to -12 dB and an empty frequency
    // at -27 — whereas any spectral measure can be fooled by what a station
    // chooses to transmit.
    private var sigPowerAcc = 0.0
    private var sigPowerCount = 0

    /** In-band signal power in dB (same scale as measureSignalStrength). */
    @Volatile
    var signalDb = -100f
        private set

    /** Stereo separation currently applied: 0 = mono, 1 = full. */
    @Volatile
    var stereoBlend = 0f
        private set

    /** Current audio high-cut corner, Hz. */
    @Volatile
    var hiCutHz = HICUT_MAX_HZ
        private set

    private var hiCutAlpha = 1f
    private var hiCutStateL = 0f
    private var hiCutStateR = 0f
    private var snrBlendTarget = 1f
    private var squelchLevel = 0f
    private val squelchAttack = 0.03f   // ~33ms to open (smooth fade-in)
    private val squelchRelease = 0.02f  // ~50ms to close (fast mute on noise)

    // Warmup: discard first N intermediate samples to flush stale filter state
    private var warmupSamples = 0
    private val warmupThreshold = intermediateRate / 10  // 100 ms — see the warmup block

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

        // Noise-measuring bandpass: 65 kHz centre, ~3 kHz wide. Narrow on
        // purpose — wider skirts reach the 76 kHz demodulator product, whose
        // level follows programme loudness and would make the measure useless.
        val nzW = 2.0 * PI * 84000.0 / intermediateRate
        val nzA = sin(nzW) / (2.0 * (84.0 / 6.0))
        val nzA0 = 1.0 + nzA
        nzBpB0 = nzA / nzA0
        nzBpB2 = -nzA / nzA0
        nzBpA1 = (-2.0 * cos(nzW)) / nzA0
        nzBpA2 = (1.0 - nzA) / nzA0

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

            // Warmup. The filters do need a moment to settle after a reset,
            // but the audio output must NOT stop while they do.
            //
            // This used to skip the rest of the loop, emitting no samples at
            // all for warmupThreshold samples — which was half a second. Every
            // frequency change calls requestReset(), so every retune deleted
            // 0.5 s x 48 kHz = 24000 audio frames, while the player's entire
            // cushion after prefill is 19200 frames. The buffer was wiped out
            // and then some, on every single retune: a field log showed the
            // ring buffer pinned at zero for the whole session and 152 dropouts
            // in two minutes, all of them clustered on frequency changes.
            //
            // Samples are now always produced; during warmup they are silent,
            // and muteRamp fades the audio back in afterwards. The threshold is
            // also 100 ms rather than 500, matching the Android build.
            val warming = warmupSamples < warmupThreshold
            if (warming) warmupSamples++

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

            // ===== Reception quality, and the squelch =====
            //
            // The squelch used to compare mean |dphi| against an upper bound,
            // muting when it looked like noise. That cannot work: mean |dphi|
            // IS the modulation level, so a loud station and static are
            // indistinguishable to it. Measured on the bench, a mono station
            // at nominal deviation reads 0.90, at 140% it reads 1.25, and pure
            // noise reads 1.44 — so the 1.2 bound silenced any station that
            // modulated hard, and made the audio cut in and out for any station
            // sitting near it. Both were reported from the field.
            //
            // The ultrasonic noise measure below has no such ambiguity: it
            // looks where the station transmits nothing at all.
            run {
                val x = rawBaseband.toDouble()
                val y = nzBpB0 * x + nzBpB2 * nzX2 - nzBpA1 * nzY1 - nzBpA2 * nzY2
                nzX2 = nzX1; nzX1 = x
                nzY2 = nzY1; nzY1 = y
                nzAcc += y * y
                if (++nzCount >= nzWindow) {
                    val rms = sqrt(nzAcc / nzCount).toFloat()
                    nzAcc = 0.0; nzCount = 0
                    noiseLevel += 0.15f * (rms - noiseLevel)


                    // Separation this signal can support
                    val t = ((noiseLevel - NOISE_STEREO_FULL) /
                             (NOISE_STEREO_NONE - NOISE_STEREO_FULL)).coerceIn(0f, 1f)
                    snrBlendTarget = 1f - t

                    // Audio bandwidth this signal can support
                    val h = ((noiseLevel - NOISE_HICUT_START) /
                             (NOISE_HICUT_FULL - NOISE_HICUT_START)).coerceIn(0f, 1f)
                    hiCutHz = HICUT_MAX_HZ + h * (HICUT_MIN_HZ - HICUT_MAX_HZ)
                    hiCutAlpha = (1.0 - exp(-2.0 * PI * hiCutHz / audioSampleRate))
                        .toFloat().coerceIn(0f, 1f)
                }
            }

            // In-band power, and the squelch. Muting is reserved for a
            // frequency with nothing on it: the cost of wrongly muting a real
            // station is far higher than the cost of a second of static, and
            // every previous squelch input got that trade wrong in one
            // direction or the other.
            sigPowerAcc += (filtI * filtI + filtQ * filtQ).toDouble()
            sigPowerCount++
            if (sigPowerCount >= intermediateRate / 16) {
                signalDb = (10.0 * log10(sigPowerAcc / sigPowerCount + 1e-10)).toFloat()
                sigPowerAcc = 0.0
                sigPowerCount = 0
                squelchOpen = if (squelchOpen) signalDb > SQUELCH_CLOSE_DB
                              else signalDb > SQUELCH_OPEN_DB
                squelchIsOpen = squelchOpen
            }

            // Modulation level is still measured, but only for the log now.
            signalQualityAcc += abs(rawBaseband)
            signalQualityCount++
            if (signalQualityCount >= intermediateRate / 16) {
                modulationLevel = (signalQualityAcc / signalQualityCount).toFloat()
                signalQualityAcc = 0.0
                signalQualityCount = 0
            }

            // Smooth the blend toward its target: attack faster than release so
            // a recovering signal regains stereo promptly but a fade does not
            // pump the image back and forth.
            val bt = if (isStereo) snrBlendTarget else 0f
            if (stereoBlend < bt) stereoBlend = (stereoBlend + 0.0003f).coerceAtMost(bt)
            else if (stereoBlend > bt) stereoBlend = (stereoBlend - 0.0001f).coerceAtLeast(bt)
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
            // Separation is scaled by the blend rather than switched on and
            // off: a pilot arriving through a noisy path used to buy full
            // stereo and all of the L-R path's extra noise along with it.
            val g = stereoBlend
            val left = filtMono + filtDiff * g
            val right = filtMono - filtDiff * g

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
            // Progressive high-cut. At a clean signal hiCutAlpha is ~1 and
            // this is a no-op; as noise rises the corner walks down to 3.2 kHz,
            // trading the top of the band — where FM noise is worst — for quiet.
            hiCutStateL += hiCutAlpha * (deEmphasisStateL - hiCutStateL)
            hiCutStateR += hiCutAlpha * (deEmphasisStateR - hiCutStateR)

            val outL = hiCutStateL * squelchLevel
            val outR = hiCutStateR * squelchLevel

            // Mute ramp for seamless frequency change (avoids initial burst).
            // Held at zero while the filters are still settling, so the samples
            // emitted during warmup are silent rather than a burst of rubbish.
            if (warming) {
                muteRamp = 0f
            } else if (muteRamp < 1f) {
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
        nzX1 = 0.0; nzX2 = 0.0; nzY1 = 0.0; nzY2 = 0.0
        nzAcc = 0.0; nzCount = 0
        sigPowerAcc = 0.0; sigPowerCount = 0; signalDb = -100f
        noiseLevel = 0f; stereoBlend = 0f; snrBlendTarget = 1f
        hiCutHz = HICUT_MAX_HZ; hiCutAlpha = 1f
        hiCutStateL = 0f; hiCutStateR = 0f
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
