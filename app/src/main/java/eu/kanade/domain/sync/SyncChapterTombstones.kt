package eu.kanade.domain.sync

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import tachiyomi.domain.chapter.service.ChapterTombstones

@Inject
@ContributesBinding(AppScope::class)
class SyncChapterTombstones(
    private val syncPreferences: SyncPreferences,
) : ChapterTombstones {
    override suspend fun remember(keys: Collection<String>) {
        syncPreferences.rememberDeletedChapters(keys)
    }
}
