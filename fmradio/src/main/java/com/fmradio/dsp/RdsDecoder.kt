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
class RdsDecoder(private val sampleRate: Int = 192000) {

    companion object {
        private const val TAG = "RdsDecoder"

        // Diagnostic logging interval
        private const val STATS_LOG_INTERVAL_MS = 5000L  // log every 5 seconds

        // RDS bit rate
        private const val RDS_BITRATE = 1187.5

        // Groups to wait before giving up on assembling a complete RadioText
        // message and showing the fragment gathered so far (~30 s at 11.4 groups/s).
        private const val RT_PARTIAL_AFTER = 350

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
        val hasData: Boolean = false
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

    // Decimation from 192 kHz to 24 kHz — more samples per bit for better clock recovery
    private val rdsDecimation = 8
    private val rdsRate = sampleRate / rdsDecimation  // 24000
    private var rdsDecimCounter = 0

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
    // PREVIOUS group's value while a block keeps failing, so the "received the
    // same pair twice" test would be satisfied by one stale reading repeated —
    // confirming wrong characters instead of filtering them.
    private val groupRaw = IntArray(4)
    private val groupValid = BooleanArray(4)  // true = this block passed CRC in current group
    // true = block passed CRC WITHOUT error correction. Single-bit correction
    // uses 26 error patterns out of 1024 syndromes, so a random multi-bit
    // error is mis-'corrected' 2.5% of the time. At the BER seen in the field
    // that made ~28% of all accepted blocks garbage. Decisions that corrupt
    // state globally (RT A/B toggle, end-of-text truncation) must only act on
    // clean blocks; corrected ones are still fine as confirmation evidence.
    private val groupClean = BooleanArray(4)

    // PS consistency checking — require 2 identical receptions before accepting
    private val psChars = CharArray(8) { ' ' }
    private val psPending = CharArray(8) { ' ' }
    private val psConfirmed = CharArray(8) { ' ' }
    private val psHitCount = IntArray(4)
    private val PS_CONFIRM_THRESHOLD = 2  // require 2 identical receptions to filter noise

    // RT data
    private val rtChars = CharArray(64) { ' ' }
    private val rtPending = CharArray(64) { ' ' }
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
    // Per-character confirmation for RadioText, mirroring what PS already does.
    // A character is only shown once the SAME code arrives twice at the same
    // position (or once from a CRC-clean block). Garbage from a mis-corrected
    // block practically never repeats identically, so hieroglyphs never reach
    // the screen.
    private val rtHitCount = IntArray(64)

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
     */
    @JvmOverloads
    fun setPilotFreq(pilotFreqRadPerSample: Double, locked: Boolean = true) {
        carrierInc = if (locked &&
            pilotFreqRadPerSample > 0.6 && pilotFreqRadPerSample < 0.65) {
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

            // Apply matched filter to both I and Q
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
                if (syncConfirmed && badBlocks > 40) {
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

        // TP (Traffic Programme) flag — bit 10 of block B
        tpFlag = (blockB and 0x0400) != 0

        // TA (Traffic Announcement) — bit 4 of block B in group 0
        if (groupType == 0) {
            val newTa = (blockB and 0x0010) != 0
            if (newTa != taFlag) {
                taFlag = newTa
                dataChanged = true
            }
            // M/S flag — bit 3 of block B in group 0
            msFlag = (blockB and 0x0008) != 0
        }

        val cValid = groupValid[2]
        val dValid = groupValid[3]
        when (groupType) {
            0 -> decodeGroup0(blockB, blockC, blockD, versionB, cValid, dValid)  // PS name + AF
            2 -> decodeGroup2(blockB, blockC, blockD, versionB, cValid, dValid)  // RadioText
        }

        // Notify listener
        if (dataChanged) {
            dataChanged = false
            notifyListener()
        }
    }

    // Group 0: Programme Service name (2 chars per group) + Alternative Frequencies
    // Uses consistency checking: character pair must be received identically twice
    private fun decodeGroup0(blockB: Int, blockC: Int, blockD: Int, versionB: Boolean,
                             cValid: Boolean, dValid: Boolean) {
        // Don't build the PS name until the PI code is confirmed: before PI
        // lock, a consistently-misaligned sync (possible during acquisition)
        // can repeat identical wrong characters and pass the consistency
        // check. PI confirms within ~1 s on air, so this costs nothing.
        if (piCode == 0) return

        val segmentAddr = blockB and 0x03
        val pos = segmentAddr * 2

        // PS chars from block D. At 89% BER on FC0013, requiring dValid means PS
        // never populates (block D rarely passes CRC), so a failed block is
        // still used — but its bits are read from groupRaw so that each
        // reception is genuinely independent, and it must then agree with a
        // third one before being shown.
        val dCode = if (dValid) blockD else groupRaw[3]
        val c1 = rdsCharToUnicode((dCode shr 8) and 0xFF)
        val c2 = rdsCharToUnicode(dCode and 0xFF)

        if (isValidRdsChar(c1) && isValidRdsChar(c2)) {
            // Consistency checking: require identical receptions before accepting
            if (psPending[pos] == c1 && psPending[pos + 1] == c2) {
                psHitCount[segmentAddr]++
            } else {
                psPending[pos] = c1
                psPending[pos + 1] = c2
                psHitCount[segmentAddr] = 1
            }

            val enough = when {
                dValid && groupClean[3] -> 1
                dValid -> PS_CONFIRM_THRESHOLD
                else -> PS_CONFIRM_THRESHOLD + 1
            }
            if (psHitCount[segmentAddr] >= enough) {
                if (psConfirmed[pos] != c1 || psConfirmed[pos + 1] != c2) {
                    psConfirmed[pos] = c1
                    psConfirmed[pos + 1] = c2
                    psChars[pos] = c1
                    psChars[pos + 1] = c2
                    dataChanged = true
                    Log.d(TAG, "PS update: ${String(psChars).trim()}")
                }
            }
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
                for (i in rtChars.indices) { rtChars[i] = ' '; rtPending[i] = '\u0000' }
                for (i in rtHitCount.indices) rtHitCount[i] = 0
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

        if (!versionB) {
            // RT has no consistency checking like PS, so corrupt blocks write
            // garbled chars directly → "hieroglyphs". Require at least block D
            // valid for the 2 chars from D. Block C chars only if cValid.
            val pos = segmentAddr * 4
            if (pos + 3 < rtChars.size) {
                val chars = intArrayOf(
                    if (cValid) (blockC shr 8) and 0xFF else -1,
                    if (cValid) blockC and 0xFF else -1,
                    if (dValid) (blockD shr 8) and 0xFF else -1,
                    if (dValid) blockD and 0xFF else -1
                )
                val clean = booleanArrayOf(
                    groupClean[2], groupClean[2], groupClean[3], groupClean[3]
                )
                var anyValid = false
                for (j in 0..3) {
                    if (chars[j] < 0) continue  // block not CRC-valid, skip this char
                    if (commitRtChar(pos + j, chars[j], clean[j])) anyValid = true
                    if (chars[j] == RDS_END_OF_TEXT && clean[j]) break
                }
                if (anyValid) dataChanged = true
            }
        } else {
            // Version B: 2 chars per segment from block D — require dValid
            if (!dValid) return
            val pos = segmentAddr * 2
            if (pos + 1 < rtChars.size) {
                val chars = intArrayOf((blockD shr 8) and 0xFF, blockD and 0xFF)
                var anyValid = false
                for (j in 0..1) {
                    if (commitRtChar(pos + j, chars[j], groupClean[3])) anyValid = true
                    if (chars[j] == RDS_END_OF_TEXT && groupClean[3]) return
                }
                if (anyValid) dataChanged = true
            }
        }
    }

    /**
     * Commit one RadioText character with confirmation.
     * Clean (uncorrected) blocks are trusted immediately; error-corrected ones
     * must deliver the same code twice at the same position before it is shown.
     * Returns true if the visible buffer changed.
     */
    private fun commitRtChar(pos: Int, code: Int, clean: Boolean): Boolean {
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
                    rtChars[k] = ' '; rtHitCount[k] = 0; rtFilled[k] = false
                }
                return true
            }
            return false
        }

        val c = rdsCharToUnicode(code)
        if (!isValidRdsChar(c)) return false

        val confirmed = if (clean) {
            true
        } else {
            if (rtPending[pos] == c) {
                rtHitCount[pos]++
                rtHitCount[pos] >= 2
            } else {
                rtPending[pos] = c
                rtHitCount[pos] = 1
                false
            }
        }
        if (!confirmed) return false

        rtPending[pos] = c
        val changed = rtChars[pos] != c || rtLength <= pos
        rtChars[pos] = c
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

    private fun buildRdsData(): RdsData {
        val ps = sanitize(String(psChars))
        val building = sanitize(String(rtChars, 0, rtLength))
        if (rtIsComplete()) {
            rtDisplay = building
            rtEverComplete = true
        } else if (!rtEverComplete || rtGroupsSinceClear > RT_PARTIAL_AFTER) {
            // Nothing complete to fall back on, or this one is taking too long.
            rtDisplay = building
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
            hasData = ps.isNotBlank() || rt.isNotBlank()
        )
    }

    fun reset() {
        rdsLpfBufI = FloatArray(rdsLpfOrder * 2)
        rdsLpfBufQ = FloatArray(rdsLpfOrder * 2)
        rdsLpfIdx = 0
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
        for (i in psPending.indices) psPending[i] = ' '
        for (i in psConfirmed.indices) psConfirmed[i] = ' '
        for (i in psHitCount.indices) psHitCount[i] = 0
        for (i in rtChars.indices) rtChars[i] = ' '
        for (i in rtPending.indices) rtPending[i] = ' '
        rtLength = 0
        rtConfirmedLength = 0
        for (i in rtFilled.indices) rtFilled[i] = false
        rtDisplay = ""; rtGroupsSinceClear = 0; rtEverComplete = false
        rtEndSeen = false; rtEndExplicit = false; rtMinLength = 0
        rtMaxSeg = -1; rtLastSeg = -1; rtWrapsAtMax = 0
        rtAbFlag = -1
        rtAbPendingFlag = -1; rtAbPendingCount = 0
        for (i in rtHitCount.indices) rtHitCount[i] = 0
        piCode = 0
        piConfirmCount = 0
        piCandidate = 0
        piCandidateCount = 0
        ptyCode = 0
        ptyCandidate = -1
        ptyCandidateCount = 0
        tpFlag = false
        taFlag = false
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
        lastStatsLogTime = 0L
    }
}
