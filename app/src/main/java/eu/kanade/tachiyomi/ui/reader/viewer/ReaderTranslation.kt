package eu.kanade.tachiyomi.ui.reader.viewer

import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.CoroutineScope
import mihon.app.di.globalAppGraph
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withUIContext

/**
 * Shared entry point for MTL translation in the non-WebGPU readers (pager + webtoon).
 *
 * Unlike the WebGPU viewer (which bakes translated images into its own [ImagePage] pipeline),
 * these readers display pages through [ReaderPageImageView]; this helper runs the same
 * [exh.yakuyomi.TranslationManager] pipeline against the original encoded bytes and hands the
 * translated WEBP back on the main thread for the holder to swap in.
 */
object ReaderTranslation {

    /**
     * Kicks off translation for [page] if (and only if) translation is enabled globally,
     * not gated, enabled for this manga, and the AI models are installed. On success,
     * [onResult] is invoked on the main thread with the translated WEBP bytes.
     */
    fun translate(
        scope: CoroutineScope,
        page: ReaderPage,
        originalBytes: ByteArray?,
        onResult: (ByteArray) -> Unit,
    ) {
        if (originalBytes == null || originalBytes.isEmpty()) return

        val manager = globalAppGraph.translationManager
        val mangaId = page.chapter.chapter.manga_id ?: 0L
        if (!manager.isEnabled() || manager.isGated() || !manager.isPerMangaEnabled(mangaId)) return

        val chapterId = page.chapter.chapter.id ?: 0L
        val pageIndex = page.index

        scope.launchIO {
            try {
                if (!manager.shouldTranslateForManga(mangaId)) return@launchIO
                val webp = manager.translatePage(mangaId, chapterId, originalBytes, pageIndex) ?: return@launchIO
                withUIContext { onResult(webp) }
            } catch (_: Exception) {
            }
        }
    }
}
