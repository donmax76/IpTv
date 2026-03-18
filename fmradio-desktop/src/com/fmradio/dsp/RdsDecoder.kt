package com.fmradio.dsp

import kotlin.math.*

/**
 * RDS (Radio Data System) decoder - Desktop version (no Android dependencies).
 *
 * Extracts PS (station name), RT (radio text), PTY (program type),
 * AF (alternate frequencies), TA/TP (traffic) from FM broadcast.
 */
class RdsDecoder(private val sampleRate: Int = 192000) {

    companion object {
        private const val RDS_CARRIER_FREQ = 57000.0
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

    // 57 kHz carrier recovery
    private var carrierPhase = 0.0
    private val carrierIncrement = 2.0 * PI * RDS_CARRIER_FREQ / sampleRate

    // RDS bandpass filter (wider, higher order for better selectivity)
    private val rdsLpfOrder = 96
    private val rdsLpfCoeffs: FloatArray
    private var rdsLpfBufI = FloatArray(rdsLpfOrder)
    private var rdsLpfBufQ = FloatArray(rdsLpfOrder)
    private var rdsLpfIdx = 0

    // Decimation to lower rate for bit recovery
    private val rdsDecimation = 8
    private val rdsRate = sampleRate / rdsDecimation
    private var rdsDecimCounter = 0

    // Bit clock recovery
    private val samplesPerBit = rdsRate.toFloat() / RDS_BITRATE.toFloat()
    private var clockPhase = 0f
    private var prevRdsSample = 0f

    // Differential decoding
    private var prevBit = 0

    // Block sync
    private var bitBuffer = 0L
    private var bitCount = 0
    private var synced = false
    private var blockIndex = 0
    private var goodBlocks = 0
    private var badBlocks = 0
    private val groupData = IntArray(4)

    // RDS data fields
    private val psChars = CharArray(8) { ' ' }
    private val rtChars = CharArray(64) { ' ' }
    private var rtLength = 0
    private var piCode = 0
    private var ptyCode = 0
    private var tpFlag = false
    private var taFlag = false
    private var msFlag = false
    private val afFrequencies = mutableSetOf<Float>()
    @Volatile private var dataChanged = false

    // Signal level tracking for adaptive thresholds
    private var rdsSignalLevel = 0f

    init {
        // Wider filter: 3 kHz cutoff for better RDS signal capture
        val cutoff = 3000f / (sampleRate.toFloat() / 2f)
        rdsLpfCoeffs = designRdsFilter(rdsLpfOrder, cutoff)
    }

    private fun designRdsFilter(order: Int, normalizedCutoff: Float): FloatArray {
        val coeffs = FloatArray(order); val mid = order / 2; var sum = 0f
        for (i in 0 until order) {
            val n = i - mid
            coeffs[i] = if (n == 0) normalizedCutoff
            else sin(PI.toFloat() * normalizedCutoff * n) / (PI.toFloat() * n)
            // Blackman window (better stopband than Hamming)
            val w = i.toFloat() / (order - 1).toFloat()
            coeffs[i] *= 0.42f - 0.5f * cos(2 * PI.toFloat() * w) + 0.08f * cos(4 * PI.toFloat() * w)
            sum += coeffs[i]
        }
        for (i in coeffs.indices) coeffs[i] /= sum
        return coeffs
    }

    fun process(baseband: FloatArray) {
        for (sample in baseband) {
            val cosC = cos(carrierPhase).toFloat()
            val sinC = sin(carrierPhase).toFloat()
            carrierPhase += carrierIncrement
            if (carrierPhase > 2 * PI) carrierPhase -= 2 * PI

            // Mix down from 57 kHz
            rdsLpfBufI[rdsLpfIdx] = sample * cosC
            rdsLpfBufQ[rdsLpfIdx] = sample * sinC
            rdsLpfIdx = (rdsLpfIdx + 1) % rdsLpfOrder

            rdsDecimCounter++
            if (rdsDecimCounter < rdsDecimation) continue
            rdsDecimCounter = 0

            // Apply LPF
            var filtI = 0f
            var filtQ = 0f
            for (j in 0 until rdsLpfOrder) {
                val idx = (rdsLpfIdx - 1 - j + rdsLpfOrder) % rdsLpfOrder
                filtI += rdsLpfBufI[idx] * rdsLpfCoeffs[j]
                filtQ += rdsLpfBufQ[idx] * rdsLpfCoeffs[j]
            }

            // Track signal level
            val mag = sqrt(filtI * filtI + filtQ * filtQ)
            rdsSignalLevel = 0.999f * rdsSignalLevel + 0.001f * mag

            // Use I channel for BPSK demodulation
            processRdsSample(filtI)
        }
    }

    private fun processRdsSample(sample: Float) {
        clockPhase += 1f
        if (clockPhase >= samplesPerBit) {
            clockPhase -= samplesPerBit
            val bit = if (sample > 0) 1 else 0
            val decodedBit = bit xor prevBit  // NRZI differential decoding
            prevBit = bit
            processBit(decodedBit)
        }

        // Clock recovery: adjust phase on zero crossings
        if ((sample > 0 && prevRdsSample <= 0) || (sample < 0 && prevRdsSample >= 0)) {
            val error = clockPhase - samplesPerBit / 2
            // Proportional correction with clamped gain
            val correction = (error * 0.15f).coerceIn(-samplesPerBit * 0.25f, samplesPerBit * 0.25f)
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
                    badBlocks = (badBlocks - 1).coerceAtLeast(0)  // reward good blocks
                } else {
                    badBlocks++
                    if (badBlocks > 20) {  // more tolerant before losing sync
                        synced = false; bitCount = 0; return
                    }
                }
                blockIndex++; bitCount = 0
                if (blockIndex >= 4) {
                    if (goodBlocks >= 2) decodeGroup()
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
        val pos = (blockB and 0x03) * 2
        val c1 = ((blockD shr 8) and 0xFF).toChar()
        val c2 = (blockD and 0xFF).toChar()
        if (c1.code in 0x20..0x7E && c2.code in 0x20..0x7E) {
            if (psChars[pos] != c1 || psChars[pos + 1] != c2) {
                psChars[pos] = c1; psChars[pos + 1] = c2; dataChanged = true
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
                if (c1.code in 0x20..0x7E) rtChars[pos] = c1
                if (c2.code in 0x20..0x7E) rtChars[pos + 1] = c2
                if (c3.code in 0x20..0x7E) rtChars[pos + 2] = c3
                if (c4.code in 0x20..0x7E) rtChars[pos + 3] = c4
                rtLength = maxOf(rtLength, pos + 4); dataChanged = true
            }
        } else {
            val pos = segmentAddr * 2
            if (pos + 1 < rtChars.size) {
                val c1 = ((blockD shr 8) and 0xFF).toChar(); val c2 = (blockD and 0xFF).toChar()
                if (c1.code in 0x20..0x7E) rtChars[pos] = c1
                if (c2.code in 0x20..0x7E) rtChars[pos + 1] = c2
                rtLength = maxOf(rtLength, pos + 2); dataChanged = true
            }
        }
    }

    private fun notifyListener() { listener?.onRdsData(buildRdsData()) }
    fun getCurrentData(): RdsData = buildRdsData()

    private fun buildRdsData(): RdsData {
        val ps = String(psChars).trim(); val rt = String(rtChars, 0, rtLength).trim()
        val ptyName = if (ptyCode in PTY_NAMES.indices) PTY_NAMES[ptyCode] else ""
        return RdsData(ps, rt, ptyCode, ptyName, piCode, tpFlag, taFlag, msFlag, afFrequencies.sorted(), ps.isNotBlank() || rt.isNotBlank())
    }

    fun reset() {
        carrierPhase = 0.0; rdsLpfBufI = FloatArray(rdsLpfOrder); rdsLpfBufQ = FloatArray(rdsLpfOrder)
        rdsLpfIdx = 0; rdsDecimCounter = 0; clockPhase = 0f; prevRdsSample = 0f; prevBit = 0
        bitBuffer = 0L; bitCount = 0; synced = false; blockIndex = 0; goodBlocks = 0; badBlocks = 0
        for (i in groupData.indices) groupData[i] = 0
        for (i in psChars.indices) psChars[i] = ' '
        for (i in rtChars.indices) rtChars[i] = ' '
        rtLength = 0; piCode = 0; ptyCode = 0; tpFlag = false; taFlag = false; msFlag = false
        afFrequencies.clear(); dataChanged = false; rdsSignalLevel = 0f
    }
}
