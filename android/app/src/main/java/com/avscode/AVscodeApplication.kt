package com.avscode

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.avscode.core.AvsLogger

/**
 * Main Application class for AVscode.
 */
class AVscodeApplication : Application() {

    companion object {
        private const val TAG = "AVscodeApplication"
        const val NOTIFICATION_CHANNEL_ID = "linux_runtime_channel"
        
        @Volatile
        private lateinit var instance: AVscodeApplication
        
        fun getInstance(): AVscodeApplication = instance
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        
        AvsLogger.i(TAG, "AVscode application starting")
        
        // Create notification channel for foreground service
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Linux Runtime Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Linux runtime active in background"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
            
            AvsLogger.d(TAG, "Notification channel created")
        }
    }
}
