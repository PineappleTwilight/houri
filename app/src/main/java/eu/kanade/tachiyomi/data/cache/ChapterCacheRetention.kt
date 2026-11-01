package eu.kanade.tachiyomi.data.cache

// KMK -->
/** Suffix appended to an image URL for each long-strip segment cut from it (see HttpPageLoader). */
const val CHAPTER_CACHE_SEGMENT_KEY_SUFFIX = "#segment-"

/**
 * Pure math behind the chapter cache's sliding retention clock. The "age" of a cache entry is the
 * `lastModified` of its backing file; reads touch that file, so an entry keeps renewing itself for
 * as long as it is being read, and a sweep drops whatever has aged past the retention.
 *
 * Deliberately free of Android types so the offline kotlinc-check harness can assert it.
 */
object ChapterCacheRetention {

    /**
     * True when a sweep with the given retention should remove an entry last touched at
     * [lastModifiedMs]. Retention of 0 (or less) disables expiry entirely.
     */
    fun isExpired(lastModifiedMs: Long, nowMs: Long, retentionMs: Long): Boolean {
        return retentionMs > 0L && lastModifiedMs + retentionMs < nowMs
    }

    /**
     * How long an entry last touched at [lastModifiedMs] still has before a sweep drops it.
     * Negative when it is already past its retention ("removed at next sweep").
     */
    fun remainingMs(lastModifiedMs: Long, nowMs: Long, retentionMs: Long): Long {
        if (retentionMs <= 0L) return Long.MAX_VALUE
        return lastModifiedMs + retentionMs - nowMs
    }

    /**
     * How long an entry last touched at [lastModifiedMs] has already lived. Never negative.
     */
    fun ageMs(lastModifiedMs: Long, nowMs: Long): Long {
        return (nowMs - lastModifiedMs).coerceAtLeast(0L)
    }

    /**
     * Breaks a (non-negative) remaining time into the largest two nonzero units for display,
     * e.g. 2 days 5 hours. Zeroes out everything below [roundToMs] so a countdown reads
     * "3 d" / "5 h" / "12 min" instead of "2 d 5 h 3 min 12 s".
     */
    fun displayParts(remainingMs: Long, roundToMs: Long = MINUTE_MS): DisplayParts {
        val totalMinutes = (remainingMs / roundToMs).coerceAtLeast(0L)
        val days = totalMinutes / (24L * 60L)
        val hours = (totalMinutes / 60L) % 24L
        val minutes = totalMinutes % 60L
        return when {
            days > 0L -> DisplayParts(days, hours, 0L)
            hours > 0L -> DisplayParts(0L, hours, minutes)
            else -> DisplayParts(0L, 0L, minutes)
        }
    }

    const val MINUTE_MS: Long = 60L * 1000L
    const val HOUR_MS: Long = 60L * MINUTE_MS
    const val DAY_MS: Long = 24L * HOUR_MS

    /** Nonzero components of a remaining time; unused components are 0. */
    data class DisplayParts(val days: Long, val hours: Long, val minutes: Long)
}
// KMK <--
