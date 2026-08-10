package com.dji.recreate2.diag

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Makes a crash leave something behind.
 *
 * The app had no `setDefaultUncaughtExceptionHandler` at all. Two defects found during the
 * 2026-08-09 audit force-closed it in the field and produced nothing to work from: the HUD toggle
 * dereferencing a renamed view id, and the tag buttons passing a NaN into `org.json`.
 *
 * **CAUTION: a crash report records what the aircraft was doing.** It carries the recent flight
 * log, which contains positions and target coordinates. Treat it as operational data. Nothing here
 * transmits it - the operator is asked on the next launch.
 */
object CrashReporter {

    private const val TAG = "CrashReporter"
    private const val REPORT_PREFIX = "crash-"

    /** Lines of flight log attached to a report. */
    private const val LOG_TAIL_CHARS = 8_000

    @Volatile private var dir: File? = null

    /** Set by the app so a report says which airframe produced it. */
    @Volatile var aircraftDescription: String? = null

    /**
     * Installs the handler.
     *
     * **Call from `Application.onCreate`, never from `attachBaseContext`.** The DJI sample requires
     * `Helper.install(this)` to run there first, and reordering that sequence breaks the native
     * layer.
     *
     * The previous handler is kept and invoked afterwards, so Android still reports the crash the
     * way it normally would. Replacing it outright would hide crashes from the platform.
     */
    fun install(context: Context, reportDir: File) {
        if (!reportDir.exists()) reportDir.mkdirs()
        dir = reportDir

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                write(thread, error, context)
            } catch (e: Throwable) {
                Log.e(TAG, "Could not write the crash report", e)
            } finally {
                // Always hand back. Swallowing this would leave the app hung instead of crashing,
                // which is worse than the crash.
                previous?.uncaughtException(thread, error)
            }
        }
        Log.d(TAG, "Crash reporter installed at ${reportDir.absolutePath}")
    }

    private fun write(thread: Thread, error: Throwable, context: Context) {
        val target = dir ?: return
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        val file = File(target, "$REPORT_PREFIX$stamp.txt")

        val version = try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            "${info.versionName} (${info.versionCode})"
        } catch (e: Exception) {
            "unknown"
        }

        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()

        file.writeText(
            buildString {
                appendLine("Recreate2 crash report")
                appendLine("time      : $stamp")
                appendLine("app       : $version")
                appendLine("android   : ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
                appendLine("device    : ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                // Which airframe this came from. Without it a report from an M30 and one from a
                // Mavic 3E are indistinguishable, and half the SDK behaviour differs between them.
                appendLine("aircraft  : ${aircraftDescription ?: "not connected / unknown"}")
                appendLine("thread    : ${thread.name}")
                appendLine()
                appendLine("--- stack trace ---")
                appendLine(trace)
                appendLine("--- recent flight log ---")
                appendLine(FlightLog.tail(LOG_TAIL_CHARS))
            }
        )
        Log.e(TAG, "Crash report written to ${file.absolutePath}")
    }

    /** Reports on disk, newest first. */
    fun reports(): List<File> {
        val d = dir ?: return emptyList()
        return (d.listFiles { f -> f.isFile && f.name.startsWith(REPORT_PREFIX) } ?: emptyArray())
            .sortedByDescending { it.lastModified() }
    }

    fun hasReports(): Boolean = reports().isNotEmpty()

    fun clearReports() {
        reports().forEach { it.delete() }
    }
}
