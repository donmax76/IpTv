package com.fmradio.dsp

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log

/**
 * Stereo audio output — thin wrapper around AudioTrack.
 *
 * Previous versions used a ring buffer + dedicated drain thread.
 * That architecture introduced a whole class of underrun/overflow bugs:
 * lock contention between writer and reader, fade-to-silence on underrun,
 * crossfade on overflow — each a potential source of audible clicks.
 *
 * New approach: write directly to AudioTrack from the DSP thread.
 * AudioTrack's own internal buffer (10× minimum) handles all jitter
 * absorption. write() blocks when full, pacing the DSP naturally.
 * No ring buffer, no drain thread, no lock, no underrun logic.
 */
class AudioPlayer(private val sampleRate: Int = 48000) {

    companion object {
        private const val TAG = "AudioPlayer"
        // Fade-in on initial playback start to prevent pop
        private const val FADE_IN_FRAMES = 2400  // ~50ms at 48 kHz
    }

    private var audioTrack: AudioTrack? = null
    @Volatile
    private var isPlaying = false
    private var framesWritten = 0L

    fun start() {
        if (isPlaying) return

        val minBufSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        // Large buffer absorbs scheduling jitter on car head units (BYD DiLink)
        // where the audio HAL may have longer scheduling intervals than phones.
        val bufferSize = minBufSize * 10

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

        framesWritten = 0L
        audioTrack?.play()
        isPlaying = true

        Log.i(TAG, "Audio started (${sampleRate}Hz, buf=$bufferSize, direct-write mode)")
    }

    /**
     * Write interleaved stereo samples (L,R,L,R,...) to AudioTrack.
     * Called from the DSP thread. Blocks if AudioTrack buffer is full,
     * which naturally paces the DSP to match the audio sample rate.
     */
    fun writeSamples(samples: ShortArray, count: Int = samples.size) {
        if (!isPlaying || count <= 0) return

        // Fade-in on initial playback to prevent startup pop
        val framesToWrite = count / 2
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

        try {
            audioTrack?.write(samples, 0, count)
        } catch (e: Exception) {
            Log.e(TAG, "Error writing audio", e)
        }

        framesWritten += framesToWrite
    }

    fun setVolume(volume: Float) {
        val vol = volume.coerceIn(0f, 1f)
        audioTrack?.setVolume(vol)
    }

    fun stop() {
        isPlaying = false
        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping audio", e)
        }
        audioTrack = null
        framesWritten = 0L
        Log.i(TAG, "Audio stopped")
    }

    fun isActive(): Boolean = isPlaying
}
