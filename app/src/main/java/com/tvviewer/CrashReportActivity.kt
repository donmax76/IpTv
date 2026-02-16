package com.tvviewer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class CrashReportActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val errorText = intent.getStringExtra(EXTRA_ERROR) ?: "No error info"
        setContentView(createLayout(errorText))
    }

    private fun createLayout(errorText: String): android.view.View {
        val scroll = ScrollView(this).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val textView = TextView(this).apply {
            setText(errorText)
            setPadding(32, 32, 32, 32)
            textSize = 12f
            setTextIsSelectable(true)
        }
        scroll.addView(textView)
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }
        layout.addView(scroll, android.widget.LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))
        val copyBtn = Button(this).apply {
            text = "Копировать и закрыть"
            setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("TVViewer Error", errorText))
                Toast.makeText(this@CrashReportActivity, "Скопировано! Вставьте в чат.", Toast.LENGTH_LONG).show()
                finish()
            }
        }
        layout.addView(copyBtn)
        return layout
    }

    companion object {
        const val EXTRA_ERROR = "error_text"
    }
}
