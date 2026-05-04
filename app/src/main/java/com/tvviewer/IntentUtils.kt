package com.tvviewer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/** Открывает канал с использованием выбранного в Settings плеера.
 *  - PLAYER_INTERNAL → Intent в PlayerActivity
 *  - PLAYER_MPV      → Intent в MpvPlayerActivity
 *  - PLAYER_EXTERNAL → внешнее приложение через ACTION_VIEW
 *  Используется во всех точках запуска плеера. */
fun Context.launchPreferredPlayer(channel: Channel, channelIndex: Int = 0) {
    val prefs = AppPreferences(this)
    when (prefs.playerType) {
        AppPreferences.PLAYER_EXTERNAL -> launchExternalVideo(channel.url)
        AppPreferences.PLAYER_MPV -> {
            val intent = Intent(this, MpvPlayerActivity::class.java).apply {
                putExtra(MpvPlayerActivity.EXTRA_CHANNEL_NAME, channel.name)
                putExtra(MpvPlayerActivity.EXTRA_CHANNEL_URL, channel.url)
                putExtra(MpvPlayerActivity.EXTRA_CHANNEL_INDEX, channelIndex)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        }
        else -> {
            val intent = Intent(this, PlayerActivity::class.java).apply {
                putExtra(PlayerActivity.EXTRA_CHANNEL_NAME, channel.name)
                putExtra(PlayerActivity.EXTRA_CHANNEL_URL, channel.url)
                putExtra(PlayerActivity.EXTRA_CHANNEL_INDEX, channelIndex)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        }
    }
}

/**
 * Запускает URL во внешнем видеоплеере (VLC / MX). Возвращает true,
 * если внешний плеер найден и стартанул, иначе false (показывает
 * Toast о том, что плеер не установлен).
 */
fun Context.launchExternalVideo(url: String): Boolean {
    val intent = Intent(Intent.ACTION_VIEW)
    intent.setDataAndType(Uri.parse(url), "video/*")
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        startActivity(intent)
        true
    } catch (e: Exception) {
        Toast.makeText(this, R.string.no_player_app, Toast.LENGTH_LONG).show()
        false
    }
}
