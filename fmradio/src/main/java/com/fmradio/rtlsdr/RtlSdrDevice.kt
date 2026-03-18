package com.fmradio.rtlsdr

import android.content.Context
import android.hardware.usb.*
import android.util.Log
import kotlinx.coroutines.*
import java.nio.ByteBuffer

/**
 * Built-in RTL-SDR driver that communicates directly with RTL2832U via Android USB Host API.
 * No external driver app needed.
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

        // RTL2832U registers
        private const val USB_TIMEOUT = 5000
        private const val CTRL_TIMEOUT = 300
        private const val BLOCK_DEMOD = 0x000
        private const val BLOCK_USB = 0x100
        private const val BLOCK_SYS = 0x200
        private const val BLOCK_TUNER = 0x300

        // RTL2832U control request types
        private const val CTRL_IN = UsbConstants.USB_DIR_IN or UsbConstants.USB_TYPE_VENDOR
        private const val CTRL_OUT = UsbConstants.USB_DIR_OUT or UsbConstants.USB_TYPE_VENDOR

        // Default sample rate for FM (1.152 MHz — divides cleanly to 48 kHz audio)
        const val DEFAULT_SAMPLE_RATE = 1152000

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
        val conn = usbConnection ?: return

        // Reset demod (write 1 to reg 0x01, then 0)
        writeReg(BLOCK_SYS, 0x3000 + 1, 0x04, 1)
        writeReg(BLOCK_SYS, 0x3000 + 1, 0x00, 1)

        // Disable IR
        writeReg(BLOCK_SYS, 0x3000 + 0x0D, 0x83, 1)

        // Init USB regs
        writeReg(BLOCK_USB, 0x2000 + 0x06, 0x09, 1)  // EPA_MAXPKT
        writeReg(BLOCK_USB, 0x2000 + 0x08, 0x00, 1)  // EPA_CTL clear FIFO

        // Enable I2C repeater for tuner access
        writeReg(BLOCK_DEMOD, 0x0001 + 1, 0x18, 1)

        // Detect tuner type
        tunerType = detectTuner()
        Log.i(TAG, "Detected tuner: $tunerType")

        // Initialize tuner
        when (tunerType) {
            TunerType.R820T, TunerType.R828D -> initR820T()
            else -> Log.w(TAG, "Unsupported tuner type: $tunerType")
        }

        // Set default sample rate
        setSampleRate(sampleRate)
    }

    private fun detectTuner(): TunerType {
        // Check for R820T (most common in RTL-SDR v2)
        val r820tAddr = 0x1A
        enableI2CRepeater(true)

        // Try reading R820T chip ID
        val data = i2cRead(r820tAddr, 0x00, 1)
        enableI2CRepeater(false)

        return if (data != null && data.isNotEmpty()) {
            when (data[0].toInt() and 0xFF) {
                0x69 -> TunerType.R820T
                0x69 or 0x80 -> TunerType.R828D
                else -> {
                    // Default to R820T for RTL-SDR v2
                    Log.i(TAG, "Assuming R820T tuner (RTL-SDR v2)")
                    TunerType.R820T
                }
            }
        } else {
            TunerType.R820T
        }
    }

    private fun initR820T() {
        enableI2CRepeater(true)

        // R820T initialization registers
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
            i2cWrite(0x1A, 0x05 + i, byteArrayOf(initRegs[i]))
        }

        enableI2CRepeater(false)
    }

    fun setFrequency(frequencyHz: Long): Boolean {
        if (!isOpen) return false
        centerFrequency = frequencyHz

        return try {
            enableI2CRepeater(true)
            setR820TFrequency(frequencyHz)
            enableI2CRepeater(false)

            // Set RTL2832U IF frequency
            val ifFreq = 0 // Zero-IF mode
            setIfFrequency(ifFreq)

            Log.d(TAG, "Frequency set to ${frequencyHz / 1000000.0} MHz")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error setting frequency", e)
            false
        }
    }

    private fun setR820TFrequency(freq: Long) {
        val tunerAddr = 0x1A

        // Calculate PLL divider for R820T
        // VCO frequency range: 1.77 GHz to 3.92 GHz
        val vcoMin = 1770000000L
        var mixDiv = 2
        var divNum = 0

        var vcoFreq = freq * mixDiv
        while (vcoFreq < vcoMin && mixDiv <= 64) {
            mixDiv *= 2
            divNum++
            vcoFreq = freq * mixDiv
        }

        // Reference frequency (28.8 MHz crystal on RTL-SDR v2)
        val refFreq = 28800000L
        val pllRef = refFreq

        val nInt = (vcoFreq / (2 * pllRef)).toInt()
        val vcoFra = ((vcoFreq - 2L * pllRef * nInt) / 1000).toInt()

        // Write PLL registers
        val ni = (nInt - 13) / 4
        val si = (nInt - 13) % 4
        val sdm = ((vcoFra.toLong() * 65536L) / (pllRef / 1000)).toInt()

        // reg 0x10: divider number
        i2cWrite(tunerAddr, 0x10, byteArrayOf(((divNum shl 5) or 0x00).toByte()))

        // reg 0x14: PLL settings
        i2cWrite(tunerAddr, 0x14, byteArrayOf((0x00 or (ni and 0x1F)).toByte()))
        i2cWrite(tunerAddr, 0x15, byteArrayOf(((si shl 6) or ((sdm shr 8) and 0x3F)).toByte()))
        i2cWrite(tunerAddr, 0x16, byteArrayOf((sdm and 0xFF).toByte()))
    }

    private fun setIfFrequency(ifFreq: Int) {
        // Write IF frequency to RTL2832U demod
        val ifFreqScaled = ((-ifFreq.toLong() * (1L shl 22)) / 28800000L + (1L shl 22)).toInt()
        writeReg(BLOCK_DEMOD, 0x0019, (ifFreqScaled shr 16) and 0x3F, 1)
        writeReg(BLOCK_DEMOD, 0x001A, (ifFreqScaled shr 8) and 0xFF, 1)
        writeReg(BLOCK_DEMOD, 0x001B, ifFreqScaled and 0xFF, 1)
    }

    fun setSampleRate(rate: Int): Boolean {
        if (!isOpen) return false
        sampleRate = rate

        return try {
            // Calculate resampler ratio
            val rsampRatio = ((28800000L * (1L shl 22)) / rate).toInt()

            writeReg(BLOCK_DEMOD, 0x009F, (rsampRatio shr 16) and 0xFFFF, 2)
            writeReg(BLOCK_DEMOD, 0x00A1, rsampRatio and 0xFFFF, 2)

            // Reset demod
            writeReg(BLOCK_DEMOD, 0x0001 + 1, 0x14, 1)
            writeReg(BLOCK_DEMOD, 0x0001 + 1, 0x10, 1)

            // Set bandwidth for R820T
            enableI2CRepeater(true)
            setR820TBandwidth(rate)
            enableI2CRepeater(false)

            Log.d(TAG, "Sample rate set to $rate Hz")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error setting sample rate", e)
            false
        }
    }

    private fun setR820TBandwidth(bandwidth: Int) {
        // Set R820T IF filter bandwidth
        val bwKhz = bandwidth / 1000
        val filterCap = when {
            bwKhz < 200 -> 0x0F
            bwKhz < 350 -> 0x0B
            bwKhz < 500 -> 0x08
            bwKhz < 800 -> 0x04
            bwKhz < 1200 -> 0x02
            else -> 0x00
        }
        i2cWrite(0x1A, 0x0A, byteArrayOf(((filterCap shl 4) or 0x0B).toByte()))
    }

    fun setGain(gainIndex: Int): Boolean {
        if (!isOpen) return false
        return try {
            enableI2CRepeater(true)

            // R820T gain table (in 0.1 dB steps)
            val lnaGains = intArrayOf(0, 9, 13, 40, 38, 13, 31, 26, 31, 26, 14, 19, 5, 35, 13, 0)
            val mixerGains = intArrayOf(0, 5, 10, 10, 19, 9, 10, 25, 17, 10, 8, 16, 13, 6, 3, 0)

            val idx = gainIndex.coerceIn(0, 15)

            // Set LNA gain
            i2cWrite(0x1A, 0x05, byteArrayOf((0x10 or idx).toByte()))
            // Set mixer gain
            i2cWrite(0x1A, 0x07, byteArrayOf((0x10 or idx).toByte()))

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
            if (enabled) {
                // AGC on
                i2cWrite(0x1A, 0x05, byteArrayOf(0x00.toByte()))
                i2cWrite(0x1A, 0x07, byteArrayOf(0x10.toByte()))
            }
            enableI2CRepeater(false)

            // RTL2832U AGC
            writeReg(BLOCK_DEMOD, 0x0019 + 8, if (enabled) 0x25 else 0x05, 1)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error setting auto gain", e)
            false
        }
    }

    fun resetBuffer(): Boolean {
        if (!isOpen) return false
        return try {
            writeReg(BLOCK_USB, 0x2000 + 0x08, 0x02, 1)  // EPA_CTL: reset FIFO
            writeReg(BLOCK_USB, 0x2000 + 0x08, 0x00, 1)
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
        isOpen = false
    }

    // --- Low-level USB control transfers ---

    private fun writeReg(block: Int, addr: Int, value: Int, len: Int) {
        val conn = usbConnection ?: return
        val data = when (len) {
            1 -> byteArrayOf((value and 0xFF).toByte())
            2 -> byteArrayOf(((value shr 8) and 0xFF).toByte(), (value and 0xFF).toByte())
            else -> byteArrayOf((value and 0xFF).toByte())
        }

        val index = (block shl 8) or 0x10
        conn.controlTransfer(
            CTRL_OUT, 0, addr, index, data, data.size, CTRL_TIMEOUT
        )
    }

    @Suppress("SameParameterValue")
    private fun readReg(block: Int, addr: Int, len: Int): Int {
        val conn = usbConnection ?: return 0
        val data = ByteArray(len)
        val index = (block shl 8) or 0x10

        conn.controlTransfer(
            CTRL_IN, 0, addr, index, data, data.size, CTRL_TIMEOUT
        )

        return when (len) {
            1 -> data[0].toInt() and 0xFF
            2 -> ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
            else -> data[0].toInt() and 0xFF
        }
    }

    private fun enableI2CRepeater(enable: Boolean) {
        writeReg(BLOCK_DEMOD, 0x0001 + 1, if (enable) 0x18 else 0x10, 1)
    }

    private fun i2cWrite(addr: Int, reg: Int, data: ByteArray) {
        val conn = usbConnection ?: return
        val buf = ByteArray(data.size + 1)
        buf[0] = reg.toByte()
        System.arraycopy(data, 0, buf, 1, data.size)

        conn.controlTransfer(
            CTRL_OUT, 0, addr, 0x0600, buf, buf.size, CTRL_TIMEOUT
        )
    }

    @Suppress("SameParameterValue")
    private fun i2cRead(addr: Int, reg: Int, len: Int): ByteArray? {
        val conn = usbConnection ?: return null
        val regBuf = byteArrayOf(reg.toByte())
        conn.controlTransfer(
            CTRL_OUT, 0, addr, 0x0600, regBuf, 1, CTRL_TIMEOUT
        )

        val data = ByteArray(len)
        val result = conn.controlTransfer(
            CTRL_IN, 0, addr, 0x0600, data, len, CTRL_TIMEOUT
        )
        return if (result >= 0) data else null
    }

    fun getFrequency(): Long = centerFrequency
    fun getSampleRate(): Int = sampleRate
    fun isDeviceOpen(): Boolean = isOpen
    fun getTunerType(): TunerType = tunerType
}
