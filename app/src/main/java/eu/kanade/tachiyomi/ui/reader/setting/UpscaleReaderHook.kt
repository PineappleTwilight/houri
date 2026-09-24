package eu.kanade.tachiyomi.ui.reader.setting

import kotlinx.coroutines.CancellationException
import mihon.app.di.globalAppGraph
import tachiyomi.core.common.util.system.ImageUtil

/**
 * Display-time upscale entry shared by the pager, webtoon, and WebGPU readers.
 * Returns upscaled WEBP bytes, or null when the page must be shown untouched.
 * Callers keep their original bytes on null, so translation and fallback paths
 * never observe this helper.
 */
object UpscaleReaderHook {

    suspend fun upscaleDisplayBytes(mangaId: Long?, bytes: ByteArray?): ByteArray? {
        if (mangaId == null || mangaId <= 0 || bytes == null || bytes.isEmpty()) return null
        return try {
            if (ImageUtil.isAnimatedAndSupported(bytes)) return null
            globalAppGraph.upscaleEngine.upscaleIfNeeded(mangaId, bytes)
        } catch (e: CancellationException) {
            throw e
        } catch (_: OutOfMemoryError) {
            null
        } catch (_: Exception) {
            null
        }
    }
}
