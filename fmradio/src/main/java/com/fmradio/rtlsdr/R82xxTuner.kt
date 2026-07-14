package com.fmradio.rtlsdr

import android.util.Log

/**
 * Rafael Micro R820T/R828D tuner driver — faithful port of librtlsdr's
 * tuner_r82xx.c (osmocom/rtl-sdr, itself derived from the Linux kernel r820t
 * driver by Mauro Carvalho Chehab / Steve Markgraf).
 *
 * Key architectural fact this port exists to honor: the R82xx is a LOW-IF
 * tuner. The LO must be programmed to (wanted_freq + intFreq) and the
 * RTL2832U demod must be put in real-sampling mode with its DDC at intFreq
 * and spectrum inversion enabled. Driving it zero-IF (LO = wanted freq,
 * DDC = 0) parks the signal at the edge of the tuner's IF filter — severely
 * attenuated and distorted reception.
 *
 * I2C addresses are the 8-bit form used on the RTL2832U bridge (R820T=0x34,
 * R828D=0x74). Register reads come back bit-reversed from the chip.
 */
class R82xxTuner(
    private val i2cAddr: Int,
    private val isR828D: Boolean,
    private val xtalHz: Long,
    private val i2cWriteRaw: (addr: Int, buf: ByteArray) -> Boolean,
    private val i2cReadRaw: (addr: Int, len: Int) -> ByteArray?
) {
    companion object {
        private const val TAG = "R82xxTuner"
        const val R820T_I2C_ADDR = 0x34
        const val R828D_I2C_ADDR = 0x74
        const val CHECK_VAL = 0x69
        const val DEFAULT_IF_FREQ = 3_570_000

        private const val REG_SHADOW_START = 5
        private const val NUM_REGS = 30
        private const val VER_NUM = 49
        private const val MAX_I2C_MSG_LEN = 8

        private val INIT_ARRAY = intArrayOf(
            0x83, 0x32, 0x75,                   /* 05 to 07 */
            0xc0, 0x40, 0xd6, 0x6c,             /* 08 to 0b */
            0xf5, 0x63, 0x75, 0x68,             /* 0c to 0f */
            0x6c, 0x83, 0x80, 0x00,             /* 10 to 13 */
            0x0f, 0x00, 0xc0, 0x30,             /* 14 to 17 */
            0x48, 0xcc, 0x60, 0x00,             /* 18 to 1b */
            0x54, 0xae, 0x4a, 0xc0              /* 1c to 1f */
        )

        // freq(MHz), open_d, rf_mux_ploy, tf_c, xtal_cap20p, xtal_cap10p, xtal_cap0p
        private val FREQ_RANGES = arrayOf(
            intArrayOf(0, 0x08, 0x02, 0xdf, 0x02, 0x01, 0x00),
            intArrayOf(50, 0x08, 0x02, 0xbe, 0x02, 0x01, 0x00),
            intArrayOf(55, 0x08, 0x02, 0x8b, 0x02, 0x01, 0x00),
            intArrayOf(60, 0x08, 0x02, 0x7b, 0x02, 0x01, 0x00),
            intArrayOf(65, 0x08, 0x02, 0x69, 0x02, 0x01, 0x00),
            intArrayOf(70, 0x08, 0x02, 0x58, 0x02, 0x01, 0x00),
            intArrayOf(75, 0x00, 0x02, 0x44, 0x02, 0x01, 0x00),
            intArrayOf(80, 0x00, 0x02, 0x44, 0x02, 0x01, 0x00),
            intArrayOf(90, 0x00, 0x02, 0x34, 0x01, 0x01, 0x00),
            intArrayOf(100, 0x00, 0x02, 0x34, 0x01, 0x01, 0x00),
            intArrayOf(110, 0x00, 0x02, 0x24, 0x01, 0x01, 0x00),
            intArrayOf(120, 0x00, 0x02, 0x24, 0x01, 0x01, 0x00),
            intArrayOf(140, 0x00, 0x02, 0x14, 0x01, 0x01, 0x00),
            intArrayOf(180, 0x00, 0x02, 0x13, 0x00, 0x00, 0x00),
            intArrayOf(220, 0x00, 0x02, 0x13, 0x00, 0x00, 0x00),
            intArrayOf(250, 0x00, 0x02, 0x11, 0x00, 0x00, 0x00),
            intArrayOf(280, 0x00, 0x02, 0x00, 0x00, 0x00, 0x00),
            intArrayOf(310, 0x00, 0x41, 0x00, 0x00, 0x00, 0x00),
            intArrayOf(450, 0x00, 0x41, 0x00, 0x00, 0x00, 0x00),
            intArrayOf(588, 0x00, 0x40, 0x00, 0x00, 0x00, 0x00),
            intArrayOf(650, 0x00, 0x40, 0x00, 0x00, 0x00, 0x00)
        )

        private val LNA_GAIN_STEPS = intArrayOf(0, 9, 13, 40, 38, 13, 31, 22, 26, 31, 26, 14, 19, 5, 35, 13)
        private val MIXER_GAIN_STEPS = intArrayOf(0, 5, 10, 10, 19, 9, 10, 25, 17, 10, 8, 16, 13, 6, 3, -8)

        private val IF_LOW_PASS_BW_TABLE = intArrayOf(
            1700000, 1600000, 1550000, 1450000, 1200000, 900000, 700000, 550000, 450000, 350000
        )

        private val BITREV_LUT = intArrayOf(0x0, 0x8, 0x4, 0xc, 0x2, 0xa, 0x6, 0xe,
            0x1, 0x9, 0x5, 0xd, 0x3, 0xb, 0x7, 0xf)
    }

    private val regs = IntArray(NUM_REGS)
    private var xtalCapSel = 4 // XTAL_HIGH_CAP_0P — librtlsdr's fixed choice
    private var filCalCode = 0
    private var input = 0
    var hasLock = false
        private set
    var intFreq = DEFAULT_IF_FREQ
        private set

    // ==================== shadow-register I2C layer ====================

    private fun shadowStore(reg: Int, values: IntArray, len: Int) {
        var r = reg - REG_SHADOW_START
        var offset = 0
        var l = len
        if (r < 0) { offset = -r; l += r; r = 0 }
        if (l <= 0) return
        if (l > NUM_REGS - r) l = NUM_REGS - r
        for (i in 0 until l) regs[r + i] = values[offset + i] and 0xFF
    }

    private fun shadowEqual(reg: Int, values: IntArray, len: Int): Boolean {
        val r = reg - REG_SHADOW_START
        if (r < 0 || len < 0 || len > NUM_REGS - r) return false
        for (i in 0 until len) {
            if (regs[r + i] != (values[i] and 0xFF)) return false
        }
        return true
    }

    private fun write(reg: Int, values: IntArray, len: Int): Boolean {
        if (shadowEqual(reg, values, len)) return true
        shadowStore(reg, values, len)

        var pos = 0
        var remaining = len
        var curReg = reg
        while (remaining > 0) {
            val size = minOf(remaining, MAX_I2C_MSG_LEN - 1)
            val buf = ByteArray(size + 1)
            buf[0] = curReg.toByte()
            for (i in 0 until size) buf[1 + i] = values[pos + i].toByte()
            if (!i2cWriteRaw(i2cAddr, buf)) {
                Log.e(TAG, "i2c write failed reg=0x${curReg.toString(16)} len=$size")
                return false
            }
            curReg += size
            remaining -= size
            pos += size
        }
        return true
    }

    private fun writeReg(reg: Int, value: Int): Boolean = write(reg, intArrayOf(value), 1)

    private fun readCacheReg(reg: Int): Int {
        val r = reg - REG_SHADOW_START
        return if (r in 0 until NUM_REGS) regs[r] else -1
    }

    private fun writeRegMask(reg: Int, value: Int, bitMask: Int): Boolean {
        val rc = readCacheReg(reg)
        if (rc < 0) return false
        val v = (rc and bitMask.inv()) or (value and bitMask)
        return write(reg, intArrayOf(v), 1)
    }

    private fun bitrev(b: Int): Int = (BITREV_LUT[b and 0xF] shl 4) or BITREV_LUT[(b shr 4) and 0xF]

    /** Read registers starting from reg 0. R82xx returns data bit-reversed. */
    private fun read(reg: Int, len: Int): IntArray? {
        if (!i2cWriteRaw(i2cAddr, byteArrayOf(reg.toByte()))) return null
        val raw = i2cReadRaw(i2cAddr, len) ?: return null
        return IntArray(len) { bitrev(raw[it].toInt() and 0xFF) }
    }

    // ==================== tuning logic ====================

    private fun setMux(freqHz: Long): Boolean {
        val freqMhz = freqHz / 1_000_000
        var idx = 0
        for (i in 0 until FREQ_RANGES.size - 1) {
            if (freqMhz < FREQ_RANGES[i + 1][0]) { idx = i; break }
            idx = i + 1
        }
        val range = FREQ_RANGES[idx]

        if (!writeRegMask(0x17, range[1], 0x08)) return false          // open drain
        if (!writeRegMask(0x1a, range[2], 0xc3)) return false          // RF_MUX, Polymux
        if (!writeReg(0x1b, range[3])) return false                    // TF band
        // XTAL CAP & drive — librtlsdr uses XTAL_HIGH_CAP_0P: cap0p | 0x00
        val capVal = when (xtalCapSel) {
            0, 1 -> range[4] or 0x08   // LOW_CAP_30P / LOW_CAP_20P
            2 -> range[5] or 0x08      // LOW_CAP_10P
            4 -> range[6]              // HIGH_CAP_0P
            else -> range[6] or 0x08   // LOW_CAP_0P
        }
        if (!writeRegMask(0x10, capVal, 0x0b)) return false
        if (!writeRegMask(0x08, 0x00, 0x3f)) return false
        return writeRegMask(0x09, 0x00, 0x3f)
    }

    private fun setPll(freqHz: Long): Boolean {
        hasLock = false
        val vcoMinKhz = 1_770_000L
        val vcoMaxKhz = vcoMinKhz * 2
        val freqKhz = (freqHz + 500) / 1000
        val pllRef = xtalHz

        // pll autotune = 128kHz
        if (!writeRegMask(0x1a, 0x00, 0x0c)) return false

        // regs 0x10..0x16 from shadow
        val r = IntArray(7) { regs[0x10 - REG_SHADOW_START + it] }

        r[0] = (r[0] and 0x10.inv()) or 0x00                   // refdiv2 = 0
        r[2] = (r[2] and 0xe0.inv()) or 0x80                   // VCO current = 100

        var mixDiv = 2
        var divNum = 0
        while (mixDiv <= 64) {
            if (freqKhz * mixDiv >= vcoMinKhz && freqKhz * mixDiv < vcoMaxKhz) {
                var divBuf = mixDiv
                while (divBuf > 2) { divBuf = divBuf shr 1; divNum++ }
                break
            }
            mixDiv = mixDiv shl 1
        }

        val data = read(0x00, 5) ?: return false
        val vcoPowerRef = if (isR828D) 1 else 2
        val vcoFineTune = (data[4] and 0x30) shr 4
        if (vcoFineTune > vcoPowerRef) divNum -= 1
        else if (vcoFineTune < vcoPowerRef) divNum += 1

        r[0] = (r[0] and 0xe0.inv()) or ((divNum shl 5) and 0xe0)

        val vcoFreq = freqHz * mixDiv
        // vco_div = int((pll_ref + 65536 * vco_freq) / (2 * pll_ref))
        val vcoDiv = (pllRef + 65536L * vcoFreq) / (2L * pllRef)
        val nint = (vcoDiv / 65536L).toInt()
        val sdm = (vcoDiv % 65536L).toInt()

        if (nint > (128 / vcoPowerRef) - 1) {
            Log.e(TAG, "No valid PLL values for $freqHz Hz")
            return false
        }

        val ni = (nint - 13) / 4
        val si = nint - 4 * ni - 13
        r[4] = ni + (si shl 6)

        r[2] = (r[2] and 0x08.inv()) or (if (sdm == 0) 0x08 else 0x00)
        r[5] = sdm and 0xff
        r[6] = sdm shr 8

        if (!write(0x10, r, 7)) return false

        var lockData: IntArray? = null
        for (i in 0 until 2) {
            try { Thread.sleep(10) } catch (_: InterruptedException) {}
            lockData = read(0x00, 3) ?: return false
            if (lockData[2] and 0x40 != 0) break
            if (i == 0) {
                // Didn't lock: increase VCO current
                if (!writeRegMask(0x12, 0x60, 0xe0)) return false
            }
        }

        if (lockData == null || lockData[2] and 0x40 == 0) {
            Log.w(TAG, "PLL not locked at $freqHz Hz")
            return true  // matches librtlsdr: not a hard error, hasLock stays false
        }
        hasLock = true

        // pll autotune = 8kHz
        return writeRegMask(0x1a, 0x08, 0x08)
    }

    private fun sysfreqSel(): Boolean {
        // librtlsdr always calls with TUNER_DIGITAL_TV / SYS_DVBT, freq=0:
        // generic (non-special-frequency) DVB-T constants
        val mixerTop = 0x24
        val lnaTop = 0xe5
        val cpCur = 0x38
        val divBufCur = 0x30
        val lnaVthL = 0x53
        val mixerVthL = 0x75
        val airCable1In = 0x00
        val cable2In = 0x00
        val lnaDischarge = 14
        val filterCur = 0x40

        if (!writeRegMask(0x1d, lnaTop, 0xc7)) return false
        if (!writeRegMask(0x1c, mixerTop, 0xf8)) return false
        if (!writeReg(0x0d, lnaVthL)) return false
        if (!writeReg(0x0e, mixerVthL)) return false
        input = airCable1In
        if (!writeRegMask(0x05, airCable1In, 0x60)) return false
        if (!writeRegMask(0x06, cable2In, 0x08)) return false
        if (!writeRegMask(0x11, cpCur, 0x38)) return false
        if (!writeRegMask(0x17, divBufCur, 0x30)) return false
        if (!writeRegMask(0x0a, filterCur, 0x60)) return false

        // LNA setup (digital TV branch of the C code)
        if (!writeRegMask(0x1d, 0, 0x38)) return false        // LNA TOP: lowest
        if (!writeRegMask(0x1c, 0, 0x04)) return false        // normal mode
        if (!writeRegMask(0x06, 0, 0x40)) return false        // PRE_DECT off
        if (!writeRegMask(0x1a, 0x30, 0x30)) return false     // agc clk 250hz
        if (!writeRegMask(0x1d, 0x18, 0x38)) return false     // LNA TOP = 3
        if (!writeRegMask(0x1c, mixerTop, 0x04)) return false // discharge mode
        if (!writeRegMask(0x1e, lnaDischarge, 0x1f)) return false
        return writeRegMask(0x1a, 0x20, 0x30)                 // agc clk 60hz
    }

    private fun setTvStandard(): Boolean {
        // Digital-TV, BW<6MHz constants from r82xx_set_tv_standard
        val filtCalLoKhz = 56000L
        val filtGain = 0x10
        val imgR = 0x00
        val filtQ = 0x10
        val hpCor = 0x6b
        val extEnable = 0x60
        val loopThrough = 0x01
        val ltAtt = 0x00
        val fltExtWidest = 0x00
        val polyfilCur = 0x60

        // Initialize the shadow registers
        for (i in INIT_ARRAY.indices) regs[i] = INIT_ARRAY[i]

        if (!writeRegMask(0x0c, 0x00, 0x0f)) return false
        if (!writeRegMask(0x13, VER_NUM, 0x3f)) return false
        if (!writeRegMask(0x1d, 0x00, 0x38)) return false
        intFreq = 3_570_000

        // Filter calibration (forced, as librtlsdr does on its single init).
        // A PLL lock failure at the 56 MHz calibration frequency falls back to
        // filCalCode=0 rather than failing the whole init — mirrors the C
        // code's behavior of proceeding to sysfreq_sel regardless.
        for (i in 0 until 2) {
            if (!writeRegMask(0x0b, hpCor, 0x60)) return false
            if (!writeRegMask(0x0f, 0x04, 0x04)) return false     // cali clk on
            if (!writeRegMask(0x10, 0x00, 0x03)) return false     // xtal cap 0p
            if (!setPll(filtCalLoKhz * 1000)) return false
            if (!hasLock) {
                Log.w(TAG, "PLL no lock at filter calibration; using filCalCode=0")
                filCalCode = 0
                break
            }
            if (!writeRegMask(0x0b, 0x10, 0x10)) return false     // start trigger
            try { Thread.sleep(2) } catch (_: InterruptedException) {}
            if (!writeRegMask(0x0b, 0x00, 0x10)) return false     // stop trigger
            if (!writeRegMask(0x0f, 0x00, 0x04)) return false     // cali clk off

            val data = read(0x00, 5) ?: return false
            filCalCode = data[4] and 0x0f
            if (filCalCode != 0 && filCalCode != 0x0f) break
        }
        if (filCalCode == 0x0f) filCalCode = 0

        if (!writeRegMask(0x0a, filtQ or filCalCode, 0x1f)) return false
        if (!writeRegMask(0x0b, hpCor, 0xef)) return false
        if (!writeRegMask(0x07, imgR, 0x80)) return false
        if (!writeRegMask(0x06, filtGain, 0x30)) return false
        if (!writeRegMask(0x1e, extEnable, 0x60)) return false
        if (!writeRegMask(0x05, loopThrough, 0x80)) return false
        if (!writeRegMask(0x1f, ltAtt, 0x80)) return false
        if (!writeRegMask(0x0f, fltExtWidest, 0x80)) return false
        return writeRegMask(0x19, polyfilCur, 0x60)
    }

    /**
     * IF filter bandwidth for the given sample rate; narrows intFreq
     * accordingly (r82xx_set_bandwidth). Returns the new intFreq.
     */
    fun setBandwidth(bwHz: Int): Int {
        val filtHpBw1 = 350000
        val filtHpBw2 = 380000
        val reg0a: Int
        var reg0b: Int
        var bw = bwHz

        if (bw > 7_000_000) {
            reg0a = 0x10; reg0b = 0x0b; intFreq = 4_570_000
        } else if (bw > 6_000_000) {
            reg0a = 0x10; reg0b = 0x2a; intFreq = 4_570_000
        } else if (bw > IF_LOW_PASS_BW_TABLE[0] + filtHpBw1 + filtHpBw2) {
            reg0a = 0x10; reg0b = 0x6b; intFreq = 3_570_000
        } else {
            reg0a = 0x00; reg0b = 0x80
            intFreq = 2_300_000
            var realBw = 0

            if (bw > IF_LOW_PASS_BW_TABLE[0] + filtHpBw1) {
                bw -= filtHpBw2; intFreq += filtHpBw2; realBw += filtHpBw2
            } else {
                reg0b = reg0b or 0x20
            }
            if (bw > IF_LOW_PASS_BW_TABLE[0]) {
                bw -= filtHpBw1; intFreq += filtHpBw1; realBw += filtHpBw1
            } else {
                reg0b = reg0b or 0x40
            }

            var i = 0
            while (i < IF_LOW_PASS_BW_TABLE.size) {
                if (bw > IF_LOW_PASS_BW_TABLE[i]) break
                i++
            }
            i--
            reg0b = reg0b or (15 - i)
            realBw += IF_LOW_PASS_BW_TABLE[i]
            intFreq -= realBw / 2
        }

        writeRegMask(0x0a, reg0a, 0x10)
        writeRegMask(0x0b, reg0b, 0xef)
        return intFreq
    }

    /** Tune. LO is internally offset by intFreq (low-IF architecture). */
    fun setFreq(freqHz: Long): Boolean {
        val loFreq = freqHz + intFreq
        if (!setMux(loFreq)) return false
        if (!setPll(loFreq) || !hasLock) return false

        // R828D input switching (Air-In vs Cable1) at 345 MHz
        if (isR828D) {
            val airCable1In = if (freqHz > 345_000_000L) 0x00 else 0x60
            if (airCable1In != input) {
                input = airCable1In
                return writeRegMask(0x05, airCable1In, 0x60)
            }
        }
        return true
    }

    /**
     * Gain control (r82xx_set_gain). gainTenthDb in tenths of dB for manual
     * mode; pass manual=false for hardware AGC (LNA/mixer auto, VGA 26.5 dB).
     */
    fun setGain(manual: Boolean, gainTenthDb: Int = 0): Boolean {
        if (manual) {
            if (!writeRegMask(0x05, 0x10, 0x10)) return false   // LNA auto off
            if (!writeRegMask(0x07, 0, 0x10)) return false      // Mixer auto off
            read(0x00, 4) ?: return false
            if (!writeRegMask(0x0c, 0x08, 0x9f)) return false   // fixed VGA 16.3 dB

            var totalGain = 0
            var lnaIndex = 0
            var mixIndex = 0
            for (i in 0 until 15) {
                if (totalGain >= gainTenthDb) break
                lnaIndex++
                totalGain += LNA_GAIN_STEPS[lnaIndex]
                if (totalGain >= gainTenthDb) break
                mixIndex++
                totalGain += MIXER_GAIN_STEPS[mixIndex]
            }
            if (!writeRegMask(0x05, lnaIndex, 0x0f)) return false
            return writeRegMask(0x07, mixIndex, 0x0f)
        } else {
            if (!writeRegMask(0x05, 0, 0x10)) return false      // LNA auto on
            if (!writeRegMask(0x07, 0x10, 0x10)) return false   // Mixer auto on
            return writeRegMask(0x0c, 0x0b, 0x9f)               // fixed VGA 26.5 dB
        }
    }

    /** Full init: register defaults, standard setup + filter calibration, sysfreq. */
    fun init(): Boolean {
        for (i in regs.indices) regs[i] = 0
        if (!write(0x05, INIT_ARRAY, INIT_ARRAY.size)) {
            Log.e(TAG, "init array write failed")
            return false
        }
        if (!setTvStandard()) {
            Log.e(TAG, "set_tv_standard failed")
            return false
        }
        if (!sysfreqSel()) {
            Log.e(TAG, "sysfreq_sel failed")
            return false
        }
        Log.i(TAG, "R82xx init complete (IF=${intFreq / 1e6} MHz, filCal=$filCalCode)")
        return true
    }
}
