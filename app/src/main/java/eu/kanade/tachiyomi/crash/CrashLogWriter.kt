package eu.kanade.tachiyomi.crash

import android.content.Context
import eu.kanade.tachiyomi.util.system.createFileInCacheDir
import java.io.File

/**
 * Single-responsibility file writer for crash reports. Extracted from
 * `CrashLogUtil.dumpLogs()` which did `Runtime.exec("logcat ... -f file")`
 * inline (blocking, Android 13+ logcat permission fragile) and had no
 * rotation, size cap, or crash-loop guard.
 *
 * - Writes the structured [CrashReport] as human-readable text.
 * - Keeps the last 5 crash files (`houri_crash_*.log`) with 2MB cap each.
 * - Returns the written file so callers can share or launch CrashActivity with it.
 */
object CrashLogWriter {

    private const val MAX_FILES = 5
    private const val MAX_BYTES_PER_FILE = 2L * 1024 * 1024

    fun write(context: Context, report: CrashReport): File {
        val file = context.createFileInCacheDir("houri_crash_${report.timestampMs}.log")
        file.writeText(render(report))
        if (file.length() > MAX_BYTES_PER_FILE) {
            val truncated = file.readText().take((MAX_BYTES_PER_FILE).toInt())
            file.writeText(truncated + "\n\n— truncated —\n")
        }
        pruneOld(context)
        appendLogcatSnapshot(context, file)
        return file
    }

    private fun render(r: CrashReport): String = buildString {
        appendLine(r.debugInfo)
        appendLine()
        r.extensionsInfo?.let { appendLine(it); appendLine() }
        appendLine("Thread: ${r.threadName} @ ${r.timestampMs}")
        appendLine("Exception: ${r.type}: ${r.message ?: ""}")
        appendLine(r.stackTrace)
        r.cause?.let {
            appendLine("Caused by: ${it.type}: ${it.message ?: ""}")
            appendLine(it.stackTrace)
        }
        if (r.suppressedCount > 0) appendLine("Suppressed: ${r.suppressedCount}")
    }

    private fun pruneOld(context: Context) {
        try {
            val files = context.cacheDir.listFiles { f -> f.name.startsWith("houri_crash_") }
                ?.sortedByDescending { it.lastModified() } ?: return
            files.drop(MAX_FILES).forEach { it.delete() }
        } catch (_: Exception) {
        }
    }

    private fun appendLogcatSnapshot(context: Context, file: File) {
        try {
            val proc = Runtime.getRuntime().exec(arrayOf("logcat", "*:E", "-d", "-t", "800", "-v", "brief"))
            val out = proc.inputStream.bufferedReader().readText().take(200_000)
            proc.waitFor()
            if (out.isNotBlank()) {
                file.appendText("\n\n— logcat (last 800 E) —\n$out")
            }
        } catch (_: Exception) {
        }
    }
}
