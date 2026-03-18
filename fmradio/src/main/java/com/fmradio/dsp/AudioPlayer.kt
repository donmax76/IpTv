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
 */
class AudioPlayer(private val sampleRate: Int = 48000) {

    companion object {
        private const val TAG = "AudioPlayer"
        // Ring buffer: ~1s of stereo audio at 48kHz (L,R interleaved)
        private const val RING_BUFFER_SAMPLES = 96000  // 48000 frames × 2 channels
        private const val LOW_WATERMARK = 2048   // ~21ms stereo
        private const val HIGH_WATERMARK = 76800 // 80%
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

    fun start() {
        if (isPlaying) return

        val minBufSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = minBufSize * 4

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

        audioTrack?.play()
        isPlaying = true

        playbackThread = Thread({
            val chunkSize = 2048  // 1024 stereo frames = 2048 samples
            val chunk = ShortArray(chunkSize)
            while (isPlaying) {
                val available: Int
                lock.lock()
                try { available = bufferedSamples } finally { lock.unlock() }

                if (available >= chunkSize) {
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
                } else if (available > 0) {
                    // Drain what we have (must be even for stereo)
                    val toDrain = available and 0x7FFFFFFE  // round down to even
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
                    try { Thread.sleep(2) } catch (_: InterruptedException) { break }
                }
            }
        }, "FmAudioDrain")
        playbackThread?.priority = Thread.MAX_PRIORITY
        playbackThread?.start()

        Log.i(TAG, "Stereo audio started (${sampleRate}Hz, buffer=${bufferSize}, ring=${RING_BUFFER_SAMPLES})")
    }

    fun writeSamples(samples: ShortArray) {
        if (!isPlaying) return
        lock.lock()
        try {
            val spaceNeeded = samples.size
            if (bufferedSamples + spaceNeeded > RING_BUFFER_SAMPLES) {
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
        Log.i(TAG, "Audio playback stopped")
    }

    fun isActive(): Boolean = isPlaying
}
