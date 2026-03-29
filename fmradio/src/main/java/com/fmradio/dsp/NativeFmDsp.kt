package com.fmradio.dsp

/**
 * JNI bridge to native C++ FM demodulator.
 * All heavy DSP runs in native code — no GC pauses, no jitter.
 */
class NativeFmDsp {
    companion object {
        var available = false
            private set

        init {
            try {
                System.loadLibrary("fmradio_dsp")
                available = true
            } catch (e: UnsatisfiedLinkError) {
                available = false
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
