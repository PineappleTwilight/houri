package tachiyomi.domain.manga.model

import android.annotation.SuppressLint
import androidx.compose.runtime.Immutable
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import mihon.core.common.extensions.EMPTY
import tachiyomi.core.common.preference.TriState
import java.io.ObjectStreamException
import java.time.Instant
import java.io.Serializable as JavaSerializable

@SuppressLint("UnsafeOptInUsageError")
@Serializable
@Immutable
data class Manga(
    val id: Long,
    val source: Long,
    val favorite: Boolean,
    val lastUpdate: Long,
    val nextUpdate: Long,
    val fetchInterval: Int,
    val dateAdded: Long,
    val viewerFlags: Long,
    val chapterFlags: Long,
    val coverLastModified: Long,
    val url: String,
    // SY -->
    val ogTitle: String,
    val ogArtist: String?,
    val ogAuthor: String?,
    val ogThumbnailUrl: String?,
    val ogDescription: String?,
    val ogGenre: List<String>?,
    val ogStatus: Long,
    // SY <--
    val updateStrategy: UpdateStrategy,
    val initialized: Boolean,
    val lastModifiedAt: Long,
    val favoriteModifiedAt: Long?,
    val version: Long,
    val notes: String,
    val memo: JsonObject,
    // KMK -->
    val scanlatorPriority: List<String> = emptyList(),
    val blacklistedChapters: List<String> = emptyList(),
    val rereadCount: Int = 0,
    val rereading: Boolean = false,
    val rereadStartedAt: Long = 0,
    val scanlatorRangeRules: List<String> = emptyList(),
    // KMK <--
) : JavaSerializable {

    // SY -->
    /* KMK --> */ @Transient /* KMK <-- */
    private val customMangaInfo = if (favorite) {
        CustomMangaInfoLookup.resolve?.invoke(id)
    } else {
        null
    }

    val title: String
        get() = customMangaInfo?.title ?: ogTitle

    val author: String?
        get() = customMangaInfo?.author ?: ogAuthor

    val artist: String?
        get() = customMangaInfo?.artist ?: ogArtist

    val thumbnailUrl: String?
        get() = customMangaInfo?.thumbnailUrl ?: ogThumbnailUrl

    val description: String?
        get() = customMangaInfo?.description ?: ogDescription

    val genre: List<String>?
        get() = customMangaInfo?.genre ?: ogGenre

    val status: Long
        get() = customMangaInfo?.status ?: ogStatus
    // SY <--

    /* KMK -->
    Custom info (user edits, title cleaning) lives outside the DB and is snapshotted
    at construction, so generated equality - which only sees constructor properties -
    treats a pre-edit and a post-edit instance as equal. Downstream distinctUntilChanged()
    then swallows the re-emission and stale titles stay on screen until restart.
    Include the resolved display values in equality/hashCode so such instances differ.
    KMK <-- */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Manga) return false
        return id == other.id &&
            source == other.source &&
            favorite == other.favorite &&
            lastUpdate == other.lastUpdate &&
            nextUpdate == other.nextUpdate &&
            fetchInterval == other.fetchInterval &&
            dateAdded == other.dateAdded &&
            viewerFlags == other.viewerFlags &&
            chapterFlags == other.chapterFlags &&
            coverLastModified == other.coverLastModified &&
            url == other.url &&
            ogTitle == other.ogTitle &&
            ogArtist == other.ogArtist &&
            ogAuthor == other.ogAuthor &&
            ogThumbnailUrl == other.ogThumbnailUrl &&
            ogDescription == other.ogDescription &&
            ogGenre == other.ogGenre &&
            ogStatus == other.ogStatus &&
            updateStrategy == other.updateStrategy &&
            initialized == other.initialized &&
            lastModifiedAt == other.lastModifiedAt &&
            favoriteModifiedAt == other.favoriteModifiedAt &&
            version == other.version &&
            notes == other.notes &&
            memo == other.memo &&
            scanlatorPriority == other.scanlatorPriority &&
            blacklistedChapters == other.blacklistedChapters &&
            rereadCount == other.rereadCount &&
            rereading == other.rereading &&
            rereadStartedAt == other.rereadStartedAt &&
            scanlatorRangeRules == other.scanlatorRangeRules &&
            title == other.title &&
            author == other.author &&
            artist == other.artist &&
            thumbnailUrl == other.thumbnailUrl &&
            description == other.description &&
            genre == other.genre &&
            status == other.status
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + source.hashCode()
        result = 31 * result + favorite.hashCode()
        result = 31 * result + lastUpdate.hashCode()
        result = 31 * result + nextUpdate.hashCode()
        result = 31 * result + fetchInterval
        result = 31 * result + dateAdded.hashCode()
        result = 31 * result + viewerFlags.hashCode()
        result = 31 * result + chapterFlags.hashCode()
        result = 31 * result + coverLastModified.hashCode()
        result = 31 * result + url.hashCode()
        result = 31 * result + ogTitle.hashCode()
        result = 31 * result + ogArtist.hashCode()
        result = 31 * result + ogAuthor.hashCode()
        result = 31 * result + ogThumbnailUrl.hashCode()
        result = 31 * result + ogDescription.hashCode()
        result = 31 * result + ogGenre.hashCode()
        result = 31 * result + ogStatus.hashCode()
        result = 31 * result + updateStrategy.hashCode()
        result = 31 * result + initialized.hashCode()
        result = 31 * result + lastModifiedAt.hashCode()
        result = 31 * result + favoriteModifiedAt.hashCode()
        result = 31 * result + version.hashCode()
        result = 31 * result + notes.hashCode()
        result = 31 * result + memo.hashCode()
        result = 31 * result + scanlatorPriority.hashCode()
        result = 31 * result + blacklistedChapters.hashCode()
        result = 31 * result + rereadCount
        result = 31 * result + rereading.hashCode()
        result = 31 * result + rereadStartedAt.hashCode()
        result = 31 * result + scanlatorRangeRules.hashCode()
        result = 31 * result + title.hashCode()
        result = 31 * result + author.hashCode()
        result = 31 * result + artist.hashCode()
        result = 31 * result + thumbnailUrl.hashCode()
        result = 31 * result + description.hashCode()
        result = 31 * result + genre.hashCode()
        result = 31 * result + status.hashCode()
        return result
    }
    // KMK <--

    val expectedNextUpdate: Instant?
        get() = nextUpdate
            /* KMK -->
            Always predict release date even for Completed entries
            .takeIf { status != SManga.COMPLETED.toLong() }?
             KMK <-- */
            .let { Instant.ofEpochMilli(it) }

    val sorting: Long
        get() = chapterFlags and CHAPTER_SORTING_MASK

    val displayMode: Long
        get() = chapterFlags and CHAPTER_DISPLAY_MASK

    val unreadFilterRaw: Long
        get() = chapterFlags and CHAPTER_UNREAD_MASK

    val downloadedFilterRaw: Long
        get() = chapterFlags and CHAPTER_DOWNLOADED_MASK

    val bookmarkedFilterRaw: Long
        get() = chapterFlags and CHAPTER_BOOKMARKED_MASK

    val unreadFilter: TriState
        get() = when (unreadFilterRaw) {
            CHAPTER_SHOW_UNREAD -> TriState.ENABLED_IS
            CHAPTER_SHOW_READ -> TriState.ENABLED_NOT
            else -> TriState.DISABLED
        }

    val bookmarkedFilter: TriState
        get() = when (bookmarkedFilterRaw) {
            CHAPTER_SHOW_BOOKMARKED -> TriState.ENABLED_IS
            CHAPTER_SHOW_NOT_BOOKMARKED -> TriState.ENABLED_NOT
            else -> TriState.DISABLED
        }

    fun sortDescending(): Boolean {
        return chapterFlags and CHAPTER_SORT_DIR_MASK == CHAPTER_SORT_DESC
    }

    companion object {
        // Generic filter that does not filter anything
        const val SHOW_ALL = 0x00000000L

        const val CHAPTER_SORT_DESC = 0x00000000L
        const val CHAPTER_SORT_ASC = 0x00000001L
        const val CHAPTER_SORT_DIR_MASK = 0x00000001L

        const val CHAPTER_SHOW_UNREAD = 0x00000002L
        const val CHAPTER_SHOW_READ = 0x00000004L
        const val CHAPTER_UNREAD_MASK = 0x00000006L

        const val CHAPTER_SHOW_DOWNLOADED = 0x00000008L
        const val CHAPTER_SHOW_NOT_DOWNLOADED = 0x00000010L
        const val CHAPTER_DOWNLOADED_MASK = 0x00000018L

        const val CHAPTER_SHOW_BOOKMARKED = 0x00000020L
        const val CHAPTER_SHOW_NOT_BOOKMARKED = 0x00000040L
        const val CHAPTER_BOOKMARKED_MASK = 0x00000060L

        const val CHAPTER_SORTING_SOURCE = 0x00000000L
        const val CHAPTER_SORTING_NUMBER = 0x00000100L
        const val CHAPTER_SORTING_UPLOAD_DATE = 0x00000200L
        const val CHAPTER_SORTING_ALPHABET = 0x00000300L
        const val CHAPTER_SORTING_MASK = 0x00000300L

        const val CHAPTER_DISPLAY_NAME = 0x00000000L
        const val CHAPTER_DISPLAY_NUMBER = 0x00100000L
        const val CHAPTER_DISPLAY_MASK = 0x00100000L

        fun create() = Manga(
            id = -1L,
            url = "",
            // Sy -->
            ogTitle = "",
            // SY <--
            source = -1L,
            favorite = false,
            lastUpdate = 0L,
            nextUpdate = 0L,
            fetchInterval = 0,
            dateAdded = 0L,
            viewerFlags = 0L,
            chapterFlags = 0L,
            coverLastModified = 0L,
            // SY -->
            ogArtist = null,
            ogAuthor = null,
            ogThumbnailUrl = null,
            ogDescription = null,
            ogGenre = null,
            ogStatus = 0L,
            // SY <--
            updateStrategy = UpdateStrategy.ALWAYS_UPDATE,
            initialized = false,
            lastModifiedAt = 0L,
            favoriteModifiedAt = null,
            version = 0L,
            notes = "",
            memo = JsonObject.EMPTY,
        )
    }

    @Throws(ObjectStreamException::class)
    private fun writeReplace(): Any {
        return JavaToKotlinXSerializable(Json.encodeToString<Manga>(this))
    }

    class JavaToKotlinXSerializable(private val data: String) : JavaSerializable {

        @Throws(ObjectStreamException::class)
        private fun readResolve(): Any {
            return Json.decodeFromString<Manga>(data)
        }
    }
}
