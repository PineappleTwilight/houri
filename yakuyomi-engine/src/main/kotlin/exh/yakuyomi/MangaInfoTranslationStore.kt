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
        runCatching { file(mangaId).writeText(json.encodeToString(translation)) }
    }

    fun clear(mangaId: Long) {
        runCatching { file(mangaId).delete() }
    }

    private fun file(mangaId: Long): File = File(dir, "$mangaId.json")
}
