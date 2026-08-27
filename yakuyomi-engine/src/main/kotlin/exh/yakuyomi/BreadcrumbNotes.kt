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
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

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
        return dir.listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.take(windowSize)
            ?.mapNotNull { f ->
                try {
                    json.decodeFromString<BreadcrumbNote>(f.readText())
                } catch (_: Exception) {
                    null
                }
            }
            ?.reversed() ?: emptyList()
    }

    fun buildContextPrompt(mangaId: Long): String {
        val notes = loadWindow(mangaId)
        if (notes.isEmpty()) return ""
        return notes.joinToString("\n") { n ->
            "Chapter ${n.chapterId}: ${n.summary} | Entities: ${n.keyEntities.joinToString(", ")}"
        }
    }

    fun appendFromTranslation(mangaId: Long, chapterId: Long, translatedTexts: List<String>) {
        if (translatedTexts.isEmpty()) return
        val summary = translatedTexts.take(3).joinToString(" | ").take(400)
        val entities = translatedTexts.flatMap { it.split(" ").filter { w -> w.firstOrNull()?.isUpperCase() == true } }.distinct().take(8)
        saveNote(BreadcrumbNote(chapterId = chapterId, mangaId = mangaId, summary = summary, keyEntities = entities))
    }
}
