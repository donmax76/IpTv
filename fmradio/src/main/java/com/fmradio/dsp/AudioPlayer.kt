package com.fmradio.dsp

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log

/**
 * Stereo audio player with non-blocking writes and retry.
 * Uses WRITE_NON_BLOCKING with immediate retry to avoid both:
 * - Blocking DSP thread (which overflows IQ channel)
 * - Losing samples (which causes clicks)
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
        val bufferSize = minBufSize * 10  // large buffer for smooth playback

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
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
        } catch (e: Exception) {
            // Fallback without PERFORMANCE_MODE_LOW_LATENCY for older devices
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
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to create AudioTrack", e2)
                return
            }
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

    /** Write samples with retry — minimizes both blocking and sample loss */
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

        // Write with retry: non-blocking first, short sleep + retry if partial
        var offset = 0
        var remaining = count
        var retries = 0
        while (remaining > 0 && retries < 3) {
            val written = track.write(samples, offset, remaining, AudioTrack.WRITE_NON_BLOCKING)
            if (written > 0) {
                offset += written
                remaining -= written
                retries = 0  // reset retries on success
            } else {
                retries++
                try { Thread.sleep(2) } catch (_: InterruptedException) { break }
            }
        }
        // If still remaining after retries, drop — better than blocking forever
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
