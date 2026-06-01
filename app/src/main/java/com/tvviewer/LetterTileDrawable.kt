package com.tvviewer

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable

/**
 * Round 221c: цветная плашка с инициалами канала. Используется как
 * fallback когда ни tvg-logo, ни LearnedLogos, ни iptv-org не дали
 * URL логотипа. Лучше пустого placeholder'а — список выглядит
 * опрятным даже для региональных каналов которых нет в iptv-org.
 *
 * Цвет фона — детерминированная функция от имени канала (одинаковый
 * канал в разных плейлистах получит ту же плашку).
 */
class LetterTileDrawable(channelName: String) : Drawable() {

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorForText(channelName)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    private val initials: String = run {
        val parts = channelName.split(' ', '-', '_', '.', '/', '|')
            .filter { it.isNotBlank() }
        when {
            parts.isEmpty() -> "?"
            parts.size == 1 -> parts[0].first().uppercaseChar().toString()
            else -> parts.take(2).joinToString("") {
                it.first().uppercaseChar().toString()
            }
        }
    }

    override fun draw(canvas: Canvas) {
        val r = bounds
        val w = r.width().toFloat()
        val h = r.height().toFloat()
        val radius = minOf(w, h) * 0.18f
        canvas.drawRoundRect(
            r.left.toFloat(), r.top.toFloat(),
            r.right.toFloat(), r.bottom.toFloat(),
            radius, radius, bgPaint
        )
        textPaint.textSize = h * 0.46f
        val cy = r.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(initials, r.centerX().toFloat(), cy, textPaint)
    }

    override fun setAlpha(alpha: Int) {
        bgPaint.alpha = alpha
        textPaint.alpha = alpha
    }

    override fun setColorFilter(filter: ColorFilter?) {
        bgPaint.colorFilter = filter
        textPaint.colorFilter = filter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
    }

    companion object {
        private val PALETTE = intArrayOf(
            0xFF7C6CF7.toInt(), // фирменный фиолетовый
            0xFF00CEC9.toInt(), // бирюзовый
            0xFFFF7675.toInt(), // коралл
            0xFF00B894.toInt(), // зелёный
            0xFFFDC094.toInt(), // персик
            0xFF74B9FF.toInt(), // голубой
            0xFFFD79A8.toInt(), // розовый
            0xFFE17055.toInt(), // оранжевый
            0xFFA29BFE.toInt(), // лавандовый
            0xFF55EFC4.toInt(), // мятный
            0xFF6C5CE7.toInt(), // индиго
            0xFFEC9A9A.toInt(), // лосось
        )

        private fun colorForText(s: String): Int {
            // Простой стабильный hash, не зависящий от поведения JVM.
            var h = 0
            for (c in s) h = h * 31 + c.code
            val idx = ((h % PALETTE.size) + PALETTE.size) % PALETTE.size
            return PALETTE[idx]
        }
    }
}
