package com.fmradio.dsp

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.util.Log

/**
 * Audio output player using Android AudioTrack for low-latency playback.
 * Uses larger buffer and PERFORMANCE_MODE_LOW_LATENCY for better quality.
 */
class AudioPlayer(private val sampleRate: Int = 48000) {

    companion object {
        private const val TAG = "AudioPlayer"
    }

    private var audioTrack: AudioTrack? = null
    @Volatile
    private var isPlaying = false

    fun start() {
        if (isPlaying) return

        val minBufSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        // Use 4x minimum buffer to avoid underruns
        val bufferSize = minBufSize * 4

        val builder = AudioTrack.Builder()
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
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        }

        audioTrack = builder.build()
        audioTrack?.play()
        isPlaying = true
        Log.i(TAG, "Audio playback started (${sampleRate}Hz, buffer=${bufferSize})")
    }

    fun writeSamples(samples: ShortArray) {
        if (!isPlaying || audioTrack == null) return
        try {
            audioTrack?.write(samples, 0, samples.size)
        } catch (e: Exception) {
            Log.e(TAG, "Error writing audio", e)
        }
    }

    fun setVolume(volume: Float) {
        val vol = volume.coerceIn(0f, 1f)
        audioTrack?.setVolume(vol)
    }

    fun stop() {
        isPlaying = false
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping audio", e)
        }
        audioTrack = null
        Log.i(TAG, "Audio playback stopped")
    }

    fun isActive(): Boolean = isPlaying
}
