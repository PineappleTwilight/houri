package exh.yakuyomi

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

/** No-op stub of [YakuyomiEngine] for the no-MTL APK variant. */
@SingleIn(AppScope::class)
@Inject
class YakuyomiEngine {

    val notEnoughMemoryReason: String
        get() = "AI translation is not available in this build"

    fun isHardwareSupported(): Boolean = false

    fun prewarm(): Boolean = false

    fun bitmapToWebP(bitmap: android.graphics.Bitmap, quality: Int = 85): ByteArray = ByteArray(0)
}

/** No-op stub of [TranslateMangaStore] for the no-MTL APK variant. */
@SingleIn(AppScope::class)
@Inject
class TranslateMangaStore(
    private val preferenceStore: PreferenceStore,
) {
    private fun pref(mangaId: Long): Preference<Boolean> =
        preferenceStore.getBoolean("yakuyomi_translate_manga_$mangaId", false)

    fun isEnabled(mangaId: Long): Boolean = false

    fun setEnabled(mangaId: Long, enabled: Boolean) {
        pref(mangaId).set(enabled)
    }

    fun toggle(mangaId: Long): Boolean = false

    fun asFlow(mangaId: Long): Flow<Boolean> = pref(mangaId).changes()

    fun getPreference(mangaId: Long): Preference<Boolean> = pref(mangaId)

    fun clear(mangaId: Long) {
        runCatching { pref(mangaId).delete() }
    }
}

/** No-op stub of [TranslationCache] for the no-MTL APK variant. */
@SingleIn(AppScope::class)
@Inject
class TranslationCache {

    fun sizeBytes(): Long = 0L

    fun pruneIfNeeded() = Unit

    fun clearAll() = Unit
}

/** No-op stub of [BreadcrumbNotes] for the no-MTL APK variant. */
@SingleIn(AppScope::class)
@Inject
class BreadcrumbNotes {

    fun buildContextPrompt(mangaId: Long): String = ""

    fun appendFromTranslation(mangaId: Long, chapterId: Long, texts: List<String>) = Unit
}
