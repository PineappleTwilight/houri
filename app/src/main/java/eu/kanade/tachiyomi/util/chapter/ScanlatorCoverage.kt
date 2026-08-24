package eu.kanade.tachiyomi.util.chapter

private const val TENTHS = 10L

/**
 * Compact chapter coverage for one scanlator, ready for display:
 * [groups] holds formatted labels like "1–5" or "0.7", and [hiddenCount]
 * counts groups beyond the display cap reported by the caller.
 */
data class ScanlatorCoverage(
    val groups: List<String>,
    val hiddenCount: Int,
)

/**
 * Groups sorted chapter numbers into runs stepped by 1 ("1–5", including
 * half-chapter sequences like 0.5–2.5). Numbers are rounded to 1 decimal in
 * scaled integer space first, so float noise from source parsing neither
 * leaks into labels nor breaks run detection. Numbers <= 0 and duplicates
 * are ignored. Returns null when nothing is left to show. [maxGroups] caps
 * how many groups stay visible; the rest are counted in [ScanlatorCoverage.hiddenCount].
 */
fun scanlatorCoverage(chapterNumbers: Collection<Double>, maxGroups: Int = 10): ScanlatorCoverage? {
    val tenths = chapterNumbers
        .filter { it > 0.0 }
        .map { Math.round(it * TENTHS) }
        .filter { it > 0L }
        .distinct()
        .sorted()
    if (tenths.isEmpty()) return null

    val ranges = buildList {
        var index = 0
        while (index < tenths.size) {
            var end = index
            while (end + 1 < tenths.size && tenths[end + 1] == tenths[end] + TENTHS) end++
            add(tenths[index] to tenths[end])
            index = end + 1
        }
    }

    val displayed = ranges.take(maxGroups)
    val groups = displayed.map { (start, end) ->
        if (start == end) tenthsLabel(start) else "${tenthsLabel(start)}–${tenthsLabel(end)}"
    }
    return ScanlatorCoverage(groups = groups, hiddenCount = ranges.size - displayed.size)
}

private fun tenthsLabel(tenths: Long): String {
    val whole = tenths / TENTHS
    val tenth = tenths % TENTHS
    return if (tenth == 0L) whole.toString() else "$whole.$tenth"
}
