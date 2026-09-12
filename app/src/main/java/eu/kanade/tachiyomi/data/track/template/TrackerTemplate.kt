package eu.kanade.tachiyomi.data.track.template

import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.BaseTracker
import eu.kanade.tachiyomi.data.track.core.TrackerAuthType
import eu.kanade.tachiyomi.data.track.core.TrackerCapabilities
import eu.kanade.tachiyomi.data.track.core.TrackerDefinition
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import tachiyomi.i18n.MR
import tachiyomi.domain.track.model.Track as DomainTrack

private const val TEMPLATE_ID = 999L

val TemplateTrackerDefinition = TrackerDefinition(
    id = TEMPLATE_ID,
    name = "TemplateTracker",
    logoRes = R.drawable.brand_myanimelist,
    authType = TrackerAuthType.OAUTH,
    capabilities = TrackerCapabilities(
        supportsReadingDates = true,
        supportsPrivateTracking = false,
        supportsRereadCount = false,
    ),
    factory = { TemplateTracker(TEMPLATE_ID) },
)

class TemplateTracker(id: Long) : BaseTracker(id, "TemplateTracker") {

    override fun getLogo(): Int = R.drawable.brand_myanimelist

    override fun getStatusList(): List<Long> = listOf(1L, 2L, 3L)

    override fun getStatus(status: Long): StringResource? = when (status) {
        1L -> MR.strings.reading
        2L -> MR.strings.completed
        3L -> MR.strings.plan_to_read
        else -> null
    }

    override fun getReadingStatus(): Long = 1L

    override fun getRereadingStatus(): Long = -1L

    override fun getCompletionStatus(): Long = 2L

    override fun getScoreList(): ImmutableList<String> = (0..10).map(Int::toString).toImmutableList()

    override suspend fun update(track: Track, didReadChapter: Boolean): Track = track

    override suspend fun bind(track: Track, hasReadChapters: Boolean): Track = track

    override suspend fun search(query: String): List<TrackSearch> = emptyList()

    override suspend fun refresh(track: Track): Track = track

    override suspend fun login(username: String, password: String) {
        saveCredentials(username, password)
    }

    override fun hasNotStartedReading(status: Long): Boolean = status == 3L

    override fun displayScore(track: DomainTrack): String = track.score.toInt().toString()
}
