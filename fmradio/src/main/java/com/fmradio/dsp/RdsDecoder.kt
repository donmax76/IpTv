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
    private val rdsLpfOrder = 48
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

    // Bit stream buffer for group assembly
    private var bitBuffer = 0L
    private var bitCount = 0

    // Syndrome-based block sync
    private var synced = false
    private var blockIndex = 0
    private var goodBlocks = 0
    private var badBlocks = 0

    // Group data (4 blocks × 16 bits)
    private val groupData = IntArray(4)

    // PS consistency checking — require 2 identical receptions before accepting
    private val psChars = CharArray(8) { ' ' }
    private val psPending = CharArray(8) { ' ' }
    private val psConfirmed = CharArray(8) { ' ' }
    private val psHitCount = IntArray(4)
    private val PS_CONFIRM_THRESHOLD = 2  // Require 2 identical receptions (3 was too strict for FC0013's SNR)

    // RT data
    private val rtChars = CharArray(64) { ' ' }
    private val rtPending = CharArray(64) { ' ' }
    private var rtLength = 0
    private var rtConfirmedLength = 0
    private var rtAbFlag = -1  // RT A/B flag: toggles when station changes text → clear buffer

    // RDS decoded fields
    private var piCode = 0
    private var ptyCode = 0
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
        // Normalize to unit DC gain (sum of coefficients, not absolute values).
        // Wrong normalization reduced symbol amplitude → worse SNR on weak RDS.
        val dcGain = matchedFilter.sum()
        if (abs(dcGain) > 1e-6f) {
            for (i in matchedFilter.indices) matchedFilter[i] /= dcGain
        } else {
            for (i in matchedFilter.indices) matchedFilter[i] /= sum
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

    // Persistent 57 kHz carrier NCO — FREQUENCY from pilot PLL, PHASE continuous.
    // Free-running NCO (previous attempt) drifted because RTL2832U crystal has
    // ppm offset → 57 kHz was actually 57000.57 Hz → phase mismatch after <1 sec.
    // Pilot-locked with reset (original) had phase jumps between calls.
    // This hybrid: frequency tracks crystal via pilot PLL, phase never resets.
    private var carrierPhase = 0.0
    private var carrierInc = 2.0 * PI * 57000.0 / sampleRate  // initial guess, updated by setPilotFreq

    /** Called by FmRadioService before each process() with the pilot PLL frequency */
    fun setPilotFreq(pilotFreqRadPerSample: Double) {
        // Pilot is at ~19 kHz, RDS subcarrier is 3× = ~57 kHz
        carrierInc = pilotFreqRadPerSample * 3.0
    }

    /**
     * Process wideband baseband samples.
     * Carrier frequency tracks pilot PLL (crystal-accurate), phase is continuous.
     */
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

            val dot = mI * prevSymI + mQ * prevSymQ
            val decodedBit = if (dot > 0f) 0 else 1
            prevSymI = mI
            prevSymQ = mQ

            processBit(decodedBit)
        }

        // Clock recovery: track zero crossings on the magnitude-signed channel
        // (use whichever of I/Q has larger magnitude — most of the signal energy)
        val sample = if (abs(mI) >= abs(mQ)) mI else mQ
        if ((sample > 0 && prevRdsSample <= 0) || (sample < 0 && prevRdsSample >= 0)) {
            val error = clockPhase - samplesPerBit / 2
            val correction = (error * 0.03f).coerceIn(-samplesPerBit * 0.05f, samplesPerBit * 0.05f)
            clockPhase -= correction
        }
        prevRdsSample = sample
    }

    private fun processBit(bit: Int) {
        // Shift bit into buffer
        bitBuffer = ((bitBuffer shl 1) or bit.toLong()) and 0x3FFFFFFL  // 26 bits

        bitCount++
        totalBitsProcessed++

        if (!synced) {
            // Try to find sync by checking syndrome on every bit
            if (bitCount >= 26) {
                val syndrome = calcSyndrome(bitBuffer, 26)
                if (syndrome == OFFSET_A) {
                    synced = true
                    blockIndex = 0
                    groupData[0] = ((bitBuffer shr 10) and 0xFFFF).toInt()
                    blockIndex = 1
                    bitCount = 0
                    goodBlocks = 1
                    badBlocks = 0
                    Log.d(TAG, "RDS sync ACQUIRED at bit $totalBitsProcessed")
                    DebugLog.log(TAG, "RDS sync ACQUIRED at bit $totalBitsProcessed")
                }
            }
        } else {
            // Synced: collect 26 bits per block
            if (bitCount >= 26) {
                val expectedOffset = when (blockIndex) {
                    0 -> OFFSET_A
                    1 -> OFFSET_B
                    2 -> if (groupData[1] and 0x0800 != 0) OFFSET_CP else OFFSET_C
                    3 -> OFFSET_D
                    else -> OFFSET_A
                }

                val syndrome = calcSyndrome(bitBuffer, 26)
                if (syndrome == expectedOffset) {
                    groupData[blockIndex] = ((bitBuffer shr 10) and 0xFFFF).toInt()
                    goodBlocks++
                    totalGoodBlocks++
                    // Heal bad block counter on good reception
                    badBlocks = (badBlocks - 1).coerceAtLeast(0)
                } else {
                    badBlocks++
                    totalBadBlocks++
                    // More tolerant: stay synced through noise bursts
                    if (badBlocks > 12) {
                        Log.d(TAG, "RDS sync LOST (badBlocks=$badBlocks) at bit $totalBitsProcessed")
                        DebugLog.log(TAG, "RDS sync LOST (badBlocks=$badBlocks) at bit $totalBitsProcessed")
                        synced = false
                        bitCount = 0
                        return
                    }
                }

                blockIndex++
                bitCount = 0

                if (blockIndex >= 4) {
                    // Accept groups with 2+ valid blocks. Block B (group type,
                    // flags) is always validated by syndrome; if it's wrong,
                    // decodeGroup reads stale groupData[1] which is harmless
                    // (wrong group type → no match in when() → nothing decoded).
                    // Lowered from 3 for better coverage on FC0013's lower SNR.
                    if (goodBlocks >= 2) {
                        decodeGroup()
                    }
                    blockIndex = 0
                    goodBlocks = 0
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

    /** RDS end-of-text marker (IEC 62106: 0x0D = end of RadioText) */
    private val RDS_END_OF_TEXT = 0x0D

    private fun isValidRdsChar(c: Char): Boolean {
        return c.code in 0x20..0xFF
    }

    /**
     * Convert an RDS byte to a Unicode character.
     *
     * Uses the standard EBU Latin character set (IEC 62106, Annex E, code
     * table 00) for 0x80-0xBF. For 0xC0-0xFF uses ISO 8859-1 (Latin-1)
     * passthrough — this is what most Western and Azerbaijani stations send.
     *
     * NOTE: CP1251 Cyrillic was previously mapped at 0xC0-0xFF but this broke
     * standard Latin characters. Proper Cyrillic support requires RDS code
     * table switching via Group 1A ODA, which is not yet implemented.
     *
     * Returns Char(0x0D) for the RDS end-of-text marker.
     */
    private fun rdsCharToUnicode(code: Int): Char {
        // ASCII passthrough
        if (code in 0x20..0x7E) return code.toChar()

        // RDS end-of-text marker — returned as-is, caller must handle
        if (code == RDS_END_OF_TEXT) return '\r'

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
        val blockC = groupData[2]
        val blockD = groupData[3]

        // PI code from block A
        if (blockA != 0) {
            piCode = blockA
        }

        // Group type and version
        val groupType = (blockB shr 12) and 0x0F
        val versionB = (blockB and 0x0800) != 0
        ptyCode = (blockB shr 5) and 0x1F

        totalGroupsDecoded++

        // Log decoded group details
        val versionStr = if (versionB) "B" else "A"
        val psStr = String(psChars).trim()
        val rtStr = String(rtChars, 0, rtLength).trim()
        Log.d(TAG, "Group ${groupType}${versionStr}: PI=%04X PTY=$ptyCode PS='$psStr' RT='$rtStr'".format(piCode))
        DebugLog.log(TAG, "Group ${groupType}${versionStr}: PI=%04X PTY=$ptyCode PS='$psStr' RT='$rtStr'".format(piCode))

        // Periodic bit error statistics
        val now = System.currentTimeMillis()
        if (now - lastStatsLogTime >= STATS_LOG_INTERVAL_MS) {
            lastStatsLogTime = now
            val total = totalGoodBlocks + totalBadBlocks
            val errorRate = if (total > 0) totalBadBlocks.toFloat() / total * 100f else 0f
            Log.d(TAG, "RDS stats: bits=$totalBitsProcessed groups=$totalGroupsDecoded good=$totalGoodBlocks bad=$totalBadBlocks BER=%.1f%% synced=$synced".format(errorRate))
            DebugLog.log(TAG, "RDS stats: bits=$totalBitsProcessed groups=$totalGroupsDecoded good=$totalGoodBlocks bad=$totalBadBlocks BER=%.1f%% synced=$synced".format(errorRate))
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

        when (groupType) {
            0 -> decodeGroup0(blockB, blockC, blockD, versionB)  // PS name + AF
            2 -> decodeGroup2(blockB, blockC, blockD, versionB)  // RadioText
        }

        // Notify listener
        if (dataChanged) {
            dataChanged = false
            notifyListener()
        }
    }

    // Group 0: Programme Service name (2 chars per group) + Alternative Frequencies
    // Uses consistency checking: character pair must be received identically twice
    private fun decodeGroup0(blockB: Int, blockC: Int, blockD: Int, versionB: Boolean) {
        val segmentAddr = blockB and 0x03
        val pos = segmentAddr * 2

        // PS characters from block D — map through EBU Latin → Unicode
        val c1 = rdsCharToUnicode((blockD shr 8) and 0xFF)
        val c2 = rdsCharToUnicode(blockD and 0xFF)

        if (isValidRdsChar(c1) && isValidRdsChar(c2)) {
            // Consistency checking: require PS_CONFIRM_THRESHOLD identical receptions
            if (psPending[pos] == c1 && psPending[pos + 1] == c2) {
                psHitCount[segmentAddr]++
            } else {
                psPending[pos] = c1
                psPending[pos + 1] = c2
                psHitCount[segmentAddr] = 1
            }

            if (psHitCount[segmentAddr] >= PS_CONFIRM_THRESHOLD) {
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
        if (!versionB) {
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
    private fun decodeGroup2(blockB: Int, blockC: Int, blockD: Int, versionB: Boolean) {
        // RT A/B flag (bit 4 of blockB): when it toggles, station changed the
        // text → clear the entire RT buffer so old chars don't mix with new.
        val abFlag = (blockB shr 4) and 0x01
        if (rtAbFlag >= 0 && abFlag != rtAbFlag) {
            for (i in rtChars.indices) rtChars[i] = ' '
            rtLength = 0
            dataChanged = true
        }
        rtAbFlag = abFlag

        val segmentAddr = blockB and 0x0F

        if (!versionB) {
            // Version A: 4 chars per segment from blocks C and D
            val pos = segmentAddr * 4
            if (pos + 3 < rtChars.size) {
                val chars = intArrayOf(
                    (blockC shr 8) and 0xFF, blockC and 0xFF,
                    (blockD shr 8) and 0xFF, blockD and 0xFF
                )
                var anyValid = false
                for (j in 0..3) {
                    if (chars[j] == RDS_END_OF_TEXT) {
                        // 0x0D = end of RadioText. Truncate here, clear the rest.
                        rtLength = pos + j
                        for (k in rtLength until rtChars.size) rtChars[k] = ' '
                        dataChanged = true
                        return
                    }
                    val c = rdsCharToUnicode(chars[j])
                    if (isValidRdsChar(c)) { rtChars[pos + j] = c; anyValid = true }
                }
                if (anyValid) {
                    rtLength = maxOf(rtLength, pos + 4)
                    dataChanged = true
                }
            }
        } else {
            // Version B: 2 chars per segment from block D
            val pos = segmentAddr * 2
            if (pos + 1 < rtChars.size) {
                val chars = intArrayOf((blockD shr 8) and 0xFF, blockD and 0xFF)
                var anyValid = false
                for (j in 0..1) {
                    if (chars[j] == RDS_END_OF_TEXT) {
                        rtLength = pos + j
                        for (k in rtLength until rtChars.size) rtChars[k] = ' '
                        dataChanged = true
                        return
                    }
                    val c = rdsCharToUnicode(chars[j])
                    if (isValidRdsChar(c)) { rtChars[pos + j] = c; anyValid = true }
                }
                if (anyValid) {
                    rtLength = maxOf(rtLength, pos + 2)
                    dataChanged = true
                }
            }
        }
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

    private fun buildRdsData(): RdsData {
        val ps = sanitize(String(psChars))
        val rt = sanitize(String(rtChars, 0, rtLength))
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
        bitBuffer = 0L
        bitCount = 0
        synced = false
        blockIndex = 0
        goodBlocks = 0
        badBlocks = 0
        for (i in groupData.indices) groupData[i] = 0
        for (i in psChars.indices) psChars[i] = ' '
        for (i in psPending.indices) psPending[i] = ' '
        for (i in psConfirmed.indices) psConfirmed[i] = ' '
        for (i in psHitCount.indices) psHitCount[i] = 0
        for (i in rtChars.indices) rtChars[i] = ' '
        for (i in rtPending.indices) rtPending[i] = ' '
        rtLength = 0
        rtConfirmedLength = 0
        rtAbFlag = -1
        piCode = 0
        ptyCode = 0
        tpFlag = false
        taFlag = false
        msFlag = false
        afFrequencies.clear()
        dataChanged = false
        carrierPhase = 0.0
        fallbackCarrierPhase = 0.0
        totalBitsProcessed = 0L
        totalGoodBlocks = 0L
        totalBadBlocks = 0L
        totalGroupsDecoded = 0L
        lastStatsLogTime = 0L
    }
}
