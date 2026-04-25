package com.tvviewer

/**
 * Utilities for detecting channel quality from its display name.
 */
object QualityUtil {

    private val regex4k = Regex("""(?i)(\b|_)(4k|uhd|2160p?)(\b|_)""")
    private val regexFhd = Regex("""(?i)(\b|_)(fhd|fullhd|full[\s_-]*hd|1080p?)(\b|_)""")
    private val regexHd = Regex("""(?i)(\b|_)(hd|720p?)(\b|_)""")
    private val regexSd = Regex("""(?i)(\b|_)(sd|480p?|360p?)(\b|_)""")

    /**
     * Returns "4K", "FHD", "HD", "SD" or empty string if undetermined.
     */
    fun detectQuality(name: String): String {
        if (name.isBlank()) return ""
        return when {
            regex4k.containsMatchIn(name) -> "4K"
            regexFhd.containsMatchIn(name) -> "FHD"
            regexHd.containsMatchIn(name) -> "HD"
            regexSd.containsMatchIn(name) -> "SD"
            else -> ""
        }
    }

    /** Numeric rank used for "quality" sort. Higher = better. */
    fun rank(name: String): Int = when (detectQuality(name)) {
        "4K" -> 4
        "FHD" -> 3
        "HD" -> 2
        "SD" -> 1
        else -> 0
    }
}

/** Top-level helper as required by spec. */
fun detectQuality(name: String): String = QualityUtil.detectQuality(name)
