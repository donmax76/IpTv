package com.fmradio.ui

import java.util.concurrent.locks.ReentrantLock
import javax.sound.sampled.*

/**
 * Desktop audio player using javax.sound.sampled with ring buffer
 * for smooth RTL-SDR streaming playback.
 */
class DesktopAudioPlayer(private val sampleRate: Int = 48000) {

    companion object {
        private const val RING_BUFFER_SAMPLES = 96000  // 2000ms
    }

    private var sourceDataLine: SourceDataLine? = null
    @Volatile
    private var isPlaying = false
    private var volume = 0.8f

    private val ringBuffer = ShortArray(RING_BUFFER_SAMPLES)
    private var writePos = 0
    private var readPos = 0
    private var bufferedSamples = 0
    private val lock = ReentrantLock()
    private var drainThread: Thread? = null

    fun start() {
        if (isPlaying) return

        val format = AudioFormat(
            sampleRate.toFloat(),
            16,     // bits per sample
            1,      // mono
            true,   // signed
            false   // little-endian
        )

        val bufSize = sampleRate  // 500ms buffer in bytes (16-bit mono)
        val info = DataLine.Info(SourceDataLine::class.java, format, bufSize)
        sourceDataLine = (AudioSystem.getLine(info) as SourceDataLine).also {
            it.open(format, bufSize)
            it.start()
        }

        writePos = 0; readPos = 0; bufferedSamples = 0
        isPlaying = true

        drainThread = Thread({
            val chunkSamples = 1024
            val chunkBytes = ByteArray(chunkSamples * 2)  // 16-bit = 2 bytes per sample
            val chunk = ShortArray(chunkSamples)

            while (isPlaying) {
                val available: Int
                lock.lock()
                try { available = bufferedSamples } finally { lock.unlock() }

                val toDrain = if (available >= chunkSamples) chunkSamples
                              else if (available >= 256) available
                              else {
                                  // Buffer underflow — write silence to avoid clicks
                                  for (i in 0 until chunkSamples * 2) chunkBytes[i] = 0
                                  try { sourceDataLine?.write(chunkBytes, 0, chunkSamples * 2) } catch (_: Exception) {}
                                  Thread.sleep(8)
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

                // Apply volume and convert to bytes (little-endian 16-bit)
                for (i in 0 until toDrain) {
                    val s = (chunk[i] * volume).toInt().coerceIn(-32767, 32767)
                    chunkBytes[i * 2] = (s and 0xFF).toByte()
                    chunkBytes[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
                }

                try {
                    sourceDataLine?.write(chunkBytes, 0, toDrain * 2)
                } catch (e: Exception) {
                    println("Audio write error: ${e.message}")
                }
            }
        }, "AudioDrain")
        drainThread?.isDaemon = true
        drainThread?.priority = Thread.MAX_PRIORITY
        drainThread?.start()

        println("Desktop audio started (${sampleRate}Hz)")
    }

    fun writeSamples(samples: ShortArray) {
        if (!isPlaying) return
        lock.lock()
        try {
            val freeSpace = RING_BUFFER_SAMPLES - bufferedSamples
            if (samples.size > freeSpace) {
                // Buffer full — drop oldest samples smoothly
                val toDrop = samples.size - freeSpace + RING_BUFFER_SAMPLES / 4
                if (toDrop > 0 && toDrop <= bufferedSamples) {
                    readPos = (readPos + toDrop) % RING_BUFFER_SAMPLES
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

    fun setVolume(vol: Float) {
        volume = vol.coerceIn(0f, 1f)
    }

    fun stop() {
        isPlaying = false
        drainThread?.interrupt()
        try { drainThread?.join(500) } catch (_: Exception) {}
        drainThread = null
        try {
            sourceDataLine?.stop()
            sourceDataLine?.close()
        } catch (_: Exception) {}
        sourceDataLine = null
    }

    fun isActive(): Boolean = isPlaying
}
