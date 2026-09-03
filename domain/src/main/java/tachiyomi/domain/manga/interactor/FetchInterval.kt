package tachiyomi.domain.manga.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.absoluteValue

@Inject
class FetchInterval(
    private val getChaptersByMangaId: GetChaptersByMangaId,
) {

    suspend fun toMangaUpdate(
        manga: Manga,
        dateTime: ZonedDateTime,
        window: Pair<Long, Long>,
    ): MangaUpdate {
        val interval = manga.fetchInterval.takeIf { it < 0 } ?: calculateInterval(
            chapters = getChaptersByMangaId.await(manga.id, applyFilter = true),
            zone = dateTime.zone,
        )
        val currentWindow = if (window.first == 0L && window.second == 0L) {
            getWindow(ZonedDateTime.now())
        } else {
            window
        }
        val nextUpdate = calculateNextUpdate(manga, interval, dateTime, currentWindow)

        return MangaUpdate(id = manga.id, nextUpdate = nextUpdate, fetchInterval = interval)
    }

    fun getWindow(dateTime: ZonedDateTime): Pair<Long, Long> {
        val today = dateTime.toLocalDate().atStartOfDay(dateTime.zone)
        val lowerBound = today.minusDays(GRACE_PERIOD)
        val upperBound = today.plusDays(GRACE_PERIOD)
        return Pair(lowerBound.toEpochSecond() * 1000, upperBound.toEpochSecond() * 1000 - 1)
    }

    // KMK -->
    internal fun calculateInterval(chapters: List<Chapter>, zone: ZoneId): Int {
        val chapterWindow = if (chapters.size <= 8) 6 else 12

        val uploadDates = chapters.asSequence()
            .filter { it.dateUpload > 0L }
            .sortedByDescending { it.dateUpload }
            .map {
                ZonedDateTime.ofInstant(Instant.ofEpochMilli(it.dateUpload), zone)
                    .toLocalDate()
                    .atStartOfDay()
            }
            .distinct()
            .take(chapterWindow)
            .toList()

        val fetchDates = chapters.asSequence()
            .sortedByDescending { it.dateFetch }
            .map {
                ZonedDateTime.ofInstant(Instant.ofEpochMilli(it.dateFetch), zone)
                    .toLocalDate()
                    .atStartOfDay()
            }
            .distinct()
            .take(chapterWindow)
            .toList()

        val gaps = when {
            // Prefer source upload dates; they describe the real release cadence.
            uploadDates.size >= 4 -> dayGaps(uploadDates)
            // Fall back to client-side fetch dates (in case the source drops upload dates).
            fetchDates.size >= 4 -> dayGaps(fetchDates)
            else -> emptyList()
        }
        if (gaps.size < 3) return DEFAULT_INTERVAL

        val sorted = gaps.sorted()
        val median = sorted[sorted.size / 2]

        // Reject hiatus/binge outliers (single gap > 3x the median) before choosing the
        // cadence; a 60-day pause between weekly chapters should not stretch the estimate.
        val filtered = gaps.filter { it > 0 && it <= median * 3 }
        if (filtered.isEmpty()) return median.toInt()

        // A recurring cadence (e.g. weekly) is more predictive than the median of noisy
        // history — the mode wins when the same gap repeats at least twice.
        val mode = filtered.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        val interval = when {
            mode != null && filtered.count { it == mode } >= 2 -> mode
            else -> {
                // Noisy history: the most recent gap best predicts the next release, but a
                // single quick release (binge catch-up) must not override a slower cadence.
                maxOf(median / 2, filtered.first())
            }
        }
        return interval.coerceIn(1, MAX_INTERVAL.toLong()).toInt()
    }

    /** Day gaps between consecutive distinct release days, newest first. */
    private fun dayGaps(days: List<LocalDateTime>): List<Long> =
        days.windowed(2).map { it[1].until(it[0], ChronoUnit.DAYS) }
    // KMK <--

    private fun calculateNextUpdate(
        manga: Manga,
        interval: Int,
        dateTime: ZonedDateTime,
        window: Pair<Long, Long>,
    ): Long {
        if (manga.nextUpdate in window.first.rangeTo(window.second + 1)) {
            return manga.nextUpdate
        }

        val latestDate = ZonedDateTime.ofInstant(
            if (manga.lastUpdate > 0) Instant.ofEpochMilli(manga.lastUpdate) else Instant.now(),
            dateTime.zone,
        )
            .toLocalDate()
            .atStartOfDay()
        val timeSinceLatest = ChronoUnit.DAYS.between(latestDate, dateTime).toInt()
        val cycle = timeSinceLatest.floorDiv(
            interval.absoluteValue.takeIf { interval < 0 }
                ?: increaseInterval(interval, timeSinceLatest, increaseWhenOver = 10),
        )
        return latestDate.plusDays((cycle + 1) * interval.absoluteValue.toLong()).toEpochSecond(dateTime.offset) * 1000
    }

    private fun increaseInterval(delta: Int, timeSinceLatest: Int, increaseWhenOver: Int): Int {
        if (delta >= MAX_INTERVAL) return MAX_INTERVAL

        // double delta again if missed more than 9 check in new delta
        val cycle = timeSinceLatest.floorDiv(delta) + 1
        return if (cycle > increaseWhenOver) {
            increaseInterval(delta * 2, timeSinceLatest, increaseWhenOver)
        } else {
            delta
        }
    }

    companion object {
        const val MAX_INTERVAL = 28

        // KMK -->
        private const val DEFAULT_INTERVAL = 7
        // KMK <--

        private const val GRACE_PERIOD = 1L

        // KMK -->
        const val MANUAL_DISABLE = 99999 // 274 years in future
        // KMK <--
    }
}
