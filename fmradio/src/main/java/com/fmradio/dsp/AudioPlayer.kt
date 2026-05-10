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
        private const val PRE_BUFFER_FRAMES = 24000  // 500ms — BYD DiLink needs large pre-buffer
    }

    private var audioTrack: AudioTrack? = null
    @Volatile
    private var isPlaying = false
    private var framesWritten = 0L
    private var preBufferDone = false

    // Smooth volume ramping to avoid clicks on focus change
    @Volatile
    private var targetVolume = 1f
    private var currentVolume = 1f
    private val volumeRampStep = 1f / (sampleRate * 0.05f)  // 50ms ramp

    // Clock drift compensation: RTL-SDR crystal ≠ Android audio clock.
    // When buf drops, duplicate frames; when buf rises, skip frames.
    // One frame per ~240 is inaudible but prevents underrun/overflow.
    private val paddingFrame = ShortArray(2)
    private var driftCounter = 0

    fun start() {
        if (isPlaying) return

        val minBufSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        // BYD DiLink reports tiny minBufSize and has low-latency audio HAL.
        // Need large buffer (1.5 sec) so buf headroom stays above 20K frames.
        val minDesired = sampleRate * 2 * 2 * 3 / 2  // 1500ms in bytes (stereo 16-bit)
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

        // Apply smooth volume ramping per-sample to avoid clicks
        val target = targetVolume
        if (currentVolume != target) {
            for (i in 0 until count step 2) {
                if (currentVolume < target) {
                    currentVolume = (currentVolume + volumeRampStep).coerceAtMost(target)
                } else if (currentVolume > target) {
                    currentVolume = (currentVolume - volumeRampStep).coerceAtLeast(target)
                }
                samples[i] = (samples[i] * currentVolume).toInt().coerceIn(-32767, 32767).toShort()
                samples[i + 1] = (samples[i + 1] * currentVolume).toInt().coerceIn(-32767, 32767).toShort()
            }
        } else if (currentVolume < 1f) {
            for (i in 0 until count) {
                samples[i] = (samples[i] * currentVolume).toInt().coerceIn(-32767, 32767).toShort()
            }
        }

        try {
            val writeMode = if (preBufferDone)
                AudioTrack.WRITE_NON_BLOCKING
            else
                AudioTrack.WRITE_BLOCKING

            val written = audioTrack?.write(samples, 0, count, writeMode) ?: 0
            val actualFrames = written / 2

            if (DebugLog.fileLoggingEnabled && (written < count || framesWritten % 70 == 0L)) {
                val track = audioTrack
                val headPos = track?.playbackHeadPosition ?: 0
                val bufDiff = framesWritten + actualFrames - headPos
                val bufSize = track?.bufferSizeInFrames ?: 0
                DebugLog.log(TAG, "aud: w=$written/$count buf=$bufDiff head=$headPos total=${framesWritten + actualFrames} bufSize=$bufSize")
            }

            framesWritten += actualFrames

            // Clock drift compensation: check buffer level every 64 writes
            if (preBufferDone && ++driftCounter >= 64) {
                driftCounter = 0
                val track = audioTrack
                if (track != null) {
                    val headPos = track.playbackHeadPosition.toLong()
                    val bufLevel = framesWritten - headPos
                    val bufCap = track.bufferSizeInFrames.toLong()
                    val target = bufCap / 2  // aim for 50% fill

                    if (bufLevel < bufCap / 4) {
                        // Buffer draining (DSP slower than AudioTrack) — pad with last frame
                        paddingFrame[0] = samples[count - 2]
                        paddingFrame[1] = samples[count - 1]
                        val padCount = ((target - bufLevel) / 200).coerceIn(1, 50).toInt()
                        for (p in 0 until padCount) {
                            val pw = track.write(paddingFrame, 0, 2, AudioTrack.WRITE_NON_BLOCKING)
                            if (pw == 2) framesWritten++
                            else break
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error writing audio", e)
        }

        if (!preBufferDone && framesWritten >= PRE_BUFFER_FRAMES) {
            audioTrack?.play()
            preBufferDone = true
        }
    }

    fun setVolume(volume: Float) {
        targetVolume = volume.coerceIn(0f, 1f)
    }

    /** Flush buffer immediately (call on frequency change to stop old audio) */
    fun flush() {
        audioTrack?.pause()
        audioTrack?.flush()
        framesWritten = 0L
        preBufferDone = false
        driftCounter = 0
        // play() will be called again from writeSamples when pre-buffer fills
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
