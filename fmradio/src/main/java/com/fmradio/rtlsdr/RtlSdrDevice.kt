package com.fmradio.rtlsdr

import android.content.Context
import android.hardware.usb.*
import android.util.Log
import kotlinx.coroutines.*

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

        // USB timeout
        private const val USB_TIMEOUT = 5000
        private const val CTRL_TIMEOUT = 300

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
        private const val SYS_DEMOD_CTL_1 = 0x300B
        private const val SYS_IR_SUSPEND = 0x300C

        // R820T/R828D I2C address (8-bit format, as used by RTL2832U firmware)
        private const val R820T_I2C_ADDR = 0x34
        private const val R828D_I2C_ADDR = 0x74

        // Default sample rate for FM (1.152 MHz — divides cleanly to 48 kHz audio)
        const val DEFAULT_SAMPLE_RATE = 1152000

        // Crystal frequency
        private const val RTL_XTAL_FREQ = 28800000L

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

            // Initialize RTL2832U
            initializeDevice()
            isOpen = true
            Log.i(TAG, "RTL-SDR device opened successfully")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error opening device", e)
            close()
            return false
        }
    }

    private fun initializeDevice() {
        // === Initialize USB (from librtlsdr rtlsdr_init_baseband) ===
        writeReg(BLOCK_USB, USB_SYSCTL, 0x09, 1)
        writeReg(BLOCK_USB, USB_EPA_MAXPKT, 0x0002, 2)
        writeReg(BLOCK_USB, USB_EPA_CTL, 0x1002, 2)

        // === Power on demod ===
        writeReg(BLOCK_SYS, SYS_DEMOD_CTL_1, 0x22, 1)
        writeReg(BLOCK_SYS, SYS_DEMOD_CTL, 0xE8, 1)

        // === Reset demod (page 1, reg 0x01) ===
        writeDemodReg(1, 0x01, 0x14, 1)
        writeDemodReg(1, 0x01, 0x10, 1)

        // === Disable spectrum inversion and adjacent channel rejection ===
        writeDemodReg(1, 0x15, 0x00, 1)
        writeDemodReg(1, 0x16, 0x0000, 2)

        // === Clear DDC shift and IF frequency registers ===
        for (i in 0..5) {
            writeDemodReg(1, 0x16 + i, 0x00, 1)
        }

        // === Set default FIR coefficients ===
        setFirCoefficients()

        // === Disable IR ===
        writeReg(BLOCK_SYS, SYS_IR_SUSPEND, 0x83, 1)

        // === Enable I2C repeater for tuner access ===
        enableI2CRepeater(true)

        // === Detect tuner type ===
        tunerType = detectTuner()
        Log.i(TAG, "Detected tuner: $tunerType")

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
            else -> Log.w(TAG, "Unsupported tuner type: $tunerType")
        }

        enableI2CRepeater(false)

        // Set default sample rate
        setSampleRate(sampleRate)
    }

    /**
     * Set FIR coefficients (from librtlsdr default_fir).
     * These are the default coefficients for the RTL2832U digital filter.
     */
    private fun setFirCoefficients() {
        // Default FIR coefficients from librtlsdr
        val fir = intArrayOf(
            -54, -36, -41, -40, -32, -14, 14, 53,     // 8-bit signed
            101, 156, 215, 273, 327, 372, 404, 421,   // 12-bit signed
            421, 404, 372, 327, 273, 215, 156, 101,
            53, 14, -14, -32, -40, -41, -36, -54
        )

        // Pack into 20 bytes: first 8 coefficients as 8-bit, remaining 24 as 12-bit packed
        val firBytes = ByteArray(20)

        // First 8 coefficients: 8-bit signed, one byte each
        for (i in 0 until 8) {
            firBytes[i] = (fir[i] and 0xFF).toByte()
        }

        // Remaining 24 coefficients: 12-bit signed, packed as 1.5 bytes each
        for (i in 0 until 8) {
            val val1 = fir[8 + i * 3] and 0xFFF
            val val2 = if (8 + i * 3 + 1 < fir.size) fir[8 + i * 3 + 1] and 0xFFF else 0
            val idx = 8 + i * 3
            if (idx < 20) firBytes[idx] = (val1 and 0xFF).toByte()
            if (idx + 1 < 20) firBytes[idx + 1] = (((val1 shr 8) and 0x0F) or ((val2 shl 4) and 0xF0)).toByte()
            if (idx + 2 < 20) firBytes[idx + 2] = ((val2 shr 4) and 0xFF).toByte()
        }

        // Write FIR to demod register 0xB1 (page 1)
        for (i in firBytes.indices) {
            writeDemodReg(1, 0x1C + i, firBytes[i].toInt() and 0xFF, 1)
        }
    }

    private fun detectTuner(): TunerType {
        // Try R820T first (most common in RTL-SDR v2/v3)
        var data = i2cRead(R820T_I2C_ADDR, 0x00, 1)
        if (data != null && data.isNotEmpty()) {
            val chipId = data[0].toInt() and 0xFF
            Log.i(TAG, "R820T chip ID: 0x${chipId.toString(16)}")
            if (chipId == 0x69) return TunerType.R820T
        }

        // Try R828D
        data = i2cRead(R828D_I2C_ADDR, 0x00, 1)
        if (data != null && data.isNotEmpty()) {
            val chipId = data[0].toInt() and 0xFF
            Log.i(TAG, "R828D chip ID: 0x${chipId.toString(16)}")
            if (chipId == 0x69) return TunerType.R828D
        }

        // Default to R820T for RTL-SDR v2
        Log.i(TAG, "Tuner not positively identified, assuming R820T")
        return TunerType.R820T
    }

    private fun initR820T() {
        // R820T initialization registers (from r82xx.c r82xx_init_array)
        // Registers 0x05 to 0x1F (27 registers)
        val initRegs = byteArrayOf(
            0x83.toByte(), 0x32.toByte(), 0x75.toByte(), // reg 0x05-0x07
            0xC0.toByte(), 0x40.toByte(), 0xD6.toByte(), // reg 0x08-0x0A
            0x6C.toByte(), 0xF5.toByte(), 0x63.toByte(), // reg 0x0B-0x0D
            0x75.toByte(), 0x68.toByte(), 0x6C.toByte(), // reg 0x0E-0x10
            0x83.toByte(), 0x80.toByte(), 0x00.toByte(), // reg 0x11-0x13
            0x0F.toByte(), 0x00.toByte(), 0xC0.toByte(), // reg 0x14-0x16
            0x30.toByte(), 0x48.toByte(), 0xCC.toByte(), // reg 0x17-0x19
            0x60.toByte(), 0x00.toByte(), 0x54.toByte(), // reg 0x1A-0x1C
            0xAE.toByte(), 0x4A.toByte(), 0xC0.toByte(), // reg 0x1D-0x1F
        )

        for (i in initRegs.indices) {
            i2cWrite(tunerI2CAddr, 0x05 + i, byteArrayOf(initRegs[i]))
        }

        // Initialize calibration
        initR820TCalibration()
    }

    /**
     * R820T initial calibration sequence from r82xx.c r82xx_init.
     * Sets up VGA, tracking filter, and runs initial calibration.
     */
    private fun initR820TCalibration() {
        // Set VGA to highest gain for calibration
        i2cWrite(tunerI2CAddr, 0x0C, byteArrayOf(0x0B.toByte()))

        // Set tracking filter to auto
        i2cWrite(tunerI2CAddr, 0x06, byteArrayOf(0x35.toByte()))

        // Set LNA to auto
        i2cWrite(tunerI2CAddr, 0x05, byteArrayOf(0x00.toByte()))

        // Set mixer to auto
        i2cWrite(tunerI2CAddr, 0x07, byteArrayOf(0x00.toByte()))

        Log.i(TAG, "R820T calibration complete")
    }

    fun setFrequency(frequencyHz: Long): Boolean {
        if (!isOpen) return false
        centerFrequency = frequencyHz

        usbLock.lock()
        return try {
            enableI2CRepeater(true)
            setR820TFrequency(frequencyHz)
            enableI2CRepeater(false)

            // Set RTL2832U IF frequency (zero-IF mode)
            setIfFrequency(0)

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

        // Calculate N-integer and fractional parts
        val nInt = (vcoFreq / (2 * pllRef)).toInt()
        val vcoFra = ((vcoFreq - 2L * pllRef * nInt) / 1000).toInt()

        // NI and SI from r82xx.c
        val ni = (nInt - 13) / 4
        val si = nInt % 4

        // SDM (sigma-delta modulator) fractional value
        val sdm = ((vcoFra.toLong() * 65536L) / (pllRef / 1000)).toInt()

        // Write PLL registers (matching r82xx.c r82xx_set_pll)
        // reg 0x10: mixer divider number
        i2cWrite(tunerI2CAddr, 0x10, byteArrayOf(((divNum shl 5) or 0x00).toByte()))

        // reg 0x14: NI + SI (PLL integer divider)
        i2cWrite(tunerI2CAddr, 0x14, byteArrayOf(((ni and 0x1F) or ((si and 0x03) shl 6)).toByte()))

        // reg 0x12-0x13: SDM fractional value (matching librtlsdr register map)
        i2cWrite(tunerI2CAddr, 0x12, byteArrayOf(((sdm shr 8) and 0xFF).toByte()))
        i2cWrite(tunerI2CAddr, 0x13, byteArrayOf((sdm and 0xFF).toByte()))

        // Wait for PLL lock
        Thread.sleep(10)

        // Check PLL lock status
        val lockData = i2cRead(tunerI2CAddr, 0x02, 1)
        if (lockData != null && lockData.isNotEmpty()) {
            val locked = (lockData[0].toInt() and 0x40) != 0
            if (!locked) {
                Log.w(TAG, "PLL not locked at ${freq / 1e6} MHz, retrying...")
                // Retry with VCO power adjustment
                i2cWrite(tunerI2CAddr, 0x12, byteArrayOf(((sdm shr 8) and 0xFF).toByte()))
                i2cWrite(tunerI2CAddr, 0x13, byteArrayOf((sdm and 0xFF).toByte()))
                Thread.sleep(20)
                val lockRetry = i2cRead(tunerI2CAddr, 0x02, 1)
                if (lockRetry != null && (lockRetry[0].toInt() and 0x40) == 0) {
                    Log.w(TAG, "PLL still not locked at ${freq / 1e6} MHz")
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

            // Set bandwidth for R820T
            enableI2CRepeater(true)
            setR820TBandwidth(rate)
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
        // Set R820T IF filter bandwidth via register 0x0A
        val bwKhz = bandwidth / 1000
        val filterCap = when {
            bwKhz < 200 -> 0x0F
            bwKhz < 350 -> 0x0B
            bwKhz < 500 -> 0x08
            bwKhz < 800 -> 0x04
            bwKhz < 1200 -> 0x02
            else -> 0x00
        }
        i2cWrite(tunerI2CAddr, 0x0A, byteArrayOf(((filterCap shl 4) or 0x0B).toByte()))
    }

    fun setGain(gainIndex: Int): Boolean {
        if (!isOpen) return false
        usbLock.lock()
        return try {
            enableI2CRepeater(true)

            val idx = gainIndex.coerceIn(0, 15)

            // Set LNA gain (reg 0x05)
            i2cWrite(tunerI2CAddr, 0x05, byteArrayOf((0x10 or idx).toByte()))
            // Set mixer gain (reg 0x07)
            i2cWrite(tunerI2CAddr, 0x07, byteArrayOf((0x10 or idx).toByte()))

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
            if (enabled) {
                // R820T: LNA AGC on (reg 0x05 bit 4 = 0 for auto)
                i2cWrite(tunerI2CAddr, 0x05, byteArrayOf(0x00.toByte()))
                // R820T: Mixer AGC on (reg 0x07 bit 4 = 0 for auto)
                i2cWrite(tunerI2CAddr, 0x07, byteArrayOf(0x10.toByte()))
            } else {
                // Manual gain mode
                i2cWrite(tunerI2CAddr, 0x05, byteArrayOf(0x10.toByte()))
                i2cWrite(tunerI2CAddr, 0x07, byteArrayOf(0x10.toByte()))
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
            writeReg(BLOCK_USB, USB_EPA_CTL, 0x1002, 2)  // Reset FIFO
            writeReg(BLOCK_USB, USB_EPA_CTL, 0x0000, 2)  // Clear reset
            true
        } catch (e: Exception) {
            false
        } finally {
            usbLock.unlock()
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
                try {
                    val data = readSamples(bufferSize)
                    if (data != null && data.isNotEmpty()) {
                        try {
                            callback(data)
                        } catch (e: Exception) {
                            if (isStreaming) {
                                Log.e(TAG, "Streaming callback error", e)
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (isStreaming) {
                        Log.e(TAG, "Streaming read error", e)
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
        } finally {
            usbLock.unlock()
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
        isOpen = false
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

    @Suppress("SameParameterValue")
    private fun readReg(block: Int, addr: Int, len: Int): Int {
        val conn = usbConnection ?: return 0
        val data = ByteArray(len)
        val index = (block shl 8) or 0x10

        conn.controlTransfer(CTRL_IN, 0, addr, index, data, data.size, CTRL_TIMEOUT)

        return when (len) {
            1 -> data[0].toInt() and 0xFF
            2 -> ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
            else -> data[0].toInt() and 0xFF
        }
    }

    /**
     * Write to RTL2832U demodulator register.
     * Uses the (addr << 8) | 0x20 addressing scheme from librtlsdr rtlsdr_demod_write_reg.
     */
    private fun writeDemodReg(page: Int, addr: Int, value: Int, len: Int) {
        val conn = usbConnection ?: return
        val data = when (len) {
            1 -> byteArrayOf((value and 0xFF).toByte())
            2 -> byteArrayOf(((value shr 8) and 0xFF).toByte(), (value and 0xFF).toByte())
            else -> byteArrayOf((value and 0xFF).toByte())
        }

        val usbAddr = (addr shl 8) or 0x20
        val index = (BLOCK_DEMOD shl 8) or 0x10  // 0x0010
        val result = conn.controlTransfer(CTRL_OUT, 0, usbAddr, index, data, data.size, CTRL_TIMEOUT)
        if (result < 0) {
            Log.w(TAG, "writeDemodReg failed: page=$page addr=0x${addr.toString(16)}")
        }

        // Dummy read after demod write (required by RTL2832U, from librtlsdr)
        readDemodReg(0x0A, 0x01, 1)
    }

    /**
     * Read from RTL2832U demodulator register.
     */
    private fun readDemodReg(page: Int, addr: Int, len: Int): Int {
        val conn = usbConnection ?: return 0
        val data = ByteArray(len)
        val usbAddr = (addr shl 8) or 0x20
        val index = (BLOCK_DEMOD shl 8)  // No 0x10 for read (matching librtlsdr)

        conn.controlTransfer(CTRL_IN, 0, usbAddr, index, data, data.size, CTRL_TIMEOUT)

        return when (len) {
            1 -> data[0].toInt() and 0xFF
            2 -> ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
            else -> data[0].toInt() and 0xFF
        }
    }

    /**
     * Enable/disable I2C repeater to access tuner via I2C bus.
     * Page 1, register 0x01: 0x18 = enable, 0x10 = disable.
     */
    private fun enableI2CRepeater(enable: Boolean) {
        val conn = usbConnection ?: return
        val data = byteArrayOf(if (enable) 0x18.toByte() else 0x10.toByte())
        val usbAddr = (0x01 shl 8) or 0x20  // 0x0120
        val index = (BLOCK_DEMOD shl 8) or 0x10
        conn.controlTransfer(CTRL_OUT, 0, usbAddr, index, data, data.size, CTRL_TIMEOUT)
    }

    /**
     * Write to tuner via I2C bus.
     * Address is 8-bit I2C address (0x34 for R820T, 0x74 for R828D).
     */
    private fun i2cWrite(addr: Int, reg: Int, data: ByteArray) {
        val conn = usbConnection ?: return
        val buf = ByteArray(data.size + 1)
        buf[0] = reg.toByte()
        System.arraycopy(data, 0, buf, 1, data.size)

        val result = conn.controlTransfer(CTRL_OUT, 0, addr, 0x0600, buf, buf.size, CTRL_TIMEOUT)
        if (result < 0) {
            Log.w(TAG, "i2cWrite failed: addr=0x${addr.toString(16)} reg=0x${reg.toString(16)}")
        }
    }

    /**
     * Read from tuner via I2C bus.
     */
    @Suppress("SameParameterValue")
    private fun i2cRead(addr: Int, reg: Int, len: Int): ByteArray? {
        val conn = usbConnection ?: return null

        // Write register address
        val regBuf = byteArrayOf(reg.toByte())
        conn.controlTransfer(CTRL_OUT, 0, addr, 0x0600, regBuf, 1, CTRL_TIMEOUT)

        // Read data
        val data = ByteArray(len)
        val result = conn.controlTransfer(CTRL_IN, 0, addr, 0x0600, data, len, CTRL_TIMEOUT)
        return if (result >= 0) data else null
    }

    fun getFrequency(): Long = centerFrequency
    fun getSampleRate(): Int = sampleRate
    fun isDeviceOpen(): Boolean = isOpen
    fun getTunerType(): TunerType = tunerType
}
