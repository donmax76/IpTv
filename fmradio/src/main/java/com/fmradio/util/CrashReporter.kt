package com.fmradio.util

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Crash/error reporter — sends to Firebase Realtime DB (no auth needed).
 * Same approach as TVViewer project.
 */
object CrashReporter {

    private const val TAG = "CrashReporter"

    // Firebase Realtime Database project ID (public write, no token needed)
    // Same Firebase project as TVViewer — crashes appear in /fmradio_crashes node
    private const val FIREBASE_PROJECT_ID = "iptv-crash-reports"

    fun send(context: Context, errorText: String) {
        Thread {
            try {
                val appVersion = try {
                    val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                    "${pInfo.versionName} (${pInfo.versionCode})"
                } catch (_: PackageManager.NameNotFoundException) { "unknown" }

                val json = JSONObject().apply {
                    put("app", "FmRadio")
                    put("error", errorText)
                    put("time", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
                    put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                    put("android", Build.VERSION.SDK_INT)
                    put("appVersion", appVersion)
                }.toString()

                sendToFirebase(json)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send crash report", e)
            }
        }.start()
    }

    private fun sendToFirebase(json: String) {
        try {
            val url = URL("https://$FIREBASE_PROJECT_ID-default-rtdb.firebaseio.com/fmradio_crashes.json")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            Log.d(TAG, "Crash sent to Firebase, response: $code")
        } catch (e: Exception) {
            Log.e(TAG, "Send to Firebase failed", e)
        }
    }
}
