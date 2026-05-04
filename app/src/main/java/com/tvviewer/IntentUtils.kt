package com.tvviewer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

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
