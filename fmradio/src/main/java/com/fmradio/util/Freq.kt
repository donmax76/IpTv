package com.fmradio.util

import java.util.Locale

/**
 * Frequency formatting, in one place and independent of the device language.
 *
 * Every frequency in the app was formatted with String.format and no locale,
 * so it used whatever the head unit's language happened to be. Set to
 * Azerbaijani or Russian, that renders 106.0 as "106,0" — a decimal comma on a
 * radio dial, in the station list, in the presets and in the "add station"
 * dialog. It also changes the width of the string, which is what the display
 * has to be laid out for.
 *
 * A frequency is a number on an instrument, not prose: it is written the same
 * way whatever language the interface is in.
 */
object Freq {

    /** "106.0" — the dial reading. */
    fun mhz(frequencyHz: Long): String =
        if (frequencyHz >= 1_000_000_000L) String.format(Locale.US, "%.3f", frequencyHz / 1_000_000.0)
        else String.format(Locale.US, "%.1f", frequencyHz / 1_000_000.0)

    /** "106.0" from a value already in MHz. */
    fun mhz(frequencyMHz: Double): String = String.format(Locale.US, "%.1f", frequencyMHz)

    /** "106" — band edges and other whole-MHz labels. */
    fun mhzWhole(frequencyHz: Long): String =
        String.format(Locale.US, "%.0f", frequencyHz / 1_000_000.0)

    /**
     * Accepts both separators, because the number keypad on a head unit set to
     * a comma locale produces a comma whatever the field was prefilled with.
     */
    fun parseMhz(text: String): Double? =
        text.trim().replace(',', '.').toDoubleOrNull()
}
