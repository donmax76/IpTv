package com.fmradio.rtlsdr

import com.sun.jna.*
import java.io.File
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
        fun rtlsdr_set_offset_tuning(dev: Pointer, on: Int): Int
        fun rtlsdr_get_offset_tuning(dev: Pointer): Int
        fun rtlsdr_get_tuner_type(dev: Pointer): Int
        fun rtlsdr_reset_buffer(dev: Pointer): Int
        fun rtlsdr_read_sync(dev: Pointer, buf: ByteArray, len: Int, nRead: IntByReference): Int

        // Asynchronous reading. librtlsdr keeps `bufNum` libusb transfers in
        // flight and delivers data on its own thread, so the USB endpoint stays
        // busy while we process — unlike read_sync, where the device is idle
        // for the whole time between calls.
        fun rtlsdr_read_async(dev: Pointer, cb: ReadAsyncCallback, ctx: Pointer?,
                              bufNum: Int, bufLen: Int): Int
        fun rtlsdr_cancel_async(dev: Pointer): Int
    }

    /** C: void (*rtlsdr_read_async_cb_t)(unsigned char *buf, uint32_t len, void *ctx) */
    interface ReadAsyncCallback : Callback {
        fun invoke(buf: Pointer, len: Int, ctx: Pointer?)
    }

    companion object {
        // libusb transfers kept in flight by librtlsdr (its own default is 15)
        private const val ASYNC_BUFFERS = 16

        private val TUNER_NAMES = arrayOf("Unknown", "E4000", "FC0012", "FC0013", "FC2580", "R820T", "R828D")

        /**
         * Try to load librtlsdr from common locations.
         */
        /** Why open() failed, so the UI can tell the user what to actually fix. */
        enum class FailureReason { NONE, LIBRARY_MISSING, NO_DEVICE, OPEN_FAILED }

        @Volatile
        var lastFailure: FailureReason = FailureReason.NONE
            internal set

        @Volatile
        var lastFailureDetail: String = ""
            internal set

        /** Directory the running JAR sits in — DLLs shipped next to it are found first. */
        private fun appDir(): File? = try {
            val src = RtlSdrNative::class.java.protectionDomain?.codeSource?.location
            if (src != null) File(src.toURI()).parentFile else null
        } catch (_: Exception) { null }

        fun loadLibrary(): LibRtlSdr? {
            // Look next to the JAR first: the Windows bundle ships rtlsdr.dll and
            // libusb-1.0.dll there, so a plain unzip-and-run works without the
            // user editing PATH or copying anything into system directories.
            appDir()?.let { dir ->
                val existing = System.getProperty("jna.library.path")
                val path = if (existing.isNullOrBlank()) dir.absolutePath
                           else dir.absolutePath + File.pathSeparator + existing
                System.setProperty("jna.library.path", path)
            }

            val names = listOf("rtlsdr", "librtlsdr")
            val errors = StringBuilder()
            for (name in names) {
                try {
                    return Native.load(name, LibRtlSdr::class.java) as LibRtlSdr
                } catch (e: UnsatisfiedLinkError) {
                    errors.append(name).append(": ").append(e.message?.take(160)).append('\n')
                }
            }
            lastFailure = FailureReason.LIBRARY_MISSING
            lastFailureDetail = "jna.library.path=${System.getProperty("jna.library.path")}\n$errors"
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
            lastFailure = FailureReason.NO_DEVICE
            lastFailureDetail = "librtlsdr loaded OK, but it reports 0 devices — " +
                "the WinUSB/libusb driver is probably not bound to the dongle's interface 0."
            return false
        }

        deviceName = try { l.rtlsdr_get_device_name(deviceIndex) } catch (_: Exception) { "RTL-SDR" }

        val devRef = PointerByReference()
        val ret = l.rtlsdr_open(devRef, deviceIndex)
        if (ret != 0) {
            println("ERROR: rtlsdr_open failed (code $ret). Device may be in use.")
            lastFailure = FailureReason.OPEN_FAILED
            lastFailureDetail = "rtlsdr_open() returned $ret — the device is visible but could " +
                "not be claimed (another program using it, or wrong driver on the interface)."
            return false
        }

        devPtr = devRef.value
        val tunerType = l.rtlsdr_get_tuner_type(devPtr!!)
        tunerName = if (tunerType in TUNER_NAMES.indices) TUNER_NAMES[tunerType] else "Unknown($tunerType)"

        isOpen = true
        lastFailure = FailureReason.NONE
        lastFailureDetail = ""
        println("RTL-SDR opened: $deviceName (tuner=$tunerName)")
        return true
    }

    fun setSampleRate(rate: Int) {
        val dev = devPtr ?: return
        configuredSampleRate = rate
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

    /**
     * Enable offset tuning (zero-IF DC spike avoidance).
     * IMPORTANT: librtlsdr repurposes this call as a bias-tee (antenna phantom
     * power) toggle on R820T/R828D tuners — it does NOT reduce noise on them.
     * It IS a real, useful DC-spike fix on the other zero-IF tuners (E4000,
     * FC0012, FC0013, FC2580), so only R820T/R828D are excluded here.
     */
    fun setOffsetTuning(enabled: Boolean) {
        if (tunerName == "R820T" || tunerName == "R828D") {
            println("Offset tuning skipped: not supported on $tunerName (would toggle bias-tee instead)")
            return
        }
        val dev = devPtr ?: return
        val l = lib ?: return
        try {
            val ret = l.rtlsdr_set_offset_tuning(dev, if (enabled) 1 else 0)
            println("Offset tuning: ${if (enabled) "ON" else "OFF"} (ret=$ret)")
        } catch (e: Exception) {
            println("Offset tuning not supported: ${e.message}")
        }
    }

    /**
     * Reset the RTL2832U endpoint FIFO.
     *
     * MUST NOT run while asynchronous reading is active: it resets the USB
     * endpoint out from under the in-flight transfers, which aborts
     * rtlsdr_read_async (observed as rc=-5) and leaves the endpoint stalled,
     * so every following read fails with -9 (LIBUSB_ERROR_PIPE). That is
     * exactly what happened on every frequency change in the field log —
     * tuning killed the stream and audio broke until things recovered.
     */
    fun resetBuffer() {
        if (isStreaming) return
        resetBufferInternal()
    }

    private fun resetBufferInternal() {

        val dev = devPtr ?: return
        lib?.rtlsdr_reset_buffer(dev)
    }

    /**
     * Full USB reset: flush FIFO + discard stale data.
     * Call after scan/seek before restarting streaming.
     */
    fun fullReset() {
        val dev = devPtr ?: return
        val l = lib ?: return
        isStreaming = false
        Thread.sleep(50)

        // Reset USB FIFO
        l.rtlsdr_reset_buffer(dev)
        Thread.sleep(10)
        l.rtlsdr_reset_buffer(dev)

        // Discard stale data (short reads with small buffer)
        val discardBuf = ByteArray(65536)
        val nRead = IntByReference()
        for (i in 0 until 3) {
            val ret = l.rtlsdr_read_sync(dev, discardBuf, discardBuf.size, nRead)
            if (ret != 0 || nRead.value <= 0) break
        }

        l.rtlsdr_reset_buffer(dev)
        println("Full USB reset completed")
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

    // Kept as a field: JNA callbacks must stay strongly reachable for as long
    // as native code can invoke them, or the JVM collects them and the next
    // callback crashes the process.
    private var asyncCallback: ReadAsyncCallback? = null
    private var asyncThread: Thread? = null
    private var workerThread: Thread? = null

    /** Bounded hand-off between the USB callback and the DSP worker. */
    private val iqQueue = java.util.concurrent.ArrayBlockingQueue<ByteArray>(32)

    // Throughput accounting, reported to the log so field problems are visible
    @Volatile private var bytesReceived = 0L
    @Volatile private var chunksDropped = 0L
    @Volatile private var streamStartMs = 0L
    var configuredSampleRate: Int = 1152000
        private set

    /**
     * Start streaming. Uses librtlsdr's asynchronous reader, which keeps
     * several libusb transfers in flight on its own thread.
     *
     * The previous implementation called rtlsdr_read_sync in a loop and ran the
     * DSP callback inline, so the USB endpoint sat idle for the entire
     * demodulation time of every chunk — the tuner's FIFO overflowed on each
     * gap and the lost samples were heard as constant audio dropouts. Now the
     * USB callback only copies data into a bounded queue and a separate worker
     * thread does the DSP, so reading never waits for processing.
     */
    fun startStreaming(bufferSize: Int = 65536, callback: (ByteArray) -> Unit): Thread {
        val dev = devPtr
        val l = lib
        isStreaming = true
        iqQueue.clear()
        bytesReceived = 0
        chunksDropped = 0
        streamStartMs = System.currentTimeMillis()

        resetBuffer()

        // Worker: drains the queue and runs the (slow) DSP callback.
        val worker = Thread({
            while (isStreaming) {
                val data = try {
                    iqQueue.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) { null } ?: continue
                try {
                    callback(data)
                } catch (e: Throwable) {
                    println("Streaming callback error: ${e.message}")
                }
            }
        }, "RtlSdrDsp")
        worker.isDaemon = true
        worker.priority = Thread.NORM_PRIORITY + 1
        worker.start()
        workerThread = worker

        if (dev != null && l != null) {
            // librtlsdr wants a buffer length that is a multiple of 512.
            val bufLen = (bufferSize / 512).coerceAtLeast(1) * 512
            val cb = object : ReadAsyncCallback {
                override fun invoke(buf: Pointer, len: Int, ctx: Pointer?) {
                    if (!isStreaming || len <= 0) return
                    try {
                        val data = buf.getByteArray(0, len)
                        bytesReceived += len
                        // Never block the USB callback: if the DSP is behind,
                        // drop the oldest chunk instead of stalling reads.
                        if (!iqQueue.offer(data)) {
                            iqQueue.poll()
                            iqQueue.offer(data)
                            chunksDropped++
                        }
                    } catch (_: Throwable) {}
                }
            }
            asyncCallback = cb

            val t = Thread({
                var attempt = 0
                while (isStreaming && attempt <= 3) {
                    println("Streaming started (async, ${ASYNC_BUFFERS}×$bufLen B)")
                    // Blocks until rtlsdr_cancel_async() is called.
                    val rc = try { l.rtlsdr_read_async(dev, cb, null, ASYNC_BUFFERS, bufLen) }
                             catch (e: Throwable) { println("read_async failed: ${e.message}"); -1 }
                    if (!isStreaming) {
                        println("Streaming stopped (clean, rc=$rc)")
                        return@Thread
                    }
                    // Ended without us asking: recover instead of limping along
                    // on synchronous reads against a possibly stalled endpoint.
                    attempt++
                    println("read_async ended unexpectedly (rc=$rc) — restart $attempt/3")
                    try { l.rtlsdr_cancel_async(dev) } catch (_: Throwable) {}
                    Thread.sleep(50)
                    resetBufferInternal()   // clears an endpoint stall
                    Thread.sleep(20)
                }
                if (isStreaming) {
                    println("Falling back to synchronous reads")
                    syncReadLoop(bufLen)
                }
            }, "RtlSdrStreaming")
            t.isDaemon = true
            t.start()
            asyncThread = t
            return t
        }

        // No device/library — degrade to the old behaviour.
        val t = Thread({ syncReadLoop(bufferSize) }, "RtlSdrStreaming")
        t.isDaemon = true
        t.start()
        asyncThread = t
        return t
    }

    private fun syncReadLoop(bufferSize: Int) {
        while (isStreaming && isOpen) {
            val data = readSamples(bufferSize)
            if (data != null && data.isNotEmpty()) {
                bytesReceived += data.size
                if (!iqQueue.offer(data)) { iqQueue.poll(); iqQueue.offer(data); chunksDropped++ }
            } else {
                Thread.sleep(1)
            }
        }
    }

    /** Achieved vs required byte rate — the number that shows whether USB keeps up. */
    fun throughputReport(): String {
        val ms = System.currentTimeMillis() - streamStartMs
        if (ms <= 0) return "n/a"
        val got = bytesReceived * 1000.0 / ms / 1e6
        val need = configuredSampleRate * 2.0 / 1e6
        return String.format(java.util.Locale.US,
            "%.3f/%.3f MB/s (%.1f%%), queue=%d, dropped=%d",
            got, need, got / need * 100, iqQueue.size, chunksDropped)
    }

    fun stopStreaming() {
        isStreaming = false
        // read_async blocks until cancelled — without this the streaming thread
        // would sit in native code forever and the device could never be retuned.
        try { devPtr?.let { lib?.rtlsdr_cancel_async(it) } } catch (_: Throwable) {}
        try { asyncThread?.join(700) } catch (_: Exception) {}
        try { workerThread?.join(300) } catch (_: Exception) {}
        asyncThread = null
        workerThread = null
        asyncCallback = null   // safe to release once native reading has stopped
        iqQueue.clear()
    }

    fun close() {
        if (isStreaming) stopStreaming()
        isStreaming = false
        isOpen = false
        devPtr?.let { lib?.rtlsdr_close(it) }
        devPtr = null
        lib = null
    }
}
