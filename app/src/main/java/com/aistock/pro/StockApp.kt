package com.aistock.pro

import android.app.Application
import com.aistock.pro.data.Prefs
import com.aistock.pro.worker.AlertScheduler
import com.aistock.pro.worker.AlertWorker

class StockApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AlertWorker.createChannel(this)
        runCatching { AlertScheduler.apply(this, Prefs(this)) }
    }
}
