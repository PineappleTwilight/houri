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

    private val PAGE_COUNT_REGEX = "\\d+".toRegex()
    private val RATING_REGEX = "(-?\\d+)px".toRegex()
    private val DATE_REGEX = "\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}".toRegex()

    fun getGenre(element: Element?): String? {
        if (element == null) return null
        // Prefer .cn element if this is a container
        val cnElement = if (element.hasClass("cn")) element else element.selectFirst(".cn")
        val target = cnElement ?: element
        val onclick = target.attr("onclick").nullIfBlank()
        if (onclick != null) {
            // onclick="document.location='https://e-hentai.org/doujinshi'" or "https://e-hentai.org/tag/other:..."
            val urlPart = onclick.substringAfter("'", "").substringBefore("'")
            if (urlPart.isNotBlank()) {
                val segment = urlPart.substringAfterLast('/').substringBefore('?').trim()
                if (segment.isNotBlank() && segment != "tag") {
                    // Tag urls contain "tag/other:xxx" -> preserve namespace part
                    if (onclick.contains("/tag/")) {
                        return segment.lowercase().replace(" ", "").trim()
                    }
                    return segment.lowercase().replace(" ", "").trim()
                }
            }
            // Fallback: try to extract quoted path directly
            val fallback = onclick.substringAfterLast('/').removeSuffix("'").removeSuffix("\"").trim()
            if (fallback.isNotBlank()) return fallback.lowercase().replace(" ", "").trim()
        }
        val text = target.text().nullIfBlank()?.lowercase()?.replace(" ", "")?.trim()
        if (!text.isNullOrBlank()) return text
        // Last resort: element itself
        return element.text().nullIfBlank()?.lowercase()?.replace(" ", "")?.trim()
    }

    fun getDateTag(element: Element?): Long? {
        val raw = element?.text()?.nullIfBlank() ?: return null
        // Extract the date substring if extra text surrounds it
        val text = DATE_REGEX.find(raw)?.value ?: raw
        return try {
            val date = ZonedDateTime.parse(text, MetadataUtil.EX_DATE_FORMAT.withZone(ZoneOffset.UTC))
            date.toInstant().toEpochMilli()
        } catch (_: Exception) {
            null
        }
    }

    fun getRating(element: Element?): Double? {
        if (element == null) return null
        // If element is not the .ir itself, look for descendant .ir
        val irElement = when {
            element.hasClass("ir") -> element
            else -> element.selectFirst(".ir")
        } ?: element
        val style = irElement.attr("style").nullIfBlank() ?: element.attr("style").nullIfBlank() ?: return null
        val matches = RATING_REGEX.findAll(style)
            .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
            .toList()
        if (matches.size < 2) return null
        val x = kotlin.math.abs(matches[0])
        val y = kotlin.math.abs(matches[1])
        var rate = 5.0 - x / 16.0
        if (y == 21) {
            rate -= 0.5
        }
        // Opacity in browse list encodes vote count, not rating fraction — ignore it
        return rate.coerceIn(0.0, 5.0)
    }

    fun getUploader(element: Element?): String? {
        if (element == null) return null
        // Prefer anchor with /uploader/ in href
        val anchor = element.selectFirst("a[href*='/uploader/']") ?: element.selectFirst("a")
        return anchor?.text()?.trimOrNull() ?: element.text().trimOrNull()?.takeIf { it.isNotBlank() }
    }

    fun getPageCount(element: Element?): Int? {
        if (element == null) return null
        // Search inside element and descendants for "X pages"
        val candidates = mutableListOf<Element>()
        candidates.add(element)
        candidates.addAll(element.select("*"))
        for (candidate in candidates) {
            val text = candidate.ownText().trimOrNull() ?: candidate.text().trimOrNull() ?: continue
            if (text.contains("pages", ignoreCase = true)) {
                val match = PAGE_COUNT_REGEX.find(text)?.value?.toIntOrNull()
                if (match != null) return match
            }
        }
        // Fallback: raw text of element
        val pageCount = element.text().trimOrNull() ?: return null
        return PAGE_COUNT_REGEX.find(pageCount)?.value?.toIntOrNull()
    }

    /** Helper to find a date element inside a container by scanning for date pattern */
    fun findDateElement(container: Element?): Element? {
        if (container == null) return null
        for (el in container.select("*")) {
            val t = el.ownText().trim()
            if (DATE_REGEX.containsMatchIn(t)) return el
        }
        for (el in container.select("div")) {
            if (DATE_REGEX.containsMatchIn(el.text())) return el
        }
        return null
    }

    /** Helper to find pages element inside container */
    fun findPagesElement(container: Element?): Element? {
        if (container == null) return null
        for (el in container.select("*")) {
            val t = el.text()
            if (t.contains("pages", ignoreCase = true) && PAGE_COUNT_REGEX.containsMatchIn(t)) {
                return el
            }
        }
        return null
    }
}
