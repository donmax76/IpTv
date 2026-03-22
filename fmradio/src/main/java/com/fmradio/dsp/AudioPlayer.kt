package com.fmradio.dsp

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import java.util.concurrent.locks.ReentrantLock

/**
 * Stereo audio output player with ring buffer to smooth out USB/SDR timing jitter.
 * Accepts interleaved stereo samples (L,R,L,R,...) from FmDemodulator.
 * Uses a dedicated playback thread to decouple USB reads from audio output.
 *
 * Key design:
 *  - Pre-buffering: accumulate ~200ms before first drain to absorb USB jitter
 *  - Fade-in: 50ms ramp on initial playback to prevent startup pop
 *  - Fade-to-silence on underrun: smooth ramp to zero prevents clicks
 *  - Crossfade on overflow: prevents audible discontinuity when dropping samples
 *  - Large ring buffer: 4s of stereo audio for maximum jitter tolerance
 */
class AudioPlayer(private val sampleRate: Int = 48000) {

    companion object {
        private const val TAG = "AudioPlayer"
        // Ring buffer: ~4s of stereo audio at 48kHz (L,R interleaved)
        private const val RING_BUFFER_SAMPLES = 384000  // 48000 frames × 2 ch × 4 sec
        private const val LOW_WATERMARK = 4096   // ~42ms stereo — minimum to drain
        private const val HIGH_WATERMARK = 345600 // 90% full — trigger overflow drop
        // Pre-buffer: accumulate this much before starting AudioTrack drain
        private const val PRE_BUFFER_SAMPLES = 19200  // ~200ms stereo (48000*2*0.2)
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
        // Use 6× minimum for extra headroom against underruns
        val bufferSize = minBufSize * 6

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

        writePos = 0
        readPos = 0
        bufferedSamples = 0
        preBufferFilled = false
        samplesPlayed = 0L
        lastOutputSample = 0

        audioTrack?.play()
        isPlaying = true

        playbackThread = Thread({
            val chunkSize = 4096  // 2048 stereo frames — larger chunks reduce overhead
            val chunk = ShortArray(chunkSize)

            while (isPlaying) {
                // Wait for pre-buffer to fill before first drain
                if (!preBufferFilled) {
                    lock.lock()
                    val avail = bufferedSamples
                    lock.unlock()
                    if (avail < PRE_BUFFER_SAMPLES) {
                        try { Thread.sleep(5) } catch (_: InterruptedException) { break }
                        continue
                    }
                    preBufferFilled = true
                    Log.i(TAG, "Pre-buffer filled ($avail samples), starting drain")
                }

                val available: Int
                lock.lock()
                try { available = bufferedSamples } finally { lock.unlock() }

                val toDrain = if (available >= chunkSize) chunkSize
                              else if (available >= 512) available and 0x7FFFFFFE
                              else {
                                  // Underrun: fade to silence from last output sample to prevent click
                                  val silenceChunk = ShortArray(chunkSize)
                                  for (i in 0 until chunkSize) {
                                      val fadeOut = (chunkSize - i).toFloat() / chunkSize
                                      val s = (lastOutputSample * fadeOut * 0.5f).toInt().coerceIn(-32767, 32767)
                                      silenceChunk[i] = s.toShort()
                                      lastOutputSample = s
                                  }
                                  lastOutputSample = 0
                                  try {
                                      audioTrack?.write(silenceChunk, 0, chunkSize)
                                  } catch (_: Exception) {}
                                  try { Thread.sleep(5) } catch (_: InterruptedException) { break }
                                  continue
                              }

                if (toDrain == 0) {
                    try { Thread.sleep(5) } catch (_: InterruptedException) { break }
                    continue
                }

                lock.lock()
                try {
                    for (i in 0 until toDrain) {
                        chunk[i] = ringBuffer[readPos]
                        readPos = (readPos + 1) % RING_BUFFER_SAMPLES
                    }
                    bufferedSamples -= toDrain
                } finally { lock.unlock() }

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

    fun writeSamples(samples: ShortArray) {
        if (!isPlaying) return
        lock.lock()
        try {
            val freeSpace = RING_BUFFER_SAMPLES - bufferedSamples
            if (samples.size > freeSpace) {
                // Overflow: drop oldest samples with crossfade to prevent click
                val toDrop = samples.size - freeSpace + RING_BUFFER_SAMPLES / 8
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
            for (s in samples) {
                ringBuffer[writePos] = s
                writePos = (writePos + 1) % RING_BUFFER_SAMPLES
            }
            bufferedSamples += samples.size
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
