package com.omnieditor.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat

/**
 * Progress reporter singleton shared between [LongJobService] and callers (e.g. the compare
 * flow in [CompareDestination]).
 *
 * Thread-safety: fields are written on the Default dispatcher coroutine thread and read
 * on the main thread inside the polling handler. All fields are primitive or
 * @Volatile-annotated; the progress callback is set before the service starts.
 */
object JobProgressReporter {
    @Volatile var progress: Float = 0f
    @Volatile var message: String = ""
    @Volatile var onCancel: (() -> Unit)? = null
    @Volatile var isActive: Boolean = false
}

/**
 * F-04: Foreground service host for long-running compare operations (>10 s / >500k lines).
 *
 * Usage pattern:
 *   1. Caller populates [JobProgressReporter] (isActive = true, onCancel = { job.cancel() })
 *   2. Caller starts the service with startService(Intent(context, LongJobService::class.java))
 *   3. Service polls [JobProgressReporter] every 500 ms and updates the notification
 *   4. Caller sets [JobProgressReporter.isActive] = false then calls stopService when done
 *
 * Tier 2 verification required — cannot test without Android runtime.
 */
class LongJobService : Service() {

    companion object {
        const val CHANNEL_ID = "omni_long_job"
        const val NOTIFICATION_ID = 1001
        private const val POLL_INTERVAL_MS = 500L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!JobProgressReporter.isActive) {
                stopSelf()
                return
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(
                NOTIFICATION_ID,
                buildNotification(JobProgressReporter.progress, JobProgressReporter.message.ifBlank { "Comparing…" }),
            )
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
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
        val notification = buildNotification(0f, "Comparing…")
        startForeground(NOTIFICATION_ID, notification)
        handler.postDelayed(pollRunnable, POLL_INTERVAL_MS)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(pollRunnable)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(progress: Float, message: String): Notification {
        val indeterminate = progress == 0f
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Omni Editor")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setProgress(100, (progress * 100).toInt(), indeterminate)
            .setOngoing(true)
            .build()
    }
}
