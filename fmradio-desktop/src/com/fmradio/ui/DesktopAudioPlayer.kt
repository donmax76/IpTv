package com.fmradio.ui

import java.util.concurrent.locks.ReentrantLock
import javax.sound.sampled.*

/**
 * Desktop stereo audio player using javax.sound.sampled with ring buffer
 * for smooth RTL-SDR streaming playback.
 * Accepts interleaved stereo samples (L,R,L,R,...) from FmDemodulator.
 */
class DesktopAudioPlayer(private val sampleRate: Int = 48000) {

    companion object {
        private const val RING_BUFFER_SAMPLES = 384000  // 4s stereo (48000 frames × 2 ch × 4)
        // 400 ms cushion. 200 ms left almost no margin: a single USB or
        // scheduling hiccup emptied the ring and produced an audible break.
        private const val PREFILL_SAMPLES = 38400        // 400ms stereo (48000*2*0.4)
        private const val FADE_IN_SAMPLES = 4800         // 50ms stereo
        private const val CROSSFADE_SAMPLES = 2048       // crossfade on buffer overflow

        // An empty ring is only an emergency once the audio DEVICE is also
        // nearly dry. Below this many frames left in the line there is no time
        // to recover and fading out is the least bad option; above it, waiting
        // costs nothing and the listener hears no interruption at all.
        private const val LINE_LOW_WATER_FRAMES = 2400   // 50 ms at 48 kHz

        // Drift compensation: the RTL-SDR's crystal runs at a slightly different
        // rate than its nominal 28.8 MHz (typically tens of ppm), so the actual
        // IQ sample rate never matches 1152000 Hz exactly. Over a long session
        // this makes the ring buffer slowly fill up or drain relative to the
        // audio hardware's fixed 48 kHz clock — perceived as audio gradually
        // "speeding up" or "slowing down" as periodic correction glitches kick
        // in. We counter this continuously by silently dropping or duplicating
        // a single stereo frame every so often, nudging the buffer back toward
        // a steady target level — far too sparse to be audible.
        private const val DRIFT_GAIN = 0.00002
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
    @Volatile
    private var prefillDone = false
    private var samplesPlayed = 0L

    private var lastOutputSample = 0

    // Diagnostics surfaced in the log: how full the ring is and how often the
    // drain thread ran dry (an underrun is exactly what a "click" sounds like).
    @Volatile private var underruns = 0L
    fun bufferedFrames(): Int = bufferedSamples / 2
    fun underrunCount(): Long = underruns

    /** Frames still queued inside the audio device — the real safety margin. */
    fun lineQueuedFrames(): Int {
        val line = sourceDataLine ?: return -1
        return try { (line.bufferSize - line.available()) / 4 } catch (_: Exception) { -1 }
    }

    // Drift correction state (see DRIFT_GAIN above).
    //
    // The target is the ring level the design can actually settle at, not the
    // prefill amount. Playback buffering lives in two places: the audio line
    // permanently holds its own bufferSize worth, and the ring holds the rest.
    // With a 400 ms prefill and a 200 ms line, the ring settles near 200 ms —
    // so aiming the controller at the full 400 ms left it saturated on the
    // "drain slower" side forever, duplicating frames it never needed to.
    // Set for real in start(), once the line's actual buffer size is known.
    private var driftTargetSamples = PREFILL_SAMPLES / 2
    private var fillEma = (PREFILL_SAMPLES / 2).toDouble()
    private var driftCredit = 0.0

    fun start() {
        if (isPlaying) return

        val format = AudioFormat(
            sampleRate.toFloat(),
            16,     // bits per sample
            2,      // stereo
            true,   // signed
            false   // little-endian
        )

        // 200 ms. This used to be 2 s (sampleRate*8), which broke the whole
        // design: SourceDataLine.write() never blocked, so the drain thread
        // emptied the ring buffer into the line as fast as it could and the
        // ring sat permanently at ~0. Every "buffer empty" check then fired
        // constantly, injecting silence into otherwise fine audio. With a
        // small line buffer write() blocks on the audio clock, the ring holds
        // the real cushion, and its level becomes meaningful again.
        val bufSize = sampleRate * 2 * 2 / 5  // 200 ms, 16-bit stereo
        val info = DataLine.Info(SourceDataLine::class.java, format, bufSize)
        sourceDataLine = (AudioSystem.getLine(info) as SourceDataLine).also {
            it.open(format, bufSize)
            it.start()
        }

        writePos = 0; readPos = 0; bufferedSamples = 0
        prefillDone = false
        underruns = 0L
        samplesPlayed = 0L
        lastOutputSample = 0
        // line.bufferSize is in bytes; /2 converts to 16-bit samples, which is
        // the unit the ring counts in.
        driftTargetSamples = (PREFILL_SAMPLES - bufSize / 2).coerceAtLeast(4800)
        fillEma = driftTargetSamples.toDouble()
        driftCredit = 0.0
        isPlaying = true

        drainThread = Thread({
            val chunkSamples = 4096  // 2048 stereo frames — larger chunks reduce overhead
            val chunkBytes = ByteArray(chunkSamples * 2)
            val chunk = ShortArray(chunkSamples)

            while (isPlaying) {
                if (!prefillDone) {
                    lock.lock()
                    val avail = bufferedSamples
                    lock.unlock()
                    if (avail < PREFILL_SAMPLES) {
                        Thread.sleep(5)
                        continue
                    }
                    prefillDone = true
                }

                val available: Int
                lock.lock()
                try { available = bufferedSamples } finally { lock.unlock() }

                // Slowly track the buffer fill level and decide whether a single-frame
                // drift correction is due (see driftTargetSamples/DRIFT_GAIN above).
                // driftAdjust is the extra amount (beyond toDrain) to *consume* from
                // the ring buffer: positive when the buffer is trending too full
                // (drain faster), negative when trending too empty (drain slower).
                fillEma = fillEma * 0.999 + available * 0.001
                driftCredit += (fillEma - driftTargetSamples) * DRIFT_GAIN
                var driftAdjust = 0
                if (driftCredit >= 2.0) { driftAdjust = 2; driftCredit -= 2.0 }
                else if (driftCredit <= -2.0) { driftAdjust = -2; driftCredit += 2.0 }

                // Ensure even number of samples for stereo
                val toDrain = if (available >= chunkSamples) chunkSamples
                              else if (available >= 512) available and 0x7FFFFFFE
                              else {
                                  // The ring being dry is NOT by itself an
                                  // underrun: the audio device still holds
                                  // whatever we already handed it, and that is
                                  // the cushion that actually protects
                                  // playback. A field log of one session
                                  // recorded 271 "underruns" of which every
                                  // single one fired with 130-200 ms still
                                  // queued in the line (the buffer is 200 ms).
                                  // Each one shoved 43 ms of fade-to-silence
                                  // into audio that was perfectly fine — about
                                  // 12 seconds of injected silence over four
                                  // minutes, and exactly what the chopping
                                  // sounded like. They clustered around every
                                  // frequency change, where the demodulator
                                  // reset briefly stops producing; while the
                                  // user stayed on one station there were none.
                                  val lineQueued = lineQueuedFrames()
                                  if (lineQueued >= LINE_LOW_WATER_FRAMES) {
                                      Thread.sleep(2)
                                      continue
                                  }
                                  underruns++
                                  // Do NOT re-prefill here. Waiting for a fresh
                                  // 400 ms cushion stalls the drain thread for
                                  // that whole time while the line buffer keeps
                                  // draining — an earlier field log showed the
                                  // result as a steady 425 ms chop cycle.
                                  DesktopLog.log(
                                      "AUDIO underrun #$underruns (ring=$available samples, " +
                                      "line=$lineQueued frames)"
                                  )
                                  // Buffer underflow — ramp to silence
                                  for (i in 0 until chunkSamples) {
                                      val fadeOut = (chunkSamples - i).toFloat() / chunkSamples
                                      val s = (lastOutputSample * fadeOut * 0.5f).toInt().coerceIn(-32767, 32767)
                                      chunkBytes[i * 2] = (s and 0xFF).toByte()
                                      chunkBytes[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
                                      lastOutputSample = s
                                  }
                                  lastOutputSample = 0
                                  try { sourceDataLine?.write(chunkBytes, 0, chunkSamples * 2) } catch (_: Exception) {}
                                  Thread.sleep(5)
                                  continue
                              }

                if (toDrain == 0) {
                    Thread.sleep(5)
                    continue
                }

                // Guard against under/over-consuming past what's actually buffered.
                if (driftAdjust < 0 && toDrain < 4) driftAdjust = 0
                if (driftAdjust > 0 && available < toDrain + driftAdjust) driftAdjust = 0
                val toConsume = toDrain + driftAdjust

                lock.lock()
                try {
                    // Too empty (driftAdjust<0): read fewer real samples, duplicate the
                    // last frame to still fill toDrain output slots (stretches playback).
                    // Too full (driftAdjust>0): read toDrain normally, then silently
                    // discard the extra frame from the ring buffer (shrinks backlog).
                    val readIntoChunk = if (driftAdjust < 0) toDrain + driftAdjust else toDrain
                    for (i in 0 until readIntoChunk) {
                        chunk[i] = ringBuffer[readPos]
                        readPos = (readPos + 1) % RING_BUFFER_SAMPLES
                    }
                    if (driftAdjust < 0) {
                        for (i in readIntoChunk until toDrain) chunk[i] = chunk[readIntoChunk - 2 + (i - readIntoChunk)]
                    } else if (driftAdjust > 0) {
                        readPos = (readPos + driftAdjust) % RING_BUFFER_SAMPLES
                    }
                    bufferedSamples -= toConsume
                } finally { lock.unlock() }

                // Apply volume, fade-in, convert to bytes
                for (i in 0 until toDrain) {
                    var fadeGain = 1.0f
                    if (samplesPlayed < FADE_IN_SAMPLES) {
                        fadeGain = samplesPlayed.toFloat() / FADE_IN_SAMPLES
                    }
                    val s = (chunk[i] * volume * fadeGain).toInt().coerceIn(-32767, 32767)
                    chunkBytes[i * 2] = (s and 0xFF).toByte()
                    chunkBytes[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
                    lastOutputSample = s
                    samplesPlayed++
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

        println("Desktop stereo audio started (${sampleRate}Hz)")
    }

    fun writeSamples(samples: ShortArray) {
        if (!isPlaying) return
        lock.lock()
        try {
            val freeSpace = RING_BUFFER_SAMPLES - bufferedSamples
            if (samples.size > freeSpace) {
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
