// Mihon -->
package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

import android.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.webgpu.GPUTexture
import ca.mpreg.webgpuviewer.draw.TextAlign
import ca.mpreg.webgpuviewer.viewer.ImagePage
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Page-processing state for [ViewerPage]s in the WebGPU viewers.
 */
enum class PageState {
    IDLE,
    QUEUED,
    LOADING,
    DECODING,
}

/**
 * Which side of a dual-page spread a [ViewerReaderPage] belongs on - app-level bookkeeping
 * for [WebGpuViewer.getSpreadAnchor]/[WebGpuViewer.buildSpreadPage], independent of the
 * decoded image itself.
 */
enum class SpreadPosition { LEFT, RIGHT, SINGLE }

// Stable key types for page identity - data classes provide correct equals/hashCode
sealed class PageKey {
    data class Reader(val chapterId: Long?, val index: Int) : PageKey()
    data class Transition(val prevId: Long?, val nextId: Long?) : PageKey()
}

fun pageKey(page: ViewerPage): PageKey = when (page) {
    is ViewerReaderPage -> PageKey.Reader(page.page.chapter.chapter.id, page.page.index)
    is ViewerTransitionPage -> PageKey.Transition(page.prevChapter?.chapter?.id, page.nextChapter?.chapter?.id)
    else -> PageKey.Transition(null, null)
}

abstract class ViewerPage {
    abstract val prevChapter: ReaderChapter?
    abstract val nextChapter: ReaderChapter?
    abstract val prev: ViewerPage?
    abstract val next: ViewerPage?

    @Volatile
    var state: PageState = PageState.IDLE

    @Volatile
    open var imagePage: ImagePage = ImagePage.Dummy(400, 400)

    open val isDecoded = true
}

class ViewerTransitionPage(
    private val viewer: WebGpuViewer,
    override val prevChapter: ReaderChapter?,
    override val nextChapter: ReaderChapter?,
) : ViewerPage() {
    override var imagePage: ImagePage = TransitionPage(viewer, prevChapter, nextChapter)

    override val prev: ViewerPage?
        get() = prevChapter?.pages?.lastOrNull()?.let { viewer.getPage(it, viewer.currentPage) }

    override val next: ViewerPage?
        get() = nextChapter?.pages?.firstOrNull()?.let { viewer.getPage(it, viewer.currentPage) }
}

class ViewerReaderPage(
    private val viewer: WebGpuViewer,
    val page: ReaderPage,
) : ViewerPage() {
    /** Cached spread ImagePage when this page is the anchor of a dual-page spread */
    var spreadPage: ImagePage.ImageSpread? = null

    /** Which side of a dual-page spread this page belongs on - set once decoding tags it. */
    internal var spreadPosition: SpreadPosition = SpreadPosition.SINGLE

    // KMK -->
    /**
     * Compressed source bytes retained for spread height-matching. Only set for
     * partner-position pages decoded in dual-page mode; freed on eviction.
     */
    @Volatile
    var spreadBytes: ByteArray? = null

    /** Guards against scheduling duplicate height-match rescales for this page */
    @Volatile
    var rescaleInFlight: Boolean = false
    // KMK <--

    override var imagePage: ImagePage = ProgressPage(viewer)

    override val isDecoded
        get() = (imagePage as? ImagePage.ImageSingle)?.isDecoded == true

    override val prevChapter: ReaderChapter?
        get() = when (page.chapter) {
            viewer.viewerChapters?.currChapter -> viewer.viewerChapters?.prevChapter
            viewer.viewerChapters?.nextChapter -> viewer.viewerChapters?.currChapter
            else -> null
        }

    override val nextChapter: ReaderChapter?
        get() = when (page.chapter) {
            viewer.viewerChapters?.currChapter -> viewer.viewerChapters?.nextChapter
            viewer.viewerChapters?.prevChapter -> viewer.viewerChapters?.currChapter
            else -> null
        }

    override val prev: ViewerPage?
        get() = page.chapter.pages?.let { pages ->
            pages.getOrNull(page.index - 1)?.let { viewer.getPage(it, viewer.currentPage) } ?: run {
                val prevChapter = prevChapter ?: return@run viewer.getPage(null, page.chapter, viewer.currentPage)

                if (prevChapter.state !is eu.kanade.tachiyomi.ui.reader.model.ReaderChapter.State.Loaded) {
                    viewer.preloadChapterThenRetry(prevChapter)
                }

                if (viewer.config.alwaysShowChapterTransition) {
                    viewer.getPage(prevChapter, page.chapter, viewer.currentPage)
                } else {
                    prevChapter.pages?.lastOrNull()?.let { viewer.getPage(it, viewer.currentPage) }
                }
            }
        }

    override val next: ViewerPage?
        get() = page.chapter.pages?.let { pages ->
            pages.getOrNull(page.index + 1)?.let { viewer.getPage(it, viewer.currentPage) } ?: run {
                val nextChapter = nextChapter ?: return@run viewer.getPage(page.chapter, null, viewer.currentPage)

                if (nextChapter.state !is eu.kanade.tachiyomi.ui.reader.model.ReaderChapter.State.Loaded) {
                    viewer.preloadChapterThenRetry(nextChapter)
                }

                if (viewer.config.alwaysShowChapterTransition) {
                    viewer.getPage(page.chapter, nextChapter, viewer.currentPage)
                } else {
                    nextChapter.pages?.firstOrNull()?.let { viewer.getPage(it, viewer.currentPage) }
                }
            }
        }
}

class ErrorPage(
    private val viewer: WebGpuViewer,
    var message: String,
    spreadPosition: SpreadPosition = SpreadPosition.SINGLE,
) : ImagePage.Render(
    (if (spreadPosition == SpreadPosition.SINGLE) viewer.pager.state.width else viewer.pager.state.width / 2).coerceAtLeast(1),
    viewer.pager.state.height.coerceAtLeast(1),
) {
    init {
        minScale = 1f
        maxScale = 1f
        homeScale = 1f
    }

    override val backgroundColor: Int
        get() = try {
            viewer.readerBackgroundColor()
        } catch (_: Exception) {
            Color.BLACK
        }

    override fun render(dst: GPUTexture, x: Float, y: Float, scale: Float) {
        if (viewer.isDestroyed || dst.width <= 0 || dst.height <= 0) return
        val padding = try {
            with(viewer.pager.state.density) { 24.dp.toPx() }
        } catch (_: Exception) {
            24f
        }
        val size = try {
            scale * with(viewer.pager.state.density) { 16.dp.toPx() }
        } catch (_: Exception) {
            16f * scale
        }

        val cx = dst.width * (0.5f + scale * x)
        val cy = dst.height * (0.5f + scale * y)

        try {
            text(
                dst,
                viewer.activity.baseContext,
                FontFamily.Default,
                message.takeIf { it.isNotBlank() } ?: "Error",
                cx,
                cy,
                size,
                color = try {
                    viewer.readerOnBackgroundColor()
                } catch (_: Exception) {
                    Color.WHITE
                },
                align = TextAlign.Center,
                maxWidth = (dst.width - 2f * padding).coerceAtLeast(0f),
            )
        } catch (_: Exception) {
        }
    }
}

class ProgressPage(
    private val viewer: WebGpuViewer,
    var foregroundColor: Int = try {
        viewer.readerOnBackgroundColor()
    } catch (_: Exception) {
        Color.WHITE
    },
) : ImagePage.Render(
    (if (!viewer.isDualPageMode()) viewer.pager.state.width else viewer.pager.state.width / 2).coerceAtLeast(1),
    viewer.pager.state.height.coerceAtLeast(1),
) {
    var progress: Float = 0f

    init {
        minScale = 1f
        maxScale = 1f
        homeScale = 1f
    }

    override val backgroundColor: Int
        get() = try {
            viewer.readerBackgroundColor()
        } catch (_: Exception) {
            Color.BLACK
        }

    override fun render(dst: GPUTexture, x: Float, y: Float, scale: Float) {
        if (viewer.isDestroyed || dst.width <= 0 || dst.height <= 0) return
        val cx = dst.width * (0.5f + scale * x)
        val cy = dst.height * (0.5f + scale * y)
        val full = try {
            width * 0.5f * scale
        } catch (_: Exception) {
            return
        }
        if (full <= 0f) return
        try {
            drawProgressRing(cx, cy, full)
            drawSpinningPineapple(cx, cy, full)
        } catch (_: Exception) {
        }
    }

    /** Dotted ring around the pineapple that fills with [progress]. */
    private fun drawProgressRing(cx: Float, cy: Float, full: Float) {
        val ringR = full * 0.42f
        val dotR = full * 0.05f
        val dots = 12
        val lit = (progress * dots).toInt().coerceIn(0, dots)
        val dim = 0x30FFFFFF.toInt()
        for (i in 0 until dots) {
            val a = -PI.toFloat() / 2f + i * (2f * PI.toFloat() / dots)
            val dx = cx + cos(a) * ringR
            val dy = cy + sin(a) * ringR
            circle(dx, dy, dotR, if (i < lit) foregroundColor else dim)
        }
    }

    /** Spinning pineapple: golden body with leaves orbiting it, time-based so it animates live. */
    private fun drawSpinningPineapple(cx: Float, cy: Float, full: Float) {
        val bodyR = full * 0.20f
        val bodyCx = cx
        val bodyCy = cy + bodyR * 0.30f
        val gold = 0xFFE0A52E.toInt()
        val goldDark = 0xFFB9781E.toInt()
        val leafGreen = 0xFF43A047.toInt()
        val leafDark = 0xFF2E7D32.toInt()

        // Body
        circle(bodyCx, bodyCy, bodyR, gold)
        // Facet shading (reads as a pineapple body, not a plain disc)
        circle(bodyCx - bodyR * 0.42f, bodyCy - bodyR * 0.28f, bodyR * 0.16f, goldDark)
        circle(bodyCx + bodyR * 0.42f, bodyCy + bodyR * 0.30f, bodyR * 0.14f, goldDark)
        circle(bodyCx, bodyCy + bodyR * 0.48f, bodyR * 0.12f, goldDark)

        // Crown leaves orbiting the body center = the "spin".
        val spin = (System.currentTimeMillis() % 900L) / 900f * 2f * PI.toFloat()
        val leafR = bodyR * 1.30f
        val leaf = bodyR * 0.30f
        val leaves = 6
        for (i in 0 until leaves) {
            val a = spin + i * (2f * PI.toFloat() / leaves)
            val lx = bodyCx + cos(a) * leafR * 0.70f
            val ly = bodyCy - sin(a) * leafR * 0.55f
            rect(lx - leaf / 2f, ly - leaf / 2f, leaf, leaf, leafGreen)
            circle(lx, ly, leaf * 0.18f, leafDark)
        }
    }
}

class TransitionPage(
    private val viewer: WebGpuViewer,
    val prevChapter: ReaderChapter?,
    val nextChapter: ReaderChapter?,
) : ImagePage.Render(
    min(viewer.pager.state.width.coerceAtLeast(1), viewer.pager.state.height.coerceAtLeast(1)).coerceAtLeast(1),
    min(viewer.pager.state.width.coerceAtLeast(1), viewer.pager.state.height.coerceAtLeast(1)).coerceAtLeast(1),
) {
    init {
        minScale = 1f
        maxScale = 1f
        homeScale = 1f
    }

    override val backgroundColor: Int
        get() = try {
            viewer.readerBackgroundColor()
        } catch (_: Exception) {
            Color.BLACK
        }

    override fun render(dst: GPUTexture, x: Float, y: Float, scale: Float) {
        if (viewer.isDestroyed || dst.width <= 0 || dst.height <= 0) return
        val lines: MutableList<String> = mutableListOf()
        try {
            prevChapter?.chapter?.let { chapter ->
                lines.add(viewer.activity.stringResource(MR.strings.action_previous_chapter) + ": " + chapter.name)
            }
            nextChapter?.chapter?.let { chapter ->
                lines.add(viewer.activity.stringResource(MR.strings.action_next_chapter) + ": " + chapter.name)
            }
        } catch (_: Exception) {
        }

        val text = lines.joinToString("\n")
        if (text.isBlank()) return

        val padding = try {
            with(viewer.pager.state.density) { 24.dp.toPx() }
        } catch (_: Exception) {
            24f
        }
        val size = try {
            scale * with(viewer.pager.state.density) { 16.dp.toPx() }
        } catch (_: Exception) {
            16f * scale
        }

        val cx = dst.width * (0.5f + scale * x)
        val cy = dst.height * (0.5f + scale * y)

        try {
            text(
                dst,
                viewer.activity.baseContext,
                FontFamily.Default,
                text,
                cx,
                cy,
                size,
                try {
                    viewer.readerOnBackgroundColor()
                } catch (_: Exception) {
                    Color.WHITE
                },
                align = TextAlign.Center,
                maxWidth = (dst.width - 2f * padding).coerceAtLeast(0f),
            )
        } catch (_: Exception) {
        }
    }
}
// Mihon <--
