package com.fmradio

import android.app.Application
import com.fmradio.dsp.DebugLog

class FmRadioApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DebugLog.init(this)
    }

    override fun onTerminate() {
        DebugLog.shutdown()
        super.onTerminate()
    }
}
