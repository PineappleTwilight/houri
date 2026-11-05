package tachiyomi.domain.chapter.service

fun interface ChapterTombstones {
    suspend fun remember(keys: Collection<String>)

    companion object {
        /** Must match the SyncYomi server's chapter key byte for byte. */
        fun key(sourceId: Long, mangaUrl: String, chapterUrl: String): String =
            "$sourceId|$mangaUrl\u001f$chapterUrl"
    }
}
