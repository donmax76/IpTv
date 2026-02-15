package com.tvviewer

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
            "uk" -> Locale("uk")
            "de" -> Locale.GERMAN
            "fr" -> Locale.FRENCH
            "es" -> Locale("es")
            "pl" -> Locale("pl")
            "tr" -> Locale("tr")
            "it" -> Locale.ITALIAN
            "zh" -> Locale.CHINESE
            "ar" -> Locale("ar")
            "pt" -> Locale("pt")
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
        "ru" to "Русский",
        "en" to "English",
        "uk" to "Українська",
        "de" to "Deutsch",
        "fr" to "Français",
        "es" to "Español",
        "pl" to "Polski",
        "tr" to "Türkçe",
        "it" to "Italiano",
        "zh" to "中文",
        "ar" to "العربية",
        "pt" to "Português",
        "az" to "Azərbaycan",
    )
}
