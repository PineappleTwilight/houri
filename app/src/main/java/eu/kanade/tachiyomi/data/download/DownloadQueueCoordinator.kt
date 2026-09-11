package eu.kanade.tachiyomi.data.download

import eu.kanade.tachiyomi.data.download.model.Download

/**
 * Extracts download-queue duplicate logic that was copy-pasted across
 * `MangaScreenModel`, `ReaderViewModel`, `UpdatesScreenModel`, and
 * `LibraryUpdateJob` (4 sites, per AUDIT.md #16).
 *
 * Centralizes “is already queued?” and “should delete chapters?” policies so
 * bulk-unfavorite can share the single-manga delete path, fixing the bug where
 * bulk-unfavorite left orphaned download files.
 */
object DownloadQueueCoordinator {

    fun isAlreadyQueued(chapterId: Long, queue: List<Download>): Boolean =
        queue.any { it.chapter.id == chapterId }

    fun pendingDownloads(chapters: List<tachiyomi.domain.chapter.model.Chapter>, queue: List<Download>): List<Long> =
        chapters.filterNot { ch -> queue.any { it.chapter.id == ch.id } }.map { it.id!! }

    fun shouldDeleteOnUnfavorite(isBulk: Boolean, singleMangaDeletes: Boolean): Boolean =
        !isBulk || singleMangaDeletes
}
