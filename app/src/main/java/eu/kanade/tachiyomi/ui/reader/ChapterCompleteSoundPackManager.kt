package eu.kanade.tachiyomi.ui.reader

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import exh.log.xLogE
import java.io.File
import java.util.Locale

/**
 * Imports and manages user-provided audio files for the chapter completion
 * sound feature. Imported files are copied into per-tier folders under
 * [ChapterCompleteSoundPlayer.EXTERNAL_SOUNDS_DIR] where they fully replace the
 * bundled sounds of that tier (see [ChapterCompleteSoundPlayer]).
 */
object ChapterCompleteSoundPackManager {

    fun customCounts(context: Context): Map<SoundTier, Int> {
        val root = File(context.filesDir, ChapterCompleteSoundPlayer.EXTERNAL_SOUNDS_DIR)
        return SoundTier.entries.associateWith { tier ->
            tierFolder(root, tier)
                .listFiles { file -> file.isFile && file.extension.lowercase() in SupportedSoundExtensions }
                ?.size ?: 0
        }
    }

    /**
     * Copies the given documents into the folder of [tier] and returns how
     * many were imported successfully.
     */
    fun import(context: Context, uris: List<Uri>, tier: SoundTier): Int {
        val root = File(context.filesDir, ChapterCompleteSoundPlayer.EXTERNAL_SOUNDS_DIR)
        val targetDir = tierFolder(root, tier).apply { mkdirs() }
        var imported = 0

        uris.forEach { uri ->
            runCatching {
                val displayName = queryDisplayName(context, uri) ?: return@runCatching
                if (!isSupportedFileName(displayName)) return@runCatching

                val target = uniqueFile(targetDir, sanitizeFileName(displayName))
                context.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                } ?: return@runCatching
                imported++
            }.onFailure { xLogE("Failed to import chapter completion sound", it) }
        }
        return imported
    }

    fun clearAll(context: Context) {
        runCatching {
            File(context.filesDir, ChapterCompleteSoundPlayer.EXTERNAL_SOUNDS_DIR).deleteRecursively()
        }.onFailure { xLogE("Failed to remove imported chapter completion sounds", it) }
    }

    private fun tierFolder(root: File, tier: SoundTier) = File(root, tier.folderName)

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(0)?.let { return it }
                }
            }
        return uri.lastPathSegment
    }

    private fun isSupportedFileName(name: String): Boolean {
        return name.substringAfterLast('.', "").lowercase(Locale.ROOT) in SupportedSoundExtensions
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[^A-Za-z0-9._ ()-]"), "_")
    }

    private fun uniqueFile(dir: File, fileName: String): File {
        var candidate = File(dir, fileName)
        if (!candidate.exists()) return candidate

        val name = candidate.nameWithoutExtension
        val extension = candidate.extension
        var index = 1
        while (candidate.exists()) {
            candidate = File(dir, "$name (${index++}).$extension")
        }
        return candidate
    }
}
