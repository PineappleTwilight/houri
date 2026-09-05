package exh.yakuyomi

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import tachiyomi.core.common.util.system.logcat
import java.io.File

@Serializable
data class BreadcrumbNote(
    val chapterId: Long,
    val mangaId: Long,
    val pageIndex: Int? = null,
    val summary: String,
    val keyEntities: List<String> = emptyList(),
    val timestamp: Long = System.currentTimeMillis(),
)

@SingleIn(AppScope::class)
@Inject
class BreadcrumbNotes(
    private val context: Context,
    private val prefs: TranslationPreferences,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun notesDir(mangaId: Long): File = File(File(context.filesDir, "yakuyomi_notes"), mangaId.toString()).apply { mkdirs() }

    private fun noteFile(mangaId: Long, chapterId: Long): File = File(notesDir(mangaId), "$chapterId.json")

    fun saveNote(note: BreadcrumbNote) {
        try {
            val f = noteFile(note.mangaId, note.chapterId)
            f.writeText(json.encodeToString(note))
        } catch (e: Exception) {
            logcat { "Breadcrumb save failed: ${e.message}" }
        }
    }

    fun loadWindow(mangaId: Long, windowSize: Int = prefs.breadcrumbWindowSize().get()): List<BreadcrumbNote> {
        val dir = notesDir(mangaId)
        if (!dir.exists()) return emptyList()
        val files = dir.listFiles() ?: return emptyList()
        return files.mapNotNull { f ->
            try {
                json.decodeFromString<BreadcrumbNote>(f.readText())
            } catch (_: Exception) {
                // Delete corrupt file
                try {
                    f.delete()
                } catch (_: Exception) {}
                null
            }
        }
            // Sort by embedded timestamp, not file lastModified which is unreliable after restore
            .sortedByDescending { it.timestamp }
            .take(windowSize.coerceIn(1, 20))
            .reversed()
    }

    fun buildContextPrompt(mangaId: Long, maxChars: Int = 800): String {
        val notes = loadWindow(mangaId)
        if (notes.isEmpty()) return ""
        // Cap total prompt contribution to avoid LLM token overflow; cloud callers use the
        // default, the local provider sizes the window to the model's context.
        val joined = notes.joinToString("\n") { n ->
            "Chapter ${n.chapterId}: ${n.summary} | Entities: ${n.keyEntities.joinToString(", ")}"
        }
        return joined.take(maxChars)
    }

    fun appendFromTranslation(mangaId: Long, chapterId: Long, translatedTexts: List<String>) {
        if (translatedTexts.isEmpty()) return
        // Filter blanks and cap
        val clean = translatedTexts.mapNotNull { it.trim().takeIf { t -> t.isNotEmpty() } }
        if (clean.isEmpty()) return
        val summary = clean.take(3).joinToString(" | ").take(400)
        // Extract entities: for EN fallback, capitalised words; for CJK-translated EN, same logic works
        // Filter short words and deduplicate, keep only plausible proper nouns length 2..24
        val entities = clean.flatMap { line ->
            line.split(Regex("[\\s,.;:!?\"'()\\[\\]{}]+"))
                .filter { w -> w.length in 2..24 && w.firstOrNull()?.isUpperCase() == true && w.all { ch -> ch.isLetter() } }
        }.distinct().take(8)
        saveNote(BreadcrumbNote(chapterId = chapterId, mangaId = mangaId, summary = summary, keyEntities = entities))
    }

    fun clearForManga(mangaId: Long) {
        try {
            notesDir(mangaId).deleteRecursively()
        } catch (_: Exception) {}
    }

    fun pruneOld(mangaId: Long, keep: Int = prefs.breadcrumbWindowSize().get()) {
        try {
            val dir = notesDir(mangaId)
            if (!dir.exists()) return
            val notes = dir.listFiles()?.mapNotNull { f ->
                try {
                    f to json.decodeFromString<BreadcrumbNote>(f.readText())
                } catch (_: Exception) {
                    null
                }
            }?.sortedByDescending { it.second.timestamp } ?: return
            notes.drop(keep).forEach { (file, _) ->
                try {
                    file.delete()
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }
}
