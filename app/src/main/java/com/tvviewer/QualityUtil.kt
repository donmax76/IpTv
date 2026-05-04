package com.tvviewer

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan

/**
 * Utilities for detecting channel quality from its display name.
 */
object QualityUtil {

    private val regex4k = Regex("""(?i)(\b|_)(4k|uhd|2160p?)(\b|_)""")
    private val regexFhd = Regex("""(?i)(\b|_)(fhd|fullhd|full[\s_-]*hd|1080p?|1080i)(\b|_)""")
    private val regexHd = Regex("""(?i)(\b|_)(hd|720p?|h264|ahd)(\b|_)""")
    private val regexSd = Regex("""(?i)(\b|_)(sd|480p?|360p?|240p?|low)(\b|_)""")

    private val ALL = listOf(
        "4K" to regex4k,
        "FHD" to regexFhd,
        "HD" to regexHd,
        "SD" to regexSd,
    )

    /**
     * Returns "4K", "FHD", "HD", "SD" or empty string if undetermined.
     */
    fun detectQuality(name: String): String {
        if (name.isBlank()) return ""
        for ((label, rx) in ALL) if (rx.containsMatchIn(name)) return label
        return ""
    }

    /** Маппит реальную высоту видео (из VideoSize плеера) в качество.
     *  Используется когда канал реально проигрывается и мы знаем
     *  фактическое разрешение — это точнее чем парсинг имени. */
    fun detectByHeight(height: Int): String = when {
        height >= 2000 -> "4K"
        height >= 1000 -> "FHD"
        height >= 700 -> "HD"
        height in 1..699 -> "SD"
        else -> ""
    }

    /** Numeric rank used for "quality" sort. Higher = better. */
    fun rank(name: String): Int = when (detectQuality(name)) {
        "4K" -> 4
        "FHD" -> 3
        "HD" -> 2
        "SD" -> 1
        else -> 0
    }

    /**
     * Colour the quality token inside the channel name (e.g. the "HD" in
     * "TNT HD") so the user can see at a glance what resolution the stream
     * is, while keeping the original name intact.
     */
    fun formatNameWithQualityBadge(ctx: Context, name: String): CharSequence =
        formatNameWithQualityBadge(ctx, name, "")

    /** Та же логика, но с приоритетом для override-бейджа из реальных
     *  метаданных потока. Если override непустой — добавляем его в
     *  начало имени цветным "[HD]". Иначе подсвечиваем токен из
     *  имени как раньше. */
    fun formatNameWithQualityBadge(ctx: Context, name: String, override: String): CharSequence {
        if (name.isBlank()) return name
        if (override.isNotEmpty()) {
            // Если в имени уже есть тот же токен — просто подсвечиваем
            // его (не дублируем бейдж).
            if (regex4k.containsMatchIn(name) || regexFhd.containsMatchIn(name) ||
                regexHd.containsMatchIn(name) || regexSd.containsMatchIn(name)) {
                return formatTokenInName(name, override)
            }
            // Иначе: префикс "[HD] " цветом качества.
            val sb = SpannableStringBuilder("[$override] ").append(name)
            sb.setSpan(ForegroundColorSpan(colorFor(override)), 0, override.length + 2,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(StyleSpan(Typeface.BOLD), 0, override.length + 2,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            return sb
        }
        return formatTokenInName(name, null)
    }

    private fun formatTokenInName(name: String, fallbackLabel: String?): CharSequence {
        val sb = SpannableStringBuilder(name)
        for ((label, rx) in ALL) {
            val m = rx.find(name) ?: continue
            val tok = m.groups[2] ?: m.groups[0] ?: continue
            val color = colorFor(fallbackLabel ?: label)
            sb.setSpan(ForegroundColorSpan(color), tok.range.first, tok.range.last + 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(StyleSpan(Typeface.BOLD), tok.range.first, tok.range.last + 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            break
        }
        return sb
    }

    fun colorFor(label: String): Int = when (label) {
        "4K" -> Color.parseColor("#FF5252")
        "FHD" -> Color.parseColor("#2979FF")
        "HD" -> Color.parseColor("#00C853")
        "SD" -> Color.parseColor("#9E9E9E")
        else -> Color.WHITE
    }
}

/** Top-level helper as required by spec. */
fun detectQuality(name: String): String = QualityUtil.detectQuality(name)
