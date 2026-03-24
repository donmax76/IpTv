package com.fmradio.dsp

import java.text.SimpleDateFormat
import java.util.*

/**
 * In-app debug logger for FM Radio audio pipeline.
 * Collects timestamped messages from all pipeline stages
 * (USB → Demod → Audio) and displays them in the UI.
 */
object DebugLog {

    private const val MAX_LINES = 200
    private val lines = ArrayDeque<String>(MAX_LINES + 10)
    private val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val lock = Any()

    @Volatile
    var enabled = false

    var onNewLine: ((String) -> Unit)? = null

    fun log(tag: String, message: String) {
        if (!enabled) return
        val ts = sdf.format(Date())
        val line = "$ts [$tag] $message"
        synchronized(lock) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
        onNewLine?.invoke(line)
    }

    fun getText(): String {
        synchronized(lock) {
            return lines.joinToString("\n")
        }
    }

    fun clear() {
        synchronized(lock) {
            lines.clear()
        }
    }
}
