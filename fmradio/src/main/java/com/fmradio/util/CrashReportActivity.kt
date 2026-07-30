package com.fmradio.util

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class CrashReportActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val errorText = intent.getStringExtra(EXTRA_ERROR) ?: "No error info"
        setContentView(createLayout(errorText))
    }

    private fun createLayout(errorText: String): android.view.View {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
            setBackgroundColor(0xFF1A1A1A.toInt())
        }

        val title = TextView(this).apply {
            text = "FM Radio - Crash Report"
            textSize = 18f
            setTextColor(0xFFFF6666.toInt())
            setPadding(16, 16, 16, 8)
        }
        layout.addView(title)

        val scroll = ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val textView = TextView(this).apply {
            text = errorText
            setPadding(32, 32, 32, 32)
            textSize = 12f
            setTextColor(0xFFCCCCCC.toInt())
            setTextIsSelectable(true)
        }
        scroll.addView(textView)
        layout.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8, 0, 0)
        }

        // The buttons the user actually reaches for. What they did instead was
        // photograph the screen, which is a fair verdict on "Copy & Close",
        // "Share" and "Report on GitHub" — the first copied only the stack
        // trace with none of the state around it, and the last built a GitHub
        // issue URL with the whole report percent-encoded into the query
        // string, which browsers cut off at a few kilobytes.
        val copyBtn = Button(this).apply {
            text = "Копировать"
            setOnClickListener {
                val full = fullReport(errorText)
                val ok = LogReport.copyToClipboard(this@CrashReportActivity, full)
                Toast.makeText(this@CrashReportActivity,
                    if (ok) "Скопировано (${full.length / 1024} КБ) — вставьте в сообщение"
                    else "Буфер обмена недоступен",
                    Toast.LENGTH_LONG).show()
            }
        }
        btnRow.addView(copyBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val shareBtn = Button(this).apply {
            text = "Отправить"
            setOnClickListener {
                val full = fullReport(errorText)
                val file = LogReport.save(this@CrashReportActivity, full)
                try {
                    if (file != null) {
                        startActivity(LogReport.shareIntent(this@CrashReportActivity, file, full))
                    } else {
                        LogReport.copyToClipboard(this@CrashReportActivity, full)
                        Toast.makeText(this@CrashReportActivity,
                            "Файл не сохранён — текст скопирован", Toast.LENGTH_LONG).show()
                    }
                } catch (_: Throwable) {
                    LogReport.copyToClipboard(this@CrashReportActivity, full)
                    Toast.makeText(this@CrashReportActivity,
                        "Нет приложения для отправки — текст скопирован", Toast.LENGTH_LONG).show()
                }
            }
        }
        btnRow.addView(shareBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val closeBtn = Button(this).apply {
            text = "Закрыть"
            setOnClickListener { finish() }
        }
        btnRow.addView(closeBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        layout.addView(btnRow)
        return layout
    }

    /**
     * The crash plus everything around it. A stack trace on its own says what
     * broke but never the state it broke in — the frequency, the gain, whether
     * RDS was locked — and that is usually the half that identifies the cause.
     */
    private fun fullReport(errorText: String): String =
        "=== CRASH ===\n" + errorText + "\n\n" + LogReport.build(this)

    companion object {
        const val EXTRA_ERROR = "error_text"
    }
}
