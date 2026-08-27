package exh.yakuyomi

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.core.common.preference.PreferenceStore

@SingleIn(AppScope::class)
@Inject
class TranslateMangaStore(
    private val preferenceStore: PreferenceStore,
) {
    fun isEnabled(mangaId: Long): Boolean =
        preferenceStore.getBoolean("yakuyomi_translate_manga_$mangaId", false).get()

    fun setEnabled(mangaId: Long, enabled: Boolean) {
        preferenceStore.getBoolean("yakuyomi_translate_manga_$mangaId", false).set(enabled)
    }

    fun toggle(mangaId: Long): Boolean {
        val cur = isEnabled(mangaId)
        setEnabled(mangaId, !cur)
        return !cur
    }
}
