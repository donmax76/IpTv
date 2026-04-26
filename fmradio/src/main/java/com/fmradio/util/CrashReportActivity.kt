package com.fmradio.util

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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

        val copyBtn = Button(this).apply {
            text = "Copy & Close"
            setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("FmRadio Error", errorText))
                Toast.makeText(this@CrashReportActivity, "Copied to clipboard", Toast.LENGTH_LONG).show()
                finish()
            }
        }
        btnRow.addView(copyBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val shareBtn = Button(this).apply {
            text = "Share"
            setOnClickListener {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "FM Radio Crash Report")
                    putExtra(Intent.EXTRA_TEXT, errorText)
                }
                startActivity(Intent.createChooser(shareIntent, "Share crash report"))
            }
        }
        btnRow.addView(shareBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        layout.addView(btnRow)
        return layout
    }

    companion object {
        const val EXTRA_ERROR = "error_text"
    }
}
