package com.tvviewer

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

abstract class BaseActivity : AppCompatActivity() {

    /** Уникальные keycode'ы за сессию по ВСЕЙ программе — чтобы юзер
     *  мог нажать любую неизвестную кнопку на любом экране и она
     *  попала в лог через ErrorLogger. Раньше логирование было только
     *  в PlayerActivity, но дешёвые TV-боксы часто перехватывают
     *  CH+/CH-/PRE-CH в firmware и они до плеера не доходят — а на
     *  главном экране юзер мог бы хоть проверить шлёт ли пульт хоть
     *  что-то. */
    companion object {
        private val seenKeyCodes = HashSet<Int>()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            val kc = event.keyCode
            if (kc != KeyEvent.KEYCODE_VOLUME_UP &&
                kc != KeyEvent.KEYCODE_VOLUME_DOWN &&
                kc != KeyEvent.KEYCODE_VOLUME_MUTE &&
                kc != KeyEvent.KEYCODE_POWER) {
                if (seenKeyCodes.add(kc)) {
                    try {
                        ErrorLogger.info(this, "KEY",
                            "code=$kc name=${KeyEvent.keyCodeToString(kc)} act=${this.javaClass.simpleName}")
                    } catch (_: Throwable) {}
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

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
