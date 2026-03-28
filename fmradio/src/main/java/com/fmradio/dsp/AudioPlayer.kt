package com.fmradio.dsp

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import java.util.concurrent.locks.ReentrantLock

/**
 * Stereo audio output player with ring buffer to smooth out USB/SDR timing jitter.
 * Accepts interleaved stereo samples (L,R,L,R,...) from FmDemodulator.
 * Uses a dedicated high-priority playback thread to decouple USB reads from audio output.
 *
 * Key design:
 *  - Lightweight lock (only held during index update, not during AudioTrack.write)
 *  - Pre-buffering: accumulate ~150ms before first drain to absorb USB jitter
 *  - Fade-in: 50ms ramp on initial playback to prevent startup pop
 *  - Crossfade on overflow: prevents audible discontinuity when dropping samples
 *  - Large ring buffer: 4s of stereo audio for maximum jitter tolerance
 */
class AudioPlayer(private val sampleRate: Int = 48000) {

    companion object {
        private const val TAG = "AudioPlayer"
        // Ring buffer: ~4s of stereo audio at 48kHz (L,R interleaved)
        private const val RING_BUFFER_SAMPLES = 384000  // 48000 frames × 2 ch × 4 sec
        // Drain when at least 2048 samples available (~21ms)
        // Keeps buffer from emptying completely — prevents micro-gaps
        private const val LOW_WATERMARK = 2048
        private const val HIGH_WATERMARK = 345600 // 90% full — trigger overflow drop
        // Pre-buffer: accumulate before starting AudioTrack drain
        private const val PRE_BUFFER_SAMPLES = 24000  // ~250ms stereo — smooth start
        // Fade-in on initial playback start to prevent pop
        private const val FADE_IN_SAMPLES = 4800  // ~50ms stereo
        // Crossfade on buffer overflow to prevent click
        private const val CROSSFADE_SAMPLES = 2048
    }

    private var audioTrack: AudioTrack? = null
    @Volatile
    private var isPlaying = false

    // Ring buffer (interleaved stereo: L,R,L,R,...)
    private val ringBuffer = ShortArray(RING_BUFFER_SAMPLES)
    private var writePos = 0
    private var readPos = 0
    private var bufferedSamples = 0
    private val lock = ReentrantLock()

    private var playbackThread: Thread? = null
    @Volatile
    private var preBufferFilled = false
    private var samplesPlayed = 0L
    private var lastOutputSample = 0

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
        // Use 6× minimum for headroom
        val bufferSize = minBufSize * 6

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

        writePos = 0
        readPos = 0
        bufferedSamples = 0
        preBufferFilled = false
        samplesPlayed = 0L
        lastOutputSample = 0

        try {
            audioTrack?.play()
            DebugLog.log("AUD", "AudioTrack.play() OK, state=${audioTrack?.playState}, rate=$sampleRate, bufSize=$bufferSize")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AudioTrack playback", e)
            DebugLog.log("AUD", "AudioTrack.play() FAILED: ${e.message}")
            audioTrack?.release()
            audioTrack = null
            return
        }
        isPlaying = true

        playbackThread = Thread({
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            } catch (_: Throwable) {}
            // Drain in smaller chunks — keeps buffer level more stable
            val maxChunk = 4096
            val chunk = ShortArray(maxChunk)

            try { while (isPlaying) {
                val avail: Int
                lock.lock()
                try { avail = bufferedSamples } finally { lock.unlock() }

                // Wait for pre-buffer to fill before first drain
                if (!preBufferFilled) {
                    if (avail < PRE_BUFFER_SAMPLES) {
                        try { Thread.sleep(2) } catch (_: InterruptedException) { break }
                        continue
                    }
                    preBufferFilled = true
                    Log.i(TAG, "Pre-buffer filled ($avail samples), starting drain")
                    DebugLog.log("AUD", "Pre-buffer filled ($avail samples), draining to AudioTrack")
                }

                // Drain but keep LOW_WATERMARK reserve — never empty the buffer
                if (avail < LOW_WATERMARK * 2) {
                    try { Thread.sleep(1) } catch (_: InterruptedException) { break }
                    continue
                }

                // Only drain what's above the reserve
                val drainable = avail - LOW_WATERMARK
                val toDrain = drainable.coerceAtMost(maxChunk) and 0x7FFFFFFE  // even for stereo

                if (toDrain == 0) {
                    try { Thread.sleep(1) } catch (_: InterruptedException) { break }
                    continue
                }

                // Copy from ring buffer under lock
                var actualDrain: Int
                lock.lock()
                try {
                    actualDrain = toDrain.coerceAtMost(bufferedSamples)
                    if (actualDrain < 2) { actualDrain = 0; continue }
                    actualDrain = actualDrain and 0x7FFFFFFE  // keep even
                    for (i in 0 until actualDrain) {
                        chunk[i] = ringBuffer[readPos]
                        readPos = (readPos + 1) % RING_BUFFER_SAMPLES
                    }
                    bufferedSamples -= actualDrain
                } finally { lock.unlock() }

                if (actualDrain == 0) continue

                // Apply fade-in on initial playback start (outside lock)
                if (samplesPlayed < FADE_IN_SAMPLES) {
                    for (i in 0 until actualDrain) {
                        if (samplesPlayed < FADE_IN_SAMPLES) {
                            val fadeGain = samplesPlayed.toFloat() / FADE_IN_SAMPLES
                            chunk[i] = (chunk[i] * fadeGain).toInt().coerceIn(-32767, 32767).toShort()
                        }
                        samplesPlayed++
                    }
                } else {
                    samplesPlayed += actualDrain
                }

                // Write to AudioTrack outside lock — this is the blocking part
                try {
                    audioTrack?.write(chunk, 0, actualDrain)
                } catch (e: Exception) {
                    Log.e(TAG, "Error writing audio", e)
                }
            } } catch (e: Throwable) {
                Log.e(TAG, "FATAL error in drain thread", e)
                DebugLog.log("AUD", "DRAIN CRASH: ${e.javaClass.simpleName}: ${e.message}")
                DebugLog.flush()
            }
        }, "FmAudioDrain")
        playbackThread?.priority = Thread.MAX_PRIORITY
        playbackThread?.start()

        Log.i(TAG, "Stereo audio started (${sampleRate}Hz, buf=$bufferSize, ring=$RING_BUFFER_SAMPLES)")
    }

    private var writeSamplesCount = 0L
    private var lastWriteLog = 0L

    /** Write samples from buffer with explicit count — zero-copy from demodulator */
    fun writeSamples(samples: ShortArray, count: Int = samples.size) {
        if (!isPlaying || count <= 0) return
        writeSamplesCount++
        val now = System.currentTimeMillis()
        if (writeSamplesCount <= 3 || now - lastWriteLog > 5000) {
            var maxAbs = 0
            for (i in 0 until count) { val a = kotlin.math.abs(samples[i].toInt()); if (a > maxAbs) maxAbs = a }
            lock.lock()
            val buf = bufferedSamples
            lock.unlock()
            DebugLog.log("AUD", "writeSamples #$writeSamplesCount: $count samples, peak=$maxAbs, buffered=$buf")
            lastWriteLog = now
        }

        lock.lock()
        try {
            val freeSpace = RING_BUFFER_SAMPLES - bufferedSamples
            if (count > freeSpace) {
                // Overflow: drop oldest samples with crossfade to prevent click
                val toDrop = count - freeSpace + RING_BUFFER_SAMPLES / 8
                if (toDrop > 0 && toDrop <= bufferedSamples) {
                    val fadeLen = CROSSFADE_SAMPLES.coerceAtMost(bufferedSamples - toDrop)
                    val newReadPos = (readPos + toDrop) % RING_BUFFER_SAMPLES
                    for (i in 0 until fadeLen) {
                        val fadeIn = i.toFloat() / fadeLen
                        val oldIdx = (newReadPos + i) % RING_BUFFER_SAMPLES
                        ringBuffer[oldIdx] = (ringBuffer[oldIdx] * fadeIn).toInt().toShort()
                    }
                    readPos = newReadPos
                    bufferedSamples -= toDrop
                }
            }
            for (i in 0 until count) {
                ringBuffer[writePos] = samples[i]
                writePos = (writePos + 1) % RING_BUFFER_SAMPLES
            }
            bufferedSamples += count
        } finally { lock.unlock() }
    }

    fun setVolume(volume: Float) {
        val vol = volume.coerceIn(0f, 1f)
        audioTrack?.setVolume(vol)
    }

    fun stop() {
        isPlaying = false
        playbackThread?.interrupt()
        try { playbackThread?.join(500) } catch (_: InterruptedException) {}
        playbackThread = null
        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping audio", e)
        }
        audioTrack = null
        writePos = 0; readPos = 0; bufferedSamples = 0
        preBufferFilled = false
        samplesPlayed = 0L
        lastOutputSample = 0
        Log.i(TAG, "Audio playback stopped")
    }

    fun isActive(): Boolean = isPlaying
}
