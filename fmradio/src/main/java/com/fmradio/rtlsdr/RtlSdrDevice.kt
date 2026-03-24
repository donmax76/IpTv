package com.fmradio.rtlsdr

import android.content.Context
import android.hardware.usb.*
import android.util.Log
import com.fmradio.dsp.DebugLog
import kotlinx.coroutines.*
import java.nio.ByteBuffer

/**
 * Built-in RTL-SDR driver that communicates directly with RTL2832U via Android USB Host API.
 * No external driver app needed.
 *
 * Register addresses and initialization sequence based on librtlsdr (osmocom/steve-m).
 */
class RtlSdrDevice(private val context: Context) {

    companion object {
        private const val TAG = "RtlSdrDevice"

        // RTL2832U vendor/product IDs
        private val SUPPORTED_DEVICES = listOf(
            Pair(0x0BDA, 0x2838), // RTL-SDR Blog V2
            Pair(0x0BDA, 0x2832), // Generic RTL2832U
            Pair(0x0BDA, 0x2831), // RTL2831U
            Pair(0x0BDA, 0x283A), // RTL-SDR V3
            Pair(0x1B80, 0xD3A8), // R820T tuner
            Pair(0x1B80, 0xD3A9), // Nooelec
        )

        // RTL2832U block IDs (matching librtlsdr DEMODB/USBB/SYSB)
        private const val BLOCK_DEMOD = 0
        private const val BLOCK_USB = 1
        private const val BLOCK_SYS = 2

        // USB timeouts
        private const val USB_TIMEOUT = 5000
        private const val CTRL_TIMEOUT = 300
        private const val I2C_TIMEOUT = 1000  // I2C needs longer timeout on some devices

        // RTL2832U control request types
        private const val CTRL_IN = UsbConstants.USB_DIR_IN or UsbConstants.USB_TYPE_VENDOR
        private const val CTRL_OUT = UsbConstants.USB_DIR_OUT or UsbConstants.USB_TYPE_VENDOR

        // RTL2832U USB block register addresses
        private const val USB_SYSCTL = 0x2000
        private const val USB_EPA_CFG = 0x2144
        private const val USB_EPA_CTL = 0x2148
        private const val USB_EPA_MAXPKT = 0x2158

        // RTL2832U system block register addresses
        private const val SYS_DEMOD_CTL = 0x3000
        private const val SYS_GPO = 0x3001
        private const val SYS_GPI = 0x3002
        private const val SYS_GPOE = 0x3003     // GPIO output enable
        private const val SYS_GPD = 0x3004      // GPIO direction
        private const val SYS_SYSINTE = 0x3005
        private const val SYS_SYSINTS = 0x3006
        private const val SYS_GPIO_CFG0 = 0x3007
        private const val SYS_GPIO_CFG1 = 0x3008
        private const val SYS_DEMOD_CTL_1 = 0x300B
        private const val SYS_IR_SUSPEND = 0x300C

        // Tuner I2C addresses (8-bit format, as used by RTL2832U firmware)
        private const val R820T_I2C_ADDR = 0x34   // R820T: 7-bit 0x1A
        private const val R828D_I2C_ADDR = 0x74   // R828D: 7-bit 0x3A
        private const val E4000_I2C_ADDR = 0xC8   // E4000: 7-bit 0x64
        private const val FC0012_I2C_ADDR = 0xC6  // FC0012: 7-bit 0x63
        private const val FC0013_I2C_ADDR = 0xC6  // FC0013: 7-bit 0x63
        private const val FC2580_I2C_ADDR = 0xAC  // FC2580: 7-bit 0x56

        // Default sample rate for FM (1.152 MHz — divides cleanly to 48 kHz audio)
        const val DEFAULT_SAMPLE_RATE = 1152000

        // Crystal frequency
        private const val RTL_XTAL_FREQ = 28800000L

        // R820T IF frequency (3.57 MHz) — matches librtlsdr R82XX_IF_FREQ
        // R820T has a bandpass IF filter centered at 3.57 MHz.
        // The LO is set to (target_freq + IF_FREQ), placing the signal at 3.57 MHz,
        // and RTL2832U's DDC shifts it to baseband.
        private const val R820T_IF_FREQ = 3570000

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
    private var tunerType: TunerType = TunerType.R820T
    private var tunerI2CAddr: Int = R820T_I2C_ADDR

    // Mutex to serialize USB control transfers — concurrent access corrupts device state
    private val usbLock = java.util.concurrent.locks.ReentrantLock()

    // Shadow register array for R820T (regs 0x05-0x1F, 27 bytes)
    // Required because R820T needs read-modify-write and register readback is unreliable.
    // Matches librtlsdr's priv->regs[] approach.
    private val r820tRegs = IntArray(0x20) // index = register address

    @Volatile
    var isStreaming = false
        private set

    @Volatile
    private var isOpening = false

    enum class TunerType {
        UNKNOWN, E4000, FC0012, FC0013, FC2580, R820T, R828D
    }

    fun open(device: UsbDevice? = null): Boolean {
        if (isOpening) {
            DebugLog.log("USB", "open() blocked — already opening")
            return false
        }
        if (isOpen) {
            DebugLog.log("USB", "open() — already open, closing first")
            close()
        }
        isOpening = true
        try {
            usbDevice = device ?: findDevice(context) ?: run {
                Log.e(TAG, "No RTL-SDR device found")
                isOpening = false
                return false
            }

            if (!usbManager.hasPermission(usbDevice)) {
                Log.e(TAG, "No USB permission")
                isOpening = false
                return false
            }

            usbConnection = usbManager.openDevice(usbDevice) ?: run {
                Log.e(TAG, "Cannot open USB device")
                isOpening = false
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
                isOpening = false
                close()
                return false
            }

            // Claim interface FIRST (needed for SET_CONFIGURATION on some devices)
            usbConnection!!.claimInterface(usbInterface, true)

            // USB SET_CONFIGURATION + SET_INTERFACE to put device in known state
            usbResetDevice()

            // Re-claim interface AFTER SET_CONFIGURATION — critical on Android 12+
            // SET_CONFIGURATION can cause kernel to release the interface internally
            usbConnection!!.releaseInterface(usbInterface)
            Thread.sleep(20)
            usbConnection!!.claimInterface(usbInterface, true)
            DebugLog.log("USB", "Interface claimed (post-config), endpoint=${bulkEndpoint?.address} maxPkt=${bulkEndpoint?.maxPacketSize}")

            // Log USB device info for diagnostics
            logDeviceInfo(dev)

            // Initialize RTL2832U
            initializeDevice()
            isOpen = true
            isOpening = false
            Log.i(TAG, "RTL-SDR device opened successfully")
            DebugLog.log("USB", "Device opened OK, tuner=$tunerType")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error opening device", e)
            DebugLog.log("USB", "open() EXCEPTION: ${e.message}")
            isOpening = false
            close()
            return false
        }
    }

    private fun initializeDevice() {
        // === Initialize USB (from librtlsdr rtlsdr_init_baseband) ===
        writeRegChecked(BLOCK_USB, USB_SYSCTL, 0x09, 1, "USB_SYSCTL")
        writeRegChecked(BLOCK_USB, USB_EPA_MAXPKT, 0x0002, 2, "USB_EPA_MAXPKT")
        writeRegChecked(BLOCK_USB, USB_EPA_CTL, 0x1002, 2, "USB_EPA_CTL reset")

        // === Power on demod ===
        writeRegChecked(BLOCK_SYS, SYS_DEMOD_CTL_1, 0x22, 1, "DEMOD_CTL_1")
        writeRegChecked(BLOCK_SYS, SYS_DEMOD_CTL, 0xE8, 1, "DEMOD_CTL")

        // === GPIO setup to power on tuner (from librtlsdr rtlsdr_set_gpio) ===
        // Many RTL-SDR dongles use GPIO5 for tuner power supply.
        // Without this, the R820T may not respond to I2C.
        setupGpio()

        // Let demod and tuner power stabilize
        Thread.sleep(50)

        // === Reset demod (page 1, reg 0x01) ===
        writeDemodReg(1, 0x01, 0x14, 1)
        Thread.sleep(5)
        writeDemodReg(1, 0x01, 0x10, 1)

        // Let demod come out of reset before writing more registers
        Thread.sleep(10)

        // === Disable zero-IF mode (page 1, reg 0x19) ===
        writeDemodReg(1, 0x19, 0x05, 1)

        // === Spectrum inversion: OFF for R820T/R828D, ON only for E4000 (librtlsdr) ===
        writeDemodReg(1, 0x15, 0x00, 1)
        writeDemodReg(1, 0x16, 0x0000, 2)

        // === Clear DDC shift and IF frequency registers ===
        for (i in 0..5) {
            writeDemodReg(1, 0x16 + i, 0x00, 1)
        }

        // === Set default FIR coefficients ===
        setFirCoefficients()

        // === Enable SDR mode, clocks and I/Q mux (CRITICAL — without this, no samples!) ===
        writeDemodReg(0, 0x19, 0x05, 1)

        // === Disable PID filter (CRITICAL — without this, data filtered as DVB-T!) ===
        writeDemodReg(0, 0x61, 0x60, 1)

        // === Set IF mode (page 1, reg 0x06) ===
        writeDemodReg(1, 0x06, 0x80, 1)

        // === Disable IR ===
        writeReg(BLOCK_SYS, SYS_IR_SUSPEND, 0x83, 1)

        // === Clear EPA reset so endpoint is ready (librtlsdr does this in reset_buffer) ===
        writeReg(BLOCK_USB, USB_EPA_CTL, 0x0000, 2)

        // === Verify USB communication works by reading back SYSCTL ===
        val sysctl = readReg(BLOCK_USB, USB_SYSCTL, 1)
        DebugLog.log("USB", "Init verify: SYSCTL=0x${sysctl.toString(16)} (expect 0x09)")

        // === Enable I2C repeater for tuner access ===
        enableI2CRepeater(true)

        // === Detect tuner type ===
        tunerType = detectTuner()
        Log.i(TAG, "Detected tuner: $tunerType")
        DebugLog.log("USB", "Tuner detected: $tunerType")

        // === Initialize tuner ===
        when (tunerType) {
            TunerType.R820T -> {
                tunerI2CAddr = R820T_I2C_ADDR
                initR820T()
            }
            TunerType.R828D -> {
                tunerI2CAddr = R828D_I2C_ADDR
                initR820T() // R828D uses same init sequence as R820T
            }
            TunerType.FC0013 -> {
                tunerI2CAddr = FC0013_I2C_ADDR
                initFC0013()
            }
            TunerType.FC0012 -> {
                tunerI2CAddr = FC0012_I2C_ADDR
                Log.w(TAG, "FC0012 tuner detected but not fully supported, trying FC0013 init")
                initFC0013() // FC0012/FC0013 are similar
            }
            else -> Log.w(TAG, "Unsupported tuner type: $tunerType")
        }

        enableI2CRepeater(false)

        // Set IF frequency based on tuner type
        val ifFreq = when (tunerType) {
            TunerType.R820T, TunerType.R828D -> R820T_IF_FREQ
            else -> 0 // FC0013, FC0012, E4000, FC2580 use zero-IF
        }
        setIfFrequency(ifFreq)

        // Set default sample rate
        setSampleRate(sampleRate)

        DebugLog.log("USB", "Init complete: rate=$sampleRate IF=${ifFreq/1e6}MHz tuner=$tunerType")
    }

    /**
     * Set FIR coefficients (from librtlsdr default_fir).
     * These are the default coefficients for the RTL2832U digital filter.
     */
    private fun setFirCoefficients() {
        // Default FIR coefficients from librtlsdr (fir_default[])
        // 32 taps: first 8 are 8-bit, remaining 24 are 12-bit packed into pairs
        val fir = intArrayOf(
            -54, -36, -41, -40, -32, -14, 14, 53,     // 8-bit signed (taps 0-7)
            101, 156, 215, 273, 327, 372, 404, 421,   // 12-bit signed (taps 8-31)
            421, 404, 372, 327, 273, 215, 156, 101,
            53, 14, -14, -32, -40, -41, -36, -54
        )

        // Pack into 20 bytes matching librtlsdr rtlsdr_set_fir():
        // Bytes 0-7: taps 0-7 as 8-bit signed
        // Bytes 8-19: taps 8-31 as 12-bit pairs (two 12-bit values in 3 bytes)
        val firBytes = ByteArray(20)

        // First 8 taps: 8-bit signed, one byte each
        for (i in 0 until 8) {
            firBytes[i] = (fir[i] and 0xFF).toByte()
        }

        // Remaining taps: 12-bit packed in pairs (3 bytes per 2 taps)
        // librtlsdr rtlsdr_set_fir() packs taps 8-15 into bytes 8-19 (4 pairs × 3 bytes)
        var byteIdx = 8
        var tapIdx = 8
        while (byteIdx < 20 && tapIdx + 1 < fir.size) {
            val v1 = fir[tapIdx] and 0xFFF
            val v2 = fir[tapIdx + 1] and 0xFFF
            firBytes[byteIdx] = (v1 and 0xFF).toByte()
            firBytes[byteIdx + 1] = (((v1 shr 8) and 0x0F) or ((v2 shl 4) and 0xF0)).toByte()
            if (byteIdx + 2 < 20) {
                firBytes[byteIdx + 2] = ((v2 shr 4) and 0xFF).toByte()
            }
            byteIdx += 3
            tapIdx += 2
        }

        // Write FIR to demod registers (page 1, starting at 0x1C)
        for (i in firBytes.indices) {
            writeDemodReg(1, 0x1C + i, firBytes[i].toInt() and 0xFF, 1)
        }
    }

    private fun detectTuner(): TunerType {
        // First: probe all I2C methods to find one that works on this device
        probeI2CMethods()

        // Tuner detection table: (address, chipIdCheck, type, name)
        data class TunerProbe(val addr: Int, val reg: Int, val expectedId: Int, val type: TunerType, val name: String)

        val tunerProbes = listOf(
            TunerProbe(R820T_I2C_ADDR, 0x00, 0x69, TunerType.R820T, "R820T"),
            TunerProbe(R828D_I2C_ADDR, 0x00, 0x69, TunerType.R828D, "R828D"),
            TunerProbe(E4000_I2C_ADDR, 0x02, 0x40, TunerType.E4000, "E4000"),  // E4000 chip ID reg
            TunerProbe(FC0012_I2C_ADDR, 0x00, 0xA1, TunerType.FC0012, "FC0012"),
            TunerProbe(FC0012_I2C_ADDR, 0x00, 0xA3, TunerType.FC0013, "FC0013"),
            TunerProbe(FC2580_I2C_ADDR, 0x01, 0x56, TunerType.FC2580, "FC2580"),
        )

        // Then try to detect tuner with the working method.
        // IMPORTANT: always use i2cRead (not i2cRawRead) to set register pointer explicitly,
        // because i2cRawRead reads the auto-incremented register (not chip ID at reg 0x00).
        for (attempt in 0 until 3) {
            // Re-enable I2C repeater before each attempt to ensure tuner is accessible
            enableI2CRepeater(false)
            Thread.sleep(10L * (attempt + 1))
            enableI2CRepeater(true)
            Thread.sleep(10)

            for (probe in tunerProbes) {
                val data = i2cRead(probe.addr, probe.reg, 1)
                if (data != null && data.isNotEmpty()) {
                    val chipId = data[0].toInt() and 0xFF
                    DebugLog.log("USB", "${probe.name} probe: chipId=0x${chipId.toString(16)} (attempt=$attempt)")
                    if (chipId == probe.expectedId) {
                        DebugLog.log("USB", "Tuner IDENTIFIED: ${probe.name} at addr=0x${probe.addr.toString(16)}")
                        return probe.type
                    }
                } else {
                    if (attempt == 0) DebugLog.log("USB", "${probe.name} probe: I2C FAILED attempt=$attempt")
                }
            }
        }

        DebugLog.log("USB", "Tuner NOT identified after 3 attempts, assuming R820T")
        return TunerType.R820T
    }

    /**
     * Write to R820T register with bitmask (like librtlsdr r82xx_write_reg_mask).
     * Only modifies bits set in mask, preserving other bits via shadow register.
     */
    private fun r820tWriteRegMask(reg: Int, value: Int, mask: Int) {
        val old = r820tRegs[reg]
        val newVal = (old and mask.inv()) or (value and mask)
        r820tRegs[reg] = newVal
        i2cWrite(tunerI2CAddr, reg, byteArrayOf(newVal.toByte()))
    }

    /**
     * Write full byte to R820T register and update shadow.
     */
    private fun r820tWriteReg(reg: Int, value: Int) {
        r820tRegs[reg] = value and 0xFF
        i2cWrite(tunerI2CAddr, reg, byteArrayOf(value.toByte()))
    }

    private fun initR820T() {
        // R820T initialization registers (from r82xx.c r82xx_init_array)
        // Registers 0x05 to 0x1F (27 registers)
        val initRegs = intArrayOf(
            0x83, 0x32, 0x75, // reg 0x05-0x07
            0xC0, 0x40, 0xD6, // reg 0x08-0x0A
            0x6C, 0xF5, 0x63, // reg 0x0B-0x0D
            0x75, 0x68, 0x6C, // reg 0x0E-0x10
            0x83, 0x80, 0x00, // reg 0x11-0x13
            0x0F, 0x00, 0xC0, // reg 0x14-0x16
            0x30, 0x48, 0xCC, // reg 0x17-0x19
            0x60, 0x00, 0x54, // reg 0x1A-0x1C
            0xAE, 0x4A, 0xC0, // reg 0x1D-0x1F
        )

        // Write init array and populate shadow registers
        for (i in initRegs.indices) {
            val reg = 0x05 + i
            r820tRegs[reg] = initRegs[i]
            i2cWrite(tunerI2CAddr, reg, byteArrayOf(initRegs[i].toByte()))
        }

        // Initialize calibration
        initR820TCalibration()
    }

    /**
     * R820T initial calibration sequence from r82xx.c r82xx_init.
     * Uses masked writes to preserve critical register bits (LNA power, mixer bias).
     */
    private fun initR820TCalibration() {
        // Set VGA gain to index 8 for calibration (reg 0x0C bits 3:0, bit 4=0 for VCO auto)
        // From r82xx.c: r82xx_write_reg_mask(priv, 0x0c, 0x08, 0x9f)
        r820tWriteRegMask(0x0C, 0x08, 0x9F)

        // Set filter cap for calibration (reg 0x0B)
        // From r82xx.c: r82xx_write_reg_mask(priv, 0x0b, 0x60, 0x60)
        r820tWriteRegMask(0x0B, 0x60, 0x60)

        // Set HPF corner to minimum (reg 0x0A bits 6:4 = 0)
        r820tWriteRegMask(0x0A, 0x00, 0x70)

        // Set calibration clock on (reg 0x0F bit 2 = 1)
        r820tWriteRegMask(0x0F, 0x04, 0x04)

        // Trigger calibration (reg 0x10 bit 0 = 0 then 1)
        r820tWriteRegMask(0x10, 0x00, 0x01)
        r820tWriteRegMask(0x10, 0x01, 0x01)

        Thread.sleep(5) // Wait for calibration

        // Turn off calibration clock (reg 0x0F bit 2 = 0)
        r820tWriteRegMask(0x0F, 0x00, 0x04)

        // Set LNA to auto (bit 4 = 0) — ONLY modify bit 4, preserve power bits
        r820tWriteRegMask(0x05, 0x00, 0x10)

        // Set mixer to auto (bit 4 = 0) — ONLY modify bit 4, preserve bias bits
        r820tWriteRegMask(0x07, 0x00, 0x10)

        Log.i(TAG, "R820T calibration complete")
    }

    fun setFrequency(frequencyHz: Long): Boolean {
        if (!isOpen) return false
        centerFrequency = frequencyHz

        usbLock.lock()
        return try {
            enableI2CRepeater(true)
            when (tunerType) {
                TunerType.R820T, TunerType.R828D -> {
                    // R820T LO = target + IF (3.57 MHz)
                    setR820TFrequency(frequencyHz + R820T_IF_FREQ)
                    enableI2CRepeater(false)
                    setIfFrequency(R820T_IF_FREQ)
                }
                TunerType.FC0013, TunerType.FC0012 -> {
                    // FC0013 is zero-IF: LO = target frequency
                    setFC0013Frequency(frequencyHz)
                    enableI2CRepeater(false)
                    setIfFrequency(0)
                }
                else -> {
                    enableI2CRepeater(false)
                }
            }
            Log.d(TAG, "Frequency set to ${frequencyHz / 1000000.0} MHz")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error setting frequency", e)
            enableI2CRepeater(false)
            false
        } finally {
            usbLock.unlock()
        }
    }

    private fun setR820TFrequency(freq: Long) {
        // Calculate PLL divider for R820T (from r82xx.c r82xx_set_pll)
        // VCO frequency range: 1.77 GHz to 3.92 GHz
        val vcoMin = 1770000000L
        var mixDiv = 2
        var divNum = 0

        while (freq * mixDiv < vcoMin && mixDiv <= 64) {
            mixDiv *= 2
            divNum++
        }

        val vcoFreq = freq * mixDiv

        // Reference frequency (28.8 MHz crystal on RTL-SDR v2)
        val pllRef = RTL_XTAL_FREQ

        // Calculate N-integer and fractional parts (all in Hz to avoid precision loss)
        val nInt = (vcoFreq / (2 * pllRef)).toInt()
        val vcoFra = vcoFreq - 2L * pllRef * nInt  // Remainder in Hz (no /1000 truncation)

        // NI and SI from r82xx.c
        val ni = (nInt - 13) / 4
        val si = nInt % 4

        // SDM (sigma-delta modulator) fractional value — keep full Hz precision
        val sdm = ((vcoFra * 65536L) / (2L * pllRef)).toInt()

        // Write PLL registers (matching r82xx.c r82xx_set_pll)
        // reg 0x10: mixer divider number (bits 7:5)
        r820tWriteRegMask(0x10, divNum shl 5, 0xE0)

        // reg 0x14: NI + SI (PLL integer divider)
        r820tWriteReg(0x14, (ni and 0x1F) or ((si and 0x03) shl 6))

        // reg 0x12-0x13: SDM fractional value
        r820tWriteReg(0x12, (sdm shr 8) and 0xFF)
        r820tWriteReg(0x13, sdm and 0xFF)

        // Wait for PLL lock
        Thread.sleep(10)

        // Check PLL lock status
        val lockData = i2cRead(tunerI2CAddr, 0x02, 1)
        if (lockData != null && lockData.isNotEmpty()) {
            val locked = (lockData[0].toInt() and 0x40) != 0
            DebugLog.log("PLL", "freq=${freq/1e6}MHz locked=$locked reg02=0x${(lockData[0].toInt() and 0xFF).toString(16)}")
            if (!locked) {
                Log.w(TAG, "PLL not locked at ${freq / 1e6} MHz, retrying...")
                // Retry: rewrite SDM registers
                r820tWriteReg(0x12, (sdm shr 8) and 0xFF)
                r820tWriteReg(0x13, sdm and 0xFF)
                Thread.sleep(20)
                val lockRetry = i2cRead(tunerI2CAddr, 0x02, 1)
                if (lockRetry != null && (lockRetry[0].toInt() and 0x40) == 0) {
                    Log.w(TAG, "PLL still not locked at ${freq / 1e6} MHz")
                    DebugLog.log("PLL", "STILL NOT LOCKED after retry!")
                }
            }
        }
    }

    private fun setIfFrequency(ifFreq: Int) {
        // Write IF frequency to RTL2832U demod (page 1, regs 0x19-0x1B)
        val ifFreqScaled = ((-ifFreq.toLong() * (1L shl 22)) / RTL_XTAL_FREQ + (1L shl 22)).toInt()
        writeDemodReg(1, 0x19, (ifFreqScaled shr 16) and 0x3F, 1)
        writeDemodReg(1, 0x1A, (ifFreqScaled shr 8) and 0xFF, 1)
        writeDemodReg(1, 0x1B, ifFreqScaled and 0xFF, 1)
    }

    fun setSampleRate(rate: Int): Boolean {
        if (!isOpen) return false
        sampleRate = rate

        usbLock.lock()
        return try {
            // Calculate resampler ratio
            val rsampRatio = ((RTL_XTAL_FREQ * (1L shl 22)) / rate).toInt()

            // Write to demod registers (page 1, regs 0x9F-0xA2)
            writeDemodReg(1, 0x9F, (rsampRatio shr 16) and 0xFFFF, 2)
            writeDemodReg(1, 0xA1, rsampRatio and 0xFFFF, 2)

            // Reset demod
            writeDemodReg(1, 0x01, 0x14, 1)
            writeDemodReg(1, 0x01, 0x10, 1)

            // Set bandwidth for tuner
            enableI2CRepeater(true)
            when (tunerType) {
                TunerType.R820T, TunerType.R828D -> setR820TBandwidth(rate)
                TunerType.FC0013, TunerType.FC0012 -> setFC0013Bandwidth(rate)
                else -> {}
            }
            enableI2CRepeater(false)

            Log.d(TAG, "Sample rate set to $rate Hz")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error setting sample rate", e)
            false
        } finally {
            usbLock.unlock()
        }
    }

    private fun setR820TBandwidth(bandwidth: Int) {
        // Set R820T IF filter bandwidth via register 0x0A upper nibble
        val bwKhz = bandwidth / 1000
        val filterCap = when {
            bwKhz < 200 -> 0x0F
            bwKhz < 350 -> 0x0B
            bwKhz < 500 -> 0x08
            bwKhz < 800 -> 0x04
            bwKhz < 1200 -> 0x02
            else -> 0x00
        }
        // Only modify upper nibble (filter cap) and HPF corner, preserve other bits
        r820tWriteRegMask(0x0A, (filterCap shl 4) or 0x0B, 0xFF)
    }

    // ========================= FC0013 Tuner Support =========================
    // Based on librtlsdr fc0013.c (Fitipower FC0013 silicon tuner)
    // FC0013 is a zero-IF tuner: LO = target frequency, no IF offset needed.

    // Shadow registers for FC0013 (regs 0x00-0x15)
    private val fc0013Regs = IntArray(0x20)

    private fun fc0013WriteReg(reg: Int, value: Int) {
        fc0013Regs[reg] = value and 0xFF
        i2cWrite(tunerI2CAddr, reg, byteArrayOf((value and 0xFF).toByte()))
    }

    private fun fc0013ReadReg(reg: Int): Int? {
        val data = i2cRead(tunerI2CAddr, reg, 1) ?: return null
        return data[0].toInt() and 0xFF
    }

    /**
     * FC0013 initialization (from librtlsdr fc0013.c fc0013_init).
     */
    private fun initFC0013() {
        // Init register values from fc0013.c
        val initRegs = intArrayOf(
            0x00, // reg 0x00: dummy (read-only chip ID)
            0x09, // reg 0x01: VCO + PLL
            0x16, // reg 0x02: xdiv high
            0x00, // reg 0x03: xdiv low
            0x00, // reg 0x04: VCO current
            0xC1, // reg 0x05: clock out / AGC
            0x00, // reg 0x06: IF frequency (zero-IF = 0)
            0x00, // reg 0x07: IF frequency / bandwidth
            0xFF, // reg 0x08: AGC clock
            0x6E, // reg 0x09: IF VGA gain
            0xB8, // reg 0x0A: IF VGA
            0x82, // reg 0x0B: PLL loop divider
            0xFC, // reg 0x0C: VCO current
            0x02, // reg 0x0D: band selection
            0x00, // reg 0x0E: VCO calibration
            0x00, // reg 0x0F
            0x00, // reg 0x10: RSSI
            0x00, // reg 0x11: clock out
            0x00, // reg 0x12: LNA power detector
            0x00, // reg 0x13: mixer gain
            0x50, // reg 0x14: LNA gain (auto + high gain)
            0x01, // reg 0x15: PLL loop divider 2
        )

        // Hardware reset sequence
        fc0013WriteReg(0x06, 0x00)
        fc0013WriteReg(0x07, 0x20)
        fc0013WriteReg(0x06, 0x00)

        // Write all init registers
        for (i in 1 until initRegs.size) {
            fc0013WriteReg(i, initRegs[i])
        }

        // Enable clock output
        fc0013WriteReg(0x11, 0x04)

        // Verify chip ID
        val chipId = fc0013ReadReg(0x00)
        DebugLog.log("USB", "FC0013 init: chipID=0x${chipId?.toString(16) ?: "null"} (expect 0xa3)")

        Log.i(TAG, "FC0013 initialization complete")
    }

    /**
     * FC0013 frequency setting (from librtlsdr fc0013.c fc0013_set_params).
     * Uses PLL with VCO divider to synthesize target frequency.
     */
    private fun setFC0013Frequency(freqHz: Long) {
        val xtalFreqKhz2 = (RTL_XTAL_FREQ / 1000 / 2).toInt() // 14400

        // Select multiplier and VCO band registers based on frequency
        val multi: Int
        var reg5: Int
        var reg6: Int

        when {
            freqHz < 37084000L -> {
                multi = 96; reg5 = 0x82; reg6 = 0x00
            }
            freqHz < 55625000L -> {
                multi = 64; reg5 = 0x02; reg6 = 0x02
            }
            freqHz < 74167000L -> {
                multi = 48; reg5 = 0x42; reg6 = 0x00
            }
            freqHz < 111250000L -> {
                multi = 32; reg5 = 0x82; reg6 = 0x02
            }
            freqHz < 148334000L -> {
                multi = 24; reg5 = 0x22; reg6 = 0x00
            }
            freqHz < 222500000L -> {
                multi = 16; reg5 = 0x42; reg6 = 0x02
            }
            freqHz < 296667000L -> {
                multi = 12; reg5 = 0x12; reg6 = 0x00
            }
            freqHz < 445000000L -> {
                multi = 8; reg5 = 0x22; reg6 = 0x02
            }
            freqHz < 593334000L -> {
                multi = 6; reg5 = 0x12; reg6 = 0x02
            }
            freqHz < 950000000L -> {
                multi = 4; reg5 = 0x02; reg6 = 0x08
            }
            else -> {
                multi = 2; reg5 = 0x02; reg6 = 0x0C
            }
        }

        val fVco = freqHz * multi
        val xin = (fVco / 1000 / xtalFreqKhz2).toInt()

        val pm = xin / 2
        val am = xin % 2

        val reg1 = am + (8 * pm)

        // Fractional part
        val remainder = fVco - xin.toLong() * xtalFreqKhz2 * 1000
        var xdiv = ((remainder shl 15) / (xtalFreqKhz2.toLong() * 1000)).toInt()
        if (xdiv >= (1 shl 15)) xdiv = (1 shl 15) - 1

        val reg2 = (xdiv shr 8) and 0x7F
        val reg3 = xdiv and 0xFF
        val reg4 = 0x0A // VCO current

        // Write PLL registers
        fc0013WriteReg(0x01, reg1)
        fc0013WriteReg(0x02, reg2)
        fc0013WriteReg(0x03, reg3)
        fc0013WriteReg(0x04, reg4)
        fc0013WriteReg(0x05, reg5)
        fc0013WriteReg(0x06, reg6)

        // VCO calibration: toggle reg 0x0E bit 7
        fc0013WriteReg(0x0E, 0x80)
        fc0013WriteReg(0x0E, 0x00)

        Thread.sleep(10)

        // Check VCO lock (reg 0x0E bit 6)
        val vcoStatus = fc0013ReadReg(0x0E) ?: 0
        if ((vcoStatus and 0x40) == 0) {
            // VCO not locked, try switching VCO
            DebugLog.log("PLL", "FC0013 VCO not locked, switching VCO")
            val curReg6 = fc0013Regs[0x06]
            fc0013WriteReg(0x06, curReg6 or 0x08)

            // Re-calibrate
            fc0013WriteReg(0x0E, 0x80)
            fc0013WriteReg(0x0E, 0x00)
            Thread.sleep(10)

            val vcoRetry = fc0013ReadReg(0x0E) ?: 0
            DebugLog.log("PLL", "FC0013 VCO retry: locked=${(vcoRetry and 0x40) != 0}")
        } else {
            DebugLog.log("PLL", "FC0013 freq=${freqHz/1e6}MHz locked=true multi=$multi")
        }
    }

    /**
     * FC0013 bandwidth setting (from librtlsdr fc0013.c).
     * Modifies reg 0x06 bandwidth bits and reg 0x07.
     */
    private fun setFC0013Bandwidth(bandwidth: Int) {
        // reg 0x06 upper bits control bandwidth filter
        val bwBits = when {
            bandwidth <= 6000000 -> 0x80  // 6 MHz
            bandwidth <= 7000000 -> 0x40  // 7 MHz
            else -> 0x00                  // 8 MHz (widest)
        }
        val curReg6 = fc0013Regs[0x06] and 0x3F
        fc0013WriteReg(0x06, curReg6 or bwBits)
    }

    /**
     * FC0013 LNA gain control (from librtlsdr fc0013.c).
     * gainIndex 0-15 maps to hardware gain values.
     */
    private fun setFC0013LnaGain(gainIndex: Int) {
        // FC0013 LNA gain table (from fc0013.c fc0013_lna_gains[])
        // Index -> register value for reg 0x14 bits 4:0
        val gainTable = intArrayOf(
            0x02, // 0: -9.9 dB
            0x03, // 1: -7.3 dB
            0x05, // 2: -6.5 dB
            0x04, // 3: -6.3 dB
            0x00, // 4: -6.3 dB
            0x07, // 5: -6.0 dB
            0x01, // 6: -5.8 dB
            0x06, // 7: -5.4 dB
            0x08, // 8: +5.8 dB
            0x09, // 9: +6.1 dB
            0x0C, // 10: +6.3 dB
            0x0D, // 11: +6.5 dB
            0x0E, // 12: +6.7 dB
            0x0F, // 13: +6.8 dB
            0x11, // 14: +7.0 dB
            0x12, // 15: +7.1 dB
        )

        val idx = gainIndex.coerceIn(0, gainTable.size - 1)
        val reg14 = (fc0013Regs[0x14] and 0xE0) or gainTable[idx]
        fc0013WriteReg(0x14, reg14)
    }

    // ========================= End FC0013 Support =========================

    fun setGain(gainIndex: Int): Boolean {
        if (!isOpen) return false
        usbLock.lock()
        return try {
            enableI2CRepeater(true)
            when (tunerType) {
                TunerType.R820T, TunerType.R828D -> {
                    val idx = gainIndex.coerceIn(0, 15)
                    r820tWriteRegMask(0x05, 0x10 or idx, 0x1F)
                    r820tWriteRegMask(0x07, 0x10 or idx, 0x1F)
                }
                TunerType.FC0013, TunerType.FC0012 -> {
                    setFC0013LnaGain(gainIndex)
                }
                else -> {}
            }
            enableI2CRepeater(false)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error setting gain", e)
            false
        } finally {
            usbLock.unlock()
        }
    }

    fun setAutoGain(enabled: Boolean): Boolean {
        if (!isOpen) return false
        usbLock.lock()
        return try {
            enableI2CRepeater(true)
            when (tunerType) {
                TunerType.R820T, TunerType.R828D -> {
                    if (enabled) {
                        r820tWriteRegMask(0x05, 0x00, 0x10)
                        r820tWriteRegMask(0x07, 0x00, 0x10)
                    } else {
                        r820tWriteRegMask(0x05, 0x10, 0x10)
                        r820tWriteRegMask(0x07, 0x10, 0x10)
                    }
                }
                TunerType.FC0013, TunerType.FC0012 -> {
                    // FC0013: reg 0x14 bit 4 = AGC enable
                    val reg14 = fc0013ReadReg(0x14) ?: 0x50
                    if (enabled) {
                        fc0013WriteReg(0x14, (reg14 and 0xE0.inv()) or 0x10) // AGC on
                    } else {
                        fc0013WriteReg(0x14, reg14 and 0xEF.toInt()) // AGC off
                    }
                }
                else -> {}
            }
            enableI2CRepeater(false)

            // RTL2832U digital AGC (page 0, reg 0x19)
            writeDemodReg(0, 0x19, if (enabled) 0x25 else 0x05, 1)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error setting auto gain", e)
            false
        } finally {
            usbLock.unlock()
        }
    }

    fun resetBuffer(): Boolean {
        if (!isOpen) return false
        usbLock.lock()
        return try {
            resetBufferInternal()
        } catch (e: Exception) {
            false
        } finally {
            usbLock.unlock()
        }
    }

    private fun resetBufferInternal(): Boolean {
        writeReg(BLOCK_USB, USB_EPA_CTL, 0x1002, 2)  // Reset FIFO
        writeReg(BLOCK_USB, USB_EPA_CTL, 0x0000, 2)  // Clear reset
        return true
    }

    /**
     * Send USB standard CLEAR_FEATURE(ENDPOINT_HALT) to unstall the bulk endpoint.
     * Android's UsbDeviceConnection doesn't provide clearHalt(), so we do it manually.
     * This is CRITICAL — without it, bulkTransfer returns -1 immediately (EPIPE).
     */
    private fun clearEndpointHalt() {
        val conn = usbConnection ?: return
        val ep = bulkEndpoint ?: return
        // USB standard request: bmRequestType=0x02 (OUT|Standard|Endpoint),
        // bRequest=0x01 (CLEAR_FEATURE), wValue=0x00 (ENDPOINT_HALT),
        // wIndex=endpoint address
        val result = conn.controlTransfer(
            0x02,   // USB_DIR_OUT | USB_TYPE_STANDARD | USB_RECIP_ENDPOINT
            0x01,   // USB_REQ_CLEAR_FEATURE
            0x00,   // USB_FEATURE_ENDPOINT_HALT
            ep.address,
            null, 0, CTRL_TIMEOUT
        )
        DebugLog.log("USB", "clearHalt ep=0x${ep.address.toString(16)}: result=$result")
    }

    /**
     * Read IQ samples using UsbRequest (async API) — more reliable than synchronous
     * bulkTransfer() on many Android devices (especially Android 12+).
     * Falls back to synchronous bulkTransfer if UsbRequest fails.
     */
    fun readSamples(length: Int, timeoutMs: Int = USB_TIMEOUT): ByteArray? {
        if (!isOpen || bulkEndpoint == null) return null
        val ep = bulkEndpoint!!
        val conn = usbConnection ?: return null

        // Try async UsbRequest first (more reliable on Android 12+)
        if (useAsyncTransfer) {
            try {
                val buf = ByteBuffer.allocateDirect(length)
                val request = UsbRequest()
                if (request.initialize(conn, ep)) {
                    @Suppress("DEPRECATION")
                    val queued = request.queue(buf, length)
                    if (queued) {
                        val response = conn.requestWait(timeoutMs.toLong())
                        if (response != null) {
                            buf.flip()
                            val bytesRead = buf.remaining()
                            if (bytesRead > 0) {
                                val result = ByteArray(bytesRead)
                                buf.get(result)
                                request.close()
                                readErrorCount = 0
                                return result
                            }
                        }
                    }
                    request.close()
                }
            } catch (e: Exception) {
                if (readErrorCount < 3) {
                    DebugLog.log("USB", "UsbRequest error: ${e.message}, trying sync")
                }
            }
        }

        // Synchronous fallback
        val buffer = ByteArray(length)
        var totalRead = 0
        var zeroReads = 0

        while (totalRead < length) {
            val toRead = minOf(length - totalRead, ep.maxPacketSize * 64)
            val read = conn.bulkTransfer(ep, buffer, totalRead, toRead, timeoutMs)

            if (read > 0) {
                totalRead += read
                zeroReads = 0
            } else if (read == 0) {
                zeroReads++
                if (zeroReads > 10) {
                    return if (totalRead > 0) buffer.copyOf(totalRead) else null
                }
            } else {
                if (totalRead == 0 && readErrorCount < 5) {
                    readErrorCount++
                    DebugLog.log("USB", "bulkTransfer=$read toRead=$toRead ep=0x${ep.address.toString(16)} maxPkt=${ep.maxPacketSize}")
                }
                return if (totalRead > 0) buffer.copyOf(totalRead) else null
            }
        }
        readErrorCount = 0
        return buffer
    }

    // Limit logging of read errors to avoid spam
    @Volatile
    private var readErrorCount = 0

    // Use async UsbRequest by default (more reliable on Android 12+)
    private var useAsyncTransfer = true

    fun startStreaming(bufferSize: Int = 16384, callback: (ByteArray) -> Unit): Job {
        isStreaming = true
        readErrorCount = 0

        // Clear any USB endpoint stall condition FIRST
        // Android bulkTransfer returns -1 (EPIPE) if endpoint is stalled
        clearEndpointHalt()

        // Full USB FIFO reset before starting stream
        resetBuffer()

        // Give FIFO time to start filling after reset
        Thread.sleep(50)

        // Discard first read to flush stale data from USB pipe
        try {
            val ep = bulkEndpoint
            val conn = usbConnection
            if (ep != null && conn != null) {
                val discard1 = readSamples(minOf(bufferSize, 16384), 500)
                DebugLog.log("USB", "Discard read1: ${discard1?.size ?: -1} bytes")
                if (discard1 == null) {
                    // If first read fails, try clear halt again and retry
                    clearEndpointHalt()
                    Thread.sleep(20)
                    val discard2 = readSamples(minOf(bufferSize, 16384), 500)
                    DebugLog.log("USB", "Discard read2 (after re-clearHalt): ${discard2?.size ?: -1} bytes")
                }
            }
        } catch (_: Exception) {}

        // Reset FIFO again for clean start
        resetBuffer()
        Thread.sleep(10)

        return CoroutineScope(Dispatchers.IO).launch {
            Log.i(TAG, "Streaming started (bufSize=$bufferSize)")
            DebugLog.log("USB", "Streaming started, bufSize=$bufferSize")
            var readCount = 0L
            var totalBytes = 0L
            var lastLogTime = System.currentTimeMillis()
            var nullReads = 0
            while (isStreaming && isActive) {
                try {
                    val data = readSamples(bufferSize)
                    if (data != null && data.isNotEmpty()) {
                        readCount++
                        totalBytes += data.size
                        // Log first few reads and then periodically
                        val now = System.currentTimeMillis()
                        if (readCount <= 3 || now - lastLogTime > 5000) {
                            DebugLog.log("USB", "read #$readCount: ${data.size}B, total=${totalBytes/1024}KB, nulls=$nullReads")
                            lastLogTime = now
                            nullReads = 0
                        }
                        try {
                            callback(data)
                        } catch (e: Exception) {
                            if (isStreaming) {
                                Log.e(TAG, "Streaming callback error", e)
                                DebugLog.log("USB", "CALLBACK ERROR: ${e.message}")
                            }
                        }
                    } else {
                        nullReads++
                        if (nullReads <= 3) {
                            DebugLog.log("USB", "readSamples returned null/empty (#$nullReads)")
                        }
                    }
                } catch (e: Exception) {
                    if (isStreaming) {
                        Log.e(TAG, "Streaming read error", e)
                        DebugLog.log("USB", "READ ERROR: ${e.message}")
                        delay(10)
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
        usbLock.lock()
        return try {
            // Stop any ongoing transfers
            isStreaming = false
            Thread.sleep(50)

            // Clear USB endpoint stall (CRITICAL for Android)
            clearEndpointHalt()

            // Reset USB FIFO multiple times to ensure clean state
            resetBufferInternal()
            Thread.sleep(10)
            resetBufferInternal()
            Thread.sleep(10)

            // Clear stall again after FIFO reset
            clearEndpointHalt()

            // Discard any stale data in USB pipe
            for (i in 0 until 3) {
                val data = readSamples(bulkEndpoint?.maxPacketSize?.times(32) ?: 16384, 200)
                DebugLog.log("USB", "fullReset discard #$i: ${data?.size ?: -1} bytes")
                if (data == null) break
            }

            resetBufferInternal()
            Thread.sleep(10)
            Log.i(TAG, "Full USB reset completed")
            DebugLog.log("USB", "Full reset completed")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error during full reset", e)
            false
        } finally {
            usbLock.unlock()
        }
    }

    fun close() {
        stopStreaming()
        isStreaming = false
        try {
            // Disable I2C repeater before closing
            try { enableI2CRepeater(false) } catch (_: Exception) {}

            // Reset EPA (endpoint) to stop any pending transfers
            try { writeReg(BLOCK_USB, USB_EPA_CTL, 0x0002, 2) } catch (_: Exception) {}

            // Disable ADC (demod_ctl bit 5 = 0)
            try {
                val demodCtl = readReg(BLOCK_SYS, SYS_DEMOD_CTL, 1)
                writeReg(BLOCK_SYS, SYS_DEMOD_CTL, demodCtl and 0xDF.inv(), 1)
            } catch (_: Exception) {}

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
        isOpen = false
        Log.i(TAG, "Device closed and reset")
    }

    // --- USB device diagnostics and reset ---

    private fun logDeviceInfo(dev: UsbDevice) {
        DebugLog.log("USB", "Device: vid=0x${dev.vendorId.toString(16)} pid=0x${dev.productId.toString(16)}" +
                " class=${dev.deviceClass} subclass=${dev.deviceSubclass} protocol=${dev.deviceProtocol}")
        DebugLog.log("USB", "Device name=${dev.deviceName} ifaces=${dev.interfaceCount}")

        val iface = usbInterface
        if (iface != null) {
            DebugLog.log("USB", "Interface: id=${iface.id} class=${iface.interfaceClass}" +
                    " subclass=${iface.interfaceSubclass} endpoints=${iface.endpointCount}")
        }

        // Try to read raw USB descriptors for chip identification
        try {
            val raw = usbConnection?.rawDescriptors
            if (raw != null && raw.size >= 18) {
                val bcdUSB = (raw[3].toInt() and 0xFF shl 8) or (raw[2].toInt() and 0xFF)
                val bcdDevice = (raw[13].toInt() and 0xFF shl 8) or (raw[12].toInt() and 0xFF)
                DebugLog.log("USB", "Descriptor: bcdUSB=0x${bcdUSB.toString(16)} bcdDevice=0x${bcdDevice.toString(16)}" +
                        " maxPkt0=${raw[7].toInt() and 0xFF}")
            }
        } catch (_: Exception) {}

        // Log file descriptor for native USB
        try {
            val fd = usbConnection?.fileDescriptor ?: -1
            DebugLog.log("USB", "FD=$fd nativeUSB=${NativeUsb.isNativeAvailable}")
        } catch (_: Exception) {}
    }

    /**
     * Try USB SET_CONFIGURATION to reset device to known state.
     * This can help on devices where the USB stack needs a kick.
     */
    private fun usbResetDevice() {
        val conn = usbConnection ?: return
        try {
            // USB standard: SET_CONFIGURATION(1)
            // bmRequestType=0x00 (OUT|Standard|Device), bRequest=0x09 (SET_CONFIGURATION)
            val r1 = conn.controlTransfer(0x00, 0x09, 1, 0, null, 0, CTRL_TIMEOUT)
            DebugLog.log("USB", "SET_CONFIGURATION(1): result=$r1")

            Thread.sleep(50)

            // USB standard: SET_INTERFACE(0, 0)
            // bmRequestType=0x01 (OUT|Standard|Interface), bRequest=0x0B (SET_INTERFACE)
            val r2 = conn.controlTransfer(0x01, 0x0B, 0, 0, null, 0, CTRL_TIMEOUT)
            DebugLog.log("USB", "SET_INTERFACE(0,0): result=$r2")

            Thread.sleep(20)
        } catch (e: Exception) {
            DebugLog.log("USB", "USB reset error: ${e.message}")
        }
    }

    /**
     * Release and re-claim interface to reset USB state.
     * Some Android USB host controllers need this before I2C works.
     */
    private fun releaseAndReclaim() {
        val conn = usbConnection ?: return
        val iface = usbInterface ?: return
        try {
            conn.releaseInterface(iface)
            Thread.sleep(50)
            conn.claimInterface(iface, true)
            Thread.sleep(20)
            DebugLog.log("USB", "Interface release+reclaim done")
        } catch (e: Exception) {
            DebugLog.log("USB", "Release/reclaim error: ${e.message}")
        }
    }

    // --- Low-level USB control transfers ---

    /**
     * Write to RTL2832U register via USB vendor request.
     * Block: BLOCK_DEMOD(0), BLOCK_USB(1), BLOCK_SYS(2)
     */
    private fun writeReg(block: Int, addr: Int, value: Int, len: Int) {
        val conn = usbConnection ?: return
        val data = when (len) {
            1 -> byteArrayOf((value and 0xFF).toByte())
            2 -> byteArrayOf(((value shr 8) and 0xFF).toByte(), (value and 0xFF).toByte())
            else -> byteArrayOf((value and 0xFF).toByte())
        }

        val index = (block shl 8) or 0x10
        val result = conn.controlTransfer(CTRL_OUT, 0, addr, index, data, data.size, CTRL_TIMEOUT)
        if (result < 0) {
            Log.w(TAG, "writeReg failed: block=$block addr=0x${addr.toString(16)} value=0x${value.toString(16)}")
        }
    }

    /** writeReg with DebugLog for critical init steps */
    private fun writeRegChecked(block: Int, addr: Int, value: Int, len: Int, label: String) {
        val conn = usbConnection ?: run {
            DebugLog.log("USB", "FAIL $label: no connection")
            return
        }
        val data = when (len) {
            1 -> byteArrayOf((value and 0xFF).toByte())
            2 -> byteArrayOf(((value shr 8) and 0xFF).toByte(), (value and 0xFF).toByte())
            else -> byteArrayOf((value and 0xFF).toByte())
        }

        val index = (block shl 8) or 0x10
        val result = conn.controlTransfer(CTRL_OUT, 0, addr, index, data, data.size, CTRL_TIMEOUT)
        if (result < 0) {
            DebugLog.log("USB", "FAIL $label: ct=$result block=$block addr=0x${addr.toString(16)} val=0x${value.toString(16)}")
        } else {
            DebugLog.log("USB", "OK $label: val=0x${value.toString(16)} (${result}B)")
        }
    }

    @Suppress("SameParameterValue")
    private fun readReg(block: Int, addr: Int, len: Int): Int {
        val conn = usbConnection ?: return 0
        val data = ByteArray(len)
        // librtlsdr: reads use index = (block << 8) WITHOUT 0x10 flag
        val index = (block shl 8)

        conn.controlTransfer(CTRL_IN, 0, addr, index, data, data.size, CTRL_TIMEOUT)

        return when (len) {
            1 -> data[0].toInt() and 0xFF
            2 -> ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
            else -> data[0].toInt() and 0xFF
        }
    }

    /**
     * Write to RTL2832U demodulator register.
     * From librtlsdr rtlsdr_demod_write_reg:
     *   addr = (reg << 8) | 0x20
     *   index = page | 0x10
     */
    private fun writeDemodReg(page: Int, addr: Int, value: Int, len: Int) {
        val conn = usbConnection ?: return
        val data = when (len) {
            1 -> byteArrayOf((value and 0xFF).toByte())
            2 -> byteArrayOf(((value shr 8) and 0xFF).toByte(), (value and 0xFF).toByte())
            else -> byteArrayOf((value and 0xFF).toByte())
        }

        val usbAddr = (addr shl 8) or 0x20
        val index = page or 0x10  // librtlsdr: index = page | 0x10
        val result = conn.controlTransfer(CTRL_OUT, 0, usbAddr, index, data, data.size, CTRL_TIMEOUT)
        if (result < 0) {
            Log.w(TAG, "writeDemodReg failed: page=$page addr=0x${addr.toString(16)}")
        }

        // Dummy read after demod write (required by RTL2832U, from librtlsdr)
        readDemodReg(0x0A, 0x01, 1)
    }

    /**
     * Read from RTL2832U demodulator register.
     * From librtlsdr rtlsdr_demod_read_reg:
     *   addr = (reg << 8) | 0x20
     *   index = page (no 0x10 flag for reads)
     */
    private fun readDemodReg(page: Int, addr: Int, len: Int): Int {
        val conn = usbConnection ?: return 0
        val data = ByteArray(len)
        val usbAddr = (addr shl 8) or 0x20
        val index = page  // librtlsdr: index = page (no 0x10 for read)

        conn.controlTransfer(CTRL_IN, 0, usbAddr, index, data, data.size, CTRL_TIMEOUT)

        return when (len) {
            1 -> data[0].toInt() and 0xFF
            2 -> ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
            else -> data[0].toInt() and 0xFF
        }
    }

    /**
     * Configure GPIO pins to power on the tuner.
     * From librtlsdr: rtlsdr_set_gpio_output + rtlsdr_set_gpio_bit
     * GPIO4 (bit 4) = tuner power on most RTL-SDR v2/v3 dongles
     * Some dongles use GPIO5 (bit 5) — we set both to be safe.
     */
    private fun setupGpio() {
        // Read current GPIO state
        val gpoVal = readReg(BLOCK_SYS, SYS_GPO, 1)
        val gpoeVal = readReg(BLOCK_SYS, SYS_GPOE, 1)
        val gpdVal = readReg(BLOCK_SYS, SYS_GPD, 1)
        DebugLog.log("USB", "GPIO before: GPO=0x${gpoVal.toString(16)} GPOE=0x${gpoeVal.toString(16)} GPD=0x${gpdVal.toString(16)}")

        // Set GPIO4 and GPIO5 as output (direction=0 means output for these bits)
        // GPD: 0 = output, GPOE: 1 = enabled
        val gpioBits = 0x30  // bits 4,5
        // In librtlsdr: GPD bit=1 means output direction
        writeReg(BLOCK_SYS, SYS_GPD, gpdVal or gpioBits, 1)          // direction = output (1=output)
        writeReg(BLOCK_SYS, SYS_GPOE, gpoeVal or gpioBits, 1)        // output enable
        writeReg(BLOCK_SYS, SYS_GPO, gpoVal or gpioBits, 1)          // set HIGH (power on)

        val gpoAfter = readReg(BLOCK_SYS, SYS_GPO, 1)
        DebugLog.log("USB", "GPIO after: GPO=0x${gpoAfter.toString(16)} (tuner power on)")
    }

    /**
     * Enable/disable I2C repeater to access tuner via I2C bus.
     * Page 1, register 0x01: 0x18 = enable, 0x10 = disable.
     * From librtlsdr: rtlsdr_set_i2c_repeater → rtlsdr_demod_write_reg(dev, 1, 0x01, ...)
     */
    private fun enableI2CRepeater(enable: Boolean) {
        val value = if (enable) 0x18 else 0x10
        writeDemodReg(1, 0x01, value, 1)
        if (enable) {
            // Give I2C repeater time to activate — some devices need 20ms+
            Thread.sleep(20)
            // Verify repeater is enabled by reading back the register
            val readback = readDemodReg(1, 0x01, 1)
            DebugLog.log("USB", "I2C repeater ${if (enable) "ON" else "OFF"}: wrote=0x${value.toString(16)} readback=0x${readback.toString(16)}")
        }
    }

    // I2C index variants to try.
    // librtlsdr uses IICB=6, index=0x0600 for both read and write.
    // Some devices may need the 0x10 write-flag: 0x0610.
    private var i2cWriteIndex = 0x0600
    private var i2cReadIndex = 0x0600

    /**
     * Write to tuner via I2C bus.
     * Address is 8-bit I2C address (0x34 for R820T, 0x74 for R828D).
     */
    private fun i2cWrite(addr: Int, reg: Int, data: ByteArray) {
        val conn = usbConnection ?: return
        val buf = ByteArray(data.size + 1)
        buf[0] = reg.toByte()
        System.arraycopy(data, 0, buf, 1, data.size)

        val result = conn.controlTransfer(CTRL_OUT, 0, addr, i2cWriteIndex, buf, buf.size, I2C_TIMEOUT)
        if (result < 0) {
            Log.w(TAG, "i2cWrite failed: addr=0x${addr.toString(16)} reg=0x${reg.toString(16)}")
        }
    }

    /**
     * Read from tuner via I2C bus.
     * Two-phase: write register address, then read data.
     */
    @Suppress("SameParameterValue")
    private fun i2cRead(addr: Int, reg: Int, len: Int): ByteArray? {
        val conn = usbConnection ?: return null

        for (attempt in 0 until 3) {
            // Write register address
            val regBuf = byteArrayOf(reg.toByte())
            val wr = conn.controlTransfer(CTRL_OUT, 0, addr, i2cWriteIndex, regBuf, 1, I2C_TIMEOUT)
            if (wr < 0) {
                Thread.sleep(10L * (attempt + 1))
                continue
            }

            Thread.sleep(2)

            // Read data
            val data = ByteArray(len)
            val result = conn.controlTransfer(CTRL_IN, 0, addr, i2cReadIndex, data, len, I2C_TIMEOUT)
            if (result >= 0) return data
            Thread.sleep(10L * (attempt + 1))
        }
        return null
    }

    /**
     * Raw I2C read — just read without setting register address first.
     * Some devices support this for chip ID probing.
     */
    private fun i2cRawRead(addr: Int, len: Int): ByteArray? {
        val conn = usbConnection ?: return null
        val data = ByteArray(len)
        val result = conn.controlTransfer(CTRL_IN, 0, addr, i2cReadIndex, data, len, I2C_TIMEOUT)
        return if (result >= 0) data else null
    }

    /**
     * Quick I2C probe: try write+read to an I2C address with given index/request.
     * Returns true if either write or read succeeds.
     */
    private fun probeI2C(conn: UsbDeviceConnection, addr: Int, wrIdx: Int, rdIdx: Int, request: Int): Boolean {
        val testBuf = byteArrayOf(0x00)
        val rdBuf = ByteArray(1)

        val wr: Int
        val rd: Int

        if (NativeUsb.isNativeAvailable) {
            // Native: returns -errno for proper diagnostics
            wr = NativeUsb.controlTransfer(conn, CTRL_OUT, request, addr, wrIdx, testBuf, 1, I2C_TIMEOUT)
            rd = NativeUsb.controlTransfer(conn, CTRL_IN, request, addr, rdIdx, rdBuf, 1, I2C_TIMEOUT)
            DebugLog.log("USB", "  addr=0x${addr.toString(16)} req=$request wr=${NativeUsb.errnoName(wr)} rd=${NativeUsb.errnoName(rd)} data=0x${(rdBuf[0].toInt() and 0xFF).toString(16)}")
        } else {
            // Java fallback: only returns -1
            wr = conn.controlTransfer(CTRL_OUT, request, addr, wrIdx, testBuf, 1, I2C_TIMEOUT)
            rd = conn.controlTransfer(CTRL_IN, request, addr, rdIdx, rdBuf, 1, I2C_TIMEOUT)
            DebugLog.log("USB", "  addr=0x${addr.toString(16)} req=$request wr=$wr rd=$rd data=0x${(rdBuf[0].toInt() and 0xFF).toString(16)}")
        }
        return wr >= 0 || rd >= 0
    }

    /**
     * Probe all I2C addressing methods to find working one.
     * Tries multiple index/request combinations and USB state resets.
     */
    private fun probeI2CMethods(): Boolean {
        val conn = usbConnection ?: return false

        DebugLog.log("USB", "Native USB: ${if (NativeUsb.isNativeAvailable) "YES" else "NO (Java API fallback)"}")

        data class I2CMethod(val wrIdx: Int, val rdIdx: Int, val request: Int, val desc: String)

        val methods = listOf(
            I2CMethod(0x0600, 0x0600, 0, "IICB=6 req=0"),
            I2CMethod(0x0610, 0x0600, 0, "IICB=6|0x10 req=0"),
            I2CMethod(0x0600, 0x0600, 1, "IICB=6 req=1"),
            I2CMethod(0x0610, 0x0600, 1, "IICB=6|0x10 req=1"),
        )

        // Probe ALL known tuner addresses — generic RTL2832U (pid=0x2832) can have any tuner
        val tunerAddrs = intArrayOf(
            R820T_I2C_ADDR, R828D_I2C_ADDR,
            E4000_I2C_ADDR, FC0012_I2C_ADDR, FC2580_I2C_ADDR
        )

        // Phase 1: Try all methods with current USB state
        DebugLog.log("USB", "=== I2C probe Phase 1: standard ===")
        for (m in methods) {
            DebugLog.log("USB", "Method: ${m.desc}")
            for (addr in tunerAddrs) {
                if (probeI2C(conn, addr, m.wrIdx, m.rdIdx, m.request)) {
                    DebugLog.log("USB", "I2C WORKS: ${m.desc} addr=0x${addr.toString(16)}")
                    i2cWriteIndex = m.wrIdx
                    i2cReadIndex = m.rdIdx
                    return true
                }
            }
        }

        // Phase 2: Release+reclaim interface, then retry
        DebugLog.log("USB", "=== I2C probe Phase 2: after release+reclaim ===")
        releaseAndReclaim()
        enableI2CRepeater(true)

        for (m in methods.take(2)) { // Only try first 2 methods (req=0)
            DebugLog.log("USB", "Method: ${m.desc}")
            for (addr in tunerAddrs) {
                if (probeI2C(conn, addr, m.wrIdx, m.rdIdx, m.request)) {
                    DebugLog.log("USB", "I2C WORKS after reclaim: ${m.desc}")
                    i2cWriteIndex = m.wrIdx
                    i2cReadIndex = m.rdIdx
                    return true
                }
            }
        }

        // Phase 3: Read SYS block chip identification registers
        DebugLog.log("USB", "=== Chip identification ===")
        for (addr in arrayOf(0x3000, 0x3001, 0x3002, 0x3003, 0x3004, 0x3005, 0x3006, 0x3007, 0x3008, 0x300A, 0x300B, 0x300C)) {
            val v = readReg(BLOCK_SYS, addr, 1)
            DebugLog.log("USB", "  SYS[0x${addr.toString(16)}]=0x${v.toString(16)}")
        }

        DebugLog.log("USB", "ALL I2C methods FAILED")
        return false
    }

    fun getFrequency(): Long = centerFrequency
    fun getSampleRate(): Int = sampleRate
    fun isDeviceOpen(): Boolean = isOpen
    fun getTunerType(): TunerType = tunerType
}
