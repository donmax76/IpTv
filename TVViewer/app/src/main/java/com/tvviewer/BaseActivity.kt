package com.tvviewer

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

abstract class BaseActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        try {
            val prefs = AppPreferences(newBase)
            val lang = prefs.language
            super.attachBaseContext(if (lang == "system") newBase else wrapContext(newBase, lang))
        } catch (e: Exception) {
            super.attachBaseContext(newBase)
        }
    }

    private fun wrapContext(context: Context, lang: String): Context {
        val locale = Locale(lang)
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocale(locale)
        } else {
            @Suppress("DEPRECATION")
            config.locale = locale
        }
        return context.createConfigurationContext(config)
    }
}
