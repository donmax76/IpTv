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
        // 500ms buffer — enough for scheduling jitter on car head units
        val bufferSize = maxOf(minBufSize * 4, sampleRate * 2 * 2 / 2)

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
        // Quarter of a second of silence up front costs a latency nobody can
        // perceive on a radio and gives the pipeline something to spend.
        primeSilence(sampleRate / 4)
        audioTrack?.play()
        framesWritten = 0L
        isPlaying = true

        bufferBytes = bufferSize
        Log.i(TAG, "Started: ${sampleRate}Hz buf=$bufferSize")
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

    fun bufferBytes(): Int = bufferBytes

    fun writeSamples(samples: ShortArray, count: Int = samples.size) {
        if (!isPlaying || count <= 0) return

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
        primeSilence(sampleRate / 4)
        audioTrack?.play()
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
