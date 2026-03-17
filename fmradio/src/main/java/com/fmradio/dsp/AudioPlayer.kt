package com.fmradio.dsp

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import java.util.concurrent.locks.ReentrantLock

/**
 * Audio output player with ring buffer to smooth out USB/SDR timing jitter.
 * Uses a dedicated playback thread to decouple USB reads from audio output.
 */
class AudioPlayer(private val sampleRate: Int = 48000) {

    companion object {
        private const val TAG = "AudioPlayer"
        // Ring buffer: ~500ms of audio at 48kHz (mono 16-bit)
        private const val RING_BUFFER_SAMPLES = 48000 / 2  // 24000 samples = 500ms
        private const val LOW_WATERMARK = 2400   // start feeding when 50ms buffered
        private const val HIGH_WATERMARK = 20000  // pause accepting when 416ms buffered
    }

    private var audioTrack: AudioTrack? = null
    @Volatile
    private var isPlaying = false

    // Ring buffer
    private val ringBuffer = ShortArray(RING_BUFFER_SAMPLES)
    private var writePos = 0
    private var readPos = 0
    private var bufferedSamples = 0
    private val lock = ReentrantLock()

    // Playback thread
    private var playbackThread: Thread? = null

    fun start() {
        if (isPlaying) return

        val minBufSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        // Use 6x minimum buffer for smooth streaming
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
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        // Reset ring buffer
        writePos = 0
        readPos = 0
        bufferedSamples = 0

        audioTrack?.play()
        isPlaying = true

        // Start dedicated playback drain thread
        playbackThread = Thread({
            val chunkSize = 1024  // drain 1024 samples at a time (~21ms)
            val chunk = ShortArray(chunkSize)
            while (isPlaying) {
                val available: Int
                lock.lock()
                try {
                    available = bufferedSamples
                } finally {
                    lock.unlock()
                }

                if (available >= chunkSize) {
                    lock.lock()
                    try {
                        for (i in 0 until chunkSize) {
                            chunk[i] = ringBuffer[readPos]
                            readPos = (readPos + 1) % RING_BUFFER_SAMPLES
                        }
                        bufferedSamples -= chunkSize
                    } finally {
                        lock.unlock()
                    }
                    try {
                        audioTrack?.write(chunk, 0, chunkSize)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error writing audio", e)
                    }
                } else if (available > 0 && available >= LOW_WATERMARK) {
                    // Drain what we have
                    val toDrain = available
                    val partial = ShortArray(toDrain)
                    lock.lock()
                    try {
                        for (i in 0 until toDrain) {
                            partial[i] = ringBuffer[readPos]
                            readPos = (readPos + 1) % RING_BUFFER_SAMPLES
                        }
                        bufferedSamples -= toDrain
                    } finally {
                        lock.unlock()
                    }
                    try {
                        audioTrack?.write(partial, 0, toDrain)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error writing audio", e)
                    }
                } else {
                    // Not enough data, sleep briefly
                    try { Thread.sleep(5) } catch (_: InterruptedException) { break }
                }
            }
        }, "FmAudioDrain")
        playbackThread?.priority = Thread.MAX_PRIORITY
        playbackThread?.start()

        Log.i(TAG, "Audio playback started (${sampleRate}Hz, buffer=${bufferSize}, ring=${RING_BUFFER_SAMPLES})")
    }

    fun writeSamples(samples: ShortArray) {
        if (!isPlaying) return
        lock.lock()
        try {
            // If ring buffer is almost full, drop oldest samples to prevent blocking
            val spaceNeeded = samples.size
            if (bufferedSamples + spaceNeeded > RING_BUFFER_SAMPLES) {
                // Drop oldest to make room
                val toDrop = (bufferedSamples + spaceNeeded) - RING_BUFFER_SAMPLES
                readPos = (readPos + toDrop) % RING_BUFFER_SAMPLES
                bufferedSamples -= toDrop
            }

            for (s in samples) {
                ringBuffer[writePos] = s
                writePos = (writePos + 1) % RING_BUFFER_SAMPLES
            }
            bufferedSamples += samples.size
        } finally {
            lock.unlock()
        }
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
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping audio", e)
        }
        audioTrack = null
        Log.i(TAG, "Audio playback stopped")
    }

    fun isActive(): Boolean = isPlaying
}
