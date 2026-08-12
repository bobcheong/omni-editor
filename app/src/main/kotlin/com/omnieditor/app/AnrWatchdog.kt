package com.omnieditor.app

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.omnieditor.app.BuildConfig
import java.io.File
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * R-41: ANR watchdog — detects main-thread stalls longer than [TIMEOUT_MS].
 *
 * Posts a no-op runnable to the main looper once per [TIMEOUT_MS] interval.
 * If the runnable is not acknowledged within the timeout, the main thread is
 * assumed to be blocked and an ANR log is written to app-private storage.
 *
 * Privacy: logs contain only device info, build version, and a thread dump.
 * No file paths, file contents or user data are included.
 */
class AnrWatchdog(private val context: Context) : Thread("OmniAnrWatchdog") {

    init {
        isDaemon = true
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val logDir = File(context.filesDir, "crash-logs")

    override fun run() {
        while (!isInterrupted) {
            val responded = CountDownLatch(1)
            mainHandler.post { responded.countDown() }
            val timedOut = !responded.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (timedOut) {
                try {
                    logAnr()
                } catch (_: Exception) {
                    // Do not crash the watchdog thread.
                }
            }
        }
    }

    private fun logAnr() {
        logDir.mkdirs()
        val logFile = File(logDir, "anr-${System.currentTimeMillis()}.txt")
        logFile.writeText(buildAnrReport())
        evictOldAnrLogs()
    }

    private fun buildAnrReport(): String = buildString {
        appendLine("Omni Editor ANR Report")
        appendLine("Time: ${Instant.now()}")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("Timeout: ${TIMEOUT_MS}ms")
        appendLine()
        appendLine("Thread dump:")
        for ((thread, stack) in Thread.getAllStackTraces()) {
            appendLine("  Thread '${thread.name}' (${thread.state}):")
            for (frame in stack) {
                appendLine("    at $frame")
            }
        }
        // NO file paths, file contents or user data — privacy constraint (R-41).
    }

    private fun evictOldAnrLogs() {
        val logs = logDir.listFiles { f -> f.name.startsWith("anr-") && f.name.endsWith(".txt") }
            ?.sortedBy { it.lastModified() }
            ?: return
        if (logs.size > MAX_ANR_LOGS) {
            logs.take(logs.size - MAX_ANR_LOGS).forEach { it.delete() }
        }
    }

    companion object {
        private const val TIMEOUT_MS = 5_000L
        private const val MAX_ANR_LOGS = 5

        /**
         * Return ANR log files sorted newest-first, for the diagnostics share flow.
         */
        fun recentLogs(context: Context): List<File> {
            val dir = File(context.filesDir, "crash-logs")
            return dir.listFiles { f -> f.name.startsWith("anr-") && f.name.endsWith(".txt") }
                ?.sortedByDescending { it.lastModified() }
                ?: emptyList()
        }
    }
}
