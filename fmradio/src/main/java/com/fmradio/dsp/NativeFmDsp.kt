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

        var available = false
            private set

        init {
            available = try {
                // libfmradio_dsp.so — built by cpp/CMakeLists.txt from fm_dsp.cpp.
                // Separate from NativeUsb's "fmradio_native" so a missing Linux USB
                // header in the NDK sysroot can't block the DSP build.
                com.fmradio.util.StartupLog.write("loading libfmradio_dsp")
                System.loadLibrary("fmradio_dsp")
                com.fmradio.util.StartupLog.write("libfmradio_dsp loaded")
                Log.i(TAG, "Native FM DSP library loaded")
                true
            } catch (e: UnsatisfiedLinkError) {
                com.fmradio.util.StartupLog.write("libfmradio_dsp NOT available: ${e.message}")
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

    /**
     * Re-converge the IQ DC blocker after the tuner's gain changed.
     *
     * Cheaper and safer than reset(): it leaves the pilot PLL, the filters and
     * the squelch alone. See reseedDc in fm_dsp.cpp.
     */
    external fun reseedDc()
    external fun getSignalDb(): Float
    external fun getIsStereo(): Boolean
    external fun getPilotPhase(): Double
    external fun getPilotFreq(): Double
    external fun getWbCount(): Int
    external fun demodulate(iqData: ByteArray, audioOut: ShortArray, wbOut: FloatArray?): Int

    /** RMS of the IQ magnitude as a fraction of ADC full scale (0..1). */
    external fun getAdcRms(): Float

    /** Percentage of raw samples pinned at the ends of the ADC range. */
    external fun getAdcClipPct(): Float

    /** Ultrasonic noise level — the reception-quality metric (lower is better). */
    external fun getNoiseLevel(): Float

    /**
     * Level in the RDS band, on the same scale as [getNoiseLevel].
     *
     * Separates "this station sends no RDS" from "it does and we cannot read
     * it" — two things that look identical from the decoder, which sees a
     * stream of noise either way. With no subcarrier present this reads about
     * 0.8 of the noise level; a station transmitting RDS reads well above it,
     * whatever its block error rate. See rdsCarrierLevel in fm_dsp.cpp.
     */
    external fun getRdsCarrierLevel(): Float

    /**
     * Level in an empty band beside RDS, for comparison with
     * [getRdsCarrierLevel].
     *
     * A station running its composite hard splashes intermodulation above the
     * stereo difference signal's 53 kHz edge, straight into the RDS band —
     * energy there is not the same as data there. Splatter and noise are both
     * broad and read the same at 58.2 kHz and at 62; a real subcarrier is
     * narrow and reads only at 58.2. See rdsShoulderLevel in fm_dsp.cpp.
     */
    external fun getRdsShoulderLevel(): Float

    /** Stereo separation currently in use: 0 = mono, 1 = full. */
    external fun getStereoBlend(): Float

    /** Current audio high-cut corner, Hz. */
    external fun getHiCutHz(): Float

    /** Current loudness-normalisation gain, 1.0 = untouched. See loudGain in fm_dsp.cpp. */
    external fun getLoudnessGain(): Float

    /** Samples suppressed by the impulse blanker since reset. */
    external fun getBlankedCount(): Long

    /** Share of audio samples that reached the limiter knee, in percent. */
    external fun getSoftClipPct(): Float

    /**
     * Runtime A/B test flag bitfield. See TEST_* constants below.
     * Allows toggling DSP tweaks live from the UI without rebuilding.
     */
    external fun setTestFlags(flags: Int)
    external fun getTestFlags(): Int

    object TestFlag {
        const val GAIN  = 0x01  // lower fmGain: more headroom before soft-clip
        const val NOTCH = 0x02  // 19 kHz notch on audio: kill pilot residue
        const val PLL   = 0x04  // faster pilot PLL: better on fading
        const val DC    = 0x08  // aggressive IQ DC blocker: kill zero-IF pumping
    }

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
