package com.avscode.runtime

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.avscode.AVscodeApplication
import com.avscode.MainActivity
import com.avscode.R
import com.avscode.core.AvsLogger
import com.avscode.core.RuntimeState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow

/**
 * Foreground service to keep the Linux runtime alive.
 * This ensures the PRoot environment persists even when the app is in background.
 */
class LinuxRuntimeService : Service() {

    companion object {
        private const val TAG = "LinuxRuntimeService"
        private const val NOTIFICATION_ID = 1001
        
        const val ACTION_START = "com.avscode.runtime.START"
        const val ACTION_STOP = "com.avscode.runtime.STOP"
        const val ACTION_RESTART = "com.avscode.runtime.RESTART"
    }

    private val binder = LocalBinder()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    var runtime: PRootRuntime? = null
        private set

    inner class LocalBinder : Binder() {
        fun getService(): LinuxRuntimeService = this@LinuxRuntimeService
    }

    override fun onCreate() {
        super.onCreate()
        AvsLogger.i(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.action?.let { action ->
            when (action) {
                ACTION_START -> startRuntime()
                ACTION_STOP -> stopRuntime()
                ACTION_RESTART -> restartRuntime()
            }
        } ?: startRuntime()

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onDestroy() {
        AvsLogger.i(TAG, "Service destroyed")
        scope.cancel()
        runtime?.destroy()
        super.onDestroy()
    }

    /**
     * Start the Linux runtime and show foreground notification.
     */
    private fun startRuntime() {
        AvsLogger.i(TAG, "Starting runtime service")
        
        // Show foreground notification
        startForeground(NOTIFICATION_ID, createNotification("Linux runtime starting..."))
        
        // Initialize runtime if not already done
        if (runtime == null) {
            runtime = PRootRuntime(applicationContext, RootfsInstaller(applicationContext))
        }
        
        scope.launch {
            try {
                runtime?.start()
                updateNotification("Linux runtime running")
            } catch (e: Exception) {
                AvsLogger.e(TAG, "Failed to start runtime", e)
                updateNotification("Failed to start runtime")
            }
        }
    }

    /**
     * Stop the Linux runtime.
     */
    private fun stopRuntime() {
        AvsLogger.i(TAG, "Stopping runtime service")
        
        scope.launch {
            try {
                runtime?.stop()
            } catch (e: Exception) {
                AvsLogger.e(TAG, "Error stopping runtime", e)
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    /**
     * Restart the Linux runtime.
     */
    private fun restartRuntime() {
        AvsLogger.i(TAG, "Restarting runtime service")
        
        scope.launch {
            try {
                runtime?.restart()
                updateNotification("Linux runtime restarted")
            } catch (e: Exception) {
                AvsLogger.e(TAG, "Failed to restart runtime", e)
                updateNotification("Failed to restart runtime")
            }
        }
    }

    /**
     * Get the current runtime state.
     */
    fun getState(): StateFlow<RuntimeState>? {
        return runtime?.state
    }

    /**
     * Create the foreground notification.
     */
    private fun createNotification(status: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, AVscodeApplication.NOTIFICATION_CHANNEL_ID)
            .setContentTitle("AVscode")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        }

        return builder.build()
    }

    /**
     * Update the foreground notification text.
     */
    private fun updateNotification(status: String) {
        val notification = createNotification(status)
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
}
