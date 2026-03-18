package com.fmradio.dsp

import android.util.Log
import kotlin.math.*

/**
 * RDS (Radio Data System) decoder.
 *
 * Receives wideband FM baseband at 192 kHz and extracts:
 * - PS (Programme Service name) — 8-char station name
 * - RT (RadioText) — up to 64-char text
 * - PTY (Programme Type) — genre code
 * - PI (Programme Identification) — station ID
 *
 * Signal chain:
 *   Baseband (192 kHz) → BPF 57kHz → BPSK demod → Clock recovery (1187.5 bps)
 *   → Differential decode → Block sync → Group decode → RDS data
 */
class RdsDecoder(private val sampleRate: Int = 192000) {

    companion object {
        private const val TAG = "RdsDecoder"

        // RDS subcarrier frequency
        private const val RDS_CARRIER_FREQ = 57000.0

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

    // 57 kHz carrier oscillator
    private var carrierPhase = 0.0
    private val carrierIncrement = 2.0 * PI * RDS_CARRIER_FREQ / sampleRate

    // Bandpass filter around 57 kHz (actually implemented as mix-to-baseband + LPF)
    private val rdsLpfOrder = 48
    private val rdsLpfCoeffs: FloatArray
    private var rdsLpfBufI = FloatArray(rdsLpfOrder)
    private var rdsLpfBufQ = FloatArray(rdsLpfOrder)
    private var rdsLpfIdx = 0

    // Decimation from 192 kHz to ~19 kHz for bit-level processing
    // 192000 / 10 = 19200, which gives ~16 samples per bit (19200/1187.5 ≈ 16.17)
    private val rdsDecimation = 10
    private val rdsRate = sampleRate / rdsDecimation  // 19200 Hz
    private var rdsDecimCounter = 0

    // Clock recovery (bit synchronization)
    private val samplesPerBit = rdsRate.toFloat() / RDS_BITRATE.toFloat() // ~16.17
    private var clockPhase = 0f
    private var prevRdsSample = 0f

    // Differential decoding
    private var prevBit = 0

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

    // RDS decoded fields
    private val psChars = CharArray(8) { ' ' }
    private val rtChars = CharArray(64) { ' ' }
    private var rtLength = 0
    private var piCode = 0
    private var ptyCode = 0
    private var tpFlag = false
    private var taFlag = false
    private var msFlag = false
    private val afFrequencies = mutableSetOf<Float>()
    @Volatile
    private var dataChanged = false

    init {
        // Low-pass filter for RDS baseband (cutoff ~2.4 kHz, RDS bandwidth is ±2 kHz)
        val cutoff = 2400f / (sampleRate.toFloat() / 2f) // Relative to Nyquist
        rdsLpfCoeffs = designRdsFilter(rdsLpfOrder, cutoff)
    }

    private fun designRdsFilter(order: Int, normalizedCutoff: Float): FloatArray {
        val coeffs = FloatArray(order)
        val mid = order / 2
        var sum = 0f
        for (i in 0 until order) {
            val n = i - mid
            coeffs[i] = if (n == 0) {
                normalizedCutoff
            } else {
                sin(PI.toFloat() * normalizedCutoff * n) / (PI.toFloat() * n)
            }
            // Hamming window
            coeffs[i] *= 0.54f - 0.46f * cos(2 * PI.toFloat() * i / (order - 1))
            sum += coeffs[i]
        }
        for (i in coeffs.indices) coeffs[i] /= sum
        return coeffs
    }

    /**
     * Process wideband baseband samples at 192 kHz.
     * Called from FmDemodulator's wideband listener.
     */
    fun process(baseband: FloatArray) {
        for (sample in baseband) {
            // Mix down 57 kHz subcarrier to baseband (complex multiply)
            val cosCarrier = cos(carrierPhase).toFloat()
            val sinCarrier = sin(carrierPhase).toFloat()
            carrierPhase += carrierIncrement
            if (carrierPhase > 2 * PI) carrierPhase -= 2 * PI

            val mixedI = sample * cosCarrier
            val mixedQ = sample * sinCarrier

            // Low-pass filter
            rdsLpfBufI[rdsLpfIdx] = mixedI
            rdsLpfBufQ[rdsLpfIdx] = mixedQ
            rdsLpfIdx = (rdsLpfIdx + 1) % rdsLpfOrder

            // Decimate
            rdsDecimCounter++
            if (rdsDecimCounter < rdsDecimation) continue
            rdsDecimCounter = 0

            // Compute filtered signal
            var filtI = 0f
            for (j in 0 until rdsLpfOrder) {
                val idx = (rdsLpfIdx - 1 - j + rdsLpfOrder) % rdsLpfOrder
                filtI += rdsLpfBufI[idx] * rdsLpfCoeffs[j]
            }

            // BPSK: use real part for bit detection
            processRdsSample(filtI)
        }
    }

    private fun processRdsSample(sample: Float) {
        // Clock recovery using Gardner timing error detector
        clockPhase += 1f

        if (clockPhase >= samplesPerBit) {
            clockPhase -= samplesPerBit

            // Decision: BPSK symbol
            val bit = if (sample > 0) 1 else 0

            // Differential decoding (RDS uses differential BPSK)
            val decodedBit = bit xor prevBit
            prevBit = bit

            processBit(decodedBit)
        }

        // Zero-crossing detector for clock adjustment
        if ((sample > 0 && prevRdsSample <= 0) || (sample < 0 && prevRdsSample >= 0)) {
            // Adjust clock phase based on zero crossing position
            val error = clockPhase - samplesPerBit / 2
            clockPhase -= error * 0.1f  // PLL bandwidth
        }
        prevRdsSample = sample
    }

    private fun processBit(bit: Int) {
        // Shift bit into buffer
        bitBuffer = ((bitBuffer shl 1) or bit.toLong()) and 0x3FFFFFFL  // 26 bits

        bitCount++

        if (!synced) {
            // Try to find sync by checking syndrome on every bit
            if (bitCount >= 26) {
                val syndrome = calcSyndrome(bitBuffer, 26)
                when (syndrome) {
                    OFFSET_A -> {
                        synced = true
                        blockIndex = 0
                        groupData[0] = ((bitBuffer shr 10) and 0xFFFF).toInt()
                        blockIndex = 1
                        bitCount = 0
                        goodBlocks = 1
                        badBlocks = 0
                    }
                }
            }
        } else {
            // Synced: collect 26 bits per block
            if (bitCount >= 26) {
                val expectedOffset = when (blockIndex) {
                    0 -> OFFSET_A
                    1 -> OFFSET_B
                    2 -> if (isGroupTypeB()) OFFSET_CP else OFFSET_C
                    3 -> OFFSET_D
                    else -> OFFSET_A
                }

                val syndrome = calcSyndrome(bitBuffer, 26)
                if (syndrome == expectedOffset) {
                    groupData[blockIndex] = ((bitBuffer shr 10) and 0xFFFF).toInt()
                    goodBlocks++
                } else {
                    badBlocks++
                    if (badBlocks > 10) {
                        synced = false
                        bitCount = 0
                        return
                    }
                }

                blockIndex++
                bitCount = 0

                if (blockIndex >= 4) {
                    if (goodBlocks >= 2) {
                        decodeGroup()
                    }
                    blockIndex = 0
                    goodBlocks = 0
                }
            }
        }
    }

    private fun isGroupTypeB(): Boolean {
        // Check version B flag in block B
        return groupData[1] and 0x0800 != 0
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
    private fun decodeGroup0(blockB: Int, blockC: Int, blockD: Int, versionB: Boolean) {
        val segmentAddr = blockB and 0x03
        val pos = segmentAddr * 2

        // PS characters from block D
        val c1 = ((blockD shr 8) and 0xFF).toChar()
        val c2 = (blockD and 0xFF).toChar()

        if (c1.isValidRdsChar() && c2.isValidRdsChar()) {
            if (psChars[pos] != c1 || psChars[pos + 1] != c2) {
                psChars[pos] = c1
                psChars[pos + 1] = c2
                dataChanged = true
                Log.d(TAG, "PS update: ${String(psChars).trim()}")
            }
        }

        // AF (Alternative Frequencies) from block C in version A
        if (!versionB) {
            val af1code = (blockC shr 8) and 0xFF
            val af2code = blockC and 0xFF
            decodeAfCode(af1code)
            decodeAfCode(af2code)
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
        // Codes 224-249: number of AFs to follow (variant info), 250=LF/MF filler
        // We ignore these
    }

    // Group 2: RadioText (4 chars per group in version A, 2 in version B)
    private fun decodeGroup2(blockB: Int, blockC: Int, blockD: Int, versionB: Boolean) {
        val textAB = (blockB shr 4) and 0x01
        val segmentAddr = blockB and 0x0F

        if (!versionB) {
            // Version A: 4 chars per segment
            val pos = segmentAddr * 4
            if (pos + 3 < rtChars.size) {
                val c1 = ((blockC shr 8) and 0xFF).toChar()
                val c2 = (blockC and 0xFF).toChar()
                val c3 = ((blockD shr 8) and 0xFF).toChar()
                val c4 = (blockD and 0xFF).toChar()

                if (c1.isValidRdsChar()) rtChars[pos] = c1
                if (c2.isValidRdsChar()) rtChars[pos + 1] = c2
                if (c3.isValidRdsChar()) rtChars[pos + 2] = c3
                if (c4.isValidRdsChar()) rtChars[pos + 3] = c4

                rtLength = maxOf(rtLength, pos + 4)
                dataChanged = true
            }
        } else {
            // Version B: 2 chars per segment
            val pos = segmentAddr * 2
            if (pos + 1 < rtChars.size) {
                val c1 = ((blockD shr 8) and 0xFF).toChar()
                val c2 = (blockD and 0xFF).toChar()

                if (c1.isValidRdsChar()) rtChars[pos] = c1
                if (c2.isValidRdsChar()) rtChars[pos + 1] = c2

                rtLength = maxOf(rtLength, pos + 2)
                dataChanged = true
            }
        }
    }

    private fun Char.isValidRdsChar(): Boolean {
        return this.code in 0x20..0x7E  // Printable ASCII
    }

    private fun notifyListener() {
        listener?.onRdsData(buildRdsData())
    }

    /** Get current RDS data snapshot */
    fun getCurrentData(): RdsData = buildRdsData()

    private fun buildRdsData(): RdsData {
        val ps = String(psChars).trim()
        val rt = String(rtChars, 0, rtLength).trim()
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
        carrierPhase = 0.0
        rdsLpfBufI = FloatArray(rdsLpfOrder)
        rdsLpfBufQ = FloatArray(rdsLpfOrder)
        rdsLpfIdx = 0
        rdsDecimCounter = 0
        clockPhase = 0f
        prevRdsSample = 0f
        prevBit = 0
        bitBuffer = 0L
        bitCount = 0
        synced = false
        blockIndex = 0
        goodBlocks = 0
        badBlocks = 0
        for (i in groupData.indices) groupData[i] = 0
        for (i in psChars.indices) psChars[i] = ' '
        for (i in rtChars.indices) rtChars[i] = ' '
        rtLength = 0
        piCode = 0
        ptyCode = 0
        tpFlag = false
        taFlag = false
        msFlag = false
        afFrequencies.clear()
        dataChanged = false
    }
}
