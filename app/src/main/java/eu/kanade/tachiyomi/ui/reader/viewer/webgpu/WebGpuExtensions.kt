// KMK -->
package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

// The reader's internal extension point: named compartments in the decode path that other code can
// add to, replace, or switch off at runtime.
//
// "Internal" is load-bearing. Extensions are in-app implementations resolved through this registry -
// there is no DexClassLoader, no APK plugin, no code loaded from outside the build. What is dynamic
// about it is that the *set* changes while the app runs: a feature registers itself once the user
// turns it on, and swapping an implementation is a register call rather than an edit to the reader.
//
// The compartment split follows where the decode path can actually be handed to someone else:
// [transformBytes] sees a page's bytes before anything has looked at them, and [onPageDecoded] fires
// once a page has a finished GPU image. Upscaling is the first, translation the second. A single
// interface with defaults rather than one interface per compartment, so an extension can occupy both
// without being registered twice, and so `order` means the same thing everywhere.
//
// Two rules keep this from being a way to break the reader:
//
// Every extension is isolated. A throwing extension is logged and skipped, never propagated - the
// decode worker is a single thread that every page queues behind, so letting one extension's failure
// reach it would turn a bad plugin into the stall this whole area has already suffered from.
// Cancellation is the exception, and is always rethrown: it is not a failure, it is the reader going
// away, and swallowing it would leak the work instead of stopping it.

/**
 * The per-page facts an extension is given about what it is looking at.
 *
 * Deliberately a small value rather than the [ViewerReaderPage] itself. [transformBytes] runs before
 * the page has an image, and everything it could usefully consult is a stable identifier - which
 * means the composition itself can be exercised without a viewer, a GPU, or an Activity.
 */
interface WebGpuPageContext {
    /** Source chapter id, or -1 when the page is not tied to one. */
    val chapterId: Long

    /** Index of the page within its chapter. */
    val pageIndex: Int

    /** Manga id, or -1 when unknown. This is what per-series features key on. */
    val mangaId: Long
}

/** The plain-data form of [WebGpuPageContext], used when running the chain without a viewer. */
data class WebGpuPageRef(
    override val chapterId: Long = -1L,
    override val pageIndex: Int = -1,
    override val mangaId: Long = -1L,
) : WebGpuPageContext

/**
 * A reader extension.
 *
 * Every member has a default, so an extension implements only the compartment it cares about.
 */
interface WebGpuExtension {
    /** Stable identity. Registering a second extension under a live id replaces the first. */
    val id: String

    /** Lower runs first. Ties keep registration order. */
    val order: Int get() = DEFAULT_ORDER

    /**
     * Sees a page's bytes before anything else has, and may return a replacement.
     *
     * Suspend because real work belongs here: on-device upscaling is a native inference call, and an
     * extension that had to block a thread to do its job would be reintroducing the stall this decode
     * path has already suffered from. Returning null means "unchanged" and passes the input through
     * untouched. The output of one extension is the input of the next, so several can be chained.
     * Mutating the array in place is not allowed - it is the reader's live buffer.
     */
    suspend fun transformBytes(context: WebGpuPageContext, bytes: ByteArray): ByteArray? = null

    /**
     * Called once a page has a finished image, with the bytes it was decoded from.
     *
     * [sourceBytes] are the page's own bytes rather than any extension's output, so a consumer sees
     * the original regardless of what the transform chain did. Runs on the decode thread.
     */
    fun onPageDecoded(viewer: WebGpuViewer, page: ViewerReaderPage, sourceBytes: ByteArray) {}
}

/** Where an extension sits when it does not say. */
const val DEFAULT_ORDER: Int = 500

/**
 * The installed extension set.
 *
 * Backed by copy-on-write because registration legitimately races the decode thread: a user toggling
 * a feature mid-chapter registers while pages are being decoded. Readers take a snapshot and iterate
 * that, so an install can never be seen half-applied and never mutates a list someone is walking.
 */
object WebGpuExtensions {

    private val installed = CopyOnWriteArrayList<WebGpuExtension>()
    private val switchedOff = ConcurrentHashMap.newKeySet<String>()

    /**
     * Adds [extension], replacing any extension already registered under the same id.
     *
     * Replacing rather than appending is the whole point: an implementation can be swapped at runtime
     * - a different upscale backend, say - without the reader learning anything about it.
     */
    fun install(extension: WebGpuExtension) {
        installed.removeAll { it.id == extension.id }
        installed.add(extension)
        installed.sortBy { it.order }
    }

    /** Removes the extension registered under [id]. Returns whether anything was removed. */
    fun uninstall(id: String): Boolean = installed.removeAll { it.id == id }

    /**
     * Switches a registered extension off without removing it.
     *
     * Separate from [uninstall] because a feature toggle is not a code change: the extension stays
     * installed so it can be switched back on without re-registering it.
     */
    fun setEnabled(id: String, enabled: Boolean): Boolean {
        if (enabled) return switchedOff.remove(id)
        if (installed.none { it.id == id }) return false
        return switchedOff.add(id)
    }

    fun isEnabled(id: String): Boolean = installed.any { it.id == id } && id !in switchedOff

    /** Ids of everything registered, switched on or not. */
    fun ids(): List<String> = installed.map { it.id }

    /**
     * A snapshot of the extensions that are currently switched on, in [WebGpuExtension.order].
     *
     * Callers iterate this rather than the live list. Copy-on-write makes that safe, but taking a
     * snapshot also means the order read here is the order used, rather than one that can shift
     * underneath the loop.
     */
    fun active(): List<WebGpuExtension> = installed.filter { it.id !in switchedOff }
}

/**
 * Runs every enabled extension's [WebGpuExtension.transformBytes] in order, threading the output of
 * one into the next.
 *
 * Deliberately a pure function over a list rather than a method on the viewer: the composition rules
 * - pass-through on null, chaining on output, isolation on failure - are the part with real logic, and
 * this shape is what lets them be tested without a GPU.
 */
internal suspend fun applyByteTransforms(
    extensions: List<WebGpuExtension>,
    context: WebGpuPageContext,
    bytes: ByteArray,
): ByteArray {
    var current = bytes
    for (extension in extensions) {
        val next = try {
            extension.transformBytes(context, current)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // A plugin that throws is a bug in the plugin. Dropping its contribution and keeping the
            // pipeline moving is the difference between one broken feature and an unreadable chapter.
            extension.logcat(LogPriority.ERROR, e) { "${extension.id} transformBytes failed" }
            null
        }
        if (next != null) current = next
    }
    return current
}

/**
 * Notifies every enabled extension that [page] finished decoding.
 *
 * The counterpart to [applyByteTransforms] for the compartment that runs against a live page rather
 * than a value, which is why it is not a pure function. Errors are isolated exactly as above: an
 * extension that throws here is logged and the rest still run, since a single misbehaving feature
 * must not cost the user the page.
 */
internal fun WebGpuViewer.runPageDecodedExtensions(page: ViewerReaderPage, sourceBytes: ByteArray) {
    for (extension in WebGpuExtensions.active()) {
        try {
            extension.onPageDecoded(this, page, sourceBytes)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            extension.logcat(LogPriority.ERROR, e) { "${extension.id} onPageDecoded failed" }
        }
    }
}

// KMK <--
