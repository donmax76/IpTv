package com.fmradio.dsp

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import java.util.concurrent.atomic.AtomicInteger

/**
 * Stereo audio output player with lock-free ring buffer to smooth out USB/SDR timing jitter.
 * Accepts interleaved stereo samples (L,R,L,R,...) from FmDemodulator.
 * Uses a dedicated high-priority playback thread to decouple USB reads from audio output.
 *
 * Key design:
 *  - Lock-free ring buffer using atomic bufferedSamples counter
 *  - Pre-buffering: accumulate ~300ms before first drain to absorb USB jitter
 *  - Fade-in: 50ms ramp on initial playback to prevent startup pop
 *  - Crossfade on overflow: prevents audible discontinuity when dropping samples
 *  - Large ring buffer: 4s of stereo audio for maximum jitter tolerance
 */
class AudioPlayer(private val sampleRate: Int = 48000) {

    companion object {
        private const val TAG = "AudioPlayer"
        // Ring buffer: ~4s of stereo audio at 48kHz (L,R interleaved)
        private const val RING_BUFFER_SAMPLES = 384000  // 48000 frames × 2 ch × 4 sec
        private const val LOW_WATERMARK = 4096   // ~43ms stereo — minimum to drain
        private const val HIGH_WATERMARK = 345600 // 90% full — trigger overflow drop
        // Pre-buffer: accumulate this much before starting AudioTrack drain
        private const val PRE_BUFFER_SAMPLES = 28800  // ~300ms stereo — absorb USB jitter
        // Fade-in on initial playback start to prevent pop
        private const val FADE_IN_SAMPLES = 4800  // ~50ms stereo
        // Crossfade on buffer overflow to prevent click
        private const val CROSSFADE_SAMPLES = 2048
    }

    private var audioTrack: AudioTrack? = null
    @Volatile
    private var isPlaying = false

    // Lock-free ring buffer (interleaved stereo: L,R,L,R,...)
    // Writer (demod thread) owns writePos; reader (drain thread) owns readPos.
    // bufferedSamples is the atomic coordination point.
    private val ringBuffer = ShortArray(RING_BUFFER_SAMPLES)
    private var writePos = 0
    private var readPos = 0
    private val bufferedSamples = AtomicInteger(0)

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
        // Use 8× minimum for extra headroom against underruns
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

        writePos = 0
        readPos = 0
        bufferedSamples.set(0)
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
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            val chunkSize = 4096  // 2048 stereo frames — larger chunks reduce overhead
            val chunk = ShortArray(chunkSize)

            while (isPlaying) {
                val avail = bufferedSamples.get()

                // Wait for pre-buffer to fill before first drain
                if (!preBufferFilled) {
                    if (avail < PRE_BUFFER_SAMPLES) {
                        // Use shorter sleep with backoff — event-like behavior
                        try { Thread.sleep(1) } catch (_: InterruptedException) { break }
                        continue
                    }
                    preBufferFilled = true
                    Log.i(TAG, "Pre-buffer filled ($avail samples), starting drain")
                    DebugLog.log("AUD", "Pre-buffer filled ($avail samples), draining to AudioTrack")
                }

                val toDrain = if (avail >= chunkSize) chunkSize
                              else if (avail >= LOW_WATERMARK) avail and 0x7FFFFFFE
                              else {
                                  // Underrun: yield briefly and retry — no long sleep
                                  Thread.yield()
                                  val retryAvail = bufferedSamples.get()
                                  if (retryAvail >= LOW_WATERMARK) {
                                      retryAvail and 0x7FFFFFFE
                                  } else {
                                      try { Thread.sleep(1) } catch (_: InterruptedException) { break }
                                      continue
                                  }
                              }

                if (toDrain == 0) {
                    try { Thread.sleep(1) } catch (_: InterruptedException) { break }
                    continue
                }

                // Read from ring buffer — no lock needed, we own readPos
                for (i in 0 until toDrain) {
                    chunk[i] = ringBuffer[readPos]
                    readPos = (readPos + 1) % RING_BUFFER_SAMPLES
                }
                bufferedSamples.addAndGet(-toDrain)

                // Apply fade-in on initial playback start
                for (i in 0 until toDrain) {
                    if (samplesPlayed < FADE_IN_SAMPLES) {
                        val fadeGain = samplesPlayed.toFloat() / FADE_IN_SAMPLES
                        chunk[i] = (chunk[i] * fadeGain).toInt().coerceIn(-32767, 32767).toShort()
                    }
                    lastOutputSample = chunk[i].toInt()
                    samplesPlayed++
                }

                try {
                    audioTrack?.write(chunk, 0, toDrain)
                } catch (e: Exception) {
                    Log.e(TAG, "Error writing audio", e)
                }
            }
        }, "FmAudioDrain")
        playbackThread?.priority = Thread.MAX_PRIORITY
        playbackThread?.start()

        Log.i(TAG, "Stereo audio started (${sampleRate}Hz, buf=$bufferSize, ring=$RING_BUFFER_SAMPLES)")
    }

    private var writeSamplesCount = 0L
    private var lastWriteLog = 0L

    fun writeSamples(samples: ShortArray) {
        if (!isPlaying) return
        writeSamplesCount++
        val now = System.currentTimeMillis()
        if (writeSamplesCount <= 3 || now - lastWriteLog > 5000) {
            var maxAbs = 0
            for (s in samples) { val a = kotlin.math.abs(s.toInt()); if (a > maxAbs) maxAbs = a }
            DebugLog.log("AUD", "writeSamples #$writeSamplesCount: ${samples.size} samples, peak=$maxAbs, buffered=${bufferedSamples.get()}")
            lastWriteLog = now
        }

        val currentBuffered = bufferedSamples.get()
        val freeSpace = RING_BUFFER_SAMPLES - currentBuffered
        if (samples.size > freeSpace) {
            // Overflow: drop oldest samples with crossfade to prevent click
            val toDrop = samples.size - freeSpace + RING_BUFFER_SAMPLES / 8
            if (toDrop > 0 && toDrop <= currentBuffered) {
                val fadeLen = CROSSFADE_SAMPLES.coerceAtMost(currentBuffered - toDrop)
                val newReadPos = (readPos + toDrop) % RING_BUFFER_SAMPLES
                for (i in 0 until fadeLen) {
                    val fadeIn = i.toFloat() / fadeLen
                    val oldIdx = (newReadPos + i) % RING_BUFFER_SAMPLES
                    ringBuffer[oldIdx] = (ringBuffer[oldIdx] * fadeIn).toInt().toShort()
                }
                readPos = newReadPos
                bufferedSamples.addAndGet(-toDrop)
            }
        }

        // Write to ring buffer — no lock needed, we own writePos
        for (s in samples) {
            ringBuffer[writePos] = s
            writePos = (writePos + 1) % RING_BUFFER_SAMPLES
        }
        bufferedSamples.addAndGet(samples.size)
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
        writePos = 0; readPos = 0; bufferedSamples.set(0)
        preBufferFilled = false
        samplesPlayed = 0L
        lastOutputSample = 0
        Log.i(TAG, "Audio playback stopped")
    }

    fun isActive(): Boolean = isPlaying
}
