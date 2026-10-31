// KMK -->
package eu.kanade.tachiyomi.ui.reader.viewer.novel

import mihon.core.archive.EpubArchiveUrls
import java.net.URI

/**
 * Rewrites a document's external references into `data:` URIs, so the engine never has to ask for
 * them over the wire.
 *
 * ### Why the book's resources are inlined rather than served
 *
 * Streaming them out of the archive through `shouldInterceptRequest` is the efficient answer and
 * it is still there as a fallback — but it is also the one link in the book path that has failed
 * on real devices three times without ever producing a log line the reader could see, while the
 * markup itself (handed over as a string) has never once failed to arrive. Whatever a given
 * engine build does with intercepted custom-origin subresources, it cannot refuse a `data:` URI
 * that is already inside the document it accepted. So the references a reader actually reads —
 * inline illustrations and the stylesheets they sit under — travel the same channel as the text,
 * and interception is left for everything else: oversized files, external images, anything not
 * worth the memory of inlining.
 *
 * A document carries only its own plates, so the cost is per-document rather than per-book: a
 * novel chapter holds a handful of images; an art book's pages are not read here. Each resource is
 * capped by [MAX_RESOURCE_BYTES] and the document by [MAX_TOTAL_BYTES]; over either, the reference
 * is re-spelled absolute for the serving path to answer — a ceiling degrades to the stream, never
 * into a missing chapter.
 *
 * Pure: resolution is [URI] arithmetic, fetching is the caller's lambda. Asserted off-device in
 * `DocumentHarness`.
 */
object NovelInline {

    /** One inlined resource: the bytes and the MIME type to declare them under. */
    data class Resource(val bytes: ByteArray, val mimeType: String)

    /**
     * Returns the resource [absoluteUrl] names, or null when it is not in the archive (external,
     * missing, or over the caller's own ceiling).
     */
    fun interface Fetcher {
        fun fetch(absoluteUrl: String): Resource?
    }

    /** Refuses to inline a single resource larger than this; the URL is left for the server path. */
    const val MAX_RESOURCE_BYTES = 12L * 1024 * 1024

    /** Refuses to inline more than this into one document, for the same reason. */
    const val MAX_TOTAL_BYTES = 48L * 1024 * 1024

    /**
     * Rewrites [markup] — one document, or one continuous-reading fragment — resolving its
     * references against [baseUrl], the document's own load URL.
     */
    fun inline(markup: String, baseUrl: String, fetch: Fetcher): String {
        val budget = Budget(fetch)
        return EXTERNAL_TAG.replace(markup) { match -> rewriteTag(match.value, baseUrl, budget) }
    }

    /**
     * The spend-accounting for one document.
     *
     * Kept per call rather than per resource so the document ceiling is enforced across all of its
     * plates: the tenth image of a chapter is not refused because it is large, but because the
     * chapter is.
     */
    private class Budget(private val fetch: Fetcher) {
        private var spent = 0L

        /** The resource as a `data:` URI, or null to leave the reference alone. */
        fun dataUri(absoluteUrl: String): String? {
            // Only the archive's own files are inlined: an external image is the publisher's
            // business, the engine fetches it normally, and copying it in would both bloat the
            // document and pin content the reader never asked to download.
            if (!EpubArchiveUrls.isArchiveUrl(absoluteUrl)) return null
            if (spent >= MAX_TOTAL_BYTES) return null
            val resource = fetchOrNull(absoluteUrl) ?: return null
            if (resource.bytes.size > MAX_RESOURCE_BYTES) return null
            spent += resource.bytes.size
            return "data:${resource.mimeType};base64," +
                java.util.Base64.getEncoder().encodeToString(resource.bytes)
        }

        /** The resource's bytes as UTF-8 text, without spending the budget a second time. */
        fun textOf(absoluteUrl: String): String? {
            if (!EpubArchiveUrls.isArchiveUrl(absoluteUrl)) return null
            if (spent >= MAX_TOTAL_BYTES) return null
            val resource = fetchOrNull(absoluteUrl) ?: return null
            if (resource.bytes.size > MAX_RESOURCE_BYTES) return null
            spent += resource.bytes.size
            return String(resource.bytes, Charsets.UTF_8)
        }

        private fun fetchOrNull(absoluteUrl: String): Resource? = try {
            fetch.fetch(absoluteUrl)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * The tags whose references are worth inlining.
     *
     * `<img>` and SVG's `<image>` are the illustrations; `<link rel="stylesheet">` is the
     * publisher's own typography, without which drop caps and ruby rules quietly vanish. Anything
     * else — scripts, video, icons — is left to the serving path.
     */
    private val EXTERNAL_TAG = Regex("""<img\b[^>]*>|<image\b[^>]*>|<link\b[^>]*>""", RegexOption.IGNORE_CASE)

    private fun rewriteTag(tag: String, baseUrl: String, budget: Budget): String = when {
        tag.startsWith("<img", ignoreCase = true) -> rewriteAttribute(tag, baseUrl, budget, setOf("src"))
        tag.startsWith("<image", ignoreCase = true) -> rewriteAttribute(tag, baseUrl, budget, setOf("xlink:href", "href"))
        tag.startsWith("<link", ignoreCase = true) -> inlineStylesheet(tag, baseUrl, budget)
        else -> tag
    }

    /**
     * Rewrites the first of [names] found anywhere in [tag] to its `data:` URI.
     *
     * Attributes are matched by name rather than position — publishers order them every which way —
     * and the match is replaced in place so the rest of the tag (`class`, `alt`, `style`) is
     * untouched.
     *
     * A reference that cannot be **inlined** but is still this book's file — a plate over a
     * ceiling, an archive entry the fetch could not read, a budget already spent — is re-spelled
     * **absolute** rather than left relative. That matters for the continuous window: an appended
     * fragment's markup is not given a base of its own, so a relative `src` left in it would
     * resolve against the *live document's* directory and silently point one or two folders away
     * from the file. The absolute spelling is the same URL the engine would have requested for the
     * main document, which the serving path already answers; the stream still wins the memory
     * argument, and correctness stops depending on where the markup happens to land.
     *
     * A value that cannot be resolved at all — a bare fragment, malformed — leaves the whole tag
     * alone: the reference then behaves exactly as it did before this existed, rather than
     * half-rewritten. So does an external URL, which is not this book's file to re-point.
     */
    private fun rewriteAttribute(tag: String, baseUrl: String, budget: Budget, names: Set<String>): String {
        val attribute = ATTRIBUTE.findAll(tag).firstOrNull {
            it.groupValues[1].lowercase() in names
        } ?: return tag
        val value = attribute.valueOrNull() ?: return tag
        val absolute = resolve(baseUrl, value) ?: return tag
        val data = budget.dataUri(absolute)
            ?: absolute.takeIf { EpubArchiveUrls.isArchiveUrl(it) }
            ?: return tag
        return tag.replaceRange(attribute.range, "${attribute.groupValues[1]}=\"$data\"")
    }

    /**
     * Replaces a stylesheet `<link>` with the stylesheet itself, every `url()` inside it inlined.
     *
     * A `<style>` element, because the reference is about to disappear along with the `<link>` that
     * carried it — there would be nothing left to fetch it with. The CSS resolves its own
     * references against the file's own URL, which is what `url(../fonts/x.woff2)` inside a
     * stylesheet has always meant.
     */
    private fun inlineStylesheet(tag: String, baseUrl: String, budget: Budget): String {
        val rel = ATTRIBUTE.findAll(tag).firstOrNull {
            it.groupValues[1].equals("rel", ignoreCase = true)
        }?.valueOrNull() ?: return tag
        if (!rel.lowercase().contains("stylesheet")) return tag
        val href = ATTRIBUTE.findAll(tag).firstOrNull {
            it.groupValues[1].equals("href", ignoreCase = true)
        }?.valueOrNull() ?: return tag

        val cssUrl = resolve(baseUrl, href) ?: return tag
        val css = budget.textOf(cssUrl) ?: return tag
        val inlined = CSS_URL.replace(css) { match ->
            // External url()s (a CDN font, a web background) are the publisher's business.
            val absolute = resolve(cssUrl, match.groupValues[1]) ?: return@replace match.value
            val data = budget.dataUri(absolute) ?: return@replace match.value
            "url($data)"
        }
        return "<style>$inlined</style>"
    }

    /**
     * `url(...)` in CSS: optional quotes, value captured up to the closing paren.
     *
     * The quoted and unquoted forms are one pattern because they share the terminator; `url("a)b")`
     * is malformed CSS and not worth a second pattern for.
     */
    private val CSS_URL = Regex("""url\(\s*['"]?([^'")]+?)['"]?\s*\)""", RegexOption.IGNORE_CASE)

    /**
     * An attribute in a tag: name, and value with its quotes already stripped.
     *
     * Two value groups — double- and single-quoted — because one alternation cannot capture both
     * into a single group; [valueOrNull] picks whichever participated.
     */
    private val ATTRIBUTE = Regex("""([A-Za-z_:][-A-Za-z0-9_:.]*)\s*=\s*(?:"([^"]*)"|'([^']*)')""")

    private fun MatchResult.valueOrNull(): String? = groups[2]?.value ?: groups[3]?.value

    /**
     * Resolves [value] against [baseUrl] to an absolute URL, or null when it cannot be.
     *
     * [URI] rather than hand-rolled joining: relative references are RFC 3986 and the platform
     * implements the merge-and-normalise rules the engine itself uses, so the spelling produced
     * here is the spelling the engine would have requested on its own — including the
     * percent-encoding, which the serving path decodes on its side of the door.
     */
    private fun resolve(baseUrl: String, value: String): String? {
        if (value.isBlank()) return null
        if (value.startsWith("#")) return null
        return try {
            URI(baseUrl).resolve(value.trim()).toString()
        } catch (_: Exception) {
            null
        }
    }
}
// KMK <--
