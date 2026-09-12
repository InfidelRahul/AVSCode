package com.avscode.runtime

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.avscode.AVscodeApplication
import com.avscode.MainActivity
import com.avscode.R
import com.avscode.RuntimeController
import com.avscode.core.AppState
import com.avscode.core.AvsLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

/**
 * Foreground service to keep the Linux runtime and VS Code Server alive in the background.
 * Delegates to the authoritative RuntimeController singleton.
 */
class LinuxRuntimeService : Service() {

    companion object {
        private const val TAG = "LinuxRuntimeService"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.avscode.runtime.START"
        const val ACTION_STOP = "com.avscode.runtime.STOP"

        fun start(context: Context) {
            val intent = Intent(context, LinuxRuntimeService::class.java).apply {
                action = ACTION_START
            }
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                AvsLogger.w(TAG, "Failed to start foreground service directly: ${e.message}")
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, LinuxRuntimeService::class.java).apply {
                action = ACTION_STOP
            }
            context.stopService(intent)
        }
    }

    private val binder = LocalBinder()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    inner class LocalBinder : Binder() {
        fun getService(): LinuxRuntimeService = this@LinuxRuntimeService
    }

    override fun onCreate() {
        super.onCreate()
        AvsLogger.i(TAG, "LinuxRuntimeService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        when (action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                startForegroundWithNotification()
                observeRuntimeState()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun startForegroundWithNotification() {
        val notification = createNotification("AVSCode Linux userspace active")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            }
            startForeground(NOTIFICATION_ID, notification, fgsType)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun observeRuntimeState() {
        val controller = RuntimeController.getInstance(applicationContext)
        scope.launch {
            controller.appState.collectLatest { state ->
                val text = when (state) {
                    is AppState.NotInstalled -> "Rootfs not installed"
                    is AppState.InstallingRootfs -> "Installing rootfs: ${(state.progress * 100).toInt()}%"
                    is AppState.StartingLinux -> "Starting Linux userspace..."
                    is AppState.Bootstrapping -> state.status
                    is AppState.StartingVsCode -> "Starting VS Code Server..."
                    is AppState.Ready -> "VS Code Server active at ${state.url}"
                    is AppState.Stopping -> "Stopping Linux userspace..."
                    is AppState.Failed -> "Linux runtime error: ${state.message}"
                }
                updateNotification(text)
            }
        }
    }

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

        return NotificationCompat.Builder(this, AVscodeApplication.NOTIFICATION_CHANNEL_ID)
            .setContentTitle("AVSCode — VS Code for Android")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    @android.annotation.SuppressLint("NotificationPermission")
    private fun updateNotification(status: String) {
        val notification = createNotification(status)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        AvsLogger.i(TAG, "LinuxRuntimeService destroyed")
        scope.cancel()
        super.onDestroy()
    }
}
