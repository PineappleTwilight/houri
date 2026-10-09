package eu.kanade.tachiyomi.data.coil

import androidx.core.net.toUri
import coil3.Extras
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.disk.DiskCache
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.getOrDefault
import coil3.request.Options
import com.hippo.unifile.UniFile
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.coil.MangaCoverFetcher.Companion.USE_CUSTOM_COVER_KEY
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import logcat.LogPriority
import mihon.app.di.globalAppGraph
import okhttp3.CacheControl
import okhttp3.Call
import okhttp3.Request
import okhttp3.Response
import okio.BufferedSource
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import okio.Source
import okio.buffer
import okio.sink
import okio.source
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.manga.model.asMangaCover
import tachiyomi.domain.source.service.SourceManager
import java.io.File
import java.io.IOException

/**
 * A [Fetcher] that fetches cover image for [Manga] object.
 *
 * It uses [Manga.thumbnailUrl] if custom cover is not set by the user.
 * Disk caching for library items is handled by [CoverCache], otherwise
 * handled by Coil's [DiskCache].
 *
 * Available request parameter:
 * - [USE_CUSTOM_COVER_KEY]: Use custom cover if set by user, default is true
 */
class MangaCoverFetcher(
    // KMK -->
    private val mangaCover: MangaCover,
    private val url: String? = mangaCover.url,
    // private val url: String?,
    // KMK <--
    private val isLibraryManga: Boolean,
    private val options: Options,
    private val coverFileLazy: Lazy<File?>,
    private val customCoverFileLazy: Lazy<File>,
    private val diskCacheKeyLazy: Lazy<String>,
    private val sourceLazy: Lazy<HttpSource?>,
    private val callFactoryLazy: Lazy<Call.Factory>,
    private val imageLoader: ImageLoader,
) : Fetcher {

    // KMK -->
    private val uiPreferences = globalAppGraph.uiPreferences
    // KMK <--

    private val diskCacheKey: String
        get() = diskCacheKeyLazy.value

    /**
     * Called each time a cover is displayed
     */
    override suspend fun fetch(): FetchResult {
        // Use custom cover if exists
        val useCustomCover = options.extras.getOrDefault(USE_CUSTOM_COVER_KEY)
        if (useCustomCover) {
            val customCoverFile = customCoverFileLazy.value
            if (customCoverFile.exists()) {
                return fileLoader(customCoverFile)
            }
        }

        // diskCacheKey is thumbnail_url
        if (url == null) error("No cover specified")
        return when (getResourceType(url)) {
            Type.File -> fileLoader(File(url.substringAfter("file://")))
            Type.URI -> fileUriLoader(url)
            Type.URL -> httpLoader()
            null -> error("Invalid image")
        }
    }

    private fun fileLoader(file: File): FetchResult {
        // KMK -->
        setRatioAndColorsInScope(mangaCover, ogFile = file)
        // KMK <--
        return SourceFetchResult(
            source = ImageSource(
                file = file.toOkioPath(),
                fileSystem = FileSystem.SYSTEM,
                diskCacheKey = diskCacheKey,
            ),
            mimeType = "image/*",
            dataSource = DataSource.DISK,
        )
    }

    private fun fileUriLoader(uri: String): FetchResult {
        // KMK -->
        setRatioAndColorsInScope(mangaCover)
        // KMK <--
        val uniFile = UniFile.fromUri(options.context, uri.toUri())
            ?: error("Invalid content URI: $uri")
        val source = uniFile.openInputStream().source().buffer()
        return SourceFetchResult(
            source = ImageSource(source = source, fileSystem = FileSystem.SYSTEM),
            mimeType = "image/*",
            dataSource = DataSource.DISK,
        )
    }

    private suspend fun httpLoader(): FetchResult {
        // Only cache separately if it's a library item
        val libraryCoverCacheFile = if (isLibraryManga) {
            coverFileLazy.value ?: error("No cover specified")
        } else {
            null
        }
        if (libraryCoverCacheFile?.exists() == true && options.diskCachePolicy.readEnabled) {
            return fileLoader(libraryCoverCacheFile)
        }

        var snapshot = readFromDiskCache()
        try {
            // Fetch from disk cache
            if (snapshot != null) {
                val snapshotCoverCache = moveSnapshotToCoverCache(snapshot, libraryCoverCacheFile)
                if (snapshotCoverCache != null) {
                    // Read from cover cache after added to library
                    return fileLoader(snapshotCoverCache)
                }

                // Read from snapshot
                // KMK -->
                setRatioAndColorsInScope(mangaCover, bufferedSource = snapshot.toImageSource().source())
                // KMK <--
                return SourceFetchResult(
                    source = snapshot.toImageSource(),
                    mimeType = "image/*",
                    dataSource = DataSource.DISK,
                )
            }

            // Fetch from network
            val response = executeNetworkRequest()
            val responseBody = checkNotNull(response.body) { "Null response source" }
            try {
                // Read from cover cache after library manga cover updated
                val responseCoverCache = writeResponseToCoverCache(response, libraryCoverCacheFile)
                if (responseCoverCache != null) {
                    return fileLoader(responseCoverCache)
                }

                // Read from disk cache
                snapshot = writeToDiskCache(response)
                if (snapshot != null) {
                    // KMK -->
                    setRatioAndColorsInScope(mangaCover, bufferedSource = snapshot.toImageSource().source())
                    // KMK <--
                    return SourceFetchResult(
                        source = snapshot.toImageSource(),
                        mimeType = "image/*",
                        dataSource = DataSource.NETWORK,
                    )
                }

                // KMK -->
                setRatioAndColorsInScope(
                    mangaCover,
                    bufferedSource = ImageSource(
                        source = responseBody.source(),
                        fileSystem = FileSystem.SYSTEM,
                    ).source(),
                )
                // KMK <--
                // Read from response if cache is unused or unusable
                return SourceFetchResult(
                    source = ImageSource(source = responseBody.source(), fileSystem = FileSystem.SYSTEM),
                    mimeType = "image/*",
                    dataSource = if (response.cacheResponse != null) DataSource.DISK else DataSource.NETWORK,
                )
            } catch (e: Exception) {
                responseBody.close()
                throw e
            }
        } catch (e: Exception) {
            snapshot?.close()
            throw e
        }
    }

    private suspend fun executeNetworkRequest(): Response {
        val client = sourceLazy.value?.client ?: callFactoryLazy.value
        val response = client.newCall(newRequest()).await()
        if (!response.isSuccessful && response.code != HTTP_NOT_MODIFIED) {
            response.close()
            throw IOException(response.message)
        }
        return response
    }

    private fun newRequest(): Request {
        val requestUrl = url ?: error("No cover URL")
        val request = Request.Builder().apply {
            url(requestUrl)

            val sourceHeaders = sourceLazy.value?.headers
            if (sourceHeaders != null) {
                headers(sourceHeaders)
            }
        }

        when {
            options.networkCachePolicy.readEnabled -> {
                // don't take up okhttp cache
                request.cacheControl(CACHE_CONTROL_NO_STORE)
            }
            else -> {
                // This causes the request to fail with a 504 Unsatisfiable Request.
                request.cacheControl(CACHE_CONTROL_NO_NETWORK_NO_CACHE)
            }
        }

        return request.build()
    }

    private fun moveSnapshotToCoverCache(snapshot: DiskCache.Snapshot, cacheFile: File?): File? {
        if (cacheFile == null) return null
        return try {
            imageLoader.diskCache?.run {
                fileSystem.source(snapshot.data).use { input ->
                    writeSourceToCoverCache(input, cacheFile)
                }
                remove(diskCacheKey)
            }
            cacheFile.takeIf { it.exists() }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to write snapshot data to cover cache ${cacheFile.name}" }
            null
        }
    }

    private fun writeResponseToCoverCache(response: Response, cacheFile: File?): File? {
        if (cacheFile == null || !options.diskCachePolicy.writeEnabled) return null
        return try {
            response.peekBody(Long.MAX_VALUE).source().use { input ->
                writeSourceToCoverCache(input, cacheFile)
            }
            cacheFile.takeIf { it.exists() }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to write response data to cover cache ${cacheFile.name}" }
            null
        }
    }

    private fun writeSourceToCoverCache(input: Source, cacheFile: File) {
        cacheFile.parentFile?.mkdirs()
        cacheFile.delete()
        try {
            cacheFile.sink().buffer().use { output ->
                output.writeAll(input)
            }
        } catch (e: Exception) {
            cacheFile.delete()
            throw e
        }
    }

    private fun readFromDiskCache(): DiskCache.Snapshot? {
        return if (options.diskCachePolicy.readEnabled) {
            imageLoader.diskCache?.openSnapshot(diskCacheKey)
        } else {
            null
        }
    }

    private fun writeToDiskCache(
        response: Response,
    ): DiskCache.Snapshot? {
        val diskCache = imageLoader.diskCache
        val editor = diskCache?.openEditor(diskCacheKey) ?: return null
        try {
            diskCache.fileSystem.write(editor.data) {
                response.body.source().readAll(this)
            }
            return editor.commitAndOpenSnapshot()
        } catch (e: Exception) {
            try {
                editor.abort()
            } catch (ignored: Exception) {
            }
            throw e
        }
    }

    private fun DiskCache.Snapshot.toImageSource(): ImageSource {
        return ImageSource(
            file = data,
            fileSystem = FileSystem.SYSTEM,
            diskCacheKey = diskCacheKey,
            closeable = this,
        )
    }

    private fun getResourceType(cover: String?): Type? {
        return when {
            cover.isNullOrEmpty() -> null
            cover.startsWith("http", true) || cover.startsWith("Custom-", true) -> Type.URL
            cover.startsWith("/") || cover.startsWith("file://") -> Type.File
            cover.startsWith("content") -> Type.URI
            else -> null
        }
    }

    // KMK -->
    /**
     * [setRatioAndColorsInScope] is called whenever a cover is loaded with [MangaCoverFetcher.fetch]
     *
     * @param bufferedSource if not null then it will load bitmap from [BufferedSource], regardless of [ogFile]
     * @param ogFile if not null then it will load bitmap from [File]. If it's null then it will try to load bitmap
     *  from [CoverCache] using either [CoverCache.customCoverCacheDir] or [CoverCache.cacheDir]
     * @param force if true then it will always re-calculate ratio & color for favorite mangas.
     */
    private fun setRatioAndColorsInScope(
        mangaCover: MangaCover,
        bufferedSource: BufferedSource? = null,
        ogFile: File? = null,
        onlyFavorite: Boolean = !uiPreferences.themeCoverBased().get(),
        force: Boolean = false,
    ) {
        if (!uiPreferences.preloadLibraryColor().get()) return
        // Non-library covers never reach this through a cached file, and the bufferedSource they do
        // arrive with belongs to Coil.
        //
        // Only a library manga gets a CoverCache file: libraryCoverCacheFile is null otherwise, and
        // both moveSnapshotToCoverCache and writeResponseToCoverCache bail out on a null cache file.
        // So every cover this guard drops would have been sampled from a source Coil is concurrently
        // reading to decode the very image being returned - the same BufferedSource instance, read by
        // BitmapFactory.decodeStream on one side and Coil on the other, then closed by Coil when the
        // request finishes. That is a read-position race that corrupts the displayed image, and the
        // coroutine outliving the close makes it an IOException for whichever side loses.
        //
        // A search results page is the worst case for it: no result is a library item, so every cover
        // on the page arrives over the network through that shared source, and one page of results
        // launches one untracked coroutine each to race Coil for it. Hence covers that never load and
        // an app that stays wedged afterwards.
        //
        // Cost of dropping them: nothing that reads the result. dominantCoverColors is read only
        // behind a libraryColored gate, and vibrantCoverColor only by the manga details screen and the
        // reader - each for the one manga being opened, which gets its own cover load anyway.
        if (!isLibraryManga) return
        FetcherScope.scope.launch {
            MangaCoverMetadata.setRatioAndColors(mangaCover, bufferedSource, ogFile, onlyFavorite, force)
        }
    }
    // KMK <--

    private object FetcherScope {
        // Bounded, and none of it is awaited: a cover load used to fan out one coroutine per cover
        // onto the shared IO pool, so a page of search results had that many bitmap decodes queued
        // behind each other with nothing metering them. Two matches the decoder pool, which is the
        // work actually competing for CPU and memory here.
        //
        // Queued rather than dropped when the limit is reached. This is a cache warm-up whose result
        // nothing awaits, so running late costs nothing, whereas refusing to start would silently
        // lose the colour until a later visit happened to re-fetch the cover.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(2))
    }

    private enum class Type {
        File,
        URI,
        URL,
    }

    class MangaFactory(
        private val callFactoryLazy: Lazy<Call.Factory>,
    ) : Fetcher.Factory<Manga> {

        private val coverCache: CoverCache by lazy { globalAppGraph.coverCache }
        private val sourceManager: SourceManager by lazy { globalAppGraph.sourceManager }

        override fun create(data: Manga, options: Options, imageLoader: ImageLoader): Fetcher {
            return MangaCoverFetcher(
                // KMK -->
                // url = data.thumbnailUrl,
                mangaCover = data.asMangaCover(),
                // KMK <--
                isLibraryManga = data.favorite,
                options = options,
                coverFileLazy = lazy { coverCache.getCoverFile(data.thumbnailUrl) },
                customCoverFileLazy = lazy { coverCache.getCustomCoverFile(data.id) },
                diskCacheKeyLazy = lazy { imageLoader.components.key(data, options)!! },
                sourceLazy = lazy { sourceManager.get(data.source) as? HttpSource },
                callFactoryLazy = callFactoryLazy,
                imageLoader = imageLoader,
            )
        }
    }

    class MangaCoverFactory(
        private val callFactoryLazy: Lazy<Call.Factory>,
    ) : Fetcher.Factory<MangaCover> {

        private val coverCache: CoverCache by lazy { globalAppGraph.coverCache }
        private val sourceManager: SourceManager by lazy { globalAppGraph.sourceManager }

        override fun create(data: MangaCover, options: Options, imageLoader: ImageLoader): Fetcher {
            return MangaCoverFetcher(
                // KMK -->
                // url = data.url,
                mangaCover = data,
                // KMK <--
                isLibraryManga = data.isMangaFavorite,
                options = options,
                coverFileLazy = lazy { coverCache.getCoverFile(data.url) },
                customCoverFileLazy = lazy { coverCache.getCustomCoverFile(data.mangaId) },
                diskCacheKeyLazy = lazy { imageLoader.components.key(data, options)!! },
                sourceLazy = lazy { sourceManager.get(data.sourceId) as? HttpSource },
                callFactoryLazy = callFactoryLazy,
                imageLoader = imageLoader,
            )
        }
    }

    companion object {
        val USE_CUSTOM_COVER_KEY = Extras.Key(true)

        private val CACHE_CONTROL_NO_STORE = CacheControl.Builder().noStore().build()
        private val CACHE_CONTROL_NO_NETWORK_NO_CACHE = CacheControl.Builder().noCache().onlyIfCached().build()

        private const val HTTP_NOT_MODIFIED = 304
    }
}
