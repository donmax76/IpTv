package com.fmradio.dsp

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
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

    // Adaptive clock-drift correction via playback speed.
    private var driftCounter = 0
    private var currentSpeed = 1.0f
    private var smoothedBufLevel = 0.5f  // EMA of buffer fill ratio

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
                DebugLog.log(TAG, "aud: w=$written/$count buf=$bufDiff bufSize=$bufSize spd=${"%.3f".format(currentSpeed)}")
            }

            framesWritten += actualFrames

            // Adaptive clock-drift correction via playback speed (API 23+).
            // RTL-SDR crystal ≠ Android audio clock → buffer drifts. Instead of
            // duplicating/dropping frames (audible clicks), we nudge AudioTrack's
            // playback speed by a tiny amount to hold the buffer at ~50%. A ±few %
            // continuous speed change is inaudible; no glitches.
            if (preBufferDone && Build.VERSION.SDK_INT >= 23 && ++driftCounter >= 32) {
                driftCounter = 0
                val track = audioTrack
                if (track != null) {
                    val headPos = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                    val bufLevel = framesWritten - headPos
                    val bufCap = track.bufferSizeInFrames.toLong().coerceAtLeast(1)
                    if (bufLevel in 0..bufCap * 2) {
                        val ratio = (bufLevel.toFloat() / bufCap).coerceIn(0f, 1f)
                        // Fast-tracking EMA: alpha=0.5 for quick response to drift
                        smoothedBufLevel = smoothedBufLevel * 0.5f + ratio * 0.5f
                        // error>0 = buffer above 50% → speed up playback to drain
                        // error<0 = buffer below 50% → slow down playback to fill
                        val error = smoothedBufLevel - 0.5f
                        val newSpeed = (1.0f + error * 0.10f).coerceIn(0.92f, 1.08f)
                        currentSpeed = newSpeed
                        try {
                            track.playbackParams = track.playbackParams.setSpeed(newSpeed)
                        } catch (_: Exception) {}
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
        currentSpeed = 1.0f
        smoothedBufLevel = 0.5f
        try { audioTrack?.playbackParams = audioTrack?.playbackParams?.setSpeed(1.0f)!! } catch (_: Exception) {}
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
