package com.fmradio.dsp

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log

/**
 * Simple stereo audio output — BLOCKING write to AudioTrack.
 *
 * AudioTrack is the master clock. write() blocks until the hardware
 * consumes enough samples — this naturally throttles the DSP thread
 * to exactly the hardware sample rate. No drift compensation needed.
 *
 * The IQ queue (64 slots) in FmRadioService absorbs any brief stall
 * from the blocking write, so USB streaming is never affected.
 */
class AudioPlayer(private val sampleRate: Int = 48000) {

    companion object {
        private const val TAG = "AudioPlayer"
        private const val FADE_IN_FRAMES = 2400  // 50ms fade-in on start
    }

    private var audioTrack: AudioTrack? = null
    @Volatile
    private var isPlaying = false
    /**
     * The device is loaded with silence but not yet running.
     *
     * Priming and starting in the same breath throws the reservoir away. The
     * device begins consuming at 48 kHz the instant play() is called, and after
     * a retune nothing arrives for a while: the tuner needs about 180 ms to
     * move its synthesiser, the USB reads come back empty across that gap, and
     * the DSP has to refill its pipeline afterwards. Half a second of primed
     * silence covers exactly half a second of that, so by the time real audio
     * turns up the reservoir is mostly gone — and with blocking writes it can
     * never be rebuilt, because the writer only ever produces at real time.
     *
     * A field log shows the result plainly: the buffer level, which used to
     * sit between 11500 and 23800 frames, ran at 4000 to 5900 for a whole
     * session. That is a hundred milliseconds of headroom on a head unit that
     * schedules when it feels like it, and it is heard as the sound breaking
     * up after a station change.
     *
     * Starting the device on the first real buffer instead costs nothing and
     * keeps the whole reservoir.
     */
    private var startPending = false
    private var framesWritten = 0L
    /** Silence queued by primeSilence, counted so the buffer-level log stays honest. */
    private var primedFrames = 0L
    private var bufferBytes = 0

    @Volatile
    private var targetVolume = 1f
    private var currentVolume = 1f
    private val volumeRampStep = 1f / (sampleRate * 0.03f)  // 30ms ramp

    fun start() {
        if (isPlaying) return

        val minBufSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        // A second and a half, and 30x whatever the device claims it needs.
        //
        // This was measured on THIS head unit and then lost. Build 3.0.315
        // raised it from 400 ms because the DiLink reports a tiny minBufSize —
        // it has a low-latency audio HAL — and the buffer it actually handed
        // back was 939 to 2560 frames, twenty to fifty milliseconds, which any
        // scheduling delay empties. A later rewrite of this file to blocking
        // writes replaced the whole calculation with 500 ms and 4x, and the
        // measurement went with it.
        //
        // The 3.0.516 field log shows what that costs now: 'real=24000frames'
        // in the report, and a running level of 4000 to 5900 frames where it
        // used to sit between 11500 and 23800. About a hundred milliseconds of
        // headroom on a device that schedules when it feels like it.
        //
        // With blocking writes the size is a ceiling, not a cost: the reservoir
        // is filled once by priming and the writer then produces in real time,
        // so a bigger buffer buys headroom and nothing else. The latency it
        // adds is the priming, which is chosen separately below.
        val minDesired = sampleRate * 2 * 2 * 3 / 2   // 1500 ms, stereo 16-bit
        val bufferSize = maxOf(minBufSize * 30, minDesired)

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        // Fill a reservoir before the device starts consuming.
        //
        // A field log with file logging on shows the queue sitting at 2700 to
        // 3500 frames out of the 24000 this buffer holds — about 60 ms of
        // audio, 12% of capacity. The DSP produces in real time from the USB
        // stream, so without priming there is no reservoir at all: the writer
        // only ever stays one callback ahead of the reader, and any scheduling
        // hiccup on a head unit empties the device. That is the 31 underruns
        // in the same report, and what is heard as choking.
        //
        // Half a second of silence up front costs a latency nobody can
        // perceive on a radio and gives the pipeline something to spend. It is
        // the same figure the retune path primes, and the same one build
        // 3.0.315 measured on this head unit.
        primeSilence(sampleRate / 2)
        startPending = true
        framesWritten = 0L
        isPlaying = true

        bufferBytes = bufferSize
        Log.i(TAG, "Started: ${sampleRate}Hz asked=$bufferSize got=${bufferFrames()}frames")
    }

    /**
     * How many times the audio device has run dry, and the buffer it has to
     * work with. Stuttering — "choking" in the field reports — is this and
     * nothing else, and until now the report had no way to show it: every
     * diagnostic in the app measured the radio and none measured the output.
     */
    fun underrunCount(): Int =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N)
            try { audioTrack?.underrunCount ?: -1 } catch (_: Throwable) { -1 }
        else -1

    /**
     * The buffer the device ACTUALLY gave us, in frames — not the size that
     * was asked for.
     *
     * A field log shows the queue cycling between 2060 and 3520 frames and
     * never filling, which with blocking writes is impossible if the buffer
     * really were the 24000 frames requested: write() would return at once
     * until it was full. So the HAL is handing back something far smaller, the
     * "500 ms of headroom" in the code comment is fiction, and the priming
     * added last release can only ever fill whatever is really there. Report
     * the real number so the next log settles it instead of another guess.
     */
    fun bufferFrames(): Int =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M)
            try { audioTrack?.bufferSizeInFrames ?: -1 } catch (_: Throwable) { -1 }
        else -1

    fun bufferBytes(): Int = bufferBytes

    fun writeSamples(samples: ShortArray, count: Int = samples.size) {
        if (!isPlaying || count <= 0) return

        // Real audio has arrived — now let the device start. This must happen
        // BEFORE the write below: the buffer is full of primed silence, so a
        // blocking write into a device that is not running would wait for a
        // reader that does not exist. See startPending.
        if (startPending) {
            startPending = false
            audioTrack?.play()
        }

        // Fade-in to avoid click on start
        if (framesWritten < FADE_IN_FRAMES) {
            for (i in 0 until count step 2) {
                val frame = framesWritten + i / 2
                if (frame < FADE_IN_FRAMES) {
                    val gain = frame.toFloat() / FADE_IN_FRAMES
                    samples[i] = (samples[i] * gain).toInt().coerceIn(-32767, 32767).toShort()
                    samples[i + 1] = (samples[i + 1] * gain).toInt().coerceIn(-32767, 32767).toShort()
                }
            }
        }

        // Volume control with smooth ramp
        val target = targetVolume
        if (currentVolume != target || currentVolume < 1f) {
            for (i in 0 until count step 2) {
                if (currentVolume < target) {
                    currentVolume = (currentVolume + volumeRampStep).coerceAtMost(target)
                } else if (currentVolume > target) {
                    currentVolume = (currentVolume - volumeRampStep).coerceAtLeast(target)
                }
                if (currentVolume < 1f) {
                    samples[i] = (samples[i] * currentVolume).toInt().coerceIn(-32767, 32767).toShort()
                    samples[i + 1] = (samples[i + 1] * currentVolume).toInt().coerceIn(-32767, 32767).toShort()
                }
            }
        }

        // BLOCKING write — AudioTrack is the master clock.
        // This call blocks until hardware has consumed enough data to accept
        // our samples. This naturally rate-limits the DSP thread to exactly
        // 48000 Hz — no drift, no compensation, no clicks.
        val written = audioTrack?.write(samples, 0, count, AudioTrack.WRITE_BLOCKING) ?: 0
        framesWritten += written / 2

        if (DebugLog.fileLoggingEnabled && framesWritten % 140 == 0L) {
            val track = audioTrack
            val headPos = track?.playbackHeadPosition ?: 0
            val bufLevel = framesWritten + primedFrames - headPos
            DebugLog.log(TAG, "aud: w=$written/$count buf=$bufLevel")
        }
    }

    fun setVolume(volume: Float) {
        targetVolume = volume.coerceIn(0f, 1f)
    }

    fun flush() {
        audioTrack?.pause()
        audioTrack?.flush()
        // Rebuild the reservoir; a flush throws it away with everything else.
        //
        // As much as the device will take, not a quarter second. The only
        // thing that calls this is a retune, and a retune stops the IQ stream
        // for as long as the tuner takes to move its PLL — measured on the
        // FC0013 at about 180 ms between writing the synthesiser and the VCO
        // reporting lock, with three empty USB reads in the gap. A 250 ms
        // reservoir barely covers that, so a field report showed 79 underruns
        // across 26 station changes: almost exactly the three per change that
        // the empty reads predict. Priming to capacity covers the stall with
        // margin. It costs latency that only exists immediately after a
        // retune, where a fraction of a second is not noticeable and a gap in
        // the audio is.
        //
        // NON_BLOCKING means asking for more than fits simply stops when full.
        primeSilence(sampleRate / 2)
        startPending = true
        framesWritten = 0L
    }

    /**
     * Queue [frames] of silence so the device has something in hand.
     *
     * NON_BLOCKING deliberately: this runs before play(), when nothing is
     * consuming, so a blocking write would fill the buffer and then wait for
     * a reader that does not exist yet — hanging the thread that starts
     * playback. A short write just means the buffer is full, which is the
     * point at which we want to stop anyway.
     */
    private fun primeSilence(frames: Int) {
        val track = audioTrack ?: return
        primedFrames = 0
        try {
            val chunk = ShortArray(2048)          // stereo pairs, all zero
            var left = frames * 2                 // two samples per frame
            while (left > 0) {
                val n = minOf(chunk.size, left)
                val w = track.write(chunk, 0, n, AudioTrack.WRITE_NON_BLOCKING)
                if (w <= 0) break                 // buffer full, or refused
                left -= w
                primedFrames += w / 2
            }
        } catch (_: Throwable) {
        }
    }

    fun stop() {
        isPlaying = false
        startPending = false
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping", e)
        }
        audioTrack = null
        framesWritten = 0L
        Log.i(TAG, "Stopped")
    }

    fun isActive(): Boolean = isPlaying
}
