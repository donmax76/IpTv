package com.fmradio.dsp

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log

/**
 * Simplified stereo audio player — writes directly to AudioTrack.
 * No ring buffer, no drain thread, no locks = no jitter.
 * AudioTrack's internal buffer handles smoothing.
 */
class AudioPlayer(private val sampleRate: Int = 48000) {

    companion object {
        private const val TAG = "AudioPlayer"
    }

    private var audioTrack: AudioTrack? = null
    @Volatile
    private var isPlaying = false
    private var writeSamplesCount = 0L
    private var lastWriteLog = 0L

    fun start() {
        if (isPlaying) return

        val minBufSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufSize <= 0) {
            Log.e(TAG, "Invalid min buffer size: $minBufSize")
            return
        }
        // Large internal buffer for jitter absorption
        val bufferSize = minBufSize * 8

        try {
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
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create AudioTrack", e)
            return
        }

        try {
            audioTrack?.play()
            DebugLog.log("AUD", "AudioTrack.play() OK, state=${audioTrack?.playState}, rate=$sampleRate, bufSize=$bufferSize")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AudioTrack", e)
            audioTrack?.release()
            audioTrack = null
            return
        }
        isPlaying = true
        writeSamplesCount = 0L
        lastWriteLog = 0L
    }

    /** Write samples directly to AudioTrack — called from DSP thread */
    fun writeSamples(samples: ShortArray, count: Int = samples.size) {
        if (!isPlaying || count <= 0) return
        val track = audioTrack ?: return

        writeSamplesCount++
        val now = System.currentTimeMillis()
        if (writeSamplesCount <= 3 || now - lastWriteLog > 5000) {
            var maxAbs = 0
            for (i in 0 until count) {
                val a = kotlin.math.abs(samples[i].toInt())
                if (a > maxAbs) maxAbs = a
            }
            DebugLog.log("AUD", "writeSamples #$writeSamplesCount: $count samples, peak=$maxAbs")
            lastWriteLog = now
        }

        // Non-blocking write — never stalls DSP thread
        // Returns number of samples actually written
        track.write(samples, 0, count, AudioTrack.WRITE_NON_BLOCKING)
    }

    fun setVolume(volume: Float) {
        audioTrack?.setVolume(volume.coerceIn(0f, 1f))
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
    }

    fun isActive(): Boolean = isPlaying
}
