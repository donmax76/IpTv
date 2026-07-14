package com.fmradio.util

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import java.util.Locale

object LocaleHelper {

    fun applyLanguage(context: Context, languageCode: String): Context {
        if (languageCode == "system") return context
        val locale = when (languageCode) {
            "ru" -> Locale("ru")
            "en" -> Locale.ENGLISH
            "az" -> Locale("az")
            else -> Locale(languageCode)
        }
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

    val supportedLanguages = listOf(
        "system" to "System",
        "en" to "English",
        "ru" to "Русский",
        "az" to "Azərbaycan"
    )
}
