package eu.kanade.tachiyomi.source.online.all

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.net.toUri
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.newCachelessCallWithProgress
import eu.kanade.tachiyomi.source.PagePreviewInfo
import eu.kanade.tachiyomi.source.PagePreviewPage
import eu.kanade.tachiyomi.source.PagePreviewSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.MetadataMangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.model.copy
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.source.online.MetadataSource
import eu.kanade.tachiyomi.source.online.NamespaceSource
import eu.kanade.tachiyomi.source.online.UrlImportableSource
import eu.kanade.tachiyomi.util.asJsoup
import exh.debug.DebugToggles
import exh.eh.EHTags
import exh.eh.EHentaiUpdateHelper
import exh.eh.EHentaiUpdateWorkerConstants
import exh.eh.GalleryEntry
import exh.log.xLogD
import exh.log.xLogI
import exh.metadata.MetadataUtil
import exh.metadata.metadata.EHentaiSearchMetadata
import exh.metadata.metadata.EHentaiSearchMetadata.Companion.CENSORSHIP_STATUS_CENSORED
import exh.metadata.metadata.EHentaiSearchMetadata.Companion.CENSORSHIP_STATUS_DECENSORED
import exh.metadata.metadata.EHentaiSearchMetadata.Companion.CENSORSHIP_STATUS_FULL
import exh.metadata.metadata.EHentaiSearchMetadata.Companion.CENSORSHIP_STATUS_MOSAIC
import exh.metadata.metadata.EHentaiSearchMetadata.Companion.CENSORSHIP_STATUS_UNCENSORED
import exh.metadata.metadata.EHentaiSearchMetadata.Companion.EH_CENSORSHIP_NAMESPACE
import exh.metadata.metadata.EHentaiSearchMetadata.Companion.EH_GENRE_NAMESPACE
import exh.metadata.metadata.EHentaiSearchMetadata.Companion.EH_META_NAMESPACE
import exh.metadata.metadata.EHentaiSearchMetadata.Companion.EH_UPLOADER_NAMESPACE
import exh.metadata.metadata.EHentaiSearchMetadata.Companion.EH_VISIBILITY_NAMESPACE
import exh.metadata.metadata.EHentaiSearchMetadata.Companion.TAG_TYPE_LIGHT
import exh.metadata.metadata.EHentaiSearchMetadata.Companion.TAG_TYPE_NORMAL
import exh.metadata.metadata.EHentaiSearchMetadata.Companion.TAG_TYPE_WEAK
import exh.metadata.metadata.RaisedSearchMetadata
import exh.metadata.metadata.RaisedSearchMetadata.Companion.TAG_TYPE_VIRTUAL
import exh.metadata.metadata.RaisedSearchMetadata.Companion.toGenreString
import exh.metadata.metadata.base.RaisedTag
import exh.source.ExhPreferences
import exh.ui.login.EhLoginActivity
import exh.util.UriFilter
import exh.util.UriGroup
import exh.util.asObservableWithAsyncStacktrace
import exh.util.dropBlank
import exh.util.ignore
import exh.util.nullIfBlank
import exh.util.trimAll
import exh.util.trimOrNull
import exh.util.urlImportFetchSearchManga
import exh.util.urlImportFetchSearchMangaSuspend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.CacheControl
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import rx.Observable
import tachiyomi.core.common.util.lang.runAsObservable
import tachiyomi.core.common.util.lang.withIOContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URLEncoder
import java.time.ZoneOffset
import java.time.ZonedDateTime

// TODO Consider gallery updating when doing tabbed browsing
// TODO: Decompose this god class (~1500 lines, implements HttpSource + MetadataSource +
//  UrlImportableSource + NamespaceSource + PagePreviewSource). Cohesive split candidates:
//  favorites sync API client, search/page parsing, metadata raising, login/cookie handling.
@AssistedInject
class EHentai(
    @Assisted override val id: Long,
    @Assisted val exh: Boolean,
    @Assisted override val lang: String = "all",
    private val context: Context,
    private val exhPreferences: ExhPreferences,
    private val updateHelper: EHentaiUpdateHelper,
) : HttpSource(),
    // KMK -->
    EhBasedSource,
    // KMK <--
    MetadataSource<EHentaiSearchMetadata, Document>,
    UrlImportableSource,
    NamespaceSource,
    PagePreviewSource {
    override val metaClass = EHentaiSearchMetadata::class

    constructor(
        id: Long,
        exh: Boolean,
        context: Context,
        lang: String = "all",
    ) : this(id, exh, lang, context, mihon.app.di.globalAppGraph.exhPreferences, mihon.app.di.globalAppGraph.eHentaiUpdateHelper)

    private val domain: String
        get() = if (exh) {
            "exhentai.org"
        } else {
            "e-hentai.org"
        }

    override val baseUrl: String
        get() = "https://$domain"

    override val supportsLatest = true

    // KMK -->
    private val ehLang = languageMapping[lang]

    // true if lang is a "natural human language"
    private fun isLangNatural(): Boolean = lang !in listOf("none", "other", "all")

    private fun languageTag(): String {
        return "language:$ehLang"
    }
    // KMK <--

    @AssistedFactory
    interface Factory {
        fun create(
            @Assisted id: Long,
            @Assisted exh: Boolean,
            @Assisted lang: String,
        ): EHentai
    }

    /**
     * Gallery list entry
     */
    data class ParsedManga(val fav: Int, val manga: SManga, val metadata: EHentaiSearchMetadata)

    private fun extendedGenericMangaParse(doc: Document) = with(doc) {
        val parsedMangas = select(".itg tr, .itg > tbody > tr").filter { element ->
            element.selectFirst("th") == null && element.selectFirst(".itd") == null
        }.mapNotNull { body ->
            val isExtended = body.selectFirst(".gl1e") != null
            val isThumbnail = body.selectFirst(".gl1t") != null
            val thumbnailElement = when {
                isExtended -> body.selectFirst(".gl1e img")
                isThumbnail -> body.selectFirst(".gl1t img, .gl3t img")
                else -> body.selectFirst(".gl2c .glthumb img, .gl1c img, .gl2c img")
            } ?: body.selectFirst("img[src*='ehgt.org'], img[data-src*='ehgt.org']") ?: return@mapNotNull null
            val column2 = body.selectFirst(".gl3e, .gl2c, .gl3t, .gl2m")
                ?: body
            val linkElement = when {
                isExtended -> body.selectFirst(".gl2e > div > a, .gl4e a, .gl1e a")
                isThumbnail -> body.selectFirst(".gl1t a, .gl3t a, .gl2t a")
                else -> body.selectFirst(".gl3c > a, .gl3c a[href*='/g/'], .glname a")
            } ?: body.selectFirst("a[href*='/g/']") ?: return@mapNotNull null
            val infoElement = body.selectFirst(".gl3e")

            val favElement = column2.children().find { it.attr("style").contains("border-color", ignoreCase = true) }
                ?: body.selectFirst("[style*='border-color']")
            val parsedTags = mutableListOf<RaisedTag>()

            ParsedManga(
                fav = run {
                    val style = favElement?.attr("style") ?: ""
                    val hex = Regex("border-color\\s*:\\s*#?([0-9a-fA-F]{3,6})").find(style)?.groupValues?.getOrNull(1)?.lowercase()?.take(3) ?: style.substringAfter("#", "").take(3).lowercase()
                    FAVORITES_BORDER_HEX_COLORS.indexOf(hex)
                },
                manga = SManga.create().apply {
                    val rawTitle = thumbnailElement.attr("title").ifBlank { thumbnailElement.attr("alt") }
                    val linkTitle = linkElement.selectFirst(".glink")?.text()?.trimOrNull()
                    title = when {
                        rawTitle.isNotBlank() -> rawTitle
                        !linkTitle.isNullOrBlank() -> linkTitle
                        else -> linkElement.text().trim().ifBlank { return@mapNotNull null }
                    }
                    url = EHentaiSearchMetadata.normalizeUrl(linkElement.attr("href"))
                    thumbnail_url = thumbnailElement.attr("src").ifBlank { thumbnailElement.attr("data-src") }.ifBlank { thumbnailElement.attr("data-original") }.ifBlank { thumbnailElement.attr("src") }

                    if (isExtended || infoElement != null) {
                        val tagContainer = linkElement.selectFirst(".gl4e") ?: linkElement
                        tagContainer.select("tr").forEach { row ->
                            val namespace = row.selectFirst(".tc")?.text()?.removeSuffix(":")?.trim() ?: return@forEach
                            row.select("div.gt, div.gtl, div.gtw").forEach { element ->
                                parsedTags.add(
                                    RaisedTag(
                                        namespace,
                                        element.text().trim(),
                                        when {
                                            element.hasClass("gtl") -> TAG_TYPE_LIGHT
                                            element.hasClass("gtw") -> TAG_TYPE_WEAK
                                            else -> TAG_TYPE_NORMAL
                                        },
                                    ),
                                )
                            }
                            if (row.select("div.gt, div.gtl, div.gtw").isEmpty()) {
                                row.select("div").forEach { element ->
                                    val elemId = element.id()
                                    val t = element.text().trim()
                                    if (t.isNotBlank() && elemId.startsWith("td_")) {
                                        val ns = elemId.substringAfter("td_").substringBefore(":")
                                        parsedTags.add(RaisedTag(ns, t, TAG_TYPE_NORMAL))
                                    }
                                }
                            }
                        }
                    } else {
                        val tagElements = body.select("div.gt[title], div.gtl[title], div.gtw[title]")
                        if (tagElements.isNotEmpty()) {
                            tagElements.forEach { element ->
                                val titleAttr = element.attr("title")
                                val namespace = titleAttr.substringBefore(":").trimOrNull() ?: "misc"
                                val name = titleAttr.substringAfter(":", "").trim().ifBlank { element.text().trim() }
                                if (name.isNotBlank()) {
                                    parsedTags += RaisedTag(
                                        namespace,
                                        name,
                                        when {
                                            element.hasClass("gtl") -> TAG_TYPE_LIGHT
                                            element.hasClass("gtw") -> TAG_TYPE_WEAK
                                            else -> TAG_TYPE_NORMAL
                                        },
                                    )
                                }
                            }
                        } else {
                            val fallbackTags = linkElement.select("div.gt")
                            fallbackTags.forEach { element ->
                                if (element.className() == "gt") {
                                    val namespace = element.attr("title").substringBefore(":").trimOrNull() ?: "misc"
                                    parsedTags += RaisedTag(
                                        namespace,
                                        element.attr("title").substringAfter(":").trim(),
                                        TAG_TYPE_NORMAL,
                                    )
                                }
                            }
                        }
                    }

                    genre = parsedTags.toGenreString()
                },
                metadata = EHentaiSearchMetadata().apply {
                    tags += parsedTags

                    censorshipStatus = detectCensorshipStatus()

                    if (isExtended && infoElement != null) {
                        val gl3e = infoElement
                        genre = EHentaiElementParsers.getGenre(gl3e.selectFirst(".cn") ?: gl3e.children().firstOrNull())
                        datePosted = EHentaiElementParsers.getDateTag(EHentaiElementParsers.findDateElement(gl3e) ?: gl3e.children().getOrNull(1))
                        averageRating = EHentaiElementParsers.getRating(gl3e.selectFirst(".ir") ?: gl3e)
                        uploader = EHentaiElementParsers.getUploader(gl3e.selectFirst("a[href*='/uploader/']") ?: gl3e.children().find { it.selectFirst("a[href*='/uploader/']") != null })
                        length = EHentaiElementParsers.getPageCount(EHentaiElementParsers.findPagesElement(gl3e) ?: gl3e.children().find { it.text().contains("pages", true) })
                    } else {
                        genre = EHentaiElementParsers.getGenre(body.selectFirst(".gl1c .cn") ?: body.selectFirst(".gl1c div") ?: body.selectFirst(".cn"))
                        val info = body.selectFirst(".gl2c")
                        val extraInfo = body.selectFirst(".gl4c")
                        if (info != null && extraInfo != null) {
                            datePosted = EHentaiElementParsers.getDateTag(EHentaiElementParsers.findDateElement(info) ?: info.selectFirst("div:containsOwn(202)"))
                            averageRating = EHentaiElementParsers.getRating(info.selectFirst(".ir") ?: extraInfo.selectFirst(".ir") ?: body.selectFirst(".ir"))
                            val uploaderEl = extraInfo.selectFirst("a[href*='/uploader/']") ?: extraInfo
                            uploader = EHentaiElementParsers.getUploader(uploaderEl)
                            length = EHentaiElementParsers.getPageCount(EHentaiElementParsers.findPagesElement(extraInfo) ?: extraInfo)
                        } else if (isThumbnail) {
                            val thumbContainer = body
                            datePosted = EHentaiElementParsers.getDateTag(EHentaiElementParsers.findDateElement(thumbContainer))
                            averageRating = EHentaiElementParsers.getRating(thumbContainer.selectFirst(".ir"))
                            uploader = EHentaiElementParsers.getUploader(thumbContainer.selectFirst("a[href*='/uploader/']"))
                            length = EHentaiElementParsers.getPageCount(EHentaiElementParsers.findPagesElement(thumbContainer))
                        } else {
                            datePosted = EHentaiElementParsers.getDateTag(EHentaiElementParsers.findDateElement(body))
                            averageRating = EHentaiElementParsers.getRating(body.selectFirst(".ir"))
                            uploader = EHentaiElementParsers.getUploader(body.selectFirst("a[href*='/uploader/']"))
                            length = EHentaiElementParsers.getPageCount(EHentaiElementParsers.findPagesElement(body))
                        }
                    }
                },
            )
        }.ifEmpty {
            selectFirst(".searchwarn")?.let { throw Exception(it.text()) }
            emptyList()
        }

        val parsedLocation = doc.location().toHttpUrlOrNull()
        val isReversed = parsedLocation != null && parsedLocation.queryParameterNames.contains(REVERSE_PARAM)

        val hasNextPage = if (isReversed) {
            (select(".searchnav a, #unext, #dnext, #uprev, #dprev").any { "prev" in it.attr("href") }) ||
                (html().contains("var prevurl=\"") && !html().contains("var prevurl=\"\""))
        } else {
            val hasNextAnchor = select(".searchnav a, #unext, #dnext, #next, #unext, a:contains(Next)").any { a ->
                val href = a.attr("href")
                href.contains("next") || href.contains("?next=")
            }
            val scriptNext = Regex("var nexturl\\s*=\\s*\"([^\"]+)\"").find(html())?.groupValues?.getOrNull(1)?.isNotBlank() == true
            hasNextAnchor || scriptNext
        }
        val nextPage = if (parsedLocation?.pathSegments?.contains("toplist.php") == true) {
            ((parsedLocation.queryParameter("p")?.toLong() ?: 0) + 2).takeIf { it <= 200 }
        } else if (hasNextPage) {
            parsedMangas.let { if (isReversed) it.first() else it.last() }
                .manga
                .url
                .let { EHentaiSearchMetadata.galleryId(it).toLong() }
        } else {
            null
        }

        parsedMangas.let { if (isReversed) it.reversed() else it } to nextPage
    }

    /**
     * Parse a list of galleries
     */
    private fun genericMangaParse(
        response: Response,
    ) = extendedGenericMangaParse(response.asJsoup()).let { (parsedManga, nextPage) ->
        MetadataMangasPage(
            parsedManga.map { it.manga },
            nextPage != null,
            parsedManga.map { it.metadata },
            nextPage,
        )
    }

    suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
        throttleFunc: suspend () -> Unit,
    ): SMangaUpdate = supervisorScope {
        val mangaDetails = if (fetchDetails) async { getMangaDetails(manga) } else null
        val chapterDetails = if (fetchChapters) async { getChapterList(manga, throttleFunc) } else null

        SMangaUpdate(mangaDetails?.await() ?: manga, chapterDetails?.await() ?: chapters)
    }

    suspend fun getChapterList(manga: SManga, throttleFunc: suspend () -> Unit): List<SChapter> {
        var url = manga.url
        var doc: Document
        val parentChain = mutableListOf<Pair<Int, String>>()

        while (true) {
            val gid = EHentaiSearchMetadata.galleryId(url).toInt()
            val cachedParent = updateHelper.parentLookupTable.get(gid)

            if (cachedParent == null) {
                throttleFunc()
                doc = client.newCall(exGet(baseUrl + url)).awaitSuccess().asJsoup()

                val parentLink = doc.select("#gdd .gdt1").find { el ->
                    el.text().lowercase() == "parent:"
                }!!.nextElementSibling()!!.selectFirst("a")?.attr("href")

                if (parentLink != null) {
                    val parentGid = EHentaiSearchMetadata.galleryId(parentLink).toInt()
                    val parentToken = EHentaiSearchMetadata.galleryToken(parentLink)

                    updateHelper.parentLookupTable.put(
                        gid,
                        GalleryEntry(parentGid.toString(), parentToken),
                    )

                    parentChain.add(gid to url)
                    url = EHentaiSearchMetadata.normalizeUrl(parentLink)
                } else {
                    break
                }
            } else {
                xLogD("Parent cache hit: %s!", gid)
                parentChain.add(gid to url)
                url = EHentaiSearchMetadata.idAndTokenToUrl(
                    cachedParent.gId,
                    cachedParent.gToken,
                )
            }
        }

        val location = doc.location()
        val self = SChapter(
            url = EHentaiSearchMetadata.normalizeUrl(location),
            name = "v1: " + doc.selectFirst("#gn")?.text().orEmpty(),
            chapter_number = 1f,
            date_upload = try {
                ZonedDateTime.parse(
                    doc.select("#gdd .gdt1").find { el ->
                        el.text().lowercase() == "posted:"
                    }?.nextElementSibling()?.text().orEmpty(),
                    MetadataUtil.EX_DATE_FORMAT.withZone(ZoneOffset.UTC),
                )?.toInstant()?.toEpochMilli() ?: 0L
            } catch (_: Exception) {
                0L
            },
            scanlator = EHentaiSearchMetadata.galleryId(location),
        )

        val newDisplay = doc.select("#gnd a[href*='/g/'], #gd2 a[href*='/g/'], .gnd a[href*='/g/']").filter { it.attr("href").contains("/g/") }

        return if (DebugToggles.INCLUDE_ONLY_ROOT_WHEN_LOADING_EXH_VERSIONS.enabled) {
            listOf(self)
        } else {
            val versionChapters = newDisplay.mapIndexedNotNull { index, newGallery ->
                val link = newGallery.attr("href").nullIfBlank() ?: return@mapIndexedNotNull null
                if (!link.contains("/g/")) return@mapIndexedNotNull null
                val name = newGallery.text().trim().ifBlank { return@mapIndexedNotNull null }
                val posted = (newGallery.nextSibling() as? TextNode)?.text()?.removePrefix(", added ")?.trim().orEmpty()
                SChapter(
                    url = EHentaiSearchMetadata.normalizeUrl(link),
                    name = "v${index + 2}: $name",
                    chapter_number = index + 2f,
                    date_upload = try {
                        if (posted.isBlank()) {
                            0L
                        } else {
                            ZonedDateTime.parse(
                                posted,
                                MetadataUtil.EX_DATE_FORMAT.withZone(ZoneOffset.UTC),
                            ).toInstant().toEpochMilli()
                        }
                    } catch (_: Exception) {
                        0L
                    },
                    scanlator = EHentaiSearchMetadata.galleryId(link),
                )
            }
            versionChapters.reversed() + self
        }
    }

    @Deprecated("Use the combined suspend API instead", replaceWith = ReplaceWith("getMangaUpdate"))
    @Suppress("DEPRECATION")
    override fun fetchChapterList(manga: SManga) = fetchChapterList(manga) {}

    @Deprecated("Use the combined suspend API instead", replaceWith = ReplaceWith("getMangaUpdate"))
    fun fetchChapterList(manga: SManga, throttleFunc: suspend () -> Unit) = runAsObservable {
        getChapterList(manga, throttleFunc)
    }

    @Deprecated("Use the suspend API instead", replaceWith = ReplaceWith("getPageList"))
    override fun fetchPageList(
        chapter: SChapter,
    ): Observable<List<Page>> = fetchChapterPage(chapter, baseUrl + chapter.url)
        .map {
            it.mapIndexed { i, s ->
                Page(i, s)
            }
        }!!

    private fun fetchChapterPage(
        chapter: SChapter,
        np: String,
        pastUrls: List<String> = emptyList(),
    ): Observable<List<String>> {
        val urls = ArrayList(pastUrls)
        return chapterPageCall(np).flatMap {
            val jsoup = it.asJsoup()
            urls += parseChapterPage(jsoup)
            val nextUrl = nextPageUrl(jsoup)
            if (nextUrl != null) {
                fetchChapterPage(chapter, nextUrl, urls)
            } else {
                Observable.just(urls)
            }
        }
    }

    private fun parseChapterPage(response: Element) = with(response) {
        val gdtm = select(".gdtm a").mapNotNull {
            val alt = it.child(0).attr("alt").toIntOrNull() ?: return@mapNotNull null
            val href = it.attr("href").nullIfBlank() ?: return@mapNotNull null
            Pair(alt, href)
        }
        val gdt = select("#gdt a").mapNotNull {
            val title = it.selectFirst("div[title]")?.attr("title") ?: it.child(0).attr("title")
            val num = title.removePrefix("Page ").substringBefore(":").trim().toIntOrNull() ?: return@mapNotNull null
            val href = it.attr("href").nullIfBlank() ?: return@mapNotNull null
            Pair(num, href)
        }
        (gdtm + gdt).sortedBy(Pair<Int, String>::first).map { it.second }.distinct()
    }

    @Suppress("DEPRECATION")
    private fun chapterPageCall(np: String): Observable<Response> {
        return client.newCall(chapterPageRequest(np)).asObservableSuccess()
    }
    private fun chapterPageRequest(np: String): Request {
        return exGet(url = np, additionalHeaders = headers)
    }

    private fun nextPageUrl(element: Element): String? {
        element.select("a[onclick=return false]").lastOrNull()?.let {
            if (it.text().trim() == ">") return it.attr("href").nullIfBlank()
        }
        element.select("table.ptt a, table.ptb a, .ptt a, .ptb a").lastOrNull()?.let { last ->
            if (last.text().trim() == ">") return last.attr("href").nullIfBlank()
        }
        element.select("td[onclick] a").lastOrNull()?.let { last ->
            if (last.text().trim() == ">") return last.attr("href").nullIfBlank()
        }
        return null
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun popularMangaRequest(page: Int) =
        // KMK -->
        if (isLangNatural()) {
            exGet("$baseUrl/?f_search=${languageTag()}&f_srdd=5&f_sr=on", page)
        } else {
            if (page > 1) {
                exGet("$baseUrl/?f_srdd=5&f_sr=on", page - 1)
            } else {
                // KMK <--
                exGet("$baseUrl/popular")
            }
        }

    private fun <T : MangasPage> Observable<T>.checkValid(): Observable<MangasPage> = map {
        it.checkValid()
    }

    private fun <T : MangasPage> T.checkValid(): MangasPage {
        if (!exh || mangas.isNotEmpty()) return this

        val igneous = exhPreferences.igneousVal().get()
        val memberId = exhPreferences.memberIdVal().get()
        val passHash = exhPreferences.passHashVal().get()

        // Check for missing authentication cookies
        if (memberId.isBlank() || passHash.isBlank()) {
            throw Exception(
                "ExHentai credentials not found. Please log in via Settings → E-Hentai/ExHentai Login",
            )
        }

        // Check for invalid igneous cookie
        if (igneous.equals("mystery", true) || igneous.isBlank()) {
            throw Exception(
                "Invalid or missing igneous cookie. Please re-login or provide a valid igneous cookie in Settings → E-Hentai/ExHentai Login",
            )
        }

        return this
    }

    @Deprecated("Use the suspend API instead", replaceWith = ReplaceWith("getLatestUpdates"))
    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> {
        @Suppress("DEPRECATION")
        return super.fetchLatestUpdates(page).checkValid()
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        return super.getLatestUpdates(page).checkValid()
    }

    @Deprecated("Use the suspend API instead", replaceWith = ReplaceWith("getPopularManga"))
    override fun fetchPopularManga(page: Int): Observable<MangasPage> {
        @Suppress("DEPRECATION")
        return super.fetchPopularManga(page).checkValid()
    }

    override suspend fun getPopularManga(page: Int): MangasPage {
        return super.getPopularManga(page).checkValid()
    }

    // Support direct URL importing
    @Deprecated("Use the suspend API instead", replaceWith = ReplaceWith("getSearchManga"))
    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> =
        urlImportFetchSearchManga(context, query) {
            @Suppress("DEPRECATION")
            super.fetchSearchManga(page, query, filters)
                .checkValid()
                .map { sortMangasByCensorship(it, filters) }
        }

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        return urlImportFetchSearchMangaSuspend(context, query) {
            super.getSearchManga(page, query, filters).checkValid()
        }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val toplist = ToplistOption.entries[filters.firstNotNullOfOrNull { (it as? ToplistOptions)?.state } ?: 0]
        if (toplist != ToplistOption.NONE) {
            val uri = "https://e-hentai.org".toUri().buildUpon()
            uri.appendPath("toplist.php")
            uri.appendQueryParameter("tl", toplist.index.toString())
            uri.appendQueryParameter("p", (page - 1).toString())

            return exGet(url = uri.toString())
        }

        val uri = baseUrl.toUri().buildUpon()
        val isReverseFilterEnabled = filters.any { it is ReverseFilter && it.state }
        val jumpSeekValue = filters.firstNotNullOfOrNull { (it as? JumpSeekFilter)?.state?.nullIfBlank() }

        uri.appendQueryParameter("f_apply", "Apply+Filter")
        uri.appendQueryParameter("f_search", buildSearchQuery(query, filters))
        filters.forEach {
            if (it is UriFilter) it.addToUri(uri)
        }
        // Reverse search results on filter
        if (isReverseFilterEnabled) {
            uri.appendQueryParameter(REVERSE_PARAM, "on")
        }
        if (jumpSeekValue != null && page == 1) {
            if (
                MATCH_SEEK_REGEX.matches(jumpSeekValue) ||
                (
                    MATCH_YEAR_REGEX.matches(jumpSeekValue) &&
                        jumpSeekValue.toIntOrNull()?.let {
                            it in 2007..2099
                        } == true
                    )
            ) {
                uri.appendQueryParameter("seek", jumpSeekValue)
            } else if (MATCH_JUMP_REGEX.matches(jumpSeekValue)) {
                uri.appendQueryParameter("jump", jumpSeekValue)
            }
        }

        return exGet(
            url = uri.toString(),
            next = if (!isReverseFilterEnabled) page else null,
            prev = if (isReverseFilterEnabled) page else null,
        )
    }

    private fun buildSearchQuery(query: String, filters: FilterList): String {
        val censorshipQuery = filters.firstNotNullOfOrNull { (it as? CensorshipFilter)?.query()?.nullIfBlank() }
        return listOf(
            query.trim(),
            combineQuery(filters),
            censorshipQuery.orEmpty(),
        ).filter { it.isNotBlank() }
            .joinToString(" ")
    }

    private fun sortMangasByCensorship(page: MangasPage, filters: FilterList): MangasPage {
        val selection = filters.filterIsInstance<CensorshipSort>().firstOrNull()?.state
        if (selection == null || selection.index == 0 || page !is MetadataMangasPage) return page

        val ordered = page.mangas.zip(page.mangasMetadata)
            .sortedWith(compareBy { censorshipRank(it.second) })
        val result = if (selection.ascending) ordered else ordered.reversed()

        return page.copy(
            mangas = result.map { it.first },
            mangasMetadata = result.map { it.second },
        )
    }

    private fun censorshipRank(metadata: RaisedSearchMetadata): Int {
        return when ((metadata as? EHentaiSearchMetadata)?.censorshipStatus?.lowercase()) {
            CENSORSHIP_STATUS_DECENSORED, CENSORSHIP_STATUS_MOSAIC, CENSORSHIP_STATUS_FULL -> 1
            CENSORSHIP_STATUS_UNCENSORED -> 2
            else -> 0
        }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun latestUpdatesRequest(page: Int) =
        // KMK -->
        if (isLangNatural()) {
            exGet("$baseUrl/?f_search=${languageTag()}", page)
        } else {
            // KMK <--
            exGet(baseUrl, page)
        }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun popularMangaParse(response: Response) = genericMangaParse(response)

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun searchMangaParse(response: Response) = genericMangaParse(response)

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun latestUpdatesParse(response: Response) = genericMangaParse(response)

    private fun exGet(
        url: String,
        next: Int? = null,
        prev: Int? = null,
        additionalHeaders: Headers? = null,
        cacheControl: CacheControl? = null,
    ): Request {
        return GET(
            when {
                next != null && next > 1 -> addParam(url, "next", next.toString())
                prev != null && prev > 0 -> addParam(url, "prev", prev.toString())
                else -> url
            },
            if (additionalHeaders != null) {
                val headers = headers.newBuilder()
                additionalHeaders.toMultimap().forEach { (t, u) ->
                    u.forEach {
                        headers.add(t, it)
                    }
                }
                headers.build()
            } else {
                headers
            },
        ).let {
            if (cacheControl == null) {
                it
            } else {
                it.newBuilder().cacheControl(cacheControl).build()
            }
        }
    }

    /**
     * Returns an observable with the updated details for a manga. Normally it's not needed to
     * override this method.
     *
     * @param manga the manga to be updated.
     */
    @Deprecated("Use the combined suspend API instead", replaceWith = ReplaceWith("getMangaUpdate"))
    @Suppress("DEPRECATION")
    override fun fetchMangaDetails(manga: SManga): Observable<SManga> {
        return client.newCall(mangaDetailsRequest(manga))
            .asObservableWithAsyncStacktrace()
            .flatMap { (stacktrace, response) ->
                if (response.isSuccessful) {
                    // Pull to most recent
                    val doc = response.asJsoup()
                    val newerGallery = doc.select("#gnd a[href*='/g/']").lastOrNull()?.takeIf { it.attr("href").contains("/g/") }
                    val pre = if (
                        newerGallery != null && DebugToggles.PULL_TO_ROOT_WHEN_LOADING_EXH_MANGA_DETAILS.enabled
                    ) {
                        manga.url = EHentaiSearchMetadata.normalizeUrl(newerGallery.attr("href"))
                        client.newCall(mangaDetailsRequest(manga))
                            .asObservableSuccess().map { it.asJsoup() }
                    } else {
                        Observable.just(doc)
                    }

                    pre.flatMap {
                        @Suppress("DEPRECATION")
                        parseToMangaCompletable(manga, it).andThen(
                            Observable.just(
                                manga.apply {
                                    initialized = true
                                },
                            ),
                        )
                    }
                } else {
                    response.close()

                    if (response.code == 404) {
                        throw GalleryNotFoundException(stacktrace)
                    } else {
                        throw Exception("HTTP error ${response.code}", stacktrace)
                    }
                }
            }
    }

    @Suppress("DEPRECATION")
    suspend fun getMangaDetails(manga: SManga): SManga {
        val exception = Exception("Async stacktrace")
        val response = client.newCall(mangaDetailsRequest(manga)).await()
        if (response.isSuccessful) {
            // Pull to most recent
            val doc = response.asJsoup()
            val newerGallery = doc.select("#gnd a[href*='/g/']").lastOrNull()?.takeIf { it.attr("href").contains("/g/") }
            val pre = if (
                newerGallery != null && DebugToggles.PULL_TO_ROOT_WHEN_LOADING_EXH_MANGA_DETAILS.enabled
            ) {
                val sManga = manga.copy(
                    url = EHentaiSearchMetadata.normalizeUrl(newerGallery.attr("href")),
                )
                client.newCall(mangaDetailsRequest(sManga)).awaitSuccess().asJsoup()
            } else {
                doc
            }
            return parseToManga(manga, pre).apply {
                initialized = true
            }
        } else {
            response.close()

            if (response.code == 404) {
                throw GalleryNotFoundException(exception)
            } else {
                throw Exception("HTTP error ${response.code}", exception)
            }
        }
    }

    override fun newMetaInstance() = EHentaiSearchMetadata()

    override suspend fun parseIntoMetadata(metadata: EHentaiSearchMetadata, input: Document) {
        with(metadata) {
            with(input) {
                val url = location()
                gId = EHentaiSearchMetadata.galleryId(url)
                gToken = EHentaiSearchMetadata.galleryToken(url)

                exh = this@EHentai.exh
                title = select("#gn").text().trimOrNull()

                altTitle = select("#gj").text().trimOrNull()

                thumbnailUrl = selectFirst("#gd1 div, #gd1 img, #gleft img")?.let { el ->
                    val style = el.attr("style").nullIfBlank()
                    when {
                        style != null && '(' in style && ')' in style -> style.substring(style.indexOf('(') + 1 until style.lastIndexOf(')')).trim().removeSurrounding("\"").removeSurrounding("'")
                        el.tagName() == "img" -> el.attr("src").nullIfBlank() ?: el.attr("data-src").nullIfBlank()
                        el.attr("src").isNotBlank() -> el.attr("src")
                        else -> style
                    }
                }?.nullIfBlank() ?: selectFirst("#gd1 div")?.attr("style")?.let {
                    if ('(' in it && ')' in it) it.substring(it.indexOf('(') + 1 until it.lastIndexOf(')')).trim() else null
                }
                genre = selectFirst("#gdc .cs, .cs")?.let { EHentaiElementParsers.getGenre(it) }

                uploader = selectFirst("#gdn a, #gdn")?.text()?.trimOrNull() ?: select("#gdn").text().trimOrNull()

                // Parse the table
                select("#gdd tr").forEach {
                    val left = it.select(".gdt1").text().trimOrNull()?.removeSuffix(":")?.lowercase() ?: return@forEach
                    val rightElement = it.selectFirst(".gdt2") ?: return@forEach
                    val right = rightElement.text().trimOrNull() ?: return@forEach
                    ignore {
                        when (left) {
                            "posted" -> {
                                val parsed = try {
                                    ZonedDateTime.parse(right, MetadataUtil.EX_DATE_FORMAT.withZone(ZoneOffset.UTC)).toInstant().toEpochMilli()
                                } catch (_: Exception) {
                                    EHentaiElementParsers.getDateTag(rightElement)
                                }
                                if (parsed != null) datePosted = parsed
                            }
                            "parent" -> parent = if (!right.equals("None", true)) {
                                rightElement.selectFirst("a")?.attr("href")?.nullIfBlank() ?: rightElement.child(0).attr("href").nullIfBlank()
                            } else {
                                null
                            }
                            "visible" -> visible = right.nullIfBlank()
                            "language" -> {
                                language = right.removeSuffix(TR_SUFFIX).trimOrNull()
                                translated = right.endsWith(TR_SUFFIX, true)
                            }
                            "file size" -> size = MetadataUtil.parseHumanReadableByteCount(right)?.toLong()
                            "length" -> {
                                val num = Regex("\\d+").find(right)?.value?.toIntOrNull()
                                if (num != null) length = num else length = right.removeSuffix("pages").trimOrNull()?.toInt()
                            }
                            "favorited" -> {
                                val num = Regex("\\d+").find(right)?.value?.toIntOrNull()
                                favorites = num
                            }
                        }
                    }
                }

                lastUpdateCheck = System.currentTimeMillis()
                if (datePosted != null &&
                    lastUpdateCheck - datePosted!! > EHentaiUpdateWorkerConstants.GALLERY_AGE_TIME
                ) {
                    aged = true
                    this@EHentai.xLogD("aged %s - too old", title)
                }

                // Parse ratings
                ignore {
                    val labelText = selectFirst("#rating_label")?.text() ?: select("#rating_label").text()
                    averageRating = labelText.removePrefix("Average:").trimOrNull()?.let {
                        Regex("[0-9]+\\.?[0-9]*").find(it)?.value?.toDoubleOrNull()
                    } ?: Regex("average_rating\\s*=\\s*([0-9.]+)").find(html())?.groupValues?.getOrNull(1)?.toDoubleOrNull()
                    val countText = selectFirst("#rating_count")?.text() ?: select("#rating_count").text()
                    ratingCount = countText.trimOrNull()?.let { Regex("\\d+").find(it)?.value?.toIntOrNull() }
                }

                // Parse tags
                tags.clear()
                select("#taglist tr").forEach {
                    val namespace = it.selectFirst(".tc")?.text()?.removeSuffix(":")?.trim() ?: it.select(".tc").text().removeSuffix(":").trim()
                    if (namespace.isBlank()) return@forEach
                    val tagDivs = it.select("div.gt, div.gtl, div.gtw, div[id^='td_']")
                    if (tagDivs.isNotEmpty()) {
                        tags += tagDivs.mapNotNull { element ->
                            val name = element.selectFirst("a")?.text()?.trimOrNull() ?: element.text().trimOrNull() ?: return@mapNotNull null
                            if (name.isBlank()) return@mapNotNull null
                            RaisedTag(
                                namespace,
                                name,
                                when {
                                    element.hasClass("gtl") -> TAG_TYPE_LIGHT
                                    element.hasClass("gtw") -> TAG_TYPE_WEAK
                                    else -> TAG_TYPE_NORMAL
                                },
                            )
                        }
                    } else {
                        tags += it.select("div").mapNotNull { element ->
                            val t = element.text().trim()
                            if (t.isBlank()) return@mapNotNull null
                            RaisedTag(
                                namespace,
                                t,
                                when {
                                    element.hasClass("gtl") -> TAG_TYPE_LIGHT
                                    element.hasClass("gtw") -> TAG_TYPE_WEAK
                                    else -> TAG_TYPE_NORMAL
                                },
                            )
                        }
                    }
                }

                // Add genre as virtual tag
                genre?.let {
                    tags += RaisedTag(EH_GENRE_NAMESPACE, it, TAG_TYPE_VIRTUAL)
                }
                val censorStatus = detectCensorshipStatus()
                censorshipStatus = censorStatus
                tags += RaisedTag(EH_CENSORSHIP_NAMESPACE, censorStatus, TAG_TYPE_VIRTUAL)
                if (aged) {
                    tags += RaisedTag(EH_META_NAMESPACE, "aged", TAG_TYPE_VIRTUAL)
                }
                uploader?.let {
                    tags += RaisedTag(EH_UPLOADER_NAMESPACE, it, TAG_TYPE_VIRTUAL)
                }
                visible?.let {
                    tags += RaisedTag(
                        EH_VISIBILITY_NAMESPACE,
                        it.substringAfter('(').substringBeforeLast(')'),
                        TAG_TYPE_VIRTUAL,
                    )
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    override suspend fun getImageUrl(page: Page): String {
        val imageUrlResponse = client.newCall(imageUrlRequest(page)).awaitSuccess()
        return realImageUrlParse(imageUrlResponse, page)
    }

    @Deprecated("Use the suspend API instead", replaceWith = ReplaceWith("getImageUrl"))
    @Suppress("DEPRECATION")
    override fun fetchImageUrl(page: Page): Observable<String> {
        return client.newCall(imageUrlRequest(page))
            .asObservableSuccess()
            .map { realImageUrlParse(it, page) }
    }

    private fun realImageUrlParse(response: Response, page: Page): String {
        with(response.asJsoup()) {
            val currentImage = getElementById("img")?.attr("src")
                ?: throw Exception("Image element not found on page")
            // Each press of the retry button will choose another server
            select("#loadfail").attr("onclick").nullIfBlank()?.let {
                page.url = addParam(page.url, "nl", it.substring(it.indexOf('\'') + 1 until it.lastIndexOf('\'')))
            }
            if (currentImage == "https://ehgt.org/g/509.gif") {
                throw Exception("Exceeded page quota")
            }
            return currentImage
        }
    }

    suspend fun fetchFavorites(): Pair<List<ParsedManga>, List<String>> {
        val favoriteUrl = "$baseUrl/favorites.php"
        val result = mutableListOf<ParsedManga>()
        var favNames: List<String>? = null

        val firstPageResponse = withIOContext {
            client.newCall(
                exGet(
                    favoriteUrl,
                    next = 1,
                    cacheControl = CacheControl.FORCE_NETWORK,
                ),
            ).await()
        }
        val firstDoc = firstPageResponse.asJsoup()
        val firstParsed = extendedGenericMangaParse(firstDoc)
        result += firstParsed.first

        favNames = firstDoc.select(".fp:not(.fps)").mapNotNull {
            it.child(2).text()
        }

        var currentPage = firstParsed.first.lastOrNull()?.manga?.url?.let {
            EHentaiSearchMetadata.galleryId(it)
        }?.toInt() ?: 0
        var hasNextPage = firstParsed.second != null

        while (hasNextPage) {
            val nextPage = currentPage
            if (nextPage <= 0) break

            val response = withIOContext {
                client.newCall(
                    exGet(
                        favoriteUrl,
                        next = nextPage,
                        cacheControl = CacheControl.FORCE_NETWORK,
                    ),
                ).await()
            }
            val doc = response.asJsoup()
            val parsed = extendedGenericMangaParse(doc)
            result += parsed.first

            currentPage = parsed.first.lastOrNull()?.manga?.url?.let {
                EHentaiSearchMetadata.galleryId(it)
            }?.toInt() ?: 0
            hasNextPage = parsed.second != null
        }

        return Pair(result, favNames)
    }

    fun spPref() = if (exh) {
        exhPreferences.exhSettingsProfile()
    } else {
        exhPreferences.ehSettingsProfile()
    }

    private fun rawCookies(sp: Int): Map<String, String> {
        val cookies: MutableMap<String, String> = mutableMapOf()
        if (exhPreferences.enableExhentai().get()) {
            cookies[EhLoginActivity.MEMBER_ID_COOKIE] = exhPreferences.memberIdVal().get()
            cookies[EhLoginActivity.PASS_HASH_COOKIE] = exhPreferences.passHashVal().get()
            cookies[EhLoginActivity.IGNEOUS_COOKIE] = exhPreferences.igneousVal().get()
            cookies["sp"] = sp.toString()

            val sessionKey = exhPreferences.exhSettingsKey().get()
            if (sessionKey.isNotBlank()) {
                cookies["sk"] = sessionKey
            }

            val sessionCookie = exhPreferences.exhSessionCookie().get()
            if (sessionCookie.isNotBlank()) {
                cookies["s"] = sessionCookie
            }

            val hathPerksCookie = exhPreferences.exhHathPerksCookies().get()
            if (hathPerksCookie.isNotBlank()) {
                cookies["hath_perks"] = hathPerksCookie
            }
        }

        // Session-less extended display mode (for users without ExHentai)
        cookies["sl"] = "dm_2"

        // Ignore all content warnings ("Offensive For Everyone")
        cookies["nw"] = "1"

        return cookies
    }

    fun cookiesHeader(cfCookies: Map<String, String> = emptyMap(), sp: Int = spPref().get()) = buildCookies(rawCookies(sp) + cfCookies)

    // Headers
    override fun headersBuilder() = super.headersBuilder().add("Cookie", cookiesHeader())

    private fun addParam(url: String, param: String, value: String) = url.toUri()
        .buildUpon()
        .appendQueryParameter(param, value)
        .toString()

    override val client =
        network.client.newBuilder()
            // .cookieJar(CookieJar.NO_COOKIES)
            // KMK -->
            .addNetworkInterceptor { chain ->
                // Keep only Cloudflare cookies from incoming cookies
                val cfCookies = chain.request().header("Cookie")?.split("; ")
                    ?.filter {
                        // Only accept cookie in form of name=value
                        if (!it.contains("=")) return@filter false
                        val name = it.substringBefore("=").trim().lowercase()
                        // KMK <--
                        name.startsWith("cf") || name.startsWith("_cf") || name.startsWith("__cf")
                    }
                    // KMK -->
                    ?.associate { it.substringBefore("=").trim() to it.substringAfter("=").trim() }
                val newCookies = cookiesHeader(cfCookies ?: emptyMap())
                xLogI("Overwritten Cookie: $newCookies")
                // KMK <--

                val newReq =
                    chain
                        .request()
                        .newBuilder()
                        .removeHeader("Cookie")
                        // KMK -->
                        .apply {
                            if (newCookies.isNotBlank()) {
                                addHeader("Cookie", newCookies)
                            }
                        }
                        // KMK <--
                        .build()

                chain.proceed(newReq)
            }
            .addInterceptor(ThumbnailPreviewInterceptor())
            .build()

    // Filters
    override fun getFilterList(): FilterList {
        return FilterList(
            Filter.Header("Note: Will ignore other parameters!"),
            ToplistOptions(),
            Filter.Separator(),
            AutoCompleteTags(),
            Watched(isEnabled = exhPreferences.exhWatchedListDefaultState().get()),
            GenreGroup(),
            CensorshipFilter(),
            CensorshipSort(),
            AdvancedGroup(),
            ReverseFilter(),
            JumpSeekFilter(),
            Filter.Header("Seek to specific date: YYYY, (YY)YY-MM, (YY)YY-MM-DD"),
            Filter.Header("or Jump by number of days/weeks/months/years: 7d, 4w, 12m, 10y"),
        )
    }

    class Watched(val isEnabled: Boolean) : Filter.CheckBox("Watched List", isEnabled), UriFilter {
        override fun addToUri(builder: Uri.Builder) {
            if (state) {
                builder.appendPath("watched")
            }
        }
    }

    enum class ToplistOption(val humanName: String, val index: Int) {
        NONE("None", 0),
        ALL_TIME("All time", 11),
        PAST_YEAR("Past year", 12),
        PAST_MONTH("Past month", 13),
        YESTERDAY("Yesterday", 15),
        ;

        override fun toString(): String {
            return humanName
        }
    }

    class ToplistOptions : Filter.Select<ToplistOption>(
        "Toplists",
        ToplistOption.entries.toTypedArray(),
    )

    class GenreOption(name: String, val genreId: Int) : Filter.CheckBox(name, false)
    class GenreGroup :
        Filter.Group<GenreOption>(
            "Genres",
            listOf(
                GenreOption("Dōjinshi", 2),
                GenreOption("Manga", 4),
                GenreOption("Artist CG", 8),
                GenreOption("Game CG", 16),
                GenreOption("Western", 512),
                GenreOption("Non-H", 256),
                GenreOption("Image Set", 32),
                GenreOption("Cosplay", 64),
                GenreOption("Asian Porn", 128),
                GenreOption("Misc", 1),
            ),
        ),
        UriFilter {
        override fun addToUri(builder: Uri.Builder) {
            val bits = state.fold(0) { acc, genre ->
                if (!genre.state) acc + genre.genreId else acc
            }
            builder.appendQueryParameter("f_cats", bits.toString())
        }
    }

    class CensorshipFilter : Filter.Select<String>(
        "Censorship",
        arrayOf("All", "Censored", "Decensored", "Mosaic", "Full", "Uncensored"),
    ) {
        fun query(): String = when (state) {
            1 -> "$EH_CENSORSHIP_NAMESPACE:$CENSORSHIP_STATUS_CENSORED"
            2 -> "$EH_CENSORSHIP_NAMESPACE:$CENSORSHIP_STATUS_DECENSORED"
            3 -> "$EH_CENSORSHIP_NAMESPACE:$CENSORSHIP_STATUS_MOSAIC"
            4 -> "$EH_CENSORSHIP_NAMESPACE:$CENSORSHIP_STATUS_FULL"
            5 -> "$EH_CENSORSHIP_NAMESPACE:$CENSORSHIP_STATUS_UNCENSORED"
            else -> ""
        }
    }

    class CensorshipSort : Filter.Sort(
        "Sort by Censorship",
        arrayOf("Default", "Censorship status"),
        Filter.Sort.Selection(0, true),
    )

    class AdvancedOption(
        name: String,
        val param: String,
        defValue: Boolean = false,
    ) : Filter.CheckBox(name, defValue), UriFilter {
        override fun addToUri(builder: Uri.Builder) {
            if (state) {
                builder.appendQueryParameter(param, "on")
            }
        }
    }

    open class PageOption(name: String, private val queryKey: String) : Filter.Text(name), UriFilter {
        override fun addToUri(builder: Uri.Builder) {
            if (state.isNotBlank()) {
                if (builder.build().getQueryParameters("f_sp").isEmpty()) {
                    builder.appendQueryParameter("f_sp", "on")
                }

                builder.appendQueryParameter(queryKey, state.trim())
            }
        }
    }

    private fun combineQuery(filters: FilterList): String {
        val stringBuilder = StringBuilder()
        val advSearch = filters.filterIsInstance<Filter.AutoComplete>().flatMap { filter ->
            filter.state.trimAll().dropBlank().mapNotNull { tag ->
                val split = tag.split(":").filterNot { it.isBlank() }
                if (split.size > 1) {
                    val namespace = split[0].removePrefix("-").removePrefix("~")
                    val exclude = split[0].startsWith("-")
                    val or = split[0].startsWith("~")

                    AdvSearchEntry(namespace to split[1], exclude, or)
                } else if (split.size == 1) {
                    val item = split.first()
                    val exclude = item.startsWith("-")
                    val or = item.startsWith("~")
                    AdvSearchEntry(null to item, exclude, or)
                } else {
                    null
                }
            }
        }

        advSearch.forEach { entry ->
            if (entry.exclude) stringBuilder.append("-")
            if (entry.or) stringBuilder.append("~")
            val namespace = entry.search.first?.let { "$it:" }.orEmpty()
            if (entry.search.second.contains(" ")) {
                stringBuilder.append(("""$namespace"${entry.search.second}$""""))
            } else {
                stringBuilder.append("$namespace${entry.search.second}$")
            }
            stringBuilder.append(" ")
        }

        return stringBuilder.toString().trim().also { xLogD(it) }
    }

    data class AdvSearchEntry(val search: Pair<String?, String>, val exclude: Boolean, val or: Boolean)

    class AutoCompleteTags :
        Filter.AutoComplete(
            name = "Tags",
            hint = "Search tags here (limit of 8)",
            values = EHTags.getNamespaces().map { "$it:" } + EHTags.getAllTags(),
            skipAutoFillTags = EHTags.getNamespaces().map { "$it:" },
            validPrefixes = listOf("-", "~"),
            state = emptyList(),
        )

    class MinPagesOption : PageOption("Minimum Pages", "f_spf")
    class MaxPagesOption : PageOption("Maximum Pages", "f_spt")

    class RatingOption :
        Filter.Select<String>(
            "Minimum Rating",
            arrayOf(
                "Any",
                "2 stars",
                "3 stars",
                "4 stars",
                "5 stars",
            ),
        ),
        UriFilter {
        override fun addToUri(builder: Uri.Builder) {
            if (state > 0) {
                builder.appendQueryParameter("f_srdd", (state + 1).toString())
                builder.appendQueryParameter("f_sr", "on")
            }
        }
    }

    class AdvancedGroup : UriGroup<Filter<*>>(
        "Advanced Options",
        listOf(
            AdvancedOption("Browse Expunged Galleries", "f_sh"),
            AdvancedOption("Require Gallery Torrent", "f_sto"),
            RatingOption(),
            MinPagesOption(),
            MaxPagesOption(),
            AdvancedOption("Disable custom Language filters", "f_sfl"),
            AdvancedOption("Disable custom Uploader filters", "f_sfu"),
            AdvancedOption("Disable custom Tag filters", "f_sft"),
        ),
    )

    class ReverseFilter : Filter.CheckBox("Reverse search results")

    class JumpSeekFilter : Filter.Text("Jump/Seek")

    override val name = if (exh) {
        "ExHentai"
    } else {
        "E-Hentai"
    }

    class GalleryNotFoundException(cause: Throwable) : RuntimeException("Gallery not found!", cause)

    // === URL IMPORT STUFF

    override val matchingHosts: List<String> = if (exh) {
        listOf(
            "exhentai.org",
        )
    } else {
        listOf(
            "g.e-hentai.org",
            "e-hentai.org",
        )
    }

    override suspend fun mapUrlToMangaUrl(uri: Uri): String? {
        return when (uri.pathSegments.firstOrNull()) {
            "g" -> {
                // Is already gallery page, do nothing
                uri.toString()
            }
            "s" -> {
                // Is page, fetch gallery token and use that
                getGalleryUrlFromPage(uri)
            }
            else -> null
        }
    }

    override fun cleanMangaUrl(url: String): String {
        return EHentaiSearchMetadata.normalizeUrl(super.cleanMangaUrl(url))
    }

    private fun getGalleryUrlFromPage(uri: Uri): String {
        val lastSplit = uri.pathSegments.last().split("-")
        val pageNum = lastSplit.last()
        val gallery = lastSplit.first()
        val pageToken = uri.pathSegments.elementAt(1)

        val json = buildJsonObject {
            put("method", "gtoken")
            put(
                "pagelist",
                buildJsonArray {
                    add(
                        buildJsonArray {
                            add(gallery.toInt())
                            add(pageToken)
                            add(pageNum.toInt())
                        },
                    )
                },
            )
        }

        val outJson = Json.decodeFromString<JsonObject>(
            client.newCall(
                Request.Builder()
                    .url(EH_API_BASE)
                    .post(json.toString().toRequestBody(JSON))
                    .build(),
            ).execute().body.string(),
        )

        val obj = outJson["tokenlist"]!!.jsonArray.first().jsonObject
        return "${uri.scheme}://${uri.host}/g/${obj["gid"]!!.jsonPrimitive.int}/${
            obj["token"]!!.jsonPrimitive.content
        }/"
    }

    override suspend fun getPagePreviewList(
        manga: SManga,
        chapters: List<SChapter>,
        page: Int,
    ): PagePreviewPage {
        val doc = client.newCall(
            exGet(
                (baseUrl + (chapters.lastOrNull()?.url ?: manga.url))
                    .toHttpUrl()
                    .newBuilder()
                    .removeAllQueryParameters("nw")
                    .addQueryParameter("p", (page - 1).toString())
                    .build()
                    .toString(),
            ),
        ).awaitSuccess().asJsoup()

        val body = doc.body()
        val previews = body
            .select("#gdt > div > div")
            .plus(body.select("#gdt > a"))
            .map {
                val preview = parseNormalPreview(it)
                PagePreviewInfo(preview.index, imageUrl = preview.toUrl())
            }
            .ifEmpty {
                body.select("#gdt div a img")
                    .map {
                        PagePreviewInfo(
                            it.attr("alt").toInt(),
                            imageUrl = it.attr("src"),
                        )
                    }
            }

        return PagePreviewPage(
            page = page,
            pagePreviews = previews,
            hasNextPage = doc.select("table.ptt tbody tr td")
                .last()!!
                .hasClass("ptdd")
                .not(),
            pagePreviewPages = doc.select("table.ptt tbody tr td a").asReversed()
                .firstNotNullOfOrNull { it.text().toIntOrNull() },
        )
    }

    override suspend fun fetchPreviewImage(page: PagePreviewInfo, cacheControl: CacheControl?): Response {
        return client.newCachelessCallWithProgress(exGet(page.imageUrl, cacheControl = cacheControl), page)
            .awaitSuccess()
    }

    /**
     * Parse normal previews with regular expressions
     */
    private fun parseNormalPreview(element: Element): EHentaiThumbnailPreview {
        val imgElement = element.selectFirst("img")
        val index = imgElement?.attr("alt")?.toInt()
            ?: element.child(0).attr("title").removePrefix("Page ").substringBefore(":").toInt()
        val styleElement = if (imgElement != null) {
            element
        } else {
            element.child(0)
        }
        val styles = styleElement.attr("style").split(";").mapNotNull { it.trimOrNull() }
        val width = styles.first { it.startsWith("width:") }
            .removePrefix("width:")
            .removeSuffix("px")
            .toInt()

        val height = styles.first { it.startsWith("height:") }
            .removePrefix("height:")
            .removeSuffix("px")
            .toInt()

        val background = styles.first { it.startsWith("background:") }
            .removePrefix("background:")
            .split(" ")

        val url = background.first { it.startsWith("url(") }
            .removePrefix("url(")
            .removeSuffix(")")

        val widthOffset = background.first { it.startsWith("-") }
            .removePrefix("-")
            .removeSuffix("px")
            .toInt()

        return EHentaiThumbnailPreview(url, width, height, widthOffset, index)
    }
    data class EHentaiThumbnailPreview(
        val imageUrl: String,
        val width: Int,
        val height: Int,
        val widthOffset: Int,
        val index: Int,
    ) {
        fun toUrl(): String {
            return BLANK_PREVIEW_THUMB.toHttpUrl().newBuilder()
                .addQueryParameter("imageUrl", imageUrl)
                .addQueryParameter("width", width.toString())
                .addQueryParameter("height", height.toString())
                .addQueryParameter("widthOffset", widthOffset.toString())
                .build()
                .toString()
        }

        companion object {
            fun parseFromUrl(url: HttpUrl) = EHentaiThumbnailPreview(
                imageUrl = url.queryParameter("imageUrl")!!,
                width = url.queryParameter("width")!!.toInt(),
                height = url.queryParameter("height")!!.toInt(),
                widthOffset = url.queryParameter("widthOffset")!!.toInt(),
                index = -1,
            )
        }
    }

    private class ThumbnailPreviewInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()

            if (request.url.host == THUMB_DOMAIN && request.url.pathSegments.contains(BLANK_THUMB)) {
                val thumbnailPreview = EHentaiThumbnailPreview.parseFromUrl(request.url)
                val response = chain.proceed(request.newBuilder().url(thumbnailPreview.imageUrl).build())
                if (response.isSuccessful) {
                    val body = ByteArrayOutputStream()
                        .use {
                            val bitmap = BitmapFactory.decodeStream(response.body.byteStream())
                                ?: throw IOException("Null bitmap($thumbnailPreview)")
                            Bitmap.createBitmap(
                                bitmap,
                                thumbnailPreview.widthOffset,
                                0,
                                thumbnailPreview.width.coerceAtMost(bitmap.width - thumbnailPreview.widthOffset),
                                thumbnailPreview.height.coerceAtMost(bitmap.height),
                            ).compress(Bitmap.CompressFormat.JPEG, 100, it)
                            it.toByteArray()
                        }
                        .toResponseBody("image/jpeg".toMediaType())

                    return response.newBuilder().body(body).build()
                } else {
                    return response
                }
            }

            return chain.proceed(request)
        }
    }

    companion object {
        private const val TR_SUFFIX = "TR"
        private const val REVERSE_PARAM = "TEH_REVERSE"
        private const val THUMB_DOMAIN = "ehgt.org"
        private const val BLANK_THUMB = "blank.gif"
        private const val BLANK_PREVIEW_THUMB = "https://$THUMB_DOMAIN/g/$BLANK_THUMB"

        private val MATCH_YEAR_REGEX = "^\\d{4}$".toRegex()
        private val MATCH_SEEK_REGEX = "^\\d{2,4}-\\d{1,2}(-\\d{1,2})?$".toRegex()
        private val MATCH_JUMP_REGEX = "^\\d+($|d$|w$|m$|y$|-$)$".toRegex()

        private const val EH_API_BASE = "https://api.e-hentai.org/api.php"
        private val JSON = "application/json; charset=utf-8".toMediaTypeOrNull()!!

        private val FAVORITES_BORDER_HEX_COLORS = listOf(
            "000",
            "f00",
            "fa0",
            "dd0",
            "080",
            "9f4",
            "4bf",
            "00f",
            "508",
            "e8e",
        )

        fun buildCookies(cookies: Map<String, String>) = cookies.entries.joinToString(separator = "; ") {
            "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
        }

        // KMK -->
        val languageMapping = mapOf(
            "ja" to "japanese",
            "en" to "english",
            "zh" to "chinese",
            "nl" to "dutch",
            "fr" to "french",
            "de" to "german",
            "hu" to "hungarian",
            "it" to "italian",
            "ko" to "korean",
            "pl" to "polish",
            "pt-BR" to "portuguese",
            "ru" to "russian",
            "es" to "spanish",
            "th" to "thai",
            "vi" to "vietnamese",
            "none" to "n/a",
            "other" to "other",
        )
        // KMK <--
    }
}
