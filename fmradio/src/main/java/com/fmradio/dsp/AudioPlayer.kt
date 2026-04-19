package com.fmradio.dsp

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log

/**
 * Stereo audio output — non-blocking write to AudioTrack.
 *
 * Uses WRITE_NON_BLOCKING so the DSP thread NEVER stalls waiting for
 * AudioTrack buffer space. On car head units (BYD DiLink) when the
 * camera app opens, the system deprioritises our threads. With blocking
 * write, DSP would stall → IQ channel fills → USB drops → stutter.
 * With non-blocking, DSP keeps processing at USB rate; if AudioTrack
 * can't accept all samples, we skip them (inaudible at matched rates,
 * tiny glitch during system transitions but no sustained stutter).
 *
 * AudioTrack buffer is 20× minimum (~800 ms) to absorb scheduling
 * jitter during camera/app switches.
 */
class AudioPlayer(private val sampleRate: Int = 48000) {

    companion object {
        private const val TAG = "AudioPlayer"
        private const val FADE_IN_FRAMES = 2400
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
        val bufferSize = minBufSize * 20

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

        Log.i(TAG, "Audio started (${sampleRate}Hz, buf=$bufferSize, non-blocking mode)")
    }

    fun writeSamples(samples: ShortArray, count: Int = samples.size) {
        if (!isPlaying || count <= 0) return

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
            // Blocking write with large buffer (20× = ~800ms). Non-blocking was
            // dropping frames silently causing crackling. Blocking paces the DSP
            // naturally and AudioTrack's 800ms buffer absorbs scheduling jitter.
            val written = audioTrack?.write(samples, 0, count) ?: 0

            // Log every 70th write (~1/sec) + any partial writes
            if (DebugLog.fileLoggingEnabled && (written < count || framesWritten % 70 == 0L)) {
                val track = audioTrack
                val headPos = track?.playbackHeadPosition ?: 0
                val bufDiff = framesWritten - headPos  // samples ahead of playback = buffer fill
                DebugLog.log(TAG, "aud: w=$written/$count buf=$bufDiff head=$headPos total=$framesWritten")
            }
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
