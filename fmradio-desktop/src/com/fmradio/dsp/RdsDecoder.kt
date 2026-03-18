package com.fmradio.dsp

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
        private const val RDS_BITRATE = 1187.5
        private const val OFFSET_A = 0x0FC
        private const val OFFSET_B = 0x198
        private const val OFFSET_C = 0x168
        private const val OFFSET_CP = 0x350
        private const val OFFSET_D = 0x1B4
        private const val CRC_POLY = 0x1B9

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

    data class RdsData(
        val ps: String = "", val rt: String = "", val pty: Int = 0,
        val ptyName: String = "", val pi: Int = 0, val tp: Boolean = false,
        val ta: Boolean = false, val ms: Boolean = false,
        val afList: List<Float> = emptyList(), val hasData: Boolean = false
    )

    interface RdsListener { fun onRdsData(data: RdsData) }
    var listener: RdsListener? = null

    // RDS bandpass filter (after carrier mix-down)
    // Blackman-Harris window for better stopband rejection
    private val rdsLpfOrder = 96
    private val rdsLpfCoeffs: FloatArray
    private var rdsLpfBufI = FloatArray(rdsLpfOrder)
    private var rdsLpfBufQ = FloatArray(rdsLpfOrder)
    private var rdsLpfIdx = 0

    // Decimation: 192 kHz → 24 kHz
    private val rdsDecimation = 8
    private val rdsRate = sampleRate / rdsDecimation  // 24000
    private var rdsDecimCounter = 0

    // Matched filter for RDS symbol shaping (root raised cosine-like)
    private val matchedFilterOrder = 20
    private val matchedFilter: FloatArray
    private var matchedBuf = FloatArray(matchedFilterOrder)
    private var matchedBufIdx = 0

    // Bit clock recovery (PLL-based, more robust than simple counter)
    private val samplesPerBit = rdsRate.toFloat() / RDS_BITRATE.toFloat()  // ~20.2
    private var clockPhase = 0f
    private var prevRdsSample = 0f
    private var prevBit = 0

    // Block sync
    private var bitBuffer = 0L
    private var bitCount = 0
    private var synced = false
    private var blockIndex = 0
    private var goodBlocks = 0
    private var badBlocks = 0
    private val groupData = IntArray(4)

    // PS consistency checking
    private val psChars = CharArray(8) { ' ' }
    private val psPending = CharArray(8) { ' ' }
    private val psConfirmed = CharArray(8) { ' ' }
    private val psHitCount = IntArray(4)
    private val PS_CONFIRM_THRESHOLD = 2

    // RT data
    private val rtChars = CharArray(64) { ' ' }
    private val rtPending = CharArray(64) { ' ' }
    private var rtLength = 0
    private var rtConfirmedLength = 0

    // RDS fields
    private var piCode = 0
    private var ptyCode = 0
    private var tpFlag = false
    private var taFlag = false
    private var msFlag = false
    private val afFrequencies = mutableSetOf<Float>()
    @Volatile private var dataChanged = false

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
            // Approximation of RRC pulse shape
            matchedFilter[i] = if (n == 0) 1f
            else sin(PI.toFloat() * n / (samplesPerBit / 2)) / (PI.toFloat() * n)
            // Hann window
            val w = 0.5f * (1f - cos(2f * PI.toFloat() * i / (matchedFilterOrder - 1)))
            matchedFilter[i] *= w
            sum += abs(matchedFilter[i])
        }
        for (i in matchedFilter.indices) matchedFilter[i] /= sum
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
            // Blackman-Harris window (same as main demodulator)
            val w = i.toFloat() / (order - 1).toFloat()
            val a0 = 0.35875f; val a1 = 0.48829f; val a2 = 0.14128f; val a3 = 0.01168f
            coeffs[i] *= a0 - a1 * cos(2 * PI.toFloat() * w) +
                    a2 * cos(4 * PI.toFloat() * w) - a3 * cos(6 * PI.toFloat() * w)
            sum += coeffs[i]
        }
        for (i in coeffs.indices) coeffs[i] /= sum
        return coeffs
    }

    /**
     * Process baseband samples with pilot-locked carrier.
     * @param baseband Raw FM baseband at 192 kHz
     * @param pilotPhase Current pilot PLL phase from FmDemodulator (19 kHz, radians)
     */
    fun process(baseband: FloatArray, pilotPhase: Double) {
        // Calculate carrier phase increment per sample from pilot
        // RDS carrier = 3 × pilot frequency (57 kHz = 3 × 19 kHz)
        val pilotInc = 2.0 * PI * 19000.0 / sampleRate
        // Estimate pilot phase at start of this buffer
        var rdsCarrierPhase = pilotPhase * 3.0

        for (idx in baseband.indices) {
            val sample = baseband[idx]

            // Generate 57 kHz carrier from 3× pilot phase
            val cosC = cos(rdsCarrierPhase).toFloat()
            val sinC = sin(rdsCarrierPhase).toFloat()
            rdsCarrierPhase += pilotInc * 3.0
            if (rdsCarrierPhase > 2 * PI) rdsCarrierPhase -= 2 * PI

            // Mix down to baseband
            rdsLpfBufI[rdsLpfIdx] = sample * cosC
            rdsLpfBufQ[rdsLpfIdx] = sample * sinC
            rdsLpfIdx = (rdsLpfIdx + 1) % rdsLpfOrder

            rdsDecimCounter++
            if (rdsDecimCounter < rdsDecimation) continue
            rdsDecimCounter = 0

            // Apply RDS lowpass filter
            var filtI = 0f
            for (j in 0 until rdsLpfOrder) {
                val jIdx = (rdsLpfIdx - 1 - j + rdsLpfOrder) % rdsLpfOrder
                filtI += rdsLpfBufI[jIdx] * rdsLpfCoeffs[j]
            }

            // Apply matched filter for better symbol detection
            matchedBuf[matchedBufIdx] = filtI
            matchedBufIdx = (matchedBufIdx + 1) % matchedFilterOrder
            var matched = 0f
            for (j in 0 until matchedFilterOrder) {
                val jIdx = (matchedBufIdx - 1 - j + matchedFilterOrder) % matchedFilterOrder
                matched += matchedBuf[jIdx] * matchedFilter[j]
            }

            processRdsSample(matched)
        }
    }

    private fun processRdsSample(sample: Float) {
        clockPhase += 1f
        if (clockPhase >= samplesPerBit) {
            clockPhase -= samplesPerBit
            val bit = if (sample > 0) 1 else 0
            val decodedBit = bit xor prevBit  // differential decoding
            prevBit = bit
            processBit(decodedBit)
        }
        // Clock recovery: adjust phase on zero crossings
        if ((sample > 0 && prevRdsSample <= 0) || (sample < 0 && prevRdsSample >= 0)) {
            val error = clockPhase - samplesPerBit / 2
            val correction = (error * 0.12f).coerceIn(-samplesPerBit * 0.2f, samplesPerBit * 0.2f)
            clockPhase -= correction
        }
        prevRdsSample = sample
    }

    private fun processBit(bit: Int) {
        bitBuffer = ((bitBuffer shl 1) or bit.toLong()) and 0x3FFFFFFL
        bitCount++
        if (!synced) {
            if (bitCount >= 26) {
                val syndrome = calcSyndrome(bitBuffer, 26)
                if (syndrome == OFFSET_A) {
                    synced = true; blockIndex = 0
                    groupData[0] = ((bitBuffer shr 10) and 0xFFFF).toInt()
                    blockIndex = 1; bitCount = 0; goodBlocks = 1; badBlocks = 0
                }
            }
        } else {
            if (bitCount >= 26) {
                val expectedOffset = when (blockIndex) {
                    0 -> OFFSET_A; 1 -> OFFSET_B
                    2 -> if (groupData[1] and 0x0800 != 0) OFFSET_CP else OFFSET_C
                    3 -> OFFSET_D; else -> OFFSET_A
                }
                val syndrome = calcSyndrome(bitBuffer, 26)
                if (syndrome == expectedOffset) {
                    groupData[blockIndex] = ((bitBuffer shr 10) and 0xFFFF).toInt()
                    goodBlocks++
                    badBlocks = (badBlocks - 1).coerceAtLeast(0)
                } else {
                    badBlocks++
                    if (badBlocks > 20) { synced = false; bitCount = 0; return }
                }
                blockIndex++; bitCount = 0
                if (blockIndex >= 4) {
                    if (goodBlocks >= 3) decodeGroup()
                    blockIndex = 0; goodBlocks = 0
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
            if (fb != 0) reg = reg xor CRC_POLY
        }
        return reg
    }

    private fun isValidRdsChar(c: Char): Boolean {
        return c.code in 0x20..0x7E
    }

    private fun decodeGroup() {
        val blockA = groupData[0]; val blockB = groupData[1]
        val blockC = groupData[2]; val blockD = groupData[3]
        if (blockA != 0) piCode = blockA
        val groupType = (blockB shr 12) and 0x0F
        val versionB = (blockB and 0x0800) != 0
        ptyCode = (blockB shr 5) and 0x1F
        tpFlag = (blockB and 0x0400) != 0
        if (groupType == 0) {
            val newTa = (blockB and 0x0010) != 0
            if (newTa != taFlag) { taFlag = newTa; dataChanged = true }
            msFlag = (blockB and 0x0008) != 0
        }
        when (groupType) {
            0 -> decodeGroup0(blockB, blockC, blockD, versionB)
            2 -> decodeGroup2(blockB, blockC, blockD, versionB)
        }
        if (dataChanged) { dataChanged = false; notifyListener() }
    }

    private fun decodeGroup0(blockB: Int, blockC: Int, blockD: Int, versionB: Boolean) {
        val pos = (blockB and 0x03)
        val charPos = pos * 2
        val c1 = ((blockD shr 8) and 0xFF).toChar()
        val c2 = (blockD and 0xFF).toChar()

        if (isValidRdsChar(c1) && isValidRdsChar(c2)) {
            if (psPending[charPos] == c1 && psPending[charPos + 1] == c2) {
                psHitCount[pos]++
            } else {
                psPending[charPos] = c1
                psPending[charPos + 1] = c2
                psHitCount[pos] = 1
            }

            if (psHitCount[pos] >= PS_CONFIRM_THRESHOLD) {
                if (psConfirmed[charPos] != c1 || psConfirmed[charPos + 1] != c2) {
                    psConfirmed[charPos] = c1
                    psConfirmed[charPos + 1] = c2
                    psChars[charPos] = c1
                    psChars[charPos + 1] = c2
                    dataChanged = true
                }
            }
        }

        if (!versionB) {
            decodeAfCode((blockC shr 8) and 0xFF)
            decodeAfCode(blockC and 0xFF)
        }
    }

    private fun decodeAfCode(code: Int) {
        if (code in 1..204) {
            val freqMHz = 87.5f + code * 0.1f
            if (afFrequencies.add(freqMHz)) dataChanged = true
        }
    }

    private fun decodeGroup2(blockB: Int, blockC: Int, blockD: Int, versionB: Boolean) {
        val segmentAddr = blockB and 0x0F
        if (!versionB) {
            val pos = segmentAddr * 4
            if (pos + 3 < rtChars.size) {
                val c1 = ((blockC shr 8) and 0xFF).toChar(); val c2 = (blockC and 0xFF).toChar()
                val c3 = ((blockD shr 8) and 0xFF).toChar(); val c4 = (blockD and 0xFF).toChar()
                var anyValid = false
                if (isValidRdsChar(c1)) { rtChars[pos] = c1; anyValid = true }
                if (isValidRdsChar(c2)) { rtChars[pos + 1] = c2; anyValid = true }
                if (isValidRdsChar(c3)) { rtChars[pos + 2] = c3; anyValid = true }
                if (isValidRdsChar(c4)) { rtChars[pos + 3] = c4; anyValid = true }
                if (anyValid) {
                    rtLength = maxOf(rtLength, pos + 4)
                    dataChanged = true
                }
            }
        } else {
            val pos = segmentAddr * 2
            if (pos + 1 < rtChars.size) {
                val c1 = ((blockD shr 8) and 0xFF).toChar(); val c2 = (blockD and 0xFF).toChar()
                var anyValid = false
                if (isValidRdsChar(c1)) { rtChars[pos] = c1; anyValid = true }
                if (isValidRdsChar(c2)) { rtChars[pos + 1] = c2; anyValid = true }
                if (anyValid) {
                    rtLength = maxOf(rtLength, pos + 2)
                    dataChanged = true
                }
            }
        }
    }

    private fun notifyListener() { listener?.onRdsData(buildRdsData()) }
    fun getCurrentData(): RdsData = buildRdsData()

    private fun buildRdsData(): RdsData {
        val ps = String(psChars).trim()
        val rt = String(rtChars, 0, rtLength).trim()
        val ptyName = if (ptyCode in PTY_NAMES.indices) PTY_NAMES[ptyCode] else ""
        return RdsData(ps, rt, ptyCode, ptyName, piCode, tpFlag, taFlag, msFlag,
            afFrequencies.sorted(), ps.isNotBlank() || rt.isNotBlank())
    }

    fun reset() {
        rdsLpfBufI = FloatArray(rdsLpfOrder); rdsLpfBufQ = FloatArray(rdsLpfOrder)
        rdsLpfIdx = 0; rdsDecimCounter = 0
        matchedBuf = FloatArray(matchedFilterOrder); matchedBufIdx = 0
        clockPhase = 0f; prevRdsSample = 0f; prevBit = 0
        bitBuffer = 0L; bitCount = 0; synced = false; blockIndex = 0; goodBlocks = 0; badBlocks = 0
        for (i in groupData.indices) groupData[i] = 0
        for (i in psChars.indices) psChars[i] = ' '
        for (i in psPending.indices) psPending[i] = ' '
        for (i in psConfirmed.indices) psConfirmed[i] = ' '
        for (i in psHitCount.indices) psHitCount[i] = 0
        for (i in rtChars.indices) rtChars[i] = ' '
        for (i in rtPending.indices) rtPending[i] = ' '
        rtLength = 0; rtConfirmedLength = 0
        piCode = 0; ptyCode = 0; tpFlag = false; taFlag = false; msFlag = false
        afFrequencies.clear(); dataChanged = false
        fallbackCarrierPhase = 0.0
    }
}
