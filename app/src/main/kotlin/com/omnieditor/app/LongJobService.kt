package com.omnieditor.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * F-04: Generic foreground service host for long-running operations (>10 s).
 *
 * Currently scaffolded. The compare flow will wrap long compares here once
 * F-01/F-02 make large-file compares possible.
 *
 * Usage pattern:
 *   1. Caller starts the service with an intent carrying a job ID
 *   2. Service creates a foreground notification with progress
 *   3. Caller communicates via a singleton job registry (or bound service)
 *   4. On completion/cancel, the service stops itself
 *
 * Tier 2 verification required — cannot test without Android runtime.
 */
class LongJobService : Service() {

    companion object {
        const val CHANNEL_ID = "omni_long_job"
        const val NOTIFICATION_ID = 1001
    }

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Background operations",
            NotificationManager.IMPORTANCE_LOW,
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification(0f, "Starting…")
        startForeground(NOTIFICATION_ID, notification)
        // Job execution will be wired when F-01/F-02 make long compares possible.
        // For now, the service starts and immediately stops.
        stopSelf()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(progress: Float, message: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Omni Editor")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setProgress(100, (progress * 100).toInt(), progress == 0f)
            .setOngoing(true)
            .build()
    }
}
