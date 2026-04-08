package com.fmradio.dsp

import android.util.Log

/**
 * JNI bridge to native C++ FM demodulator.
 * All heavy DSP runs in native code — no GC pauses, no jitter.
 *
 * The native library is shared with NativeUsb (libfmradio_native.so) and
 * loaded once at class init. If the library is missing or doesn't expose the
 * native symbols (e.g., ABI mismatch on an old build), `available` stays
 * false and FmRadioService falls back to the Kotlin FmDemodulator.
 */
class NativeFmDsp {
    companion object {
        private const val TAG = "NativeFmDsp"

        @JvmStatic
        var available = false
            private set

        init {
            available = try {
                System.loadLibrary("fmradio_native")
                Log.i(TAG, "Native FM DSP library loaded")
                true
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "Native FM DSP library not available: ${e.message}")
                false
            } catch (e: Throwable) {
                Log.e(TAG, "Native FM DSP load failed", e)
                false
            }
        }
    }

    // Pre-allocated buffers — reused every call, zero GC
    private val audioBuffer = ShortArray(2800 * 2)
    private val wbBuffer = FloatArray(6000)

    external fun init()
    external fun reset()
    external fun getSignalDb(): Float
    external fun getIsStereo(): Boolean
    external fun getPilotPhase(): Double
    external fun getWbCount(): Int
    external fun demodulate(iqData: ByteArray, audioOut: ShortArray, wbOut: FloatArray?): Int

    /** Demodulate IQ data, return audio count and fill pre-allocated buffers */
    fun process(iqData: ByteArray): DemodResult {
        val count = demodulate(iqData, audioBuffer, wbBuffer)
        return DemodResult(audioBuffer, count)
    }

    /** Get wideband buffer for RDS (valid after process()) */
    fun getWbBuffer(): FloatArray = wbBuffer

    /** Result holder matching FmDemodulator.DemodResult */
    class DemodResult(val samples: ShortArray, var count: Int = 0)
}
