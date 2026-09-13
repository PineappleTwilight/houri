package eu.kanade.tachiyomi.ui.reader.viewer

import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.CoroutineScope

/**
 * Translates a reader page's [originalBytes] and delivers translated WEBP via
 * [onResult] on the main thread. The default implementation is
 * [ReaderTranslation]; custom pipelines (or a no-op for tests) register via
 * [PageTranslators.register].
 */
interface PageTranslator {
    fun translate(
        scope: CoroutineScope,
        page: ReaderPage,
        originalBytes: ByteArray?,
        onResult: (ByteArray) -> Unit,
    )
}

object PageTranslators {
    @Volatile
    var current: PageTranslator = ReaderTranslation
        private set

    fun register(translator: PageTranslator) {
        current = translator
    }

    fun reset() {
        current = ReaderTranslation
    }
}
