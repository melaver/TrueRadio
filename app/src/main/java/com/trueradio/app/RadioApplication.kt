package com.trueradio.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class RadioApplication : Application() {

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "radio_dj_playback_channel"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        // No SDK-version check needed: minSdk is already 26 (Build.VERSION_CODES.O), required by
        // the Spotify App Remote SDK, so NotificationChannel (added in O) is always available.
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notif_channel_desc)
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }
}
