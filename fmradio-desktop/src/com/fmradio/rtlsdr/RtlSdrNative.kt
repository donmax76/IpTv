package com.fmradio.rtlsdr

import com.sun.jna.*
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference

/**
 * Direct librtlsdr access via JNA — no rtl_tcp server needed.
 * Mirrors the Python pyrtlsdr behavior: open device, set params, read IQ samples.
 *
 * Requires librtlsdr.dll (Windows) or librtlsdr.so (Linux) on library path.
 */
class RtlSdrNative {

    /**
     * JNA interface to librtlsdr C library.
     */
    interface LibRtlSdr : Library {
        fun rtlsdr_get_device_count(): Int
        fun rtlsdr_get_device_name(index: Int): String
        fun rtlsdr_open(dev: PointerByReference, index: Int): Int
        fun rtlsdr_close(dev: Pointer): Int
        fun rtlsdr_set_center_freq(dev: Pointer, freq: Int): Int
        fun rtlsdr_get_center_freq(dev: Pointer): Int
        fun rtlsdr_set_sample_rate(dev: Pointer, rate: Int): Int
        fun rtlsdr_get_sample_rate(dev: Pointer): Int
        fun rtlsdr_set_tuner_gain_mode(dev: Pointer, manual: Int): Int
        fun rtlsdr_set_tuner_gain(dev: Pointer, gain: Int): Int
        fun rtlsdr_get_tuner_gains(dev: Pointer, gains: IntArray?): Int
        fun rtlsdr_set_agc_mode(dev: Pointer, on: Int): Int
        fun rtlsdr_set_direct_sampling(dev: Pointer, on: Int): Int
        fun rtlsdr_set_bias_tee(dev: Pointer, on: Int): Int
        fun rtlsdr_get_tuner_type(dev: Pointer): Int
        fun rtlsdr_reset_buffer(dev: Pointer): Int
        fun rtlsdr_read_sync(dev: Pointer, buf: ByteArray, len: Int, nRead: IntByReference): Int
    }

    companion object {
        private val TUNER_NAMES = arrayOf("Unknown", "E4000", "FC0012", "FC0013", "FC2580", "R820T", "R828D")

        /**
         * Try to load librtlsdr from common locations.
         */
        fun loadLibrary(): LibRtlSdr? {
            val names = listOf("rtlsdr", "librtlsdr")
            for (name in names) {
                try {
                    return Native.load(name, LibRtlSdr::class.java) as LibRtlSdr
                } catch (_: UnsatisfiedLinkError) { }
            }
            return null
        }

        fun getDeviceCount(lib: LibRtlSdr): Int = lib.rtlsdr_get_device_count()
    }

    private var lib: LibRtlSdr? = null
    private var devPtr: Pointer? = null

    @Volatile
    var isOpen = false
        private set

    @Volatile
    var isStreaming = false
        private set

    var tunerName = "Unknown"
        private set
    var deviceName = "RTL-SDR"
        private set

    /**
     * Open RTL-SDR device by index (default 0).
     * Returns true on success.
     */
    fun open(deviceIndex: Int = 0): Boolean {
        val l = loadLibrary()
        if (l == null) {
            println("ERROR: librtlsdr not found. Install RTL-SDR drivers.")
            return false
        }
        lib = l

        val count = l.rtlsdr_get_device_count()
        if (count == 0) {
            println("ERROR: No RTL-SDR devices found.")
            return false
        }

        deviceName = try { l.rtlsdr_get_device_name(deviceIndex) } catch (_: Exception) { "RTL-SDR" }

        val devRef = PointerByReference()
        val ret = l.rtlsdr_open(devRef, deviceIndex)
        if (ret != 0) {
            println("ERROR: rtlsdr_open failed (code $ret). Device may be in use.")
            return false
        }

        devPtr = devRef.value
        val tunerType = l.rtlsdr_get_tuner_type(devPtr!!)
        tunerName = if (tunerType in TUNER_NAMES.indices) TUNER_NAMES[tunerType] else "Unknown($tunerType)"

        isOpen = true
        println("RTL-SDR opened: $deviceName (tuner=$tunerName)")
        return true
    }

    fun setSampleRate(rate: Int) {
        val dev = devPtr ?: return
        lib?.rtlsdr_set_sample_rate(dev, rate)
    }

    fun setFrequency(frequencyHz: Long) {
        val dev = devPtr ?: return
        lib?.rtlsdr_set_center_freq(dev, frequencyHz.toInt())
    }

    /**
     * Get list of supported tuner gain values (in tenths of dB).
     * E.g. for R820T: [0, 9, 14, 27, 37, 77, 87, 125, 144, 157, 166, 197,
     *                   207, 229, 254, 280, 297, 328, 338, 364, 372, 386,
     *                   402, 421, 434, 439, 445, 480, 496]
     */
    fun getSupportedGains(): IntArray {
        val dev = devPtr ?: return intArrayOf()
        val l = lib ?: return intArrayOf()
        val count = l.rtlsdr_get_tuner_gains(dev, null)
        if (count <= 0) return intArrayOf()
        val gains = IntArray(count)
        l.rtlsdr_get_tuner_gains(dev, gains)
        return gains
    }

    fun setAutoGain(enabled: Boolean) {
        val dev = devPtr ?: return
        val l = lib ?: return
        l.rtlsdr_set_tuner_gain_mode(dev, if (enabled) 0 else 1)
        l.rtlsdr_set_agc_mode(dev, if (enabled) 1 else 0)
        println("Gain mode: ${if (enabled) "auto" else "manual"}")
    }

    fun setGain(gainTenths: Int) {
        val dev = devPtr ?: return
        val l = lib ?: return
        l.rtlsdr_set_tuner_gain_mode(dev, 1) // manual
        l.rtlsdr_set_tuner_gain(dev, gainTenths)
        println("Tuner gain set to: ${gainTenths / 10.0} dB")
    }

    /**
     * Set gain to maximum supported by the tuner.
     * Returns the actual gain value set (in tenths of dB), or -1 on failure.
     */
    fun setMaxGain(): Int {
        val gains = getSupportedGains()
        if (gains.isEmpty()) {
            println("WARNING: Could not query tuner gains, using auto-gain")
            setAutoGain(true)
            return -1
        }
        val maxGain = gains.max()
        println("Supported gains: ${gains.map { it / 10.0 }} dB")
        println("Setting maximum gain: ${maxGain / 10.0} dB")
        setGain(maxGain)
        return maxGain
    }

    /**
     * Enable direct sampling for HF/shortwave reception (0-28 MHz).
     * mode: 0=disabled (normal), 1=I-ADC, 2=Q-ADC
     * For most RTL-SDR dongles, mode 2 (Q-ADC) works best.
     */
    fun setDirectSampling(mode: Int) {
        val dev = devPtr ?: return
        lib?.rtlsdr_set_direct_sampling(dev, mode)
    }

    fun resetBuffer() {
        val dev = devPtr ?: return
        lib?.rtlsdr_reset_buffer(dev)
    }

    /**
     * Read IQ samples synchronously. Returns raw unsigned 8-bit IQ interleaved data.
     */
    fun readSamples(length: Int): ByteArray? {
        val dev = devPtr ?: return null
        val l = lib ?: return null
        val buf = ByteArray(length)
        val nRead = IntByReference()
        val ret = l.rtlsdr_read_sync(dev, buf, length, nRead)
        if (ret != 0) {
            println("rtlsdr_read_sync error: $ret")
            return null
        }
        return if (nRead.value == length) buf else buf.copyOf(nRead.value)
    }

    /**
     * Start streaming in a background thread. Calls callback with IQ data chunks.
     */
    fun startStreaming(bufferSize: Int = 65536, callback: (ByteArray) -> Unit): Thread {
        isStreaming = true
        resetBuffer()
        val thread = Thread({
            println("Streaming started (direct USB)")
            while (isStreaming && isOpen) {
                val data = readSamples(bufferSize)
                if (data != null && data.isNotEmpty()) {
                    callback(data)
                } else {
                    Thread.sleep(1)
                }
            }
            println("Streaming stopped")
        }, "RtlSdrStreaming")
        thread.isDaemon = true
        thread.start()
        return thread
    }

    fun stopStreaming() {
        isStreaming = false
    }

    fun close() {
        isStreaming = false
        isOpen = false
        devPtr?.let { lib?.rtlsdr_close(it) }
        devPtr = null
        lib = null
    }
}
