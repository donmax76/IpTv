package com.tvviewer

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

abstract class BaseActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyColorTheme()
        super.onCreate(savedInstanceState)
    }

    private fun applyColorTheme() {
        val prefs = AppPreferences(this)
        val themeRes = when (prefs.colorTheme) {
            "blue" -> R.style.Theme_TVViewer_Blue
            "green" -> R.style.Theme_TVViewer_Green
            "orange" -> R.style.Theme_TVViewer_Orange
            "red" -> R.style.Theme_TVViewer_Red
            else -> return // default purple, already set in manifest
        }
        setTheme(themeRes)
    }

    private fun wrapContext(context: Context): Context {
        return try {
            val lang = AppPreferences(context).language
            if (lang == "system") return context
            val locale = Locale(lang)
            Locale.setDefault(locale)
            val config = Configuration(context.resources.configuration)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                config.setLocale(locale)
            } else {
                @Suppress("DEPRECATION")
                config.locale = locale
            }
            context.createConfigurationContext(config)
        } catch (e: Exception) {
            context
        }
    }
}
