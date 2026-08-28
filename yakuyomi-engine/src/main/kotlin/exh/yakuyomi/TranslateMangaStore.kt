package exh.yakuyomi

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

@SingleIn(AppScope::class)
@Inject
class TranslateMangaStore(
    private val preferenceStore: PreferenceStore,
) {
    private fun pref(mangaId: Long): Preference<Boolean> =
        preferenceStore.getBoolean("yakuyomi_translate_manga_$mangaId", false)

    fun isEnabled(mangaId: Long): Boolean = pref(mangaId).get()

    fun setEnabled(mangaId: Long, enabled: Boolean) {
        pref(mangaId).set(enabled)
    }

    fun toggle(mangaId: Long): Boolean {
        val cur = isEnabled(mangaId)
        setEnabled(mangaId, !cur)
        return !cur
    }

    fun asFlow(mangaId: Long): Flow<Boolean> = pref(mangaId).changes()

    fun getPreference(mangaId: Long): Preference<Boolean> = pref(mangaId)

    fun clear(mangaId: Long) {
        try {
            pref(mangaId).delete()
        } catch (_: Exception) {}
    }
}
