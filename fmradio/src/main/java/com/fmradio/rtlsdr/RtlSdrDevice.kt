package com.fmradio.rtlsdr

import android.content.Context
import android.hardware.usb.*
import android.util.Log
import kotlinx.coroutines.*

/**
 * Built-in RTL-SDR driver that communicates directly with RTL2832U via Android USB Host API.
 * No external driver app needed.
 *
 * Low-level register addressing faithfully follows librtlsdr (osmocom/rtl-sdr):
 *  - Generic block registers (USB/SYS/GPIO): index = (block<<8)|0x10 for writes,
 *    (block<<8) for reads; wValue = raw register address.
 *  - Demod-internal registers (DSP/FIR/IF-freq/resampler): index = page|0x10 for
 *    writes, page for reads; wValue = (addr<<8)|0x20.
 *  - I2C tuner registers: index = (IICB<<8)|0x10 for writes, (IICB<<8) for reads;
 *    wValue = the 7-bit I2C device address.
 * Mixing these up (as earlier versions of this file did) means the RTL2832U's
 * internal I2C bridge silently drops writes intended for the tuner chip.
 */
class RtlSdrDevice(private val context: Context) {

    companion object {
        private const val TAG = "RtlSdrDevice"

        // RTL2832U vendor/product IDs — full known_devices list from librtlsdr.
        // FC0013-based sticks in particular ship under many brands (Terratec,
        // Dexatek, MSI, SVEON, ...), so the short list used previously missed
        // most of them.
        private val SUPPORTED_DEVICES = listOf(
            Pair(0x0BDA, 0x2832), // Generic RTL2832U
            Pair(0x0BDA, 0x2838), // Generic RTL2832U OEM (RTL-SDR v1/v2/v3)
            Pair(0x0413, 0x6680), // DigitalNow Quad DVB-T PCI-E card
            Pair(0x0413, 0x6F0F), // Leadtek WinFast DTV Dongle mini D
            Pair(0x0458, 0x707F), // Genius TVGo DVB-T03 (Ver. B)
            Pair(0x0CCD, 0x00A9), // Terratec Cinergy T Stick Black (rev 1)
            Pair(0x0CCD, 0x00B3), // Terratec NOXON DAB/DAB+ (rev 1)
            Pair(0x0CCD, 0x00B4), // Terratec Deutschlandradio DAB Stick
            Pair(0x0CCD, 0x00B5), // Terratec NOXON DAB - Radio Energy
            Pair(0x0CCD, 0x00B7), // Terratec Media Broadcast DAB Stick
            Pair(0x0CCD, 0x00B8), // Terratec BR DAB Stick
            Pair(0x0CCD, 0x00B9), // Terratec WDR DAB Stick
            Pair(0x0CCD, 0x00C0), // Terratec MuellerVerlag DAB Stick
            Pair(0x0CCD, 0x00C6), // Terratec Fraunhofer DAB Stick
            Pair(0x0CCD, 0x00D3), // Terratec Cinergy T Stick RC (Rev.3)
            Pair(0x0CCD, 0x00D7), // Terratec T Stick PLUS
            Pair(0x0CCD, 0x00E0), // Terratec NOXON DAB/DAB+ (rev 2)
            Pair(0x1554, 0x5020), // PixelView PV-DT235U(RN)
            Pair(0x15F4, 0x0131), // Astrometa DVB-T/DVB-T2
            Pair(0x15F4, 0x0133), // HanfTek DAB+FM+DVB-T
            Pair(0x185B, 0x0620), // Compro Videomate U620F
            Pair(0x185B, 0x0650), // Compro Videomate U650F
            Pair(0x185B, 0x0680), // Compro Videomate U680F
            Pair(0x1B80, 0xD393), // GIGABYTE GT-U7300
            Pair(0x1B80, 0xD394), // DIKOM USB-DVBT HD
            Pair(0x1B80, 0xD395), // Peak 102569AGPK
            Pair(0x1B80, 0xD397), // KWorld KW-UB450-T
            Pair(0x1B80, 0xD398), // Zaapa ZT-MINDVBZP
            Pair(0x1B80, 0xD39D), // SVEON STV20 DVB-T USB & FM
            Pair(0x1B80, 0xD3A4), // Twintech UT-40
            Pair(0x1B80, 0xD3A8), // ASUS U3100MINI_PLUS_V2 / Nooelec
            Pair(0x1B80, 0xD3AF), // SVEON STV27 DVB-T USB & FM
            Pair(0x1B80, 0xD3B0), // SVEON STV21 DVB-T USB & FM
            Pair(0x1D19, 0x1101), // Dexatek DK DVB-T (Logilink VG0002A)
            Pair(0x1D19, 0x1102), // Dexatek DK (MSI DigiVox mini II V3.0)
            Pair(0x1D19, 0x1103), // Dexatek DK 5217 DVB-T
            Pair(0x1D19, 0x1104), // MSI DigiVox Micro HD
            Pair(0x1F4D, 0xA803), // Sweex DVB-T USB
            Pair(0x1F4D, 0xB803), // GTek T803
            Pair(0x1F4D, 0xC803), // Lifeview LV5TDeluxe
            Pair(0x1F4D, 0xD286), // MyGica TD312
            Pair(0x1F4D, 0xD803), // PROlectrix DV107669
            // Legacy entries kept from the previous list (not in librtlsdr)
            Pair(0x0BDA, 0x2831), // RTL2831U
            Pair(0x0BDA, 0x283A), // RTL-SDR V3 (alt PID)
        )

        private const val CTRL_TIMEOUT = 300
        private const val USB_TIMEOUT = 5000

        // Block indices — match librtlsdr's `enum blocks`
        private const val DEMODB = 0
        private const val USBB = 1
        private const val SYSB = 2
        private const val IICB = 6

        // USB block registers (librtlsdr `enum usb_reg`)
        private const val USB_SYSCTL = 0x2000
        private const val USB_EPA_CTL = 0x2148
        private const val USB_EPA_MAXPKT = 0x2158

        // SYS block registers (librtlsdr `enum sys_reg`)
        private const val DEMOD_CTL = 0x3000
        private const val DEMOD_CTL_1 = 0x300B

        private const val CTRL_IN = UsbConstants.USB_DIR_IN or UsbConstants.USB_TYPE_VENDOR
        private const val CTRL_OUT = UsbConstants.USB_DIR_OUT or UsbConstants.USB_TYPE_VENDOR

        const val DEFAULT_SAMPLE_RATE = 1152000
        private const val RTL_XTAL_HZ = 28_800_000L

        // Tuner I2C addresses + chip-ID check values (librtlsdr detection order).
        // These are the 8-BIT bus addresses librtlsdr passes in wValue: R820T
        // is 0x34, NOT its 7-bit form 0x1A — probing/programming at 0x1A hits
        // an empty bus address and the tuner is never even detected.
        private const val R820T_I2C_ADDR = R82xxTuner.R820T_I2C_ADDR
        private const val R828D_I2C_ADDR = R82xxTuner.R828D_I2C_ADDR
        private const val FC0013_I2C_ADDR = 0xC6
        private const val R82XX_CHECK_VAL = R82xxTuner.CHECK_VAL
        private const val FC0013_CHECK_VAL = 0xA3

        fun findDevice(context: Context): UsbDevice? {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
            for (device in usbManager.deviceList.values) {
                for ((vid, pid) in SUPPORTED_DEVICES) {
                    if (device.vendorId == vid && device.productId == pid) {
                        return device
                    }
                }
            }
            return null
        }
    }

    private var usbManager: UsbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var usbDevice: UsbDevice? = null
    private var usbConnection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var bulkEndpoint: UsbEndpoint? = null

    private var isOpen = false
    private var centerFrequency: Long = 100000000L // 100 MHz default
    private var sampleRate: Int = DEFAULT_SAMPLE_RATE
    private var tunerType: TunerType = TunerType.UNKNOWN
    private var r82xx: R82xxTuner? = null

    @Volatile
    var isStreaming = false
        private set

    enum class TunerType {
        UNKNOWN, E4000, FC0012, FC0013, FC2580, R820T, R828D
    }

    fun open(device: UsbDevice? = null): Boolean {
        try {
            usbDevice = device ?: findDevice(context) ?: run {
                Log.e(TAG, "No RTL-SDR device found")
                return false
            }

            if (!usbManager.hasPermission(usbDevice)) {
                Log.e(TAG, "No USB permission")
                return false
            }

            usbConnection = usbManager.openDevice(usbDevice) ?: run {
                Log.e(TAG, "Cannot open USB device")
                return false
            }

            // Find bulk transfer interface and endpoint
            val dev = usbDevice!!
            for (i in 0 until dev.interfaceCount) {
                val iface = dev.getInterface(i)
                for (j in 0 until iface.endpointCount) {
                    val ep = iface.getEndpoint(j)
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                        ep.direction == UsbConstants.USB_DIR_IN
                    ) {
                        usbInterface = iface
                        bulkEndpoint = ep
                        break
                    }
                }
                if (bulkEndpoint != null) break
            }

            if (bulkEndpoint == null) {
                Log.e(TAG, "No bulk endpoint found")
                close()
                return false
            }

            usbConnection!!.claimInterface(usbInterface, true)

            initializeDevice()
            isOpen = true
            Log.i(TAG, "RTL-SDR device opened successfully (tuner=$tunerType)")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error opening device", e)
            close()
            return false
        }
    }

    private fun initializeDevice() {
        if (usbConnection == null) return

        // ---- rtlsdr_init_baseband() ----
        // Initialize USB
        writeReg(USBB, USB_SYSCTL, 0x09, 1)
        writeReg(USBB, USB_EPA_MAXPKT, 0x0002, 2)
        writeReg(USBB, USB_EPA_CTL, 0x1002, 2)

        // Power on demod
        writeReg(SYSB, DEMOD_CTL_1, 0x22, 1)
        writeReg(SYSB, DEMOD_CTL, 0xe8, 1)

        // Reset demod (bit 3, soft_rst)
        demodWriteReg(1, 0x01, 0x14, 1)
        demodWriteReg(1, 0x01, 0x10, 1)

        // Disable spectrum inversion and adjacent channel rejection
        demodWriteReg(1, 0x15, 0x00, 1)
        demodWriteReg(1, 0x16, 0x0000, 2)

        // Clear both DDC shift and IF frequency registers
        for (i in 0 until 6) demodWriteReg(1, 0x16 + i, 0x00, 1)

        // Program the demod FIR (DAB/FM coefficient set, same as the Windows
        // driver / librtlsdr's fir_default). Power-on FIR contents are
        // undocumented, and on a warm re-open the registers may hold whatever
        // a previous driver left there — never rely on defaults.
        setFir()

        // Enable SDR mode, disable DAGC (bit 5)
        demodWriteReg(0, 0x19, 0x05, 1)

        // Init FSM state-holding register
        demodWriteReg(1, 0x93, 0xf0, 1)
        demodWriteReg(1, 0x94, 0x0f, 1)

        // Disable AGC (en_dagc, bit 0)
        demodWriteReg(1, 0x11, 0x00, 1)

        // Disable RF and IF AGC loop
        demodWriteReg(1, 0x04, 0x00, 1)

        // Disable PID filter (enable_PID = 0)
        demodWriteReg(0, 0x61, 0x60, 1)

        // opt_adc_iq = 0, default ADC_I/ADC_Q datapath
        demodWriteReg(0, 0x06, 0x80, 1)

        // Enable Zero-IF mode, DC cancellation, IQ estimation/compensation
        demodWriteReg(1, 0xb1, 0x1b, 1)

        // Disable 4.096 MHz clock output on pin TP_CK0
        demodWriteReg(0, 0x0d, 0x83, 1)

        // ---- Probe tuners (matches librtlsdr's detection order) ----
        enableI2CRepeater(true)
        tunerType = detectTuner()
        Log.i(TAG, "Detected tuner: $tunerType")

        when (tunerType) {
            TunerType.R820T, TunerType.R828D -> {
                val addr = if (tunerType == TunerType.R828D) R828D_I2C_ADDR else R820T_I2C_ADDR
                val xtal = if (tunerType == TunerType.R828D) 16_000_000L else RTL_XTAL_HZ
                val tuner = R82xxTuner(
                    i2cAddr = addr,
                    isR828D = tunerType == TunerType.R828D,
                    xtalHz = xtal,
                    i2cWriteRaw = ::i2cWriteRaw,
                    i2cReadRaw = ::i2cReadRaw
                )
                r82xx = tuner
                tuner.init()
            }
            TunerType.FC0013 -> fc0013Init()
            else -> Log.w(TAG, "Unsupported/undetected tuner type: $tunerType — tuning will not work")
        }
        enableI2CRepeater(false)

        // ---- Per-tuner demod configuration (librtlsdr rtlsdr_open) ----
        if (tunerType == TunerType.R820T || tunerType == TunerType.R828D) {
            // The R82xx is a LOW-IF tuner: the RTL2832U must run in real
            // sampling on the I-ADC with its DDC at the tuner IF and spectrum
            // inversion enabled. Running it zero-IF (the previous behavior)
            // parks the signal at the edge of the tuner's IF filter —
            // attenuated, distorted reception across the board.
            demodWriteReg(1, 0xb1, 0x1a, 1)   // disable Zero-IF mode
            demodWriteReg(0, 0x08, 0x4d, 1)   // only enable In-phase ADC input
            setIfFrequency(r82xx?.intFreq ?: R82xxTuner.DEFAULT_IF_FREQ)
            demodWriteReg(1, 0x15, 0x01, 1)   // enable spectrum inversion
        } else {
            setIfFrequency(0)
            demodWriteReg(0, 0x08, 0xcd, 1)   // enable I+Q ADC input
            demodWriteReg(1, 0xb1, 0x1b, 1)   // enable Zero-IF mode
            demodWriteReg(1, 0x15, 0x00, 1)   // no spectrum inversion
        }

        setSampleRate(sampleRate)
    }

    /**
     * Program the RTL2832U FIR filter (librtlsdr rtlsdr_set_fir): 16 signed
     * coefficients — first 8 as int8, last 8 as packed 12-bit — written to
     * demod page 1, regs 0x1c..0x2f.
     */
    private fun setFir() {
        // librtlsdr fir_default: DAB/FM coefficient set
        val coeffs = intArrayOf(
            -54, -36, -41, -40, -32, -14, 14, 53,
            101, 156, 215, 273, 327, 372, 404, 421
        )
        val fir = IntArray(20)
        for (i in 0 until 8) fir[i] = coeffs[i] and 0xFF
        var i = 0
        while (i < 8) {
            val val0 = coeffs[8 + i]
            val val1 = coeffs[8 + i + 1]
            fir[8 + i * 3 / 2] = (val0 shr 4) and 0xFF
            fir[8 + i * 3 / 2 + 1] = ((val0 shl 4) or ((val1 shr 8) and 0x0F)) and 0xFF
            fir[8 + i * 3 / 2 + 2] = val1 and 0xFF
            i += 2
        }
        for (j in 0 until 20) demodWriteReg(1, 0x1c + j, fir[j], 1)
    }

    private fun detectTuner(): TunerType {
        // Order matches librtlsdr: E4000 (unsupported here) -> FC0013 -> R820T -> R828D
        val fc0013Id = i2cReadReg(FC0013_I2C_ADDR, 0x00)
        if (fc0013Id == FC0013_CHECK_VAL) return TunerType.FC0013

        val r820tId = i2cReadReg(R820T_I2C_ADDR, 0x00)
        if (r820tId == R82XX_CHECK_VAL) return TunerType.R820T

        val r828dId = i2cReadReg(R828D_I2C_ADDR, 0x00)
        if (r828dId == R82XX_CHECK_VAL) return TunerType.R828D

        Log.w(TAG, "No known tuner responded to I2C probe (fc0013=$fc0013Id r820t=$r820tId r828d=$r828dId)")
        return TunerType.UNKNOWN
    }

    // R820T/R828D handling lives in R82xxTuner (faithful librtlsdr port);
    // this class only wires its raw I2C callbacks and demod configuration.

    // =========================================================================
    //  FC0013 tuner — ported from librtlsdr's tuner_fc001x.c (Fitipower FC0013)
    // =========================================================================

    private var rssiCalibrationValue = 0

    private fun fc0013Init() {
        // reg 0x01..0x15 initial values
        val initRegs = byteArrayOf(
            0x09, 0x16, 0x00, 0x00, 0x17, 0x82.toByte(), 0x2a, 0xff.toByte(),
            0x6e, 0xb8.toByte(), 0x82.toByte(), 0xfc.toByte(), 0x11, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x10, 0x01
        )
        for (i in initRegs.indices) {
            i2cWrite(FC0013_I2C_ADDR, 0x01 + i, byteArrayOf(initRegs[i]))
        }
        // RSSI calibration: with EN_CAL_RSSI set and the LNA powered down,
        // the RSSI pin reads the no-signal DC baseline — captured here as the
        // reference the AGC loop later compares live readings against.
        fc0013WriteMask(0x09, 0x10, 0x10)
        fc0013WriteMask(0x06, 0x01, 0x01)
        try { Thread.sleep(100) } catch (_: InterruptedException) {}
        rssiCalibrationValue = demodReadReg(3, 0x01)
        fc0013WriteMask(0x09, 0x00, 0x10)
        fc0013WriteMask(0x06, 0x00, 0x01)
        Log.i(TAG, "FC0013 RSSI calibration baseline: $rssiCalibrationValue")
    }

    /**
     * Host-driven FC0013 LNA gain adjustment based on live RSSI, ported from
     * librtlsdr's fc001x_get_i2c_register(). librtlsdr runs this whenever an
     * application polls tuner gain (SDR#, rtl_tcp do it continuously); we
     * call it periodically during playback. Steps the LNA down when a strong
     * signal overloads the front end (distortion/noise) and back up when the
     * signal is weak. Only active in AGC gain mode; a no-op for other tuners.
     */
    fun fc0013AgcTick() {
        if (!isOpen || tunerType != TunerType.FC0013) return
        try {
            enableI2CRepeater(true)
            val gainMode = i2cReadReg(FC0013_I2C_ADDR, 0x0d)
            if (gainMode and 8 == 0) {
                // AGC mode: zero mixer + IF gain registers, judge input level
                // purely from RSSI relative to the calibration baseline
                i2cWrite(FC0013_I2C_ADDR, 0x12, byteArrayOf(0x00))
                i2cWrite(FC0013_I2C_ADDR, 0x13, byteArrayOf(0x00))
                val lna = i2cReadReg(FC0013_I2C_ADDR, 0x14) and 0x1f
                val rssi = demodReadReg(3, 0x01)
                val diff = rssi - rssiCalibrationValue
                // Gain ladder (descending): 0x10 → 0x17 → 0x08 → 0x1e → 0x02
                val newLna = when (lna) {
                    0x10 -> if (diff > 6) 0x17 else -1
                    0x17 -> if (diff > 15) 0x08 else if (diff < 3) 0x10 else -1
                    0x08 -> if (diff > 14) 0x1e else if (diff < 3) 0x17 else -1
                    0x1e -> if (diff > 13) 0x02 else if (diff < 3) 0x08 else -1
                    0x02 -> if (diff < 3) 0x1e else -1
                    else -> 0x10 // unknown state — reset to max gain
                }
                if (newLna >= 0) fc0013WriteMask(0x14, newLna, 0x1f)
            }
            enableI2CRepeater(false)
        } catch (e: Exception) {
            Log.w(TAG, "FC0013 AGC tick failed", e)
        }
    }

    private fun fc0013WriteMask(reg: Int, data: Int, bitMask: Int) {
        val current = i2cReadReg(FC0013_I2C_ADDR, reg)
        val newVal = (current and bitMask.inv()) or (data and bitMask)
        i2cWrite(FC0013_I2C_ADDR, reg, byteArrayOf(newVal.toByte()))
    }

    private fun fc0013SetVhfTrack(freq: Long) {
        val tmp = i2cReadReg(FC0013_I2C_ADDR, 0x1d) and 0xe3
        val newVal = when {
            freq <= 177_500_000L -> tmp or 0x1c
            freq <= 184_500_000L -> tmp or 0x18
            freq <= 191_500_000L -> tmp or 0x14
            freq <= 198_500_000L -> tmp or 0x10
            freq <= 205_500_000L -> tmp or 0x0c
            freq <= 219_500_000L -> tmp or 0x08
            freq < 300_000_000L -> tmp or 0x04
            else -> tmp or 0x1c // UHF and GPS
        }
        i2cWrite(FC0013_I2C_ADDR, 0x1d, byteArrayOf(newVal.toByte()))
    }

    /** Set FC0013 tuner frequency. Returns false if no valid PLL combination exists. */
    private fun fc0013SetFrequency(freq: Long): Boolean {
        val xtalFreqDiv2 = RTL_XTAL_HZ / 2 // 14.4 MHz

        fc0013SetVhfTrack(freq)

        if (freq < 300_000_000L) {
            fc0013WriteMask(0x07, 0x10, 0x10) // enable VHF filter
            fc0013WriteMask(0x14, 0x00, 0x60) // disable UHF & GPS
        } else {
            fc0013WriteMask(0x07, 0x00, 0x10) // disable VHF filter
            fc0013WriteMask(0x14, 0x40, 0x60) // enable UHF, disable GPS
        }

        // Select frequency divider and VCO multiplier (FC0013 branch of fc001x_set_freq)
        val multi: Int
        var reg5: Int
        when {
            freq < 37_084_000L -> { multi = 96; reg5 = 0x82 }
            freq < 55_625_000L -> { multi = 64; reg5 = 0x02 }
            freq < 74_167_000L -> { multi = 48; reg5 = 0x42 }
            freq < 111_250_000L -> { multi = 32; reg5 = 0x82 }
            freq < 148_334_000L -> { multi = 24; reg5 = 0x22 }
            freq < 222_500_000L -> { multi = 16; reg5 = 0x42 }
            freq < 296_667_000L -> { multi = 12; reg5 = 0x12 }
            freq < 445_000_000L -> { multi = 8; reg5 = 0x22 }
            freq < 593_334_000L -> { multi = 6; reg5 = 0x0a }
            freq < 948_600_000L -> { multi = 4; reg5 = 0x12 }
            else -> { multi = 2; reg5 = 0x0a }
        }

        var reg6 = if (multi % 3 == 0) 0x00 else 0x02

        val fVco = freq * multi
        var vcoSelect = false
        if (fVco >= 3_060_000_000L) {
            reg6 = reg6 or 0x08
            vcoSelect = true
        }

        var xdiv = fVco / xtalFreqDiv2
        if ((fVco - xdiv * xtalFreqDiv2) >= (xtalFreqDiv2 / 2)) xdiv++

        var pm = (xdiv / 8).toInt()
        var am = (xdiv - 8 * pm).toInt()

        if (am < 2) {
            am += 8
            pm--
        }

        val reg1: Int
        val reg2: Int
        if (pm > 31) {
            reg1 = am + 8 * (pm - 31)
            reg2 = 31
        } else {
            reg1 = am
            reg2 = pm
        }

        if (reg1 > 15 || reg2 < 0x0b) {
            Log.e(TAG, "No valid FC0013 PLL combination for $freq Hz")
            return false
        }

        reg6 = reg6 or 0x20 // fix clock out

        val xinRaw = ((fVco % xtalFreqDiv2) shl 15) / xtalFreqDiv2 // < 32768, safe to truncate
        var xin = xinRaw.toInt()
        if (xin >= 16384) xin -= 32768
        val reg3 = (xin shr 8) and 0xFF
        val reg4 = xin and 0xFF

        val tmp06 = i2cReadReg(FC0013_I2C_ADDR, 0x06)
        reg6 = reg6 or (tmp06 and 0xc0) // bits 6-7 describe the bandwidth, preserve them

        reg5 = reg5 or 0x07

        i2cWrite(FC0013_I2C_ADDR, 0x01, byteArrayOf(
            reg1.toByte(), reg2.toByte(), reg3.toByte(), reg4.toByte(), reg5.toByte(), reg6.toByte()
        ))

        if (multi == 64) fc0013WriteMask(0x11, 0x04, 0x04) else fc0013WriteMask(0x11, 0x00, 0x04)

        // VCO calibration
        i2cWrite(FC0013_I2C_ADDR, 0x0e, byteArrayOf(0x80.toByte()))
        i2cWrite(FC0013_I2C_ADDR, 0x0e, byteArrayOf(0x00))
        i2cWrite(FC0013_I2C_ADDR, 0x0e, byteArrayOf(0x00))
        var tmp0e = i2cReadReg(FC0013_I2C_ADDR, 0x0e) and 0x3f

        if (vcoSelect) {
            if (tmp0e > 0x3c) {
                reg6 = reg6 and 0x08.inv()
                i2cWrite(FC0013_I2C_ADDR, 0x06, byteArrayOf(reg6.toByte()))
                i2cWrite(FC0013_I2C_ADDR, 0x0e, byteArrayOf(0x80.toByte()))
                i2cWrite(FC0013_I2C_ADDR, 0x0e, byteArrayOf(0x00))
            }
        } else {
            if (tmp0e < 0x02) {
                reg6 = reg6 or 0x08
                i2cWrite(FC0013_I2C_ADDR, 0x06, byteArrayOf(reg6.toByte()))
                i2cWrite(FC0013_I2C_ADDR, 0x0e, byteArrayOf(0x80.toByte()))
                i2cWrite(FC0013_I2C_ADDR, 0x0e, byteArrayOf(0x00))
            }
        }

        // Residual PLL rounding error, compensated digitally via the demod's DDC
        val actualVco = xtalFreqDiv2 * xdiv + (xtalFreqDiv2 * xin) / 32768L
        val tuningError = ((fVco - actualVco) / multi).toInt()
        setIfFrequency(tuningError)

        return true
    }

    private val fc0013IfGains = intArrayOf(
        0x80, 0x40, 0x20, 0x01, 0x03, 0x05, 0x07, 0x09,
        0x0b, 0x0d, 0x0f, 0x11, 0x13, 0x15, 0x17, 0x19, 0x1b, 0x1d, 0x1f
    )

    private fun setFc0013Gain(index: Int) {
        val gainMode = i2cReadReg(FC0013_I2C_ADDR, 0x0d)
        if (gainMode and 8 == 0) return // in AGC mode, manual gain writes are ignored
        i2cWrite(FC0013_I2C_ADDR, 0x12, byteArrayOf(0x00)) // mixer gain fixed at 0 for manual IF gain steps
        val idx = index.coerceIn(0, fc0013IfGains.size - 1)
        i2cWrite(FC0013_I2C_ADDR, 0x13, byteArrayOf(fc0013IfGains[idx].toByte()))
    }

    private fun setFc0013GainMode(manual: Boolean) {
        fc0013WriteMask(0x0d, if (manual) 8 else 0, 0x08)
    }

    // =========================================================================
    //  Public tuning / gain API — dispatches to the detected tuner's driver
    // =========================================================================

    fun setFrequency(frequencyHz: Long): Boolean {
        if (!isOpen) return false
        centerFrequency = frequencyHz

        return try {
            enableI2CRepeater(true)
            val ok = when (tunerType) {
                TunerType.R820T, TunerType.R828D ->
                    // LO offset by intFreq + DDC compensation are handled by
                    // the low-IF configuration set up at init — do NOT touch
                    // the demod DDC here.
                    r82xx?.setFreq(frequencyHz) == true
                TunerType.FC0013 -> fc0013SetFrequency(frequencyHz)
                else -> {
                    Log.w(TAG, "setFrequency: no supported tuner detected")
                    false
                }
            }
            enableI2CRepeater(false)
            if (ok) Log.d(TAG, "Frequency set to ${frequencyHz / 1000000.0} MHz")
            ok
        } catch (e: Exception) {
            Log.e(TAG, "Error setting frequency", e)
            false
        }
    }

    /** Write the RTL2832U digital down-converter's IF-frequency compensation registers. */
    private fun setIfFrequency(ifFreqHz: Int) {
        // Signed math, matching the FC0013 reference driver's int32_t
        // rtlsdr_set_if_freq signature. For the positive IFs the R82xx path
        // uses (1.7/3.57 MHz) this is identical to osmocom's uint32_t variant;
        // for FC0013's tuning-error compensation (small, provably non-negative
        // with round-to-nearest xdiv, but signed by contract) this is the
        // faithful behavior.
        val ifFreqScaled = ((ifFreqHz.toLong() * (1L shl 22)).toDouble() / RTL_XTAL_HZ.toDouble() * -1.0).toInt()
        demodWriteReg(1, 0x19, (ifFreqScaled shr 16) and 0x3F, 1)
        demodWriteReg(1, 0x1a, (ifFreqScaled shr 8) and 0xFF, 1)
        demodWriteReg(1, 0x1b, ifFreqScaled and 0xFF, 1)
    }

    fun setSampleRate(rate: Int): Boolean {
        if (!isOpen) return false
        if (rate <= 225_000 || rate > 3_200_000 || (rate > 300_000 && rate <= 900_000)) {
            Log.e(TAG, "Invalid sample rate: $rate Hz")
            return false
        }
        sampleRate = rate

        return try {
            // Resampler ratio: 28-bit fractional divider, matches rtlsdr_set_sample_rate()
            var rsampRatio = (RTL_XTAL_HZ * (1L shl 22)) / rate
            rsampRatio = rsampRatio and 0x0FFFFFFCL

            enableI2CRepeater(true)
            when (tunerType) {
                TunerType.R820T, TunerType.R828D -> {
                    // Narrow the tuner IF filter to the sample rate and move
                    // the demod DDC to the (changed) IF — matches librtlsdr's
                    // set_bw path (1.152 MHz rate → 1.2 MHz filter, IF 1.7 MHz).
                    // The LO offset also depends on intFreq, so re-tune,
                    // matching librtlsdr's set_center_freq call in that path.
                    val newIf = r82xx?.setBandwidth(rate)
                    if (newIf != null) setIfFrequency(newIf)
                    r82xx?.setFreq(centerFrequency)
                }
                TunerType.FC0013 -> { /* FC0013 filter bandwidth is fixed at 5 MHz in hardware */ }
                else -> {}
            }
            enableI2CRepeater(false)

            demodWriteReg(1, 0x9f, ((rsampRatio shr 16) and 0xFFFF).toInt(), 2)
            demodWriteReg(1, 0xa1, (rsampRatio and 0xFFFF).toInt(), 2)

            // Reset demod (bit 3, soft_rst) — required after resampler ratio change
            demodWriteReg(1, 0x01, 0x14, 1)
            demodWriteReg(1, 0x01, 0x10, 1)

            Log.d(TAG, "Sample rate set to $rate Hz")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error setting sample rate", e)
            false
        }
    }

    fun setGain(gainIndex: Int): Boolean {
        if (!isOpen) return false
        return try {
            enableI2CRepeater(true)
            when (tunerType) {
                TunerType.R820T, TunerType.R828D -> {
                    // Map 0..15 index onto the r82xx gain range (0..49.6 dB)
                    val gainTenths = (gainIndex.coerceIn(0, 15) * 496) / 15
                    r82xx?.setGain(manual = true, gainTenthDb = gainTenths)
                }
                TunerType.FC0013 -> setFc0013Gain(gainIndex)
                else -> {}
            }
            enableI2CRepeater(false)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error setting gain", e)
            false
        }
    }

    fun setAutoGain(enabled: Boolean): Boolean {
        if (!isOpen) return false
        return try {
            enableI2CRepeater(true)
            when (tunerType) {
                TunerType.R820T, TunerType.R828D ->
                    // gain=0 in manual mode matches librtlsdr's set_gain_mode;
                    // callers then pick the actual gain via setGain()
                    r82xx?.setGain(manual = !enabled, gainTenthDb = 0)
                TunerType.FC0013 -> setFc0013GainMode(!enabled)
                else -> {}
            }
            enableI2CRepeater(false)

            // RTL2832U-side digital AGC (independent of the tuner's own AGC).
            // Register is page 0, addr 0x19 — an earlier "0x19 + 8" here wrote
            // to 0x21 instead, so the demod DAGC was never actually enabled.
            demodWriteReg(0, 0x19, if (enabled) 0x25 else 0x05, 1)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error setting auto gain", e)
            false
        }
    }

    fun resetBuffer(): Boolean {
        if (!isOpen) return false
        return try {
            writeReg(USBB, USB_EPA_CTL, 0x1002, 2)
            writeReg(USBB, USB_EPA_CTL, 0x0000, 2)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun readSamples(length: Int): ByteArray? {
        if (!isOpen || bulkEndpoint == null) return null

        val buffer = ByteArray(length)
        var totalRead = 0

        while (totalRead < length) {
            val toRead = minOf(bulkEndpoint!!.maxPacketSize * 32, length - totalRead)
            val tempBuf = ByteArray(toRead)
            val read = usbConnection?.bulkTransfer(bulkEndpoint, tempBuf, toRead, USB_TIMEOUT) ?: -1

            if (read > 0) {
                System.arraycopy(tempBuf, 0, buffer, totalRead, read)
                totalRead += read
            } else if (read < 0) {
                Log.w(TAG, "Bulk transfer error")
                return if (totalRead > 0) buffer.copyOf(totalRead) else null
            }
        }
        return buffer
    }

    fun startStreaming(bufferSize: Int = 16384, callback: (ByteArray) -> Unit): Job {
        isStreaming = true

        // Full USB FIFO reset before starting stream
        resetBuffer()

        // Discard first read to flush stale data from USB pipe
        val discardBuf = ByteArray(bufferSize)
        try {
            val ep = bulkEndpoint
            if (ep != null) {
                usbConnection?.bulkTransfer(ep, discardBuf, discardBuf.size, 200)
            }
        } catch (_: Exception) {}

        // Reset FIFO again for clean start
        resetBuffer()

        return CoroutineScope(Dispatchers.IO).launch {
            Log.i(TAG, "Streaming started (bufSize=$bufferSize)")
            while (isStreaming && isActive) {
                val data = readSamples(bufferSize)
                if (data != null && data.isNotEmpty()) {
                    try {
                        callback(data)
                    } catch (e: Exception) {
                        Log.e(TAG, "Streaming callback error", e)
                    }
                }
            }
            Log.i(TAG, "Streaming stopped")
        }
    }

    fun stopStreaming() {
        isStreaming = false
    }

    /**
     * Full device reset: flush USB FIFO, re-init endpoint.
     * Call after scan or any operation that leaves USB in bad state.
     */
    fun fullReset(): Boolean {
        if (!isOpen) return false
        return try {
            // Stop any ongoing transfers
            isStreaming = false
            Thread.sleep(50)

            // Reset USB FIFO multiple times to ensure clean state
            resetBuffer()
            Thread.sleep(10)
            resetBuffer()

            // Discard any stale data in USB pipe
            val ep = bulkEndpoint
            if (ep != null) {
                val discardBuf = ByteArray(ep.maxPacketSize * 32)
                // Read and discard with short timeout
                for (i in 0 until 3) {
                    val read = usbConnection?.bulkTransfer(ep, discardBuf, discardBuf.size, 100) ?: -1
                    if (read <= 0) break
                }
            }

            resetBuffer()
            Log.i(TAG, "Full USB reset completed")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error during full reset", e)
            false
        }
    }

    fun close() {
        stopStreaming()
        isStreaming = false
        try {
            if (usbInterface != null) {
                usbConnection?.releaseInterface(usbInterface)
            }
            usbConnection?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing device", e)
        }
        usbDevice = null
        usbConnection = null
        usbInterface = null
        bulkEndpoint = null
        r82xx = null
        isOpen = false
    }

    // =========================================================================
    //  Low-level USB control transfers — see class doc for the three addressing
    //  conventions these implement (generic block / demod page / I2C).
    // =========================================================================

    /** Generic block register access (USB_SYSCTL, DEMOD_CTL, GPIO, ...). */
    private fun writeReg(block: Int, addr: Int, value: Int, len: Int) {
        val conn = usbConnection ?: return
        val data = if (len == 1) byteArrayOf((value and 0xFF).toByte())
                   else byteArrayOf(((value shr 8) and 0xFF).toByte(), (value and 0xFF).toByte())
        val index = (block shl 8) or 0x10
        conn.controlTransfer(CTRL_OUT, 0, addr, index, data, data.size, CTRL_TIMEOUT)
    }

    /** Demod-internal (DSP/FIR/IF-freq/resampler) register access — uses page+addr encoding. */
    private fun demodWriteReg(page: Int, addr: Int, value: Int, len: Int) {
        val conn = usbConnection ?: return
        val data = if (len == 1) byteArrayOf((value and 0xFF).toByte())
                   else byteArrayOf(((value shr 8) and 0xFF).toByte(), (value and 0xFF).toByte())
        val index = 0x10 or page
        val wValue = (addr shl 8) or 0x20
        conn.controlTransfer(CTRL_OUT, 0, wValue, index, data, data.size, CTRL_TIMEOUT)
        // librtlsdr issues a dummy read of page 0x0a reg 0x01 after every demod
        // write (write-latch/settling; undocumented but present in all forks)
        demodReadReg(0x0a, 0x01)
    }

    /** Demod-internal single-byte register read (reads use index=page, no 0x10 flag). */
    private fun demodReadReg(page: Int, addr: Int): Int {
        val conn = usbConnection ?: return 0
        val data = ByteArray(1)
        val wValue = (addr shl 8) or 0x20
        val r = conn.controlTransfer(CTRL_IN, 0, wValue, page, data, 1, CTRL_TIMEOUT)
        return if (r >= 0) data[0].toInt() and 0xFF else 0
    }

    private fun enableI2CRepeater(enable: Boolean) {
        demodWriteReg(1, 0x01, if (enable) 0x18 else 0x10, 1)
    }

    /** Raw I2C buffer write (caller provides register-prefixed payload). */
    private fun i2cWriteRaw(addr: Int, buf: ByteArray): Boolean {
        val conn = usbConnection ?: return false
        val index = (IICB shl 8) or 0x10
        return conn.controlTransfer(CTRL_OUT, 0, addr, index, buf, buf.size, CTRL_TIMEOUT) == buf.size
    }

    /** Raw I2C read (register pointer must be written first via i2cWriteRaw). */
    private fun i2cReadRaw(addr: Int, len: Int): ByteArray? {
        val conn = usbConnection ?: return null
        val index = IICB shl 8
        val data = ByteArray(len)
        val r = conn.controlTransfer(CTRL_IN, 0, addr, index, data, len, CTRL_TIMEOUT)
        return if (r == len) data else null
    }

    /** Multi-byte I2C write to a tuner chip (register address + payload in one transfer). */
    private fun i2cWrite(addr: Int, reg: Int, data: ByteArray) {
        val conn = usbConnection ?: return
        val buf = ByteArray(data.size + 1)
        buf[0] = reg.toByte()
        System.arraycopy(data, 0, buf, 1, data.size)
        val index = (IICB shl 8) or 0x10
        conn.controlTransfer(CTRL_OUT, 0, addr, index, buf, buf.size, CTRL_TIMEOUT)
    }

    /** Single-byte I2C register read from a tuner chip. Returns 0 on failure. */
    private fun i2cReadReg(addr: Int, reg: Int): Int {
        val conn = usbConnection ?: return 0
        val writeIndex = (IICB shl 8) or 0x10
        conn.controlTransfer(CTRL_OUT, 0, addr, writeIndex, byteArrayOf(reg.toByte()), 1, CTRL_TIMEOUT)

        val readIndex = IICB shl 8
        val data = ByteArray(1)
        val result = conn.controlTransfer(CTRL_IN, 0, addr, readIndex, data, 1, CTRL_TIMEOUT)
        return if (result >= 0) data[0].toInt() and 0xFF else 0
    }

    fun getFrequency(): Long = centerFrequency
    fun getSampleRate(): Int = sampleRate
    fun isDeviceOpen(): Boolean = isOpen
    fun getTunerType(): TunerType = tunerType
}
