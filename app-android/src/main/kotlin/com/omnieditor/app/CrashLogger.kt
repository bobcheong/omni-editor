package com.omnieditor.app

import android.content.Context
import android.os.Build
import com.omnieditor.app.BuildConfig
import java.io.File
import java.time.Instant

/**
 * R-41: Crash logger — captures uncaught exceptions to app-private storage.
 *
 * Writes a structured crash report to `<filesDir>/crash-logs/` and then
 * delegates to the original handler so the system can display its own dialog.
 *
 * Privacy constraints:
 * - No file paths or file contents in any report.
 * - No user data is collected.
 * - Reports are never sent automatically; they are only shared on user request.
 *
 * Ring buffer: at most [MAX_LOGS] files are kept; oldest are evicted first.
 */
class CrashLogger private constructor(
    private val context: Context,
    private val defaultHandler: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {

    private val logDir = File(context.filesDir, "crash-logs")
    private val maxLogs = MAX_LOGS

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            logDir.mkdirs()
            val logFile = File(logDir, "crash-${System.currentTimeMillis()}.txt")
            logFile.writeText(buildReport(thread, throwable))
            evictOldLogs()
        } catch (_: Exception) {
            // Never let the crash handler itself crash — the original handler
            // must still be called so the system can terminate the process.
        }
        defaultHandler?.uncaughtException(thread, throwable)
    }

    private fun buildReport(thread: Thread, throwable: Throwable): String = buildString {
        appendLine("Omni Editor Crash Report")
        appendLine("Time: ${Instant.now()}")
        appendLine("Thread: ${thread.name}")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("App: version=${BuildConfig.VERSION_NAME} sha=${BuildConfig.GIT_SHA}")
        appendLine()
        appendLine("Stack trace:")
        appendLine(throwable.stackTraceToString())
        // NO file paths, file contents or user data — privacy constraint (R-41).
    }

    private fun evictOldLogs() {
        val logs = logDir.listFiles { f -> f.name.startsWith("crash-") && f.name.endsWith(".txt") }
            ?.sortedBy { it.lastModified() }
            ?: return
        if (logs.size > maxLogs) {
            logs.take(logs.size - maxLogs).forEach { it.delete() }
        }
    }

    companion object {
        private const val MAX_LOGS = 10

        /**
         * Install this handler as the process-wide uncaught exception handler.
         * Safe to call multiple times — subsequent calls are no-ops if already installed.
         */
        fun install(context: Context) {
            val current = Thread.getDefaultUncaughtExceptionHandler()
            // Guard: don't double-wrap if already installed.
            if (current is CrashLogger) return
            Thread.setDefaultUncaughtExceptionHandler(
                CrashLogger(context.applicationContext, current)
            )
        }

        /**
         * Return crash log files sorted newest-first, for the diagnostics share flow.
         */
        fun recentLogs(context: Context): List<File> {
            val dir = File(context.filesDir, "crash-logs")
            return dir.listFiles { f -> f.name.startsWith("crash-") && f.name.endsWith(".txt") }
                ?.sortedByDescending { it.lastModified() }
                ?: emptyList()
        }
    }
}
