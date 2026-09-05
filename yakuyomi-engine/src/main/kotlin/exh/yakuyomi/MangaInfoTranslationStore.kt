package exh.yakuyomi

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Disk cache for translated manga metadata (one JSON file per manga under
 * `filesDir/manga_info_translations/`). Lets users re-import/reuse translations without
 * re-calling the LLM.
 */
@SingleIn(AppScope::class)
@Inject
class MangaInfoTranslationStore(
    private val context: Context,
) {
    private val dir = File(context.filesDir, "manga_info_translations").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true }

    fun get(mangaId: Long): MangaInfoTranslation? {
        val f = file(mangaId)
        if (!f.exists()) return null
        return runCatching { json.decodeFromString<MangaInfoTranslation>(f.readText()) }.getOrNull()
    }

    fun put(mangaId: Long, translation: MangaInfoTranslation) {
        if (mangaId <= 0) return
        val safeTitle = translation.title.trim().take(300).ifBlank { return }
        val safeDesc = translation.description?.trim()?.take(2000)
        val safe = translation.copy(title = safeTitle, description = safeDesc)
        runCatching {
            val f = file(mangaId)
            val tmp = File(f.parentFile, "${f.name}.tmp")
            tmp.writeText(json.encodeToString(safe))
            if (f.exists()) f.delete()
            if (!tmp.renameTo(f)) {
                tmp.copyTo(f, overwrite = true)
                tmp.delete()
            }
        }
    }

    fun clear(mangaId: Long) {
        runCatching { file(mangaId).delete() }
    }

    private fun file(mangaId: Long): File = File(dir, "$mangaId.json")
}
