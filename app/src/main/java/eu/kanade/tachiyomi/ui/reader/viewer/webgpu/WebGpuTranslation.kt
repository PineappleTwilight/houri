// Mihon -->
package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

import android.graphics.BitmapFactory
import ca.mpreg.webgpuviewer.renderer.Image
import ca.mpreg.webgpuviewer.renderer.Image.Companion.invoke
import ca.mpreg.webgpuviewer.viewer.ImagePage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.nio.ByteBuffer

private val translationSemaphore = Semaphore(2)

// KMK -->
/**
 * Queue MTL translation for a page and swap in the baked result when ready.
 * Reusable for decode-time scheduling and explicit retry. The swap requires a live
 * decoded [ImagePage.ImageSingle] still in cache: placeholders (progress/error) and
 * evicted pages drop the result, but a concurrent spread height-match rescale - which
 * legitimately replaces [ViewerPage.imagePage] between schedule and completion - no
 * longer discards the translation (it used to match on captured identity, making MTL
 * permanently fail for any page rescaled mid-flight).
 */
internal fun WebGpuViewer.scheduleTranslation(page: ViewerReaderPage, sourceBytes: ByteArray) {
    val mgr = translationManager ?: return
    if (sourceBytes.size !in 1..32 * 1024 * 1024) return
    if (!mgr.isEnabled() || mgr.isGated()) return
    val mangaId = page.page.chapter.chapter.manga_id
        ?: viewerChapters?.currChapter?.chapter?.manga_id
        ?: 0L
    if (!mgr.isPerMangaEnabled(mangaId)) return

    val chapterId = page.page.chapter.chapter.id ?: 0L
    val pageIndex = page.page.index

    // Declare the chapter's page count so chapter-list/overlay progress is accurate.
    mgr.setChapterTotalPages(mangaId, chapterId, page.page.chapter.pages?.size ?: 0)

    scope.launch(Dispatchers.Default) {
        try {
            if (!mgr.shouldTranslateForManga(mangaId)) return@launch
            val translatedWebP = translationSemaphore.withPermit {
                mgr.translatePage(
                    mangaId = mangaId,
                    chapterId = chapterId,
                    imageBytes = sourceBytes,
                    pageIndex = pageIndex,
                )
            }
            if (translatedWebP == null) return@launch
            val translatedBitmap = BitmapFactory.decodeByteArray(translatedWebP, 0, translatedWebP.size)
            if (translatedBitmap != null) {
                try {
                    val translatedImage = Image(
                        ByteBuffer.allocateDirect(translatedBitmap.width * translatedBitmap.height * 4).apply {
                            translatedBitmap.copyPixelsToBuffer(this)
                            rewind()
                        },
                        translatedBitmap.width,
                        translatedBitmap.height,
                        createMipMaps = true,
                        backgroundColor = if (config.automaticBackground) null else readerBackgroundColor(),
                    )
                    val translatedPage = ImagePage.ImageSingle(translatedImage)
                    synchronized(lock) {
                        val current = page.imagePage
                        if (pageInCache(page) && current is ImagePage.ImageSingle && !current.destroyed) {
                            if (!page.hasTranslation) {
                                page.compareOriginal?.let {
                                    if (it !== current) {
                                        try {
                                            it.cleanup()
                                        } catch (_: Exception) {
                                        }
                                    }
                                }
                                page.compareOriginal = current
                                page.hasTranslation = true
                            }
                            if (config.compareTranslation) {
                                page.compareTranslated?.let {
                                    if (it !== translatedPage) {
                                        try {
                                            it.cleanup()
                                        } catch (_: Exception) {
                                        }
                                    }
                                }
                                page.compareTranslated = translatedPage
                            } else {
                                page.compareTranslated?.let {
                                    try {
                                        it.cleanup()
                                    } catch (_: Exception) {
                                    }
                                }
                                page.compareTranslated = null
                                val old = page.imagePage
                                page.imagePage = translatedPage
                                if (page.hasTranslation && old !== page.compareOriginal) {
                                    try {
                                        old.cleanup()
                                    } catch (_: Exception) {
                                    }
                                }
                            }
                            (page.imagePage as? ImagePage.ImageSingle)?.let { tp ->
                                if (page.spreadPosition == SpreadPosition.SINGLE) {
                                    if (!applyWideZoomIfNeeded(tp)) applyFitModeAnchor(tp)
                                }
                                applyDoubleTapZoomPolicy(tp)
                            }
                            pager.state.invalidate()
                        } else {
                            translatedPage.cleanup()
                        }
                    }
                } finally {
                    translatedBitmap.recycle()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {}
    }
}

/**
 * Retry translating the currently displayed page after a failure (or when the
 * user explicitly requests it). Reads the source bytes again from the page stream
 * and re-runs the translation pipeline.
 */
internal fun ViewerReaderPage.sourceBytes(): ByteArray {
    val bytes = try {
        page.stream?.invoke()?.use { it.readBytes() }
    } catch (_: Exception) {
        null
    }
    return bytes ?: ByteArray(0)
}
// KMK <--
// Mihon <--
