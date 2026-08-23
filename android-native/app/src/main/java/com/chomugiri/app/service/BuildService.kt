package com.chomugiri.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.chomugiri.app.MainActivity
import com.chomugiri.app.R

/**
 * Keeps a running build alive while the app is in the background.
 *
 * Without this, Android does exactly what it is designed to do to a backgrounded app: throttle
 * its network, then eventually kill the process outright under memory pressure. A pipeline run is
 * minutes of streaming HTTP, so leaving the app to check something else was silently killing the
 * job — you came back to a chat that had generated nothing.
 *
 * A foreground service with an ongoing notification is the sanctioned way to say "this work is
 * user-visible, keep it running". It is started only while a job is actually in flight and
 * stopped the moment the last one finishes, so there is no permanent notification sitting in the
 * shade doing nothing.
 */
class BuildService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra(EXTRA_STATUS) ?: "Working..."
        startForegroundCompat(buildNotification(text))
        // START_NOT_STICKY: if the process dies anyway, the coroutine died with it, so relaunching
        // an empty service would only show a notification for work that is no longer happening.
        return START_NOT_STICKY
    }

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Declaring the type is mandatory from API 34; dataSync is the category this fits —
            // network work the user explicitly started and is waiting on.
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun buildNotification(text: String): Notification {
        ensureChannel(this)
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("ChomuGiri is building")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "chomugiri_builds"
        private const val NOTIF_ID = 4201
        private const val EXTRA_STATUS = "status"

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val mgr = context.getSystemService(NotificationManager::class.java) ?: return
            if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Builds in progress", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while ChomuGiri is generating or building in the background."
                    setShowBadge(false)
                }
            )
        }

        /** Starts the service, or updates its notification text if it is already running. */
        fun start(context: Context, status: String) {
            val i = Intent(context, BuildService::class.java).putExtra(EXTRA_STATUS, status)
            runCatching { context.startForegroundService(i) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, BuildService::class.java)) }
        }
    }
}
