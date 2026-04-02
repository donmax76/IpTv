package com.fmradio.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

/**
 * Audio spectrum visualizer — displays frequency bars like an equalizer.
 * Receives audio samples and computes a simple FFT-like visualization.
 */
class SpectrumView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    companion object {
        private const val BAR_COUNT = 32
        private const val SMOOTHING = 0.7f  // 0=instant, 1=frozen
        private const val MIN_DB = -60f
        private const val MAX_DB = 0f
    }

    private val barHeights = FloatArray(BAR_COUNT)
    private val smoothedHeights = FloatArray(BAR_COUNT)
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        maskFilter = BlurMaskFilter(8f, BlurMaskFilter.Blur.OUTER)
    }

    // Gradient colors: green → cyan → magenta
    private val barColors = IntArray(BAR_COUNT).also { colors ->
        for (i in 0 until BAR_COUNT) {
            val ratio = i.toFloat() / (BAR_COUNT - 1)
            colors[i] = when {
                ratio < 0.5f -> interpolateColor(0xFF00CC00.toInt(), 0xFF00CCCC.toInt(), ratio * 2)
                else -> interpolateColor(0xFF00CCCC.toInt(), 0xFFCC00CC.toInt(), (ratio - 0.5f) * 2)
            }
        }
    }

    private fun interpolateColor(c1: Int, c2: Int, t: Float): Int {
        val a = ((Color.alpha(c1) * (1 - t) + Color.alpha(c2) * t)).toInt()
        val r = ((Color.red(c1) * (1 - t) + Color.red(c2) * t)).toInt()
        val g = ((Color.green(c1) * (1 - t) + Color.green(c2) * t)).toInt()
        val b = ((Color.blue(c1) * (1 - t) + Color.blue(c2) * t)).toInt()
        return Color.argb(a, r, g, b)
    }

    /**
     * Update visualizer with audio samples (interleaved stereo L,R,L,R...).
     * Call from audio thread — lightweight computation.
     */
    fun updateAudio(samples: ShortArray, count: Int) {
        if (count < BAR_COUNT * 2) return

        // Simple band-energy analysis (not full FFT — fast enough for visualization)
        val samplesPerBar = count / (BAR_COUNT * 2)  // /2 for stereo
        for (i in 0 until BAR_COUNT) {
            var energy = 0.0
            val start = i * samplesPerBar * 2
            for (j in 0 until samplesPerBar * 2) {
                val idx = start + j
                if (idx < count) {
                    val s = samples[idx].toFloat() / 32767f
                    energy += s * s
                }
            }
            energy /= (samplesPerBar * 2).coerceAtLeast(1)
            val db = (10 * Math.log10(energy + 1e-10)).toFloat()
            val normalized = ((db - MIN_DB) / (MAX_DB - MIN_DB)).coerceIn(0f, 1f)
            barHeights[i] = normalized
        }

        // Smooth on UI thread
        postInvalidate()
    }

    /** Clear bars (e.g. when playback stops) */
    fun clear() {
        for (i in barHeights.indices) barHeights[i] = 0f
        for (i in smoothedHeights.indices) smoothedHeights[i] = 0f
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val gap = 2f
        val barWidth = (w - gap * (BAR_COUNT - 1)) / BAR_COUNT

        for (i in 0 until BAR_COUNT) {
            // Smooth animation
            smoothedHeights[i] = smoothedHeights[i] * SMOOTHING + barHeights[i] * (1 - SMOOTHING)
            val barH = smoothedHeights[i] * h * 0.9f  // 90% max height

            if (barH < 2f) continue

            val left = i * (barWidth + gap)
            val top = h - barH
            val right = left + barWidth
            val bottom = h

            // Draw glow
            glowPaint.color = barColors[i] and 0x44FFFFFF
            canvas.drawRoundRect(left, top, right, bottom, 2f, 2f, glowPaint)

            // Draw bar
            barPaint.color = barColors[i]
            canvas.drawRoundRect(left, top, right, bottom, 2f, 2f, barPaint)
        }
    }
}
