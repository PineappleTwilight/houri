// Mihon -->
package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

import android.graphics.BitmapFactory
import ca.mpreg.webgpuviewer.renderer.Image
import ca.mpreg.webgpuviewer.renderer.Image.Companion.invoke
import ca.mpreg.webgpuviewer.viewer.ImagePage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.nio.ByteBuffer

// KMK -->
/**
 * Queue MTL translation for a page and swap in the baked result when ready.
 * Reusable for decode-time scheduling and explicit retry. The swap is guarded by
 * image identity so a stale result never clobbers a newer page.
 */
internal fun WebGpuViewer.scheduleTranslation(page: ViewerReaderPage, sourceBytes: ByteArray) {
    val mgr = translationManager ?: return
    if (sourceBytes.size !in 1..32 * 1024 * 1024) return
    if (!mgr.isEnabled() || mgr.isGated()) return
    val mangaId = page.page.chapter.chapter.manga_id
        ?: viewerChapters?.currChapter?.chapter?.manga_id
        ?: 0L
    if (!mgr.isPerMangaEnabled(mangaId)) return

    // Capture the imagePage we expect to still be current when translation completes.
    val originalImagePage = page.imagePage
    val chapterId = page.page.chapter.chapter.id ?: 0L
    val pageIndex = page.page.index

    // Declare the chapter's page count so chapter-list/overlay progress is accurate.
    mgr.setChapterTotalPages(mangaId, chapterId, page.page.chapter.pages?.size ?: 0)

    scope.launch(Dispatchers.Default) {
        try {
            if (!mgr.shouldTranslateForManga(mangaId)) return@launch
            val translatedWebP = mgr.translatePage(
                mangaId = mangaId,
                chapterId = chapterId,
                imageBytes = sourceBytes,
                pageIndex = pageIndex,
            )
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
                        if (pageInCache(page) && page.imagePage === originalImagePage && !originalImagePage.destroyed) {
                            val old = page.imagePage
                            page.imagePage = translatedPage
                            old.cleanup()
                            translatedPage.let { tp ->
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
