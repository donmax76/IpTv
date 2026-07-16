package com.tvviewer

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration

/**
 * Utility for detecting TV mode and adapting UI behavior.
 */
object TvUtils {

    /**
     * Returns true if the app is running on an Android TV device.
     */
    fun isTv(context: Context): Boolean {
        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        return uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    }

    /**
     * Returns true if the device has a large screen (tablet/TV).
     * Based on smallest width >= 600dp.
     */
    fun isLargeScreen(context: Context): Boolean {
        val sw = context.resources.configuration.smallestScreenWidthDp
        return sw >= 600
    }

    /**
     * Returns true if the device likely uses a D-pad/remote for navigation.
     */
    fun usesRemoteNavigation(context: Context): Boolean {
        return isTv(context) || !context.packageManager.hasSystemFeature("android.hardware.touchscreen")
    }
}
