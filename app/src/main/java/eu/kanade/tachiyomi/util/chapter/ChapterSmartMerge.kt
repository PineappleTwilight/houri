package eu.kanade.tachiyomi.util.chapter

import tachiyomi.domain.chapter.model.Chapter

fun scanlatorBlacklistKey(chapterNumber: Double, scanlator: String?): String = "$chapterNumber@${scanlator.orEmpty()}"

/**
 * A rule pinning one scanlator over a span of chapter numbers, stored encoded as
 * "from:to:scanlator" inside [tachiyomi.domain.manga.model.Manga.scanlatorRangeRules].
 */
data class ScanlatorRangeRule(
    val from: Double,
    val to: Double,
    val scanlator: String,
) {
    fun covers(chapterNumber: Double): Boolean = chapterNumber >= from && chapterNumber <= to
}

fun encodeScanlatorRangeRule(rule: ScanlatorRangeRule): String = "${rule.from}:${rule.to}:${rule.scanlator}"

fun parseScanlatorRangeRule(raw: String): ScanlatorRangeRule? {
    val parts = raw.split(':', limit = 3)
    if (parts.size != 3) return null
    val from = parts[0].toDoubleOrNull() ?: return null
    val to = parts[1].toDoubleOrNull() ?: return null
    if (from > to) return null
    return ScanlatorRangeRule(from, to, parts[2])
}

/**
 * Smart merge: keeps one chapter per chapter number, preferring scanlators in
 * [priority] order (unknown scanlators sort last, stable by original order).
 * Chapters whose "number@scanlator" key is in [blacklisted] are skipped for
 * that number; a number with no remaining candidates is hidden entirely.
 * Chapters without a positive number are never deduplicated.
 *
 * When [rangeRules] contain a rule covering a number, its scanlator wins for that
 * number regardless of global order; if it has no candidate for the number, the
 * global priority order applies. The last matching rule wins when several overlap.
 */
fun List<Chapter>.applyScanlatorPriority(
    priority: List<String>,
    blacklistedChapters: Set<String>,
    rangeRules: List<String> = emptyList(),
): List<Chapter> {
    if (priority.isEmpty() && blacklistedChapters.isEmpty() && rangeRules.isEmpty()) return this

    val rules = rangeRules.mapNotNull(::parseScanlatorRangeRule)

    val chosenIds = asSequence()
        .filter { it.chapterNumber > 0.0 }
        .groupBy { it.chapterNumber }
        .values
        .mapNotNull { sameNumber ->
            val preferredScanlator = rules.lastOrNull { it.covers(sameNumber.first().chapterNumber) }?.scanlator
            sameNumber
                .filter { scanlatorBlacklistKey(it.chapterNumber, it.scanlator) !in blacklistedChapters }
                .minWithOrNull(
                    compareBy<Chapter> { chapter ->
                        if (chapter.scanlator == preferredScanlator) 0 else 1
                    }.thenBy { candidate ->
                        priority.indexOf(candidate.scanlator)
                            .takeIf { it != -1 }
                            ?: Int.MAX_VALUE
                    },
                )
                ?.id
        }
        .toSet()

    return filter { it.chapterNumber <= 0.0 || it.id in chosenIds }
}
