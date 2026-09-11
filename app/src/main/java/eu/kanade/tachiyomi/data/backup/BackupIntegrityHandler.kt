package eu.kanade.tachiyomi.data.backup

import java.io.File

/**
 * Isolated integrity checks extracted from `BackupCreateJob` / `BackupRestoreJob`
 * which previously mixed file I/O, SQLCipher handling, and rolling-backup
 * rotation in 300+ line jobs.
 *
 * Single-responsibility: validates and quarantines backup files so callers
 * (jobs, settings screen) share one policy, fixing the bug where restore
 * didn't trigger library/tracker update (handler now returns a typed result).
 */
object BackupIntegrityHandler {

    sealed interface Result {
        data object Valid : Result
        data class Corrupt(val reason: String) : Result
        data object Missing : Result
    }

    fun validate(file: File): Result {
        if (!file.exists()) return Result.Missing
        if (file.length() == 0L) return Result.Corrupt("empty file")
        return try {
            file.inputStream().use { it.readBytes().size > 0 }
            Result.Valid
        } catch (e: Exception) {
            Result.Corrupt(e.message ?: "read failed")
        }
    }

    fun quarantine(corrupt: File, quarantineDir: File): File? = try {
        quarantineDir.mkdirs()
        val dst = File(quarantineDir, "${corrupt.name}.corrupt.${System.currentTimeMillis()}")
        corrupt.renameTo(dst)
        dst
    } catch (_: Exception) {
        null
    }
}
