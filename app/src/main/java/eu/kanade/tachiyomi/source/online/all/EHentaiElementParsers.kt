package eu.kanade.tachiyomi.source.online.all

import exh.metadata.MetadataUtil
import exh.util.nullIfBlank
import exh.util.trimOrNull
import org.jsoup.nodes.Element
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * Stateless leaf parsers for E-Hentai gallery-list HTML elements, extracted from
 * [EHentai] so the source class only orchestrates requests.
 */
internal object EHentaiElementParsers {

    private val PAGE_COUNT_REGEX = "[0-9]*".toRegex()
    private val RATING_REGEX = "([0-9]*)px".toRegex()

    fun getGenre(element: Element?): String? {
        return element?.attr("onclick")
            ?.nullIfBlank()
            ?.substringAfterLast('/')
            ?.removeSuffix("'")
            ?.trim()
            ?.substringAfterLast('/')
            ?.removeSuffix("'")
            ?: element?.text()
                ?.nullIfBlank()
                ?.lowercase()
                ?.replace(" ", "")
                ?.trim()
    }

    fun getDateTag(element: Element?): Long? {
        val text = element?.text()?.nullIfBlank()
        return if (text != null) {
            val date = ZonedDateTime.parse(text, MetadataUtil.EX_DATE_FORMAT.withZone(ZoneOffset.UTC))
            date?.toInstant()?.toEpochMilli()
        } else {
            null
        }
    }

    fun getRating(element: Element?): Double? {
        val ratingStyle = element?.attr("style")?.nullIfBlank()
        return if (ratingStyle != null) {
            val matches = RATING_REGEX.findAll(ratingStyle)
                .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
                .toList()
            if (matches.size == 2) {
                var rate = 5 - matches[0] / 16
                if (matches[1] == 21) {
                    rate--
                    rate + 0.5
                } else {
                    rate.toDouble()
                }
            } else {
                null
            }
        } else {
            null
        }
    }

    fun getUploader(element: Element?): String? {
        return element?.select("a")?.text()?.trimOrNull()
    }

    fun getPageCount(element: Element?): Int? {
        val pageCount = element?.text()?.trimOrNull()
        return if (pageCount != null) {
            PAGE_COUNT_REGEX.find(pageCount)?.value?.toIntOrNull()
        } else {
            null
        }
    }
}
