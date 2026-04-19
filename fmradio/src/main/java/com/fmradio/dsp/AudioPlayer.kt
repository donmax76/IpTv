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
        // Pre-buffer: accumulate this many frames before starting playback.
        // Without this, AudioTrack buffer stays near-empty (128-683 frames =
        // 3-14ms) and any scheduling jitter causes underrun → click.
        private const val PRE_BUFFER_FRAMES = 14400  // 300ms at 48 kHz
    }

    private var audioTrack: AudioTrack? = null
    @Volatile
    private var isPlaying = false
    private var framesWritten = 0L
    private var preBufferDone = false

    fun start() {
        if (isPlaying) return

        val minBufSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = minBufSize * 50  // request maximum, Android may cap it

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
        preBufferDone = false
        // DON'T play() yet — fill buffer first, then start in writeSamples()
        isPlaying = true

        val actualBufFrames = audioTrack?.bufferSizeInFrames ?: 0
        Log.i(TAG, "Audio started: ${sampleRate}Hz reqBuf=$bufferSize actualBuf=$actualBufFrames frames minBuf=$minBufSize")
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
            val written = audioTrack?.write(samples, 0, count, AudioTrack.WRITE_NON_BLOCKING) ?: 0

            // Only count ACTUALLY written frames (not requested).
            // Bug: was counting all requested → framesWritten overstated →
            // pre-buffer thought it was full when it wasn't → thin buffer.
            val actualFrames = written / 2

            if (DebugLog.fileLoggingEnabled && (written < count || framesWritten % 70 == 0L)) {
                val track = audioTrack
                val headPos = track?.playbackHeadPosition ?: 0
                val bufDiff = framesWritten + actualFrames - headPos
                DebugLog.log(TAG, "aud: w=$written/$count buf=$bufDiff head=$headPos total=${framesWritten + actualFrames}")
            }

            framesWritten += actualFrames
        } catch (e: Exception) {
            Log.e(TAG, "Error writing audio", e)
        }

        // Start playback only after buffer is sufficiently filled
        if (!preBufferDone && framesWritten >= PRE_BUFFER_FRAMES) {
            audioTrack?.play()
            preBufferDone = true
            val actualBuf = audioTrack?.bufferSizeInFrames ?: 0
            Log.i(TAG, "Pre-buffer: $framesWritten frames written, actualBuf=$actualBuf, starting playback")
        }
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
        preBufferDone = false
        Log.i(TAG, "Audio stopped")
    }

    fun isActive(): Boolean = isPlaying
}
