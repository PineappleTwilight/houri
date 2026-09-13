package eu.kanade.tachiyomi.ui.reader.viewer

import androidx.annotation.ColorInt
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import eu.kanade.tachiyomi.ui.reader.viewer.pager.L2RPagerViewer
import eu.kanade.tachiyomi.ui.reader.viewer.pager.R2LPagerViewer
import eu.kanade.tachiyomi.ui.reader.viewer.pager.VerticalPagerViewer
import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.WebGpuViewer
import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.WebGpuViewerContinuous
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonViewer
import mihon.app.di.globalAppGraph

/**
 * Pluggable factory for [Viewer] implementations.
 *
 * A provider returns a [Viewer] for the reading modes it handles, or null to
 * let the next provider try. Providers are consulted in [order]; the built-in
 * [WebGpuProvider] and [LegacyProvider] mirror the historical
 * `ReadingMode.toViewer` mapping. Custom readers (e.g. a novel viewer) register
 * via [ViewerRegistry.register] with a lower [order] to take precedence.
 */
interface ViewerProvider {
    val order: Int
    fun create(mode: ReadingMode, activity: ReaderActivity, @ColorInt seedColor: Int?): Viewer?
}

object ViewerRegistry {
    private val providers = mutableListOf<ViewerProvider>(WebGpuProvider, LegacyProvider)

    fun register(provider: ViewerProvider) {
        providers.removeAll { it::class == provider::class }
        providers.add(provider)
        providers.sortBy { it.order }
    }

    fun create(mode: ReadingMode, activity: ReaderActivity, @ColorInt seedColor: Int?): Viewer {
        for (provider in providers.sortedBy { it.order }) {
            val viewer = runCatching { provider.create(mode, activity, seedColor) }.getOrNull()
            if (viewer != null) return viewer
        }
        error("No ViewerProvider handled reading mode: $mode")
    }
}

private object WebGpuProvider : ViewerProvider {
    override val order: Int = 0

    override fun create(mode: ReadingMode, activity: ReaderActivity, seedColor: Int?): Viewer? {
        // KMK --> Gate the WebGPU renderer behind a total-RAM check: on low-memory devices the
        // native renderer crashes with an uncatchable SIGSEGV (null GPUTexture in createView)
        // when it cannot allocate GPU-visible memory. Fall back to the legacy pager viewers.
        // KMK <--
        val basePreferences = globalAppGraph.basePreferences
        val wantsHighQuality = basePreferences.highQualityRenderer().get() &&
            exh.yakuyomi.DeviceMemory.isWebGpuSupported(activity)
        if (!wantsHighQuality) return null
        val isWebGpuAvailable = try {
            ca.mpreg.webgpuviewer.renderer.WebGpuRenderer.isAvailable
        } catch (_: Throwable) {
            false
        }
        if (!isWebGpuAvailable) return null
        return try {
            when (mode) {
                ReadingMode.LEFT_TO_RIGHT -> WebGpuViewer(activity, isReversed = false, isVertical = false)
                ReadingMode.RIGHT_TO_LEFT -> WebGpuViewer(activity, isReversed = true, isVertical = false)
                ReadingMode.VERTICAL -> WebGpuViewer(activity, isReversed = false, isVertical = true)
                ReadingMode.WEBTOON -> WebGpuViewerContinuous(activity)
                ReadingMode.CONTINUOUS_VERTICAL -> WebGpuViewerContinuous(activity)
                ReadingMode.DEFAULT -> null
            }
        } catch (e: Throwable) {
            android.util.Log.w("ReadingMode", "WebGPU viewer failed, falling back to pager", e)
            null
        }
    }
}

private object LegacyProvider : ViewerProvider {
    override val order: Int = 100

    override fun create(mode: ReadingMode, activity: ReaderActivity, seedColor: Int?): Viewer? {
        return when (mode) {
            ReadingMode.LEFT_TO_RIGHT -> L2RPagerViewer(activity, seedColor = seedColor)
            ReadingMode.RIGHT_TO_LEFT -> R2LPagerViewer(activity, seedColor = seedColor)
            ReadingMode.VERTICAL -> VerticalPagerViewer(activity, seedColor = seedColor)
            ReadingMode.WEBTOON -> WebtoonViewer(activity, seedColor = seedColor)
            ReadingMode.CONTINUOUS_VERTICAL -> WebtoonViewer(activity, isContinuous = false, seedColor = seedColor)
            ReadingMode.DEFAULT -> null
        }
    }
}
