package com.fmradio.dsp

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log

class AudioPlayer(private val sampleRate: Int = 48000) {

    companion object {
        private const val TAG = "AudioPlayer"
        private const val FADE_IN_FRAMES = 2400
        private const val PRE_BUFFER_FRAMES = 24000
        private const val CALIBRATION_SECONDS = 5  // longer = more accurate rate measurement
    }

    private var audioTrack: AudioTrack? = null
    @Volatile
    private var isPlaying = false
    private var framesWritten = 0L
    private var preBufferDone = false

    @Volatile
    private var targetVolume = 1f
    private var currentVolume = 1f
    private val volumeRampStep = 1f / (sampleRate * 0.05f)

    // Auto-calibration: measure actual DSP output rate over first few seconds,
    // then recreate AudioTrack at that rate. This handles RTL-SDR crystal drift
    // (BYD FC0013 dongle is ~3.3% slow) without pitch change or frame tricks.
    private var calibrating = true
    private var calibrationFrames = 0L
    private var calibrationStartNs = 0L
    private var measuredRate = sampleRate
    private var actualRate = 0  // for logging

    fun start() {
        if (isPlaying) return

        calibrating = true
        calibrationFrames = 0L
        calibrationStartNs = 0L
        measuredRate = sampleRate

        createAudioTrack(sampleRate)

        framesWritten = 0L
        preBufferDone = false
        isPlaying = true

        val actualBufFrames = audioTrack?.bufferSizeInFrames ?: 0
        Log.i(TAG, "Audio started: ${sampleRate}Hz actualBuf=$actualBufFrames")
    }

    private fun createAudioTrack(rate: Int) {
        val minBufSize = AudioTrack.getMinBufferSize(
            rate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val minDesired = rate * 2 * 2 * 3 / 2  // 1500ms
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
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    fun writeSamples(samples: ShortArray, count: Int = samples.size) {
        if (!isPlaying || count <= 0) return

        // Count frames for calibration
        val frames = count / 2
        if (calibrating) {
            if (calibrationStartNs == 0L) {
                calibrationStartNs = System.nanoTime()
            }
            calibrationFrames += frames
            val elapsed = (System.nanoTime() - calibrationStartNs) / 1_000_000_000.0
            if (elapsed >= CALIBRATION_SECONDS && calibrationFrames > sampleRate) {
                val measured = (calibrationFrames / elapsed).toInt()
                if (measured in (sampleRate * 85 / 100)..(sampleRate * 100 / 100)) {
                    // Use exact measured rate (no undershoot/overshoot).
                    // A buffer guard handles residual drift at the extremes.
                    measuredRate = measured
                    actualRate = measured
                    // Recreate AudioTrack at the measured rate
                    val oldTrack = audioTrack
                    oldTrack?.pause()
                    oldTrack?.flush()
                    oldTrack?.stop()
                    oldTrack?.release()

                    createAudioTrack(measured)
                    framesWritten = 0L
                    preBufferDone = false

                    Log.i(TAG, "Calibrated: DSP rate=$measured Hz (nominal $sampleRate)")
                    DebugLog.log(TAG, "Calibrated: DSP rate=$measured Hz (drift=${((sampleRate - measured) * 100f / sampleRate).let { "%.1f".format(it) }}%)")
                }
                calibrating = false
            }
        }

        // Fade-in
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

        // Volume ramping
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

            // Buffer guard: check level before writing. If buffer is nearly
            // full (>90%), skip some input samples to prevent overflow → clicks.
            // If nearly empty (<15%), pad with last frame to prevent underrun.
            val track = audioTrack
            var writeOffset = 0
            var writeCount = count
            if (preBufferDone && track != null) {
                val headPos = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                val bufLevel = framesWritten - headPos
                val bufCap = track.bufferSizeInFrames.toLong()
                if (bufCap > 0) {
                    val fill = (bufLevel.toFloat() / bufCap).coerceIn(0f, 1f)
                    if (fill > 0.90f && writeCount > 4) {
                        // Buffer nearly full — skip ~10% of input samples
                        val skip = (writeCount * 0.10f).toInt() and 0xFFFE  // even number
                        writeCount -= skip
                    } else if (fill < 0.15f && writeCount >= 4) {
                        // Buffer nearly empty — duplicate last frame after write
                        // (handled below after write)
                    }
                }
            }

            val written = audioTrack?.write(samples, writeOffset, writeCount, writeMode) ?: 0
            val actualFrames = written / 2

            if (DebugLog.fileLoggingEnabled && (written < writeCount || framesWritten % 70 == 0L)) {
                val headPos = track?.playbackHeadPosition ?: 0
                val bufDiff = framesWritten + actualFrames - headPos
                val bufSize = track?.bufferSizeInFrames ?: 0
                DebugLog.log(TAG, "aud: w=$written/$writeCount buf=$bufDiff bufSize=$bufSize rate=$actualRate")
            }

            framesWritten += actualFrames

            // Low-buffer padding: if buffer was below 15%, pad with last frame
            if (preBufferDone && track != null && count >= 4) {
                val headPos = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                val bufLevel = framesWritten - headPos
                val bufCap = track.bufferSizeInFrames.toLong()
                if (bufCap > 0 && bufLevel < bufCap * 15 / 100) {
                    val lastL = samples[count - 2]
                    val lastR = samples[count - 1]
                    val pad = ShortArray(40)
                    for (p in 0 until 40 step 2) { pad[p] = lastL; pad[p + 1] = lastR }
                    val padW = track.write(pad, 0, 40, AudioTrack.WRITE_NON_BLOCKING)
                    if (padW > 0) framesWritten += padW / 2
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

    fun flush() {
        audioTrack?.pause()
        audioTrack?.flush()
        framesWritten = 0L
        preBufferDone = false
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
