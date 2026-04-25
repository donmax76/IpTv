package com.tvviewer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.net.URLEncoder

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
        val reportBtn = Button(this).apply {
            text = "Сообщить на GitHub"
            setOnClickListener {
                val title = "[Android crash] " + errorText.lineSequence()
                    .firstOrNull { it.isNotBlank() }?.take(80).orEmpty()
                val body = buildString {
                    append("Автоматический отчёт об ошибке.\n\n")
                    append("**App**: ").append(BuildConfig.VERSION_NAME)
                        .append(" (build ").append(BuildConfig.VERSION_CODE).append(")\n")
                    append("**Android**: ").append(Build.VERSION.RELEASE)
                        .append(" (sdk ").append(Build.VERSION.SDK_INT).append(")\n")
                    append("**Device**: ").append(Build.MANUFACTURER).append(" ")
                        .append(Build.MODEL).append("\n\n")
                    append("**Stacktrace**:\n```\n")
                    append(errorText.takeLast(4000))
                    append("\n```\n")
                }
                val url = "https://github.com/donmax76/iptv/issues/new" +
                    "?title=" + URLEncoder.encode(title, "UTF-8") +
                    "&body=" + URLEncoder.encode(body, "UTF-8")
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (_: Exception) {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("GitHub issue URL", url))
                    Toast.makeText(this@CrashReportActivity,
                        "Не удалось открыть браузер. URL скопирован в буфер.",
                        Toast.LENGTH_LONG).show()
                }
            }
        }
        layout.addView(reportBtn)

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
