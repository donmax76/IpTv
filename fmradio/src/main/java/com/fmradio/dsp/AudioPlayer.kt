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
 *  - Pre-buffering: accumulate ~80ms before first drain to absorb USB jitter
 *  - Silence padding: on underrun, write silence to AudioTrack to prevent clicks
 *  - Large ring buffer: 2s of stereo audio for maximum jitter tolerance
 */
class AudioPlayer(private val sampleRate: Int = 48000) {

    companion object {
        private const val TAG = "AudioPlayer"
        // Ring buffer: ~2s of stereo audio at 48kHz (L,R interleaved)
        private const val RING_BUFFER_SAMPLES = 192000  // 48000 frames × 2 ch × 2 sec
        private const val LOW_WATERMARK = 4096   // ~42ms stereo — minimum to drain
        private const val HIGH_WATERMARK = 172800 // 90% full — trigger overflow drop
        // Pre-buffer: accumulate this much before starting AudioTrack drain
        private const val PRE_BUFFER_SAMPLES = 7680  // ~80ms stereo (48000*2*0.08)
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

        audioTrack?.play()
        isPlaying = true

        playbackThread = Thread({
            val chunkSize = 2048  // 1024 stereo frames
            val chunk = ShortArray(chunkSize)
            val silenceChunk = ShortArray(chunkSize) // pre-allocated silence
            var consecutiveUnderruns = 0

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

                if (available >= chunkSize) {
                    consecutiveUnderruns = 0
                    lock.lock()
                    try {
                        for (i in 0 until chunkSize) {
                            chunk[i] = ringBuffer[readPos]
                            readPos = (readPos + 1) % RING_BUFFER_SAMPLES
                        }
                        bufferedSamples -= chunkSize
                    } finally { lock.unlock() }
                    try {
                        audioTrack?.write(chunk, 0, chunkSize)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error writing audio", e)
                    }
                } else if (available >= 2) {
                    // Drain what we have (must be even for stereo)
                    consecutiveUnderruns = 0
                    val toDrain = available and 0x7FFFFFFE
                    if (toDrain > 0) {
                        val partial = ShortArray(toDrain)
                        lock.lock()
                        try {
                            for (i in 0 until toDrain) {
                                partial[i] = ringBuffer[readPos]
                                readPos = (readPos + 1) % RING_BUFFER_SAMPLES
                            }
                            bufferedSamples -= toDrain
                        } finally { lock.unlock() }
                        try {
                            audioTrack?.write(partial, 0, toDrain)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error writing audio", e)
                        }
                    }
                } else {
                    // Underrun: write silence to prevent AudioTrack gap/click
                    consecutiveUnderruns++
                    if (consecutiveUnderruns <= 10) {
                        // Write silence to keep AudioTrack stream continuous
                        try {
                            audioTrack?.write(silenceChunk, 0, chunkSize)
                        } catch (_: Exception) {}
                    } else {
                        // Sustained underrun — likely no data coming, sleep to save CPU
                        try { Thread.sleep(5) } catch (_: InterruptedException) { break }
                    }
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
            val spaceNeeded = samples.size
            if (bufferedSamples + spaceNeeded > RING_BUFFER_SAMPLES) {
                // Overflow: drop oldest samples, but fade them to avoid click
                val toDrop = (bufferedSamples + spaceNeeded) - RING_BUFFER_SAMPLES
                readPos = (readPos + toDrop) % RING_BUFFER_SAMPLES
                bufferedSamples -= toDrop
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
        Log.i(TAG, "Audio playback stopped")
    }

    fun isActive(): Boolean = isPlaying
}
