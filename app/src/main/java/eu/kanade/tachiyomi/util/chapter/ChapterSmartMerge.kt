package eu.kanade.tachiyomi.util.chapter

import tachiyomi.domain.chapter.model.Chapter

fun scanlatorBlacklistKey(chapterNumber: Double, scanlator: String?): String = "$chapterNumber@${scanlator.orEmpty()}"

/**
 * Smart merge: keeps one chapter per chapter number, preferring scanlators in
 * [priority] order (unknown scanlators sort last, stable by original order).
 * Chapters whose "number@scanlator" key is in [blacklisted] are skipped for
 * that number; a number with no remaining candidates is hidden entirely.
 * Chapters without a positive number are never deduplicated.
 */
fun List<Chapter>.applyScanlatorPriority(
    priority: List<String>,
    blacklistedChapters: Set<String>,
): List<Chapter> {
    if (priority.isEmpty() && blacklistedChapters.isEmpty()) return this

    val chosenIds = asSequence()
        .filter { it.chapterNumber > 0.0 }
        .groupBy { it.chapterNumber }
        .values
        .mapNotNull { sameNumber ->
            sameNumber
                .filter { scanlatorBlacklistKey(it.chapterNumber, it.scanlator) !in blacklistedChapters }
                .minByOrNull { candidate ->
                    priority.indexOf(candidate.scanlator)
                        .takeIf { it != -1 }
                        ?: Int.MAX_VALUE
                }
                ?.id
        }
        .toSet()

    return filter { it.chapterNumber <= 0.0 || it.id in chosenIds }
}
