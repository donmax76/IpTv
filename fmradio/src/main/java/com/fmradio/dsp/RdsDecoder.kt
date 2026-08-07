package com.fmradio.dsp

import android.util.Log
import kotlin.math.*

/**
 * RDS (Radio Data System) decoder — pilot-locked carrier approach.
 *
 * Uses 3× pilot phase from the FM demodulator's PLL for the 57 kHz
 * RDS carrier, ensuring frequency lock even with RTL-SDR oscillator offset.
 * This is the same approach used in SDR#, gr-rds, and other professional decoders.
 *
 * Extracts: PS (station name), RT (radio text), PTY (program type),
 * AF (alternate frequencies), TA/TP (traffic announcements).
 */
class RdsDecoder(private val sampleRate: Int = FmDemodulator.INTERMEDIATE_RATE) {

    companion object {
        private const val TAG = "RdsDecoder"

        // Diagnostic logging interval
        private const val STATS_LOG_INTERVAL_MS = 5000L  // log every 5 seconds

        // RDS bit rate
        private const val RDS_BITRATE = 1187.5

        // Groups to wait before giving up on assembling a complete RadioText
        // message and showing what has arrived so far.
        //
        // This was 350, about 30 seconds. That is fine for a station whose
        // RadioText is its own name and never changes, and useless for one
        // that puts the current track there: 106.3 rewrites its text every
        // few seconds, and each rewrite toggles the A/B flag, which clears the
        // buffer and restarts this counter. The new message then had to become
        // COMPLETE before it could be shown — every one of its positions
        // filled — because a previous message had once completed. At the block
        // error rate this tuner sees, that rarely happens inside the few
        // seconds the message exists, so the screen kept showing the previous
        // text and the new one never appeared. "It cannot keep up" is exactly
        // right.
        //
        // 70 groups is about six seconds at the ~9-11 groups/s actually
        // decoded in the field: long enough to prefer a complete message when
        // one is coming, short enough that a changing station still gets seen.
        private const val RT_PARTIAL_AFTER = 70

        // ~100 blocks of memory, about two seconds of reception.
        private const val BER_EMA_ALPHA = 0.01f

        // RDS sync word (offset words for blocks A, B, C, D)
        private const val OFFSET_A = 0x0FC
        private const val OFFSET_B = 0x198
        private const val OFFSET_C = 0x168
        private const val OFFSET_CP = 0x350
        private const val OFFSET_D = 0x1B4

        // RDS CRC generator polynomial: x^10 + x^8 + x^7 + x^5 + x^4 + x^3 + 1
        private const val CRC_POLY = 0x1B9

        // Programme Type names
        val PTY_NAMES = arrayOf(
            "None", "News", "Current Affairs", "Information",
            "Sport", "Education", "Drama", "Culture",
            "Science", "Varied", "Pop Music", "Rock Music",
            "Easy Listening", "Light Classical", "Serious Classical", "Other Music",
            "Weather", "Finance", "Children", "Social",
            "Religion", "Phone-In", "Travel", "Leisure",
            "Jazz", "Country", "National Music", "Oldies",
            "Folk", "Documentary", "Alarm Test", "Alarm"
        )
    }

    // RDS data output
    data class RdsData(
        val ps: String = "",        // Programme Service name (8 chars)
        val rt: String = "",        // RadioText (up to 64 chars)
        val pty: Int = 0,           // Programme Type code
        val ptyName: String = "",   // Programme Type name
        val pi: Int = 0,            // Programme Identification
        val tp: Boolean = false,    // Traffic Programme flag
        val ta: Boolean = false,    // Traffic Announcement flag
        val ms: Boolean = false,    // Music/Speech flag (true = music)
        val afList: List<Float> = emptyList(), // Alternative Frequencies (MHz)
        val hasData: Boolean = false,
        /**
         * True when [ps] / [rt] is the WHOLE message, not what has arrived so
         * far. Showing a half-assembled message on screen is right — it is
         * live and it grows. Writing one to storage is not: it is kept for
         * ever and shown again next time as though it were the station's real
         * text, which is what "it shows it but cut short" means in the field.
         */
        val psComplete: Boolean = false,
        val rtComplete: Boolean = false
    )

    interface RdsListener {
        fun onRdsData(data: RdsData)
    }

    var listener: RdsListener? = null

    // RDS bandpass filter (after carrier mix-down)
    // Reduced to 48 taps (was 96): RDS is narrowband (±2kHz), 48 taps is sufficient
    // with Blackman-Harris window. Saves ~50% CPU in RDS processing.
    private val rdsLpfOrder = 64  // longer filter = better noise rejection for FC0013's weak RDS
    private val rdsLpfCoeffs: FloatArray
    // Double-buffer trick: size 2×N, eliminates modulo in filter inner loop
    private var rdsLpfBufI = FloatArray(rdsLpfOrder * 2)
    private var rdsLpfBufQ = FloatArray(rdsLpfOrder * 2)
    private var rdsLpfIdx = 0

    // Down to 24 kHz — more samples per bit for better clock recovery.
    // Derived, not written as 8: the intermediate rate moved from 192 kHz to
    // 240 kHz and a fixed 8 would have left the decoder decoding at 30 kHz
    // while every bit-timing constant here still assumed 24 kHz.
    private val rdsDecimation = (sampleRate / 24000).coerceAtLeast(1)
    private val rdsRate = sampleRate / rdsDecimation  // 24000
    private var rdsDecimCounter = 0

    /**
     * The real channel filter, and it runs AFTER decimation on purpose.
     *
     * The filter above has to be gentle: it works at 240 kHz, where 64 taps buy
     * a transition band of about 4*240000/64 = 15 kHz. Measured, it is 1.2 dB
     * down at 2.4 kHz — and only 3.3 dB down at 4 kHz. Four kilohertz is
     * exactly where the trouble is. Mixing by 57 kHz puts the stereo
     * difference signal's upper sideband, which reaches 53 kHz, right there,
     * and on a stereo station that sideband is far stronger than the RDS
     * injection. All that was ever asked of the 240 kHz filter was to stop
     * aliasing at the decimation, which it does: 101 dB down at 24 kHz.
     *
     * Ninety-six taps at 24 kHz give a transition of 1 kHz for a tenth of the
     * arithmetic. Same measurement: 0.65 dB down at 2.4 kHz, where RDS lives,
     * and 139 dB down at 4 kHz, where the stereo sideband does. On a synthetic
     * station at full stereo modulation this is worth about 4 dB of block error
     * rate — not the whole story on a station that will not decode, but it is
     * free, and nothing here should be paying 3 dB to a neighbour it can
     * simply refuse to listen to.
     */
    private val rdsSharpOrder = 96
    private val rdsSharpCoeffs: FloatArray
    private var rdsSharpBufI = FloatArray(rdsSharpOrder * 2)
    private var rdsSharpBufQ = FloatArray(rdsSharpOrder * 2)
    private var rdsSharpIdx = 0

    // Matched filter for RDS symbol shaping (root raised cosine-like, improves SNR)
    // 32 taps (was 20): longer filter = better noise rejection at the cost of
    // latency (runs on RDS thread, not DSP — CPU is free).
    private val matchedFilterOrder = 32
    private val matchedFilter: FloatArray
    // Double-buffer trick — separate I and Q matched filter buffers for complex DBPSK
    private var matchedBufI = FloatArray(matchedFilterOrder * 2)
    private var matchedBufQ = FloatArray(matchedFilterOrder * 2)
    private var matchedBufIdx = 0

    // Bit clock recovery (PLL-based)
    private val samplesPerBit = rdsRate.toFloat() / RDS_BITRATE.toFloat()  // ~20.2
    private var clockPhase = 0f
    private var prevRdsSample = 0f

    // Complex differential decoding — stores previous symbol's I/Q for phase-agnostic detection
    private var prevSymI = 0f
    private var prevSymQ = 0f
    private var prevSymValid = false  // false until first symbol captured

    // Bit stream buffer for group assembly
    private var bitBuffer = 0L
    private var bitCount = 0

    // Syndrome-based block sync with confirmation
    private var synced = false
    private var syncConfirmed = false
    private var syncConfirmGood = 0
    private var syncConfirmBad = 0
    private var blockIndex = 0
    private var goodBlocks = 0
    private var badBlocks = 0

    // Group data (4 blocks × 16 bits) + validity flags
    private val groupData = IntArray(4)
    // Raw, uncorrected 16 data bits of every block in the current group, stored
    // whether or not the CRC passed. PS is allowed to use a CRC-failed block D
    // (otherwise it barely ever populates at FC0013's error rate), but it must
    // read those bits from HERE, not from groupData. groupData keeps the
    // PREVIOUS group's value while a block keeps failing, so a vote taken on it
    // would count one stale reading over and over and elect wrong characters
    // rather than filter them.
    private val groupRaw = IntArray(4)
    private val groupValid = BooleanArray(4)  // true = this block passed CRC in current group
    // true = block passed CRC WITHOUT error correction. Single-bit correction
    // uses 26 error patterns out of 1024 syndromes, so a random multi-bit
    // error is mis-'corrected' 2.5% of the time. At the BER seen in the field
    // that made ~28% of all accepted blocks garbage. Decisions that corrupt
    // state globally (RT A/B toggle, end-of-text truncation) must only act on
    // clean blocks; corrected ones are still fine as confirmation evidence.
    private val groupClean = BooleanArray(4)

    /**
     * Which character really belongs at each position, decided by weighted vote.
     *
     * The old rule was "show it once the same code arrives twice RUNNING". That
     * is a filter, not a decision, and it wastes most of the evidence: if a
     * position comes through correctly a fraction p of the time, two consecutive
     * receptions agree with probability p squared. A PS segment only comes round
     * about once a second, so at the block error rates this tuner actually sees
     * — the field reports show 46% to 93% — whole segments were still blank
     * after a minute. That is what "it takes forever to write the text" means,
     * and it is why a station whose RadioText changes every few minutes never
     * finished a single message.
     *
     * Counting instead of comparing takes the same evidence much further. The
     * correct character is the most common one, because errors scatter across
     * many different wrong values while the truth always lands on the same one:
     * a leader emerges after a handful of receptions instead of waiting for two
     * to line up by luck. Receptions are weighted by what they are worth — a
     * block that passed CRC untouched is strong evidence, one that needed a bit
     * corrected is weaker (single-bit correction mis-corrects about 2.5% of
     * random multi-bit errors), and the raw bits of a block that failed outright
     * are weakest. At this error rate the weakest kind is most of what arrives,
     * and throwing it away is why RadioText barely accumulated at all.
     *
     * A candidate is shown only once it has enough weight AND enough of a lead
     * over the runner-up, so noise that happens to agree twice cannot install a
     * character that the rest of the evidence contradicts.
     */
    private class CharVote(private val positions: Int) {
        companion object {
            /** Candidates tracked per position. Errors scatter; three is plenty. */
            private const val SLOTS = 3
            /** One CRC-clean reception is enough on its own, as it was before. */
            const val W_CLEAN = 6
            /** Two error-corrected ones, matching the old confirm threshold. */
            const val W_CORRECTED = 3
            /** Three raw ones — the old rule wanted three CONSECUTIVE. */
            const val W_RAW = 2
            private const val ACCEPT = 6
            private const val LEAD = 3
        }

        private val cand = CharArray(positions * SLOTS)
        private val score = IntArray(positions * SLOTS)

        fun clear() = clearFrom(0)

        fun clearFrom(from: Int) {
            for (i in from * SLOTS until positions * SLOTS) {
                cand[i] = ' '; score[i] = 0
            }
        }

        /** Record one reception. Returns the winner, or null while undecided. */
        fun vote(pos: Int, c: Char, weight: Int): Char? {
            val base = pos * SLOTS
            var slot = -1
            var weakest = base
            for (s in 0 until SLOTS) {
                val i = base + s
                if (score[i] > 0 && cand[i] == c) { slot = i; break }
                if (score[i] < score[weakest]) weakest = i
            }
            if (slot < 0) {
                // No free slot: discount the weakest rather than evicting it, so
                // a burst of noise cannot displace a candidate that a minute of
                // reception has already built up.
                if (score[weakest] > weight) { score[weakest] -= weight; return leader(base) }
                slot = weakest
                cand[slot] = c; score[slot] = 0
            }
            score[slot] += weight
            return leader(base)
        }

        private fun leader(base: Int): Char? {
            var best = base
            for (s in 1 until SLOTS) if (score[base + s] > score[best]) best = base + s
            var runner = 0
            for (s in 0 until SLOTS) {
                val i = base + s
                if (i != best && score[i] > runner) runner = score[i]
            }
            return if (score[best] >= ACCEPT && score[best] - runner >= LEAD) cand[best] else null
        }
    }

    /** Weight one reception by how much the block it came from can be trusted. */
    private fun charWeight(valid: Boolean, clean: Boolean): Int = when {
        valid && clean -> CharVote.W_CLEAN
        valid -> CharVote.W_CORRECTED
        else -> CharVote.W_RAW
    }

    // PS is decided by weighted vote per character — see CharVote.
    private val psChars = CharArray(8) { ' ' }
    private val psVote = CharVote(8)
    /** Positions the vote has actually decided. All eight = the name is whole. */
    private val psFilled = BooleanArray(8)

    // RT data
    private val rtChars = CharArray(64) { ' ' }
    private var rtLength = 0
    private var rtConfirmedLength = 0
    private var rtAbFlag = -1  // RT A/B flag: toggles when station changes text → clear buffer
    private var rtAbPendingFlag = -1   // candidate new A/B value awaiting confirmation
    private var rtAbPendingCount = 0
    // RadioText display buffer. Stations that put the current track in the
    // RadioText change the message every few minutes, and on a weak signal a
    // message takes longer than that to assemble — so the screen used to show
    // fragments of one message being replaced by fragments of the next, and you
    // never got to read either. Keep the last message that arrived COMPLETE on
    // screen while the next one is still being built, and swap only when the new
    // one has no gaps. If nothing ever completes, fall back to whatever has been
    // gathered after RT_PARTIAL_AFTER groups so the display can't get stuck.
    private val rtFilled = BooleanArray(64)
    private var rtDisplay = ""
    private var rtGroupsSinceClear = 0
    private var rtEverComplete = false
    private var rtEndSeen = false
    private var rtEndExplicit = false
    private var rtMinLength = 0
    private var rtMaxSeg = -1
    private var rtLastSeg = -1
    private var rtWrapsAtMax = 0
    // Per-character confirmation for RadioText, by the same weighted vote PS uses.
    private val rtVote = CharVote(64)

    // RDS decoded fields
    private var piCode = 0
    private var piConfirmCount = 0   // how many groups confirmed the current PI
    private var piCandidate = 0      // candidate PI awaiting confirmation
    private var piCandidateCount = 0
    private var ptyCode = 0
    private var ptyCandidate = -1
    private var ptyCandidateCount = 0
    private var tpFlag = false
    private var taFlag = false
    private var taCandidate = false
    private var taCandidateCount = 0
    private var msFlag = false
    private val afFrequencies = mutableSetOf<Float>()
    @Volatile
    private var dataChanged = false

    // Diagnostic logging counters
    private var totalBitsProcessed = 0L
    private var totalGoodBlocks = 0L
    private var totalBadBlocks = 0L
    /**
     * Share of blocks currently failing their CRC, as a running average over
     * roughly the last hundred blocks (~2 s).
     *
     * The lifetime good/bad ratio that used to be reported is not a measure of
     * reception: it counts every block tried during the initial sync search,
     * when the decoder is deliberately testing bit positions that are not block
     * boundaries at all. On a bench signal that decodes perfectly it reads 86%,
     * which is worse than useless — it was read as evidence of a bad aerial.
     */
    private var berEma = -1f
    // Syndrome matches seen while searching, per offset — see processBit.
    private var searchHitA = 0; private var searchHitB = 0; private var searchHitC = 0
    private var searchHitCp = 0; private var searchHitD = 0; private var searchBits = 0
    private var totalGroupsDecoded = 0L
    private var lastStatsLogTime = 0L

    // Fallback 57 kHz NCO (used when no pilot phase is available)
    private var fallbackCarrierPhase = 0.0
    private val fallbackCarrierInc = 2.0 * PI * 57000.0 / sampleRate

    init {
        // RDS LPF: 2.5 kHz cutoff with Blackman-Harris window
        // RDS signal bandwidth is ±2 kHz around 57 kHz subcarrier
        val cutoff = 2500f / sampleRate
        rdsLpfCoeffs = designLowPassFilter(rdsLpfOrder, cutoff)
        rdsSharpCoeffs = designLowPassFilter(rdsSharpOrder, 2800f / rdsRate)

        // Simple matched filter (approximate RRC, improves SNR)
        matchedFilter = FloatArray(matchedFilterOrder)
        val mid = matchedFilterOrder / 2
        var sum = 0f
        for (i in 0 until matchedFilterOrder) {
            val n = i - mid
            matchedFilter[i] = if (n == 0) 1f
            else sin(PI.toFloat() * n / (samplesPerBit / 2)) / (PI.toFloat() * n)
            val w = 0.5f * (1f - cos(2f * PI.toFloat() * i / (matchedFilterOrder - 1)))
            matchedFilter[i] *= w
            sum += abs(matchedFilter[i])
        }
        // Energy normalization (√Σx²) — correct for matched filters.
        // Previous DC-gain normalization caused bad scaling when filter
        // had negative coefficients from the window.
        val energy = matchedFilter.map { it * it }.sum()
        val normFactor = kotlin.math.sqrt(energy)
        if (normFactor > 1e-6f) {
            for (i in matchedFilter.indices) matchedFilter[i] /= normFactor
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
            // Blackman-Harris window (same as main demodulator) — ~92 dB stopband
            val w = i.toFloat() / (order - 1).toFloat()
            val a0 = 0.35875f; val a1 = 0.48829f; val a2 = 0.14128f; val a3 = 0.01168f
            coeffs[i] *= a0 - a1 * cos(2 * PI.toFloat() * w) +
                    a2 * cos(4 * PI.toFloat() * w) - a3 * cos(6 * PI.toFloat() * w)
            sum += coeffs[i]
        }
        for (i in coeffs.indices) coeffs[i] /= sum
        return coeffs
    }

    // Persistent carrier NCO — phase continuous, frequency from pilot PLL.
    // Previous attempts:
    //   pilotPhase×3 with reset: phase jumped 94° between calls → 6% extra BER
    //   free-running 57kHz: crystal PPM drift → carrier off after ~1 sec
    //   pilot-freq-locked (with 15Hz PLL): PLL jittered → carrier jittered
    // NOW: pilot-freq-locked with STABLE 1Hz PLL → should work correctly.
    private var carrierPhase = 0.0
    private var carrierInc = 2.0 * PI * 57000.0 / sampleRate

    /**
     * Steer the 57 kHz carrier from the pilot PLL — but only when the pilot is
     * actually there.
     *
     * On a mono station there is no pilot to lock to, so the PLL free-runs and
     * wanders. A field log on such a station showed it drifting between
     * 0.621762 and 0.621798 rad/sample, and this multiplied that wander by
     * three straight into the RDS carrier: about 3.3 Hz of moving frequency
     * error, on a signal already running at 66% block errors. Nominal 57 kHz is
     * strictly better than three times a free-running guess.
     *
     * The plausibility window is derived from the sample rate, not written as
     * the literal 0.6..0.65 it used to be. Those numbers were the 19 kHz pilot
     * expressed in radians per sample AT 192 kHz (2*pi*19000/192000 = 0.6218).
     * At the 240 kHz intermediate rate the same pilot is 0.4974, which the old
     * window rejects — so a perfectly locked PLL would have been discarded on
     * every station and the carrier left free-running, silently.
     */
    @JvmOverloads
    fun setPilotFreq(pilotFreqRadPerSample: Double, locked: Boolean = true) {
        val nominalPilot = 2.0 * PI * 19000.0 / sampleRate
        carrierInc = if (locked &&
            pilotFreqRadPerSample > nominalPilot * 0.965 &&
            pilotFreqRadPerSample < nominalPilot * 1.045) {
            pilotFreqRadPerSample * 3.0
        } else {
            2.0 * PI * 57000.0 / sampleRate
        }
    }

    fun process(baseband: FloatArray, count: Int, pilotPhase: Double) {
        for (idx in 0 until count) {
            val sample = baseband[idx]

            val cosC = cos(carrierPhase).toFloat()
            val sinC = sin(carrierPhase).toFloat()
            carrierPhase += carrierInc
            if (carrierPhase > 2 * PI) carrierPhase -= 2 * PI

            // Mix down to baseband (double-buffer write)
            rdsLpfBufI[rdsLpfIdx] = sample * cosC
            rdsLpfBufI[rdsLpfIdx + rdsLpfOrder] = sample * cosC
            rdsLpfBufQ[rdsLpfIdx] = sample * sinC
            rdsLpfBufQ[rdsLpfIdx + rdsLpfOrder] = sample * sinC
            rdsLpfIdx = (rdsLpfIdx + 1) % rdsLpfOrder

            // Decimate
            rdsDecimCounter++
            if (rdsDecimCounter < rdsDecimation) continue
            rdsDecimCounter = 0

            // Apply RDS lowpass filter to BOTH I and Q — we don't know the carrier
            // phase offset between pilot and RDS subcarrier, so we need the full complex
            // baseband for phase-agnostic differential BPSK detection.
            var filtI = 0f
            var filtQ = 0f
            val rdsBase = rdsLpfIdx
            for (j in 0 until rdsLpfOrder) {
                val p = rdsBase + rdsLpfOrder - 1 - j
                filtI += rdsLpfBufI[p] * rdsLpfCoeffs[j]
                filtQ += rdsLpfBufQ[p] * rdsLpfCoeffs[j]
            }

            decimatedSample(filtI, filtQ)
        }
    }

    /**
     * One complex sample at the decimated rate: sharpen, match, detect.
     *
     * Shared by both process() overloads. It was written out twice, and the
     * second copy is where a change gets forgotten.
     */
    private fun decimatedSample(inI: Float, inQ: Float) {
        rdsSharpBufI[rdsSharpIdx] = inI
        rdsSharpBufI[rdsSharpIdx + rdsSharpOrder] = inI
        rdsSharpBufQ[rdsSharpIdx] = inQ
        rdsSharpBufQ[rdsSharpIdx + rdsSharpOrder] = inQ
        rdsSharpIdx = (rdsSharpIdx + 1) % rdsSharpOrder
        var filtI = 0f
        var filtQ = 0f
        val sBase = rdsSharpIdx
        for (j in 0 until rdsSharpOrder) {
            val p = sBase + rdsSharpOrder - 1 - j
            filtI += rdsSharpBufI[p] * rdsSharpCoeffs[j]
            filtQ += rdsSharpBufQ[p] * rdsSharpCoeffs[j]
        }

        matchedBufI[matchedBufIdx] = filtI
        matchedBufI[matchedBufIdx + matchedFilterOrder] = filtI
        matchedBufQ[matchedBufIdx] = filtQ
        matchedBufQ[matchedBufIdx + matchedFilterOrder] = filtQ
        matchedBufIdx = (matchedBufIdx + 1) % matchedFilterOrder
        var mI = 0f
        var mQ = 0f
        val mBase = matchedBufIdx
        for (j in 0 until matchedFilterOrder) {
            val p = mBase + matchedFilterOrder - 1 - j
            mI += matchedBufI[p] * matchedFilter[j]
            mQ += matchedBufQ[p] * matchedFilter[j]
        }

        processRdsSample(mI, mQ)
    }

    /**
     * Process wideband baseband samples with fallback free-running NCO.
     * Use this overload when pilot phase is not available.
     */
    fun process(baseband: FloatArray, count: Int = baseband.size) {
        for (idx in 0 until count) {
            val sample = baseband[idx]
            val cosCarrier = cos(fallbackCarrierPhase).toFloat()
            val sinCarrier = sin(fallbackCarrierPhase).toFloat()
            fallbackCarrierPhase += fallbackCarrierInc
            if (fallbackCarrierPhase > 2 * PI) fallbackCarrierPhase -= 2 * PI

            rdsLpfBufI[rdsLpfIdx] = sample * cosCarrier
            rdsLpfBufI[rdsLpfIdx + rdsLpfOrder] = sample * cosCarrier
            rdsLpfBufQ[rdsLpfIdx] = sample * sinCarrier
            rdsLpfBufQ[rdsLpfIdx + rdsLpfOrder] = sample * sinCarrier
            rdsLpfIdx = (rdsLpfIdx + 1) % rdsLpfOrder

            rdsDecimCounter++
            if (rdsDecimCounter < rdsDecimation) continue
            rdsDecimCounter = 0

            var filtI = 0f
            var filtQ = 0f
            val rdsBase = rdsLpfIdx
            for (j in 0 until rdsLpfOrder) {
                val p = rdsBase + rdsLpfOrder - 1 - j
                filtI += rdsLpfBufI[p] * rdsLpfCoeffs[j]
                filtQ += rdsLpfBufQ[p] * rdsLpfCoeffs[j]
            }

            decimatedSample(filtI, filtQ)
        }
    }

    /**
     * Complex differential BPSK decoder — phase-agnostic.
     *
     * Since the 57 kHz RDS subcarrier has an unknown phase offset relative to the
     * pilot-derived carrier (hardware group delay + spec says RDS is in quadrature
     * with the third pilot harmonic), we cannot rely on a single I or Q channel.
     * Instead we compute the complex product curr * conj(prev) at each symbol
     * decision; its real part is positive when the symbol matches the previous one
     * and negative when it flipped — exactly what DBPSK requires, independent of
     * absolute carrier phase.
     */
    private fun processRdsSample(mI: Float, mQ: Float) {
        clockPhase += 1f

        if (clockPhase >= samplesPerBit) {
            clockPhase -= samplesPerBit

            if (!prevSymValid) {
                // First symbol — just capture the reference, don't decode.
                // With prevSymI/Q both 0, the dot product and projection would
                // be 0 → wrong bit decision + clock hammered by false crossings.
                prevSymI = mI
                prevSymQ = mQ
                prevSymValid = true
                return
            }

            val dot = mI * prevSymI + mQ * prevSymQ
            val decodedBit = if (dot > 0f) 0 else 1
            prevSymI = mI
            prevSymQ = mQ

            processBit(decodedBit)
        }

        // Clock recovery: project onto previous symbol's axis for stable timing.
        if (!prevSymValid) return
        val proj = mI * prevSymI + mQ * prevSymQ
        if ((proj > 0f && prevRdsSample <= 0f) || (proj < 0f && prevRdsSample >= 0f)) {
            val error = clockPhase - samplesPerBit / 2
            val correction = (error * 0.05f).coerceIn(-samplesPerBit * 0.1f, samplesPerBit * 0.1f)
            clockPhase -= correction
        }
        prevRdsSample = proj
    }

    private fun processBit(bit: Int) {
        bitBuffer = ((bitBuffer shl 1) or bit.toLong()) and 0x3FFFFFFL  // 26 bits

        bitCount++
        totalBitsProcessed++

        if (!synced) {
            // Search: check syndrome on every incoming bit
            if (bitCount >= 26) {
                val syndrome = calcSyndrome(bitBuffer, 26)

                // Diagnostic, and the one that settles what is actually wrong.
                // A real RDS stream puts one of each offset word on the air
                // 11.4 times a second, so a receiver that is demodulating it
                // sees roughly that many syndrome matches per second for EVERY
                // offset. Random bits give one match per 1024 positions, which
                // at 1187.5 bit/s is about 1.2 a second — and it is the same
                // 1.2 for all five, because it is pure chance.
                //
                // Whichever of those two the field shows decides where the
                // fault is: below the decoder, or inside it. Nothing else in
                // the log distinguishes them, which is why this has been guessed
                // at twice.
                when (syndrome) {
                    OFFSET_A  -> searchHitA++
                    OFFSET_B  -> searchHitB++
                    OFFSET_C  -> searchHitC++
                    OFFSET_CP -> searchHitCp++
                    OFFSET_D  -> searchHitD++
                }
                searchBits++
                if (searchBits >= (RDS_BITRATE * 5).toInt()) {
                    val secs = searchBits / RDS_BITRATE
                    val line = ("RDS search: A=%.1f B=%.1f C=%.1f C'=%.1f D=%.1f matches/s " +
                        "(real signal ~11 each, random bits ~1.2 each)")
                        .format(searchHitA / secs, searchHitB / secs, searchHitC / secs,
                                searchHitCp / secs, searchHitD / secs)
                    Log.d(TAG, line)
                    DebugLog.log(TAG, line)
                    com.fmradio.util.StatusSnapshot.rdsSearch = line.removePrefix("RDS search: ")
                    searchHitA = 0; searchHitB = 0; searchHitC = 0
                    searchHitCp = 0; searchHitD = 0; searchBits = 0
                }

                if (syndrome == OFFSET_A) {
                    // Candidate sync — enter confirmation phase
                    synced = true
                    syncConfirmed = false
                    syncConfirmGood = 1
                    syncConfirmBad = 0
                    blockIndex = 0
                    groupData[0] = ((bitBuffer shr 10) and 0xFFFF).toInt()
                    groupRaw[0] = groupData[0]
                    groupValid[0] = true
                    groupClean[0] = true  // matched OFFSET_A exactly, no correction
                    blockIndex = 1
                    bitCount = 0
                    goodBlocks = 1
                    badBlocks = 0
                    Log.d(TAG, "RDS sync candidate at bit $totalBitsProcessed")
                }
            }
        } else {
            if (bitCount >= 26) {
                val expectedOffset = when (blockIndex) {
                    0 -> OFFSET_A
                    1 -> OFFSET_B
                    2 -> if (groupData[1] and 0x0800 != 0) OFFSET_CP else OFFSET_C
                    3 -> OFFSET_D
                    else -> OFFSET_A
                }

                groupRaw[blockIndex] = ((bitBuffer shr 10) and 0xFFFF).toInt()

                val corrected = tryCorrectBlock(bitBuffer, expectedOffset)
                if (corrected != null) {
                    groupData[blockIndex] = corrected
                    groupValid[blockIndex] = true
                    groupClean[blockIndex] = lastBlockWasClean
                    goodBlocks++
                    totalGoodBlocks++
                    berEma = if (berEma < 0f) 0f else berEma + BER_EMA_ALPHA * (0f - berEma)
                    badBlocks = 0  // reset: truly consecutive bad counter
                    if (!syncConfirmed) {
                        syncConfirmGood++
                        // Decaying window: a good block forgives an earlier bad one,
                        // so a real but noisy signal can still confirm.
                        syncConfirmBad = (syncConfirmBad - 1).coerceAtLeast(0)
                    }
                } else {
                    groupValid[blockIndex] = false
                    groupClean[blockIndex] = false
                    badBlocks++
                    totalBadBlocks++
                    berEma = if (berEma < 0f) 1f else berEma + BER_EMA_ALPHA * (1f - berEma)
                    if (!syncConfirmed) syncConfirmBad++
                }

                // Sync confirmation: confirm at 3 good blocks; reject only after
                // 6 bad (with decay above). Looser than before so FC0013's noisy
                // RDS can lock instead of being rejected at the first bad block.
                if (!syncConfirmed) {
                    if (syncConfirmBad >= 6) {
                        Log.d(TAG, "RDS sync REJECTED (good=$syncConfirmGood bad=$syncConfirmBad)")
                        DebugLog.log(TAG, "RDS sync REJECTED (good=$syncConfirmGood bad=$syncConfirmBad)")
                        synced = false
                        bitCount = 26  // resume syndrome search immediately, no 26-bit blind wait
                        return
                    }
                    if (syncConfirmGood >= 3) {
                        syncConfirmed = true
                        Log.d(TAG, "RDS sync CONFIRMED at bit $totalBitsProcessed")
                        DebugLog.log(TAG, "RDS sync CONFIRMED at bit $totalBitsProcessed")
                    }
                }

                // Once confirmed, lose sync after 40 consecutive bad blocks.
                // FC0013 has 89% BER — at 40, a confirmed sync survives noise
                // bursts long enough to decode PS over multiple groups.
                // Give up on the framing after twelve consecutive failures,
                // not forty-one.
                //
                // Forty-one in a row is not something reception does. On 107.0,
                // at -10 dB with a noise reading of 0.013 and the subcarrier
                // measured 6.1 dB above the band beside it, the field log loses
                // sync every two to five seconds and ALWAYS with badBlocks=41.
                // Half the blocks on that station fail; forty-one consecutive
                // failures by chance is a probability of 5e-13. What actually
                // happens is that the bit framing slips, after which every
                // block fails by construction — and the proof is in the next
                // line of the log every time: sync is re-confirmed 50 to 150 ms
                // later, on the same signal, because the data was never bad.
                //
                // So the forty-one is pure waste: nine tenths of a second of
                // decoding thrown away each time, several times a minute.
                // Measured end to end on a standards-correct signal, 40 s at 4%
                // injection, block error rate at 41 / 12 / 6:
                //
                //   L-R    noise    41 ->  12 ->   6      RadioText complete
                //   mono   0.070   11.7   7.4    6.4      6.5 s throughout
                //   mono   0.090   73.9  48.2   42.0      never -> 36.1 -> 24.7 s
                //   0.45   0.090   69.7  48.0   41.6      name 7.3 -> 3.4 -> 2.0 s
                //
                // Still improving at six, and it should: giving up costs the
                // hundred milliseconds the field log shows re-acquisition
                // taking, while carrying on at a slipped framing costs
                // everything until the counter runs out. Six consecutive
                // failures happen by chance often enough at these error rates,
                // and that is fine — being wrong is cheap and being right is
                // not.
                if (syncConfirmed && badBlocks > 6) {
                    Log.d(TAG, "RDS sync LOST (badBlocks=$badBlocks)")
                    DebugLog.log(TAG, "RDS sync LOST (badBlocks=$badBlocks)")
                    synced = false
                    syncConfirmed = false
                    bitCount = 26  // resume search immediately
                    return
                }

                blockIndex++
                bitCount = 0

                if (blockIndex >= 4) {
                    // Require block B valid (it carries the group type — without it
                    // we can't interpret C/D). Block A (PI) and data blocks C/D are
                    // checked individually inside decodeGroup via groupValid[], so a
                    // group with a corrupt A but good B+D can still yield PS text.
                    if (syncConfirmed && groupValid[1]) {
                        decodeGroup()
                    }
                    blockIndex = 0
                    goodBlocks = 0
                    for (i in groupValid.indices) { groupValid[i] = false; groupClean[i] = false }
                }
            }
        }
    }

    private fun calcSyndrome(data: Long, bits: Int): Int {
        var reg = 0
        for (i in bits - 1 downTo 0) {
            val bit = ((data shr i) and 1).toInt()
            val fb = (reg shr 9) and 1
            reg = ((reg shl 1) or bit) and 0x3FF
            if (fb != 0) {
                reg = reg xor CRC_POLY
            }
        }
        return reg
    }

    // Pre-computed syndrome table for single-bit error correction.
    // For each of 26 bit positions, the syndrome when ONLY that bit is flipped.
    // Used to correct blocks with exactly 1 bit error → ~15% more valid blocks.
    private val singleBitSyndromes: Map<Int, Int> by lazy {
        val table = mutableMapOf<Int, Int>()
        for (bitPos in 0 until 26) {
            val errorPattern = 1L shl bitPos
            val syndrome = calcSyndrome(errorPattern, 26)
            table[syndrome] = bitPos
        }
        table
    }

    /**
     * Try to correct single-bit errors in a 26-bit RDS block.
     * Returns corrected 16-bit data if successful, null if not correctable.
     */
    /** Set by tryCorrectBlock: true when the last accepted block needed no correction. */
    private var lastBlockWasClean = false

    private fun tryCorrectBlock(rawBlock: Long, expectedOffset: Int): Int? {
        val syndrome = calcSyndrome(rawBlock, 26)
        if (syndrome == expectedOffset) {
            // No error
            lastBlockWasClean = true
            return ((rawBlock shr 10) and 0xFFFF).toInt()
        }
        // Error syndrome = actual XOR expected
        val errorSyndrome = syndrome xor expectedOffset
        val bitPos = singleBitSyndromes[errorSyndrome]
        if (bitPos != null) {
            // Single-bit error found — correct it
            lastBlockWasClean = false
            val corrected = rawBlock xor (1L shl bitPos)
            return ((corrected shr 10) and 0xFFFF).toInt()
        }
        return null  // multi-bit error, not correctable
    }

    /** RDS end-of-text marker (IEC 62106: 0x0D = end of RadioText) */
    private val RDS_END_OF_TEXT = 0x0D

    private fun isValidRdsChar(c: Char): Boolean {
        return c.code in 0x20..0xFE  // 0xFF = filler, 0x00-0x1F = control
    }

    /**
     * Convert an RDS byte to a Unicode character.
     */
    private fun rdsCharToUnicode(code: Int): Char {
        if (code == 0xFF || code < 0x20) return ' '  // filler/control → space
        if (code == RDS_END_OF_TEXT) return '\r'
        if (code in 0x20..0x7E) return code.toChar()

        return when (code) {
            // EBU Latin code table 00, row 8 (0x80-0x8F)
            0x80 -> 'á'; 0x81 -> 'à'; 0x82 -> 'é'; 0x83 -> 'è'
            0x84 -> 'í'; 0x85 -> 'ì'; 0x86 -> 'ó'; 0x87 -> 'ò'
            0x88 -> 'ú'; 0x89 -> 'ù'; 0x8A -> 'Ñ'; 0x8B -> 'Ç'
            0x8C -> 'Ş'; 0x8D -> 'ß'; 0x8E -> 'Ə'; 0x8F -> 'İ'

            // EBU Latin code table 00, row 9 (0x90-0x9F)
            0x90 -> 'â'; 0x91 -> 'ä'; 0x92 -> 'ê'; 0x93 -> 'ë'
            0x94 -> 'î'; 0x95 -> 'ï'; 0x96 -> 'ô'; 0x97 -> 'ö'
            0x98 -> 'û'; 0x99 -> 'ü'; 0x9A -> 'ñ'; 0x9B -> 'ç'
            0x9C -> 'ş'; 0x9D -> 'ğ'; 0x9E -> 'ə'; 0x9F -> 'ı'

            // EBU Latin code table 00, row A (0xA0-0xAF)
            0xA0 -> 'ª'; 0xA1 -> 'α'; 0xA2 -> '©'; 0xA3 -> '‰'
            0xA4 -> 'Ğ'; 0xA5 -> 'ě'; 0xA6 -> 'ň'; 0xA7 -> 'ő'
            0xA8 -> 'π'; 0xA9 -> '€'; 0xAA -> '£'; 0xAB -> '$'
            0xAC -> '←'; 0xAD -> '↑'; 0xAE -> '→'; 0xAF -> '↓'

            // EBU Latin code table 00, row B (0xB0-0xBF)
            0xB0 -> 'º'; 0xB1 -> '¹'; 0xB2 -> '²'; 0xB3 -> '³'
            0xB4 -> '±'; 0xB5 -> 'İ'; 0xB6 -> 'ń'; 0xB7 -> 'ű'
            0xB8 -> 'µ'; 0xB9 -> '¿'; 0xBA -> '÷'; 0xBB -> '°'
            0xBC -> '¼'; 0xBD -> '½'; 0xBE -> '¾'; 0xBF -> '§'

            // 0xC0-0xFF: ISO 8859-1 (Latin-1) passthrough.
            // Covers À-ÿ (common accented Latin characters).
            // The RDS spec defines 0xC0-0xCF as combining diacritical marks
            // but virtually no real FM station uses them — they use pre-composed
            // characters from 0x80-0xBF or plain ASCII instead.
            in 0xC0..0xFF -> code.toChar()

            else -> ' '  // control chars and undefined → space
        }
    }

    private fun decodeGroup() {
        val blockA = groupData[0]
        val blockB = groupData[1]

        // PI confirmation. A clean block A locks immediately; a corrected one
        // needs a second agreeing read. This still prevents a single false-sync
        // group from setting a wrong PI and rejecting every real group after it.
        if (groupValid[0] && blockA != 0) {
            if (piCode == 0) {
                // A block A that passed CRC with NO correction applied is
                // already strong evidence: the checkword covers all 16 data
                // bits and an uncorrected pass has no ambiguity to it. Waiting
                // for three of them was the single biggest reason nothing
                // appeared for a long time — PS is not assembled at all until
                // PI locks, so every extra confirmation delays the station name
                // and the radiotext behind it. Measured on synthesized RDS at a
                // realistic error rate, the name took 20-70 s to appear.
                // Error-corrected blocks still need two agreeing reads.
                if (groupClean[0]) {
                    piCode = blockA
                    piConfirmCount = 3
                } else if (blockA == piCandidate) {
                    piCandidateCount++
                    if (piCandidateCount >= 2) {
                        piCode = blockA
                        piConfirmCount = 2
                    }
                } else {
                    piCandidate = blockA
                    piCandidateCount = 1
                }
            } else if (blockA != piCode) {
                // Mismatch with confirmed PI — skip this group
                return
            }
        }
        val blockC = groupData[2]
        val blockD = groupData[3]

        // Group type and version
        val groupType = (blockB shr 12) and 0x0F
        val versionB = (blockB and 0x0800) != 0
        // PTY comes from block B; a mis-corrected block B yields a random
        // genre (field logs showed PTY flipping 9 → 16 → 9 on one station).
        // Only accept it from a clean block, and require two agreeing reads.
        if (groupClean[1]) {
            val pty = (blockB shr 5) and 0x1F
            if (pty == ptyCandidate) {
                if (++ptyCandidateCount >= 2) ptyCode = pty
            } else {
                ptyCandidate = pty
                ptyCandidateCount = 1
            }
        }

        totalGroupsDecoded++
        rtGroupsSinceClear++

        // Log decoded group details
        val versionStr = if (versionB) "B" else "A"
        val psStr = String(psChars).trim()
        val rtStr = String(rtChars, 0, rtLength).trim()
        Log.d(TAG, "Group ${groupType}${versionStr}: PI=%04X PTY=$ptyCode PS='$psStr' RT='$rtStr'".format(piCode))
        // ~11 groups a second: too fast for the always-on in-memory ring, which
        // it would flush in under a minute. The 5 s stats line below is what
        // that ring carries; this one is for a full file log.
        if (DebugLog.fileLoggingEnabled) {
            DebugLog.log(TAG, "Group ${groupType}${versionStr}: PI=%04X PTY=$ptyCode PS='$psStr' RT='$rtStr'".format(piCode))
        }

        // Periodic bit error statistics
        val now = System.currentTimeMillis()
        run {
            // Unconditionally, whether or not file logging is on: this is what
            // a report has to say to be worth reading. See StatusSnapshot.
            val total = totalGoodBlocks + totalBadBlocks
            com.fmradio.util.StatusSnapshot.rdsSynced = synced
            com.fmradio.util.StatusSnapshot.rdsBerPct =
                if (berEma >= 0f) berEma * 100f else 0f
            com.fmradio.util.StatusSnapshot.rdsBerLifetimePct =
                if (total > 0) totalBadBlocks.toFloat() / total * 100f else 0f
            com.fmradio.util.StatusSnapshot.rdsGroups = totalGroupsDecoded
            com.fmradio.util.StatusSnapshot.rdsPs = psStr
            com.fmradio.util.StatusSnapshot.rdsRt = rtStr
        }
        if (now - lastStatsLogTime >= STATS_LOG_INTERVAL_MS) {
            lastStatsLogTime = now
            val total = totalGoodBlocks + totalBadBlocks
            val errorRate = if (total > 0) totalBadBlocks.toFloat() / total * 100f else 0f
            val line = ("RDS stats: bits=$totalBitsProcessed groups=$totalGroupsDecoded " +
                "good=$totalGoodBlocks bad=$totalBadBlocks BERnow=%.1f%% BERlife=%.1f%% synced=$synced")
                .format(if (berEma >= 0f) berEma * 100f else 0f, errorRate)
            Log.d(TAG, line)
            DebugLog.log(TAG, line)
        }

        // TP (Traffic Programme) and TA (Traffic Announcement), both from
        // block B, and both only from a block B that passed CRC UNTOUCHED.
        //
        // These were read from blockB unconditionally, which is wrong twice
        // over. groupData holds the last block B that passed, so on a failing
        // block the flags were re-read from a stale word; and a block that
        // needed a bit corrected carries a random value in any single bit,
        // which is exactly what these flags are. TA now drives the volume, so
        // one wrong bit is a jump in loudness with no announcement behind it —
        // it has to be right, not merely likely.
        //
        // Two agreeing clean readings, the same rule PTY already uses. On air
        // group 0 repeats about four times a second, so a real announcement is
        // acted on within a second of starting.
        if (groupClean[1]) {
            tpFlag = (blockB and 0x0400) != 0

            if (groupType == 0) {
                val newTa = (blockB and 0x0010) != 0
                if (newTa == taCandidate) {
                    if (++taCandidateCount >= 2 && newTa != taFlag) {
                        taFlag = newTa
                        dataChanged = true
                        DebugLog.log(TAG, "TA -> $taFlag (TP=$tpFlag)")
                    }
                } else {
                    taCandidate = newTa
                    taCandidateCount = 1
                }
                // M/S flag — bit 3 of block B in group 0
                msFlag = (blockB and 0x0008) != 0
            }
        }

        val cValid = groupValid[2]
        val dValid = groupValid[3]
        // Nothing is placed anywhere unless block B passed CRC.
        //
        // Both text groups take their position in the message from block B, and
        // groupData keeps the PREVIOUS group's block B while this one is
        // failing — so a failed block B does not mean "no address", it means a
        // STALE one, indistinguishable from a real one. Characters were being
        // written confidently into whatever slot the last group happened to
        // use: at the error rates here that is roughly every other group,
        // scattering good characters across the wrong positions. It looks like
        // reception noise and is not.
        if (groupValid[1]) {
            when (groupType) {
                0 -> decodeGroup0(blockB, blockC, blockD, versionB, cValid, dValid)  // PS name + AF
                2 -> decodeGroup2(blockB, blockC, blockD, versionB, cValid, dValid)  // RadioText
            }
        }

        // Notify listener
        if (dataChanged) {
            dataChanged = false
            notifyListener()
        }
    }

    // Group 0: Programme Service name (2 chars per group) + Alternative Frequencies
    // Each character is decided by weighted vote — see CharVote.
    private fun decodeGroup0(blockB: Int, blockC: Int, blockD: Int, versionB: Boolean,
                             cValid: Boolean, dValid: Boolean) {
        // Don't build the PS name until the PI code is confirmed: before PI
        // lock, a consistently-misaligned sync (possible during acquisition)
        // repeats the same wrong characters, and a vote cannot tell a
        // consistent error from the truth. PI confirms within ~1 s on air, so
        // this costs nothing.
        if (piCode == 0) return

        val segmentAddr = blockB and 0x03
        val pos = segmentAddr * 2

        // PS chars from block D. At 89% BER on FC0013, requiring dValid means PS
        // never populates (block D rarely passes CRC), so a failed block is
        // still used — but its bits are read from groupRaw, never groupData,
        // so that each reception is genuinely independent. groupData holds the
        // last block D that PASSED, and re-reading that would let one lucky
        // block vote for itself over and over.
        val dCode = if (dValid) blockD else groupRaw[3]
        val c1 = rdsCharToUnicode((dCode shr 8) and 0xFF)
        val c2 = rdsCharToUnicode(dCode and 0xFF)

        // Each of the two characters is voted on separately. They arrive in the
        // same block, but a block that failed CRC has usually only damaged part
        // of itself, so tying the two together throws away a good half whenever
        // the other half is wrong.
        val w = charWeight(dValid, dValid && groupClean[3])
        var psChanged = false
        if (isValidRdsChar(c1)) psVote.vote(pos, c1, w)?.let {
            if (psChars[pos] != it) { psChars[pos] = it; psChanged = true }
            psFilled[pos] = true
        }
        if (isValidRdsChar(c2)) psVote.vote(pos + 1, c2, w)?.let {
            if (psChars[pos + 1] != it) { psChars[pos + 1] = it; psChanged = true }
            psFilled[pos + 1] = true
        }
        if (psChanged) {
            dataChanged = true
            Log.d(TAG, "PS update: ${String(psChars).trim()}")
        }

        // AF (Alternative Frequencies) from block C in version A
        if (!versionB && cValid) {
            decodeAfCode((blockC shr 8) and 0xFF)
            decodeAfCode(blockC and 0xFF)
        }
    }

    /** Decode an AF code to frequency and add to list. Codes 1-204 map to 87.6-107.9 MHz. */
    private fun decodeAfCode(code: Int) {
        if (code in 1..204) {
            val freqMHz = 87.5f + code * 0.1f
            if (afFrequencies.add(freqMHz)) {
                dataChanged = true
                Log.d(TAG, "AF: $freqMHz MHz")
            }
        }
    }

    // Group 2: RadioText (4 chars per group in version A, 2 in version B)
    // Only triggers dataChanged when at least one valid character is found
    private fun decodeGroup2(blockB: Int, blockC: Int, blockD: Int, versionB: Boolean,
                             cValid: Boolean, dValid: Boolean) {
        // RT A/B flag (bit 4 of blockB): when it toggles, the station changed
        // the text → clear the buffer so old chars don't mix with new.
        // Only honour it from a CRC-clean block B: a mis-corrected block B
        // carries a random A/B bit, and acting on it wipes good text — which
        // is exactly the "text keeps getting truncated" symptom. Require two
        // consecutive clean readings of the new value before clearing.
        if (groupClean[1]) {
            val abFlag = (blockB shr 4) and 0x01
            if (rtAbFlag >= 0 && abFlag != rtAbFlag) {
                // Real message change — clear at once so the new text isn't
                // mixed into the old one. Requiring a clean block B is what
                // prevents a mis-corrected block from wiping good text; adding
                // a further confirmation delay would itself cause mixing.
                for (i in rtChars.indices) rtChars[i] = ' '
                rtVote.clear()
                for (i in rtFilled.indices) rtFilled[i] = false
                rtLength = 0
                rtEndSeen = false; rtEndExplicit = false; rtMinLength = 0
                rtMaxSeg = -1; rtLastSeg = -1; rtWrapsAtMax = 0
                rtGroupsSinceClear = 0
                dataChanged = true
            }
            rtAbFlag = abFlag
        }

        val segmentAddr = blockB and 0x0F

        // Where the message ends, for stations that never send the 0x0D
        // terminator. The segment address cycles 0..N over and over, so once N
        // has stayed the highest across two complete cycles the length is known.
        val perSegment = if (versionB) 2 else 4
        if (segmentAddr > rtMaxSeg) {
            rtMaxSeg = segmentAddr
            rtWrapsAtMax = 0
        } else if (segmentAddr < rtLastSeg && rtWrapsAtMax < 4) {
            rtWrapsAtMax++
        }
        rtLastSeg = segmentAddr
        if (rtWrapsAtMax >= 2) {
            rtEndSeen = true
            // Only a guess from the segment count — a 0x0D terminator is
            // authoritative and must not be overwritten by it, or a message
            // that ends mid-segment can never be counted as complete.
            if (!rtEndExplicit) rtMinLength = (rtMaxSeg + 1) * perSegment
        }

        // A block that failed CRC is still read, at the lowest weight.
        //
        // Discarding it was safe but expensive: RadioText used to take only
        // blocks that passed, and in the field 46% to 93% of blocks do not.
        // On 106.3 that meant a message was still half-assembled when the
        // station replaced it, so nothing was ever finished — the exact
        // complaint. The vote is what makes reading them safe: a wrong bit
        // lands on a different character each time and never builds a lead,
        // while the right one is reinforced by every reception including the
        // failed blocks. Only the two decisions that damage the whole message —
        // the A/B toggle above and the 0x0D terminator — still insist on a
        // block that passed CRC untouched.
        val rawC = groupRaw[2]
        val rawD = groupRaw[3]
        val srcC = if (cValid) blockC else rawC
        val srcD = if (dValid) blockD else rawD
        val wC = charWeight(cValid, cValid && groupClean[2])
        val wD = charWeight(dValid, dValid && groupClean[3])
        val cleanC = cValid && groupClean[2]
        val cleanD = dValid && groupClean[3]

        if (!versionB) {
            val pos = segmentAddr * 4
            if (pos + 3 < rtChars.size) {
                val chars = intArrayOf(
                    (srcC shr 8) and 0xFF, srcC and 0xFF,
                    (srcD shr 8) and 0xFF, srcD and 0xFF
                )
                val clean = booleanArrayOf(cleanC, cleanC, cleanD, cleanD)
                val weight = intArrayOf(wC, wC, wD, wD)
                var anyValid = false
                for (j in 0..3) {
                    if (commitRtChar(pos + j, chars[j], clean[j], weight[j])) anyValid = true
                    if (chars[j] == RDS_END_OF_TEXT && clean[j]) break
                }
                if (anyValid) dataChanged = true
            }
        } else {
            // Version B: 2 chars per segment from block D.
            val pos = segmentAddr * 2
            if (pos + 1 < rtChars.size) {
                val chars = intArrayOf((srcD shr 8) and 0xFF, srcD and 0xFF)
                var anyValid = false
                for (j in 0..1) {
                    if (commitRtChar(pos + j, chars[j], cleanD, wD)) anyValid = true
                    if (chars[j] == RDS_END_OF_TEXT && cleanD) return
                }
                if (anyValid) dataChanged = true
            }
        }
    }

    /**
     * Commit one RadioText character.
     *
     * [weight] says how much this reception counts for — see CharVote. [clean]
     * is separate and stricter: it gates the decisions that damage the whole
     * message rather than one character, so a 0x0D from anything but a block
     * that passed CRC untouched is ignored outright rather than merely weighed.
     *
     * Returns true if the visible buffer changed.
     */
    private fun commitRtChar(pos: Int, code: Int, clean: Boolean, weight: Int): Boolean {
        if (pos < 0 || pos >= rtChars.size) return false

        if (code == RDS_END_OF_TEXT) {
            // A stray 0x0D from a mis-corrected block used to truncate the whole
            // message — only honour it from a clean block.
            if (!clean) return false
            // Only the FIRST marker of a run is the real end: a message that
            // stops mid-segment is padded with more 0x0D, and taking the last
            // one would place the end past the last character actually sent —
            // leaving positions that never get filled and a message that can
            // never count as complete.
            rtEndSeen = true
            rtMinLength = if (rtEndExplicit) minOf(rtMinLength, pos) else pos
            rtEndExplicit = true
            if (pos < rtLength) {
                rtLength = pos
                for (k in pos until rtChars.size) {
                    rtChars[k] = ' '; rtFilled[k] = false
                }
                rtVote.clearFrom(pos)
                return true
            }
            return false
        }

        val c = rdsCharToUnicode(code)
        if (!isValidRdsChar(c)) return false

        val win = rtVote.vote(pos, c, weight) ?: return false

        val changed = rtChars[pos] != win || rtLength <= pos
        rtChars[pos] = win
        rtFilled[pos] = true
        if (pos >= rtLength) rtLength = pos + 1
        return changed
    }

    private fun notifyListener() {
        listener?.onRdsData(buildRdsData())
    }

    /** Get current RDS data snapshot */
    fun getCurrentData(): RdsData = buildRdsData()

    private fun sanitize(text: String): String {
        return text
            .replace(Regex("[\\x00-\\x1F\\x7F]"), "")  // remove control chars
            .replace(Regex("\\s{3,}"), "  ")            // collapse 3+ spaces to 2
            .trim()
    }

    /**
     * True when the whole message has arrived: its end is known — a clean 0x0D
     * terminator, a segment cycle that has settled, or all 64 characters — and
     * no position in between is still missing.
     *
     * The end marker is what makes this meaningful. rtLength on its own only
     * records the highest position seen so far, so without it the first segment
     * to arrive looks like a complete four-character message.
     */
    private fun rtIsComplete(): Boolean {
        if (rtLength <= 0) return false
        if (!rtEndSeen && rtLength < rtChars.size) return false
        if (rtLength < rtMinLength) return false
        for (i in 0 until rtLength) if (!rtFilled[i]) return false
        return true
    }

    /**
     * The message up to its first missing character.
     *
     * A partly-received message used to be rendered straight out of rtChars,
     * where a position that has not arrived yet still holds a space — so a
     * hole is indistinguishable from a real space and the text reads as
     * damaged: 'BAKU R  RO  M 93' for what is actually 'BAKU RETRO FM 93.3'.
     * Cutting at the first hole instead shows a shorter message rather than a
     * wrong one, and it grows left to right as the segments land, which looks
     * like text arriving instead of text broken.
     *
     * Segments do not have to arrive in order, so if the very first one is
     * missing this is empty while later positions are known. The caller falls
     * back to the gapped form in that case: something imperfect beats nothing.
     */
    private fun rtHoleFreePrefix(): String {
        var end = 0
        while (end < rtLength && rtFilled[end]) end++
        return if (end <= 0) "" else sanitize(String(rtChars, 0, end))
    }

    /**
     * The best readable form of a message that has not finished arriving.
     *
     * Cutting at the first hole is right while the message is mostly holes, and
     * wrong once it is mostly there. Segments do not arrive in order, so a
     * single missing position near the front hides everything behind it: a
     * bench run at the error rate this tuner sees had 47 of the 52 characters
     * decoded and was displaying four of them, for the whole minute. That is
     * the "it writes the text very slowly" complaint — the text had arrived,
     * the display was refusing to show it.
     *
     * So once two thirds of the span is known, show the span. A word missing a
     * letter still reads; a message truncated to its first word does not. Below
     * that the gapped form really would be more hole than text, and the prefix
     * is the honest choice.
     */
    private fun rtPartial(): String {
        var last = -1
        var filled = 0
        for (i in 0 until rtLength) if (rtFilled[i]) { last = i; filled++ }
        if (last < 0) return ""
        val span = last + 1
        if (filled * 3 >= span * 2) return sanitize(String(rtChars, 0, span))
        return rtHoleFreePrefix()
    }

    private fun buildRdsData(): RdsData {
        val ps = sanitize(String(psChars))
        val building = sanitize(String(rtChars, 0, rtLength))
        if (rtIsComplete()) {
            rtDisplay = building
            rtEverComplete = true
        } else if (!rtEverComplete || rtGroupsSinceClear > RT_PARTIAL_AFTER) {
            // Nothing complete to fall back on, or this one is taking too long.
            rtDisplay = rtPartial().ifBlank { building }
        }
        val rt = rtDisplay
        val ptyName = if (ptyCode in PTY_NAMES.indices) PTY_NAMES[ptyCode] else ""
        return RdsData(
            ps = ps,
            rt = rt,
            pty = ptyCode,
            ptyName = ptyName,
            pi = piCode,
            tp = tpFlag,
            ta = taFlag,
            ms = msFlag,
            afList = afFrequencies.sorted(),
            hasData = ps.isNotBlank() || rt.isNotBlank(),
            psComplete = psFilled.all { it },
            // The buffer is complete AND that is what is being shown; after a
            // clear, rtDisplay still holds the previous finished message while
            // rtIsComplete() already describes the new one being assembled.
            rtComplete = rtIsComplete() && rt == building
        )
    }

    fun reset() {
        rdsLpfBufI = FloatArray(rdsLpfOrder * 2)
        rdsLpfBufQ = FloatArray(rdsLpfOrder * 2)
        rdsLpfIdx = 0
        rdsSharpBufI = FloatArray(rdsSharpOrder * 2)
        rdsSharpBufQ = FloatArray(rdsSharpOrder * 2)
        rdsSharpIdx = 0
        rdsDecimCounter = 0
        matchedBufI = FloatArray(matchedFilterOrder * 2)
        matchedBufQ = FloatArray(matchedFilterOrder * 2)
        matchedBufIdx = 0
        clockPhase = 0f
        prevRdsSample = 0f
        prevSymI = 0f
        prevSymQ = 0f
        prevSymValid = false
        bitBuffer = 0L
        bitCount = 0
        synced = false
        syncConfirmed = false
        syncConfirmGood = 0
        syncConfirmBad = 0
        blockIndex = 0
        goodBlocks = 0
        badBlocks = 0
        for (i in groupData.indices) { groupData[i] = 0; groupRaw[i] = 0 }
        for (i in groupValid.indices) groupValid[i] = false
        for (i in psChars.indices) psChars[i] = ' '
        psVote.clear()
        for (i in psFilled.indices) psFilled[i] = false
        for (i in rtChars.indices) rtChars[i] = ' '
        rtLength = 0
        rtConfirmedLength = 0
        for (i in rtFilled.indices) rtFilled[i] = false
        rtDisplay = ""; rtGroupsSinceClear = 0; rtEverComplete = false
        rtEndSeen = false; rtEndExplicit = false; rtMinLength = 0
        rtMaxSeg = -1; rtLastSeg = -1; rtWrapsAtMax = 0
        rtAbFlag = -1
        rtAbPendingFlag = -1; rtAbPendingCount = 0
        rtVote.clear()
        piCode = 0
        piConfirmCount = 0
        piCandidate = 0
        piCandidateCount = 0
        ptyCode = 0
        ptyCandidate = -1
        ptyCandidateCount = 0
        tpFlag = false
        taFlag = false
        taCandidate = false
        taCandidateCount = 0
        msFlag = false
        afFrequencies.clear()
        dataChanged = false
        carrierPhase = 0.0
        carrierInc = 2.0 * PI * 57000.0 / sampleRate
        fallbackCarrierPhase = 0.0
        totalBitsProcessed = 0L
        totalGoodBlocks = 0L
        totalBadBlocks = 0L
        totalGroupsDecoded = 0L
        berEma = -1f
        // Clear what the report reads, not just what the decoder reads.
        //
        // These counters reset here, but their StatusSnapshot mirrors are only
        // written from inside the group-decode path — so on a station with no
        // RDS nothing overwrites them and the report keeps showing the last
        // station that had any. A field report taken on 106.0 read
        // "synced=true groups=112 PS='RETRO FM' RT='BAKU RETRO  M 93.3MH'",
        // all of it from 93.3 MHz, forty seconds and eight stations earlier.
        // Every number in that line was true of a frequency the radio was no
        // longer tuned to, which is worse than having no line at all.
        com.fmradio.util.StatusSnapshot.let {
            it.rdsSynced = false
            it.rdsBerPct = 0f
            it.rdsBerLifetimePct = 0f
            it.rdsGroups = 0L
            it.rdsPs = ""
            it.rdsRt = ""
            it.rdsSearch = ""
        }
        searchHitA = 0; searchHitB = 0; searchHitC = 0
        searchHitCp = 0; searchHitD = 0; searchBits = 0
        lastStatsLogTime = 0L
    }
}
