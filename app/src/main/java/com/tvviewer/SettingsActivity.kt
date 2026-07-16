package com.tvviewer

import android.os.Bundle
import android.view.View
import android.view.ViewGroup

class SettingsActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // Round 180: Settings больше не на весь экран — боковая панель
        // справа на 50% ширины. MainActivity видна сквозь полупрозрачный
        // backdrop. Ширина задаётся программно потому что FrameLayout
        // не поддерживает процентные размеры.
        val container = findViewById<ViewGroup>(R.id.settingsContainer)
        val halfW = (resources.displayMetrics.widthPixels * 0.5f).toInt()
            .coerceAtLeast(320)
        container.layoutParams = container.layoutParams.apply { width = halfW }

        // Тап по затемнению закрывает Settings (стандартный паттерн
        // для боковых панелей в Android).
        findViewById<View>(R.id.settingsDimBg).setOnClickListener { finish() }

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settingsContainer, SettingsFragment(), SettingsFragment.TAG)
                .commit()
        }
    }
}
