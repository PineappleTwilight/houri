// KMK -->
package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

import eu.kanade.tachiyomi.ui.reader.setting.UpscaleReaderHook

// The reader's own extensions, installed so they are present before any viewer can ask for them.
//
// Both are adapters over code that already works and is not going anywhere: [UpscaleReaderHook] is
// shared with the pager and webtoon readers, and translation is driven by TranslationManager with
// its own caching and gating. Moving them in means the reader stops knowing they exist; it does not
// mean reimplementing them, and the behaviour a user gets is byte-for-byte what it was.
//
// Registered from [WebGpuExtensions]' initialiser rather than from a call site, so there is no window
// in which a viewer exists without them. A user switching a feature off goes through
// [WebGpuExtensions.setEnabled], which leaves the instance registered and the behaviour out.

/**
 * On-device upscaling as a [WebGpuExtension].
 *
 * Occupies the transform compartment because that is exactly what it is: the display bytes the
 * decoder receives, with the originals kept for translation, EXIF and spread matching. Order is low
 * so it runs before anything else sees the bytes.
 */
internal object UpscaleExtension : WebGpuExtension {

    override val id: String = "houri.upscale"

    /** Before everything else, so later extensions see the upscaled bytes. */
    override val order: Int = 0

    override suspend fun transformBytes(context: WebGpuPageContext, bytes: ByteArray): ByteArray? =
        UpscaleReaderHook.upscaleDisplayBytes(context.mangaId.takeIf { it > 0 }, bytes)
}

/**
 * AI translation as a [WebGpuExtension].
 *
 * Occupies the post-decode compartment rather than the transform one: it replaces the finished image
 * with a translated one, and doing that to the encoded bytes would mean re-encoding a page that is
 * already on the GPU. [WebGpuExtension.onPageDecoded] is handed the page's own bytes, so it sees the
 * original regardless of what the transform chain did to the display copy.
 *
 * Its gating - enabled, per-manga, size limits, chapter load state - stays where it was, inside
 * scheduleTranslation, so turning a feature off upstream behaves identically here.
 */
internal object TranslationExtension : WebGpuExtension {

    override val id: String = "houri.translation"

    /**
     * After the transform chain. Not because translation needs upscaled bytes - it wants the
     * original - but because it must not delay the display path: it runs once the page is already
     * decoded and visible.
     */
    override val order: Int = 100

    override fun onPageDecoded(viewer: WebGpuViewer, page: ViewerReaderPage, sourceBytes: ByteArray) {
        viewer.scheduleTranslation(page, sourceBytes)
    }
}

/**
 * Installs the reader's built-in extensions.
 *
 * Idempotent, and deliberately not guarded against being called twice: [WebGpuExtensions.install]
 * replaces by id, so a second call cannot end up with two upscales chained. That matters because this
 * is reachable from more than one plausible place, and a doubled upscale would silently cost the
 * user twice the work for a marginally different image.
 */
internal fun installBuiltinWebGpuExtensions() {
    WebGpuExtensions.install(UpscaleExtension)
    WebGpuExtensions.install(TranslationExtension)
}
// KMK <--
