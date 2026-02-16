package com.tvviewer

import android.content.Context
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CrashReporter {

    private const val TAG = "CrashReporter"

    fun send(context: Context, errorText: String) {
        Thread {
            try {
                val prefs = AppPreferences(context)
                val json = JSONObject().apply {
                    put("error", errorText)
                    put("time", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
                    put("device", Build.MANUFACTURER + " " + Build.MODEL)
                    put("android", Build.VERSION.SDK_INT)
                }.toString()

                prefs.crashReportUrl?.takeIf { it.isNotBlank() }?.let { sendToUrl(it, json) }
                prefs.crashReportFirebaseId?.takeIf { it.isNotBlank() }?.let { sendToFirebase(it, json) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send crash report", e)
            }
        }.start()
    }

    private fun sendToUrl(urlString: String, json: String) {
        try {
            val url = URL(urlString)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            Log.d(TAG, "Crash sent to URL, response: ${conn.responseCode}")
        } catch (e: Exception) {
            Log.e(TAG, "Send to URL failed", e)
        }
    }

    private fun sendToFirebase(projectId: String, json: String) {
        try {
            val url = URL("https://$projectId-default-rtdb.firebaseio.com/crashes.json")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            Log.d(TAG, "Crash sent to Firebase, response: ${conn.responseCode}")
        } catch (e: Exception) {
            Log.e(TAG, "Send to Firebase failed", e)
        }
    }
}
