package eu.kanade.tachiyomi.util.chapter

/**
 * Compact chapter coverage for one scanlator, ready for display:
 * [groups] holds formatted labels like "1–5" or "8", and [hiddenCount]
 * counts groups beyond the display cap reported by the caller.
 */
data class ScanlatorCoverage(
    val groups: List<String>,
    val hiddenCount: Int,
)

/**
 * Groups sorted chapter numbers into whole-number runs ("1–5") and standalone
 * entries ("4.5"). Numbers <= 0 and duplicates are ignored. Returns null when
 * nothing is left to show. [maxGroups] caps how many groups are kept visible;
 * the rest are counted in [ScanlatorCoverage.hiddenCount].
 */
fun scanlatorCoverage(chapterNumbers: Collection<Double>, maxGroups: Int = 10): ScanlatorCoverage? {
    val numbers = chapterNumbers.filter { it > 0.0 }.distinct().sorted()
    if (numbers.isEmpty()) return null

    val ranges = buildList {
        var index = 0
        while (index < numbers.size) {
            var end = index
            while (end + 1 < numbers.size && numbers[end + 1] == numbers[end] + 1.0) end++
            add(numbers[index] to numbers[end])
            index = end + 1
        }
    }

    val displayed = ranges.take(maxGroups)
    val groups = displayed.map { (start, end) ->
        if (start == end) start.asCoverageLabel() else "${start.asCoverageLabel()}–${end.asCoverageLabel()}"
    }
    return ScanlatorCoverage(groups = groups, hiddenCount = ranges.size - displayed.size)
}

private fun Double.asCoverageLabel(): String =
    if (this % 1.0 == 0.0) toInt().toString() else toString()
