package eu.kanade.tachiyomi.data.database

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import exh.log.xLogE
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// KMK -->
private const val DEFAULT_DATABASE_NAME = "tachiyomi.db"
private const val BACKUP_DIR_NAME = "database_backup"
private const val BACKUP_FILE_PREFIX = "tachiyomi-"
private const val MAX_BACKUPS = 3
private const val BACKUP_INTERVAL_MS = 24L * 60 * 60 * 1000

private enum class IntegrityResult { OK, CORRUPT, FAILED }

/**
 * Protects the SQLite database against corruption and wipes by combining three
 * measures, all executed directly on the database file before the app's own
 * connection pool opens it:
 *
 * - a startup integrity probe with automatic recovery from the latest backup,
 * - a rolling local backup refreshed at most once per day,
 * - quarantining an unrecoverable database instead of crash-looping.
 *
 * Supports both plaintext databases ([passphrase] null) and SQLCipher
 * encrypted ones (passphrase supplied, e.g. from CbzCrypto).
 */
class DatabaseMaintenanceManager(
    private val context: Context,
    private val databaseName: String = DEFAULT_DATABASE_NAME,
    private val passphrase: ByteArray? = null,
) {

    private val backupDir get() = File(context.filesDir, BACKUP_DIR_NAME)
    private val dbFile get() = context.getDatabasePath(databaseName)

    suspend fun performStartupMaintenance() = withContext(Dispatchers.IO) {
        if (!dbFile.exists()) return@withContext

        when (integrityCheckResult()) {
            IntegrityResult.OK -> refreshBackup()
            IntegrityResult.CORRUPT -> {
                xLogE("$databaseName failed the integrity check")
                if (!restoreLatestBackup()) {
                    quarantineCorruptDatabase()
                }
            }
            IntegrityResult.FAILED -> Unit
        }
    }

    /** Returns the first column of the single row produced by [sql], or null when unavailable. */
    private fun rawQueryScalar(sql: String, readOnly: Boolean): String? {
        return if (passphrase == null) {
            val flags = if (readOnly) SQLiteDatabase.OPEN_READONLY else SQLiteDatabase.OPEN_READWRITE
            val db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, flags)
            try {
                db.rawQuery(sql, null).use(Cursor::readScalar)
            } finally {
                db.close()
            }
        } else {
            // Same library load AppModule performs; repeated loads are no-ops
            System.loadLibrary("sqlcipher")
            val flags = if (readOnly) {
                net.zetetic.database.sqlcipher.SQLiteDatabase.OPEN_READONLY
            } else {
                net.zetetic.database.sqlcipher.SQLiteDatabase.OPEN_READWRITE
            }
            val db = net.zetetic.database.sqlcipher.SQLiteDatabase.openDatabase(
                dbFile.absolutePath,
                passphrase,
                null,
                flags,
                null,
            )
            try {
                db.rawQuery(sql, emptyArray<Any>()).use(Cursor::readScalar)
            } finally {
                db.close()
            }
        }
    }

    private fun integrityCheckResult(): IntegrityResult {
        val verdict = runCatching { rawQueryScalar("PRAGMA quick_check(1)", readOnly = true) }
            .getOrNull()
            ?.lowercase()
        return when (verdict) {
            "ok" -> IntegrityResult.OK
            null -> IntegrityResult.FAILED
            else -> IntegrityResult.CORRUPT
        }
    }

    private fun latestBackup(): File? {
        return backupDir.listFiles()
            ?.filter { it.isFile && it.name.startsWith(BACKUP_FILE_PREFIX) && it.name.endsWith(".db") }
            ?.maxByOrNull { it.name }
    }

    private fun restoreLatestBackup(): Boolean {
        val backup = latestBackup()
        if (backup == null) {
            xLogE("No database backup available to restore from")
            return false
        }
        return runCatching {
            removeSidecarFiles()
            backup.copyTo(dbFile, overwrite = true)
            xLogE("Restored $databaseName from ${backup.name}")
            true
        }.getOrElse {
            xLogE("Failed to restore $databaseName from backup", it)
            false
        }
    }

    private fun quarantineCorruptDatabase() {
        runCatching {
            val quarantine = File(dbFile.absolutePath + ".corrupt-" + System.currentTimeMillis())
            removeSidecarFiles()
            if (dbFile.renameTo(quarantine)) {
                xLogE("Quarantined corrupt database as ${quarantine.name}")
            } else {
                xLogE("Could not quarantine corrupt $databaseName")
            }
        }
    }

    private fun refreshBackup() {
        val newest = latestBackup()
        if (newest != null && System.currentTimeMillis() - newest.lastModified() < BACKUP_INTERVAL_MS) return

        runCatching {
            rawQueryScalar("PRAGMA wal_checkpoint(TRUNCATE)", readOnly = false)
            backupDir.mkdirs()
            val target = File(backupDir, "$BACKUP_FILE_PREFIX${System.currentTimeMillis()}.db")
            dbFile.copyTo(target, overwrite = true)
            pruneOldBackups()
        }.onFailure {
            xLogE("Failed to back up $databaseName", it)
        }
    }

    private fun pruneOldBackups() {
        backupDir.listFiles()
            ?.filter { it.isFile && it.name.startsWith(BACKUP_FILE_PREFIX) && it.name.endsWith(".db") }
            ?.sortedByDescending { it.name }
            ?.drop(MAX_BACKUPS)
            ?.forEach { it.delete() }
    }

    private fun removeSidecarFiles() {
        File(dbFile.absolutePath + "-wal").delete()
        File(dbFile.absolutePath + "-shm").delete()
    }
}

private fun Cursor.readScalar(): String? {
    return if (moveToFirst()) getString(0) else null
}
// KMK <--
