package com.runway.assistant

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class RunwayApp : Application() {

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    "recorder",
                    getString(R.string.channel_recorder),
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
            manager.createNotificationChannel(
                NotificationChannel(
                    "replay",
                    getString(R.string.channel_replay),
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        }
    }
}
