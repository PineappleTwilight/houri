// Mihon -->
package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

import ca.mpreg.webgpuviewer.viewer.ImagePage
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import kotlin.math.abs

/** How far either side of the anchor [continuousPage] will cache; wider asks walk uncached. */
internal const val CONTINUOUS_PAGE_CACHE_RADIUS = 64

/** Sentinel for "this chapter slot has never been reconciled", and for an absent neighbour. */
private const val UNSEEN_VERSION = -1

/**
 * The per-anchor page window the continuous render walk resolves through.
 *
 * The walk asks for every page in the window on every frame, and each ask used to walk the
 * neighbour chain from the anchor - so a window reaching six pages below cost 1+2+3+4+5+6 chain
 * steps on the main thread inside the viewer's lock. Filled in as the walk goes, so the whole window
 * costs one pass. Entries hold page identities, and a decode swaps a page's image rather than its
 * identity, so a shell landing mid-frame is still seen.
 *
 * Deliberately invalidated by [WebGpuViewer.syncPageList] as well as by an anchor change: a split
 * replaces a page with its segments mid-session, and any entry still naming the removed parent is a
 * hole - its image is already cleaned, so that slot draws nothing and every slot below it sits one
 * page out, for as long as the anchor does not move.
 */
internal class ContinuousPageWindow(val radius: Int) {
    private val slots = arrayOfNulls<ViewerPage>(2 * radius + 1)
    private val known = BooleanArray(2 * radius + 1)

    @Volatile
    var anchor: ViewerPage? = null

    fun isKnown(index: Int): Boolean = known[index + radius]

    fun get(index: Int): ViewerPage? = slots[index + radius]

    fun put(index: Int, page: ViewerPage?) {
        slots[index + radius] = page
        known[index + radius] = true
    }

    /**
     * Marks [index] unknown again so the next ask for it re-walks, leaving the anchor and every
     * other slot alone.
     *
     * For an entry that has gone stale rather than for the whole window: the render walk hits this
     * once per page per frame, so dropping everything would throw away the fill-in the window
     * exists to provide. Out of range throws like the other accessors do.
     */
    fun invalidate(index: Int) {
        known[index + radius] = false
    }

    fun reset(anchor: ViewerPage?) {
        this.anchor = anchor
        known.fill(false)
    }
}

/**
 * Last [eu.kanade.tachiyomi.ui.reader.model.ReaderChapter.pageListVersion] reconciled against, per
 * chapter slot.
 *
 * All three slots, not just the current chapter: the strip spans prev/current/next, and the chapter
 * that gets split mid-session is as likely to be one the reader is only part-way into as the one they
 * are on. Watching the current chapter alone left those splits unreconciled, which is the
 * in-between-chapters case.
 *
 * A flat list rather than one packed int because each chapter counts independently, so any
 * packing scheme eventually folds two distinct triples onto the same value and hides a change.
 */
internal class PageListWatch {
    private var seen = intArrayOf(UNSEEN_VERSION, UNSEEN_VERSION, UNSEEN_VERSION)

    /** False while nothing changed since the last [sync] / [reset]. */
    fun isUnchanged(versions: IntArray): Boolean = versions.contentEquals(seen)

    /** The versions last reconciled against, so a change log can name both sides of it. */
    fun current(): List<Int> = seen.toList()

    fun reset(versions: IntArray) {
        seen = versions
    }
}

/** Page-list versions of the three chapters a strip spans, in slot order. */
internal fun ViewerChapters.pageListVersions(): IntArray = intArrayOf(
    currChapter.pageListVersion,
    prevChapter?.pageListVersion ?: UNSEEN_VERSION,
    nextChapter?.pageListVersion ?: UNSEEN_VERSION,
)

/**
 * Reconciles against a page list the loader replaced underneath us.
 *
 * Cheap when nothing changed: three volatile int compares. Returns true when the viewer had to
 * move off a page the split removed, so the caller should stop - it was about to walk outward
 * from a node that no longer exists, and re-anchoring has already queued the right pages.
 */
internal fun WebGpuViewer.syncPageList(chapters: ViewerChapters): Boolean {
    val version = chapters.pageListVersions()
    if (pageListWatch.isUnchanged(version)) return false
    logcat(LogPriority.DEBUG) {
        "Page list changed ${pageListWatch.current()} -> ${version.toList()} - reconciling"
    }
    pageListWatch.reset(version)
    // Any replacement invalidates the render window, not only one the reader happens to be looking
    // at. The split is normally announced long before the reader reaches it - the whole point of
    // preloading a long strip - and a window entry naming the removed parent keeps drawing a
    // cleaned-up page forever, with the rest of the window offset by the segment count.
    continuousPageWindow.reset(null)
    val discarded = evictReplacedPages() ?: return false
    // Re-enter from whatever now stands in the discarded page's place - not from the chapter's
    // resume target, which is where the chapter was opened and can be pages away from where the
    // reader actually was.
    val replacement = discarded.page.chapter.splitReplacementOf(discarded.page) ?: discarded.page
    logcat(LogPriority.DEBUG) {
        "Re-anchoring on the replacement for ch=${discarded.page.chapter.chapter.id}/" +
            "i=${discarded.page.index} (${replacement.index}, segment=${replacement.splitSegment})"
    }
    // No eviction reference: the one available is the shell just torn down, and eviction distance
    // is measured from the reference's own position - which a superseded page no longer has, so
    // every same-chapter distance came out shifted by one for the call that builds the page the
    // window is being re-anchored on.
    reanchorTo(getSpreadAnchor(getPage(replacement, null)))
    return true
}

/**
 * Everything a [ViewerReaderPage] owns, released in one place.
 *
 * A shell holds more than its [ImagePage]: a spread composed from it, the source bytes a
 * height-match rescale would need, a rescale already running against them, the pair of pages a
 * translation swap parked, and the coalesced retry job armed for that rescale. Any of those
 * outlives the image and holds GPU memory or a coroutine, so dropping the shell without releasing
 * them leaks.
 *
 * This existed as six hand-copied subsets, and they had already drifted - which is how
 * `wantedByRender` ended up cleared on the eviction path but not the device-loss path, leaving a
 * shell the one-shot [ensureDecoding] would refuse to re-queue. One list, one owner.
 *
 * Takes no lock: [ViewerPage.state] and every field here are volatile, and `cleanup()` only posts
 * its GPU work to a background scope rather than performing it, so a caller holding [lock] is
 * neither needed nor harmed.
 */
internal fun WebGpuViewer.releasePageResources(page: ViewerReaderPage) {
    // Before the image goes: cleanupCompare compares its two parked pages against the current one
    // to decide which is the owner, so it has to run while the current one is still set.
    resetSpreadHeightRetry(page)
    runCatching { page.spreadPage?.cleanup() }
    page.spreadPage = null
    runCatching { page.cleanupCompare() }
    page.spreadBytes = null
    page.rescaleInFlight = false
    // ensureDecoding is one-shot against this flag, so a shell that is being dropped must stop
    // counting as renderer-demanded or it can never be queued again if it is ever handed back.
    page.wantedByRender = false
    page.state = PageState.IDLE
    runCatching { page.imagePage.cleanup() }
}

/**
 * Drops cached shells for pages a split has replaced, and reports the page the viewer should
 * land on if it was showing one of them.
 *
 * A page too tall for the decoder is replaced by its segments after this viewer has already
 * built its page graph. The parent shell stays cached under its own [PageKey.Reader] and keeps
 * rendering the segment its stream now points at, while the chapter's list also holds that
 * segment - so the strip was drawn twice, the whole and the pieces overlapping. Nothing else
 * evicts it: it is not idle, not farthest, and its key still looks valid.
 *
 * Returns the shell that was on screen when a split removed it, or null when nothing the viewer
 * was showing was removed. [currentPage] is deliberately left naming it: [reanchorTo] overwrites it
 * immediately, and nulling it here only gave the interleaved [WebGpuViewer.getPage] a cache with no
 * protected live page and the render a frame in which no page was current at all.
 */
private fun WebGpuViewer.evictReplacedPages(): ViewerReaderPage? {
    var dropped: ViewerReaderPage? = null
    synchronized(lock) {
        val orphaned = pageCache.values.filterIsInstance<ViewerReaderPage>()
            .filter { it.page.supersededBySplit }
        if (orphaned.isEmpty()) return null
        logcat(LogPriority.DEBUG) {
            "Dropping ${orphaned.size} superseded shell(s): " +
                orphaned.joinToString { "ch=${it.page.chapter.chapter.id}/i=${it.page.index}" }
        }
        orphaned.forEach { shell ->
            pageCache.remove(pageKey(shell))
            decodeQueue.remove(shell)
            releasePageResources(shell)
            stuckSignal.trySend(Unit)
        }
        // Read through a local: currentPage is a var, so the compiler will not smart-cast it past
        // the check below, and the shell is the only thing still holding the page once it is
        // out of the cache.
        val shown = currentPage
        if (shown is ViewerReaderPage && shown in orphaned) {
            dropped = shown
        }
    }
    return dropped
}

/**
 * Resolves the page the render asks for [index] spreads/steps away from the current one, and
 * makes sure it is queued for decode. This is the viewer's whole job inside the render loop.
 *
 * Continuous never forms spreads: [getSpreadAnchor] and [buildSpreadPage] both early-return the
 * page's own imagePage, so they are skipped outright instead of re-proving it on every frame walk.
 * Pager keeps the full pipeline, including the identity reuse inside [buildSpreadPage].
 */
internal fun WebGpuViewer.resolveFetchedPage(index: Int): ImagePage? {
    val current = currentPage ?: return null

    if (isContinuous) {
        val page = continuousPage(index) ?: return null
        ensureDecoding(page)
        return page.imagePage
    }

    // For index 0, return the current spread
    if (index == 0) {
        val anchor = getSpreadAnchor(current)
        ensureDecoding(anchor)
        return buildSpreadPage(anchor)
    }

    // Navigate by spreads from current
    var page = current
    val step = if (index > 0) 1 else -1
    repeat(abs(index)) {
        page = nextPage(page, step) ?: return null
    }

    val anchor = getSpreadAnchor(page)
    ensureDecoding(anchor)
    return buildSpreadPage(anchor)
}

internal fun WebGpuViewer.continuousPage(index: Int): ViewerPage? {
    val window = continuousPageWindow
    if (index !in -window.radius..window.radius) {
        return walkContinuousPage(index)
    }
    if (window.isKnown(index)) {
        val hit = window.get(index)
        if (hit == null) return null
        // Identity, not key: a shell the cache dropped and rebuilt is a different object in the
        // same slot. Eviction here is routine rather than exceptional - the continuous cache
        // budget is 7 + 2*reach against a window radius of 64 - and the window outlives every
        // eviction, because only an anchor change, a split, a rebuild or a device loss resets it.
        // Serving the evicted shell is what makes a page in a scrolled-into chapter never load:
        // the render draws its cleaned-up image, and the decode worker drops any page the cache
        // no longer holds, so nothing ever decodes it. Re-walking rebuilds it.
        //
        // Read under the lock rather than bare: the walk below takes it on every step anyway, and
        // pageCache is a LinkedHashMap that eviction mutates from the viewer's own threads.
        if (synchronized(lock) { pageInCache(hit) }) return hit
        window.invalidate(index)
    }

    val anchor = window.anchor ?: currentPage ?: return null
    if (window.anchor !== anchor) {
        window.reset(anchor)
    }
    window.put(0, anchor)

    // Walks outward from the anchor, recording every index it passes so a later ask for a
    // nearby index is a cache hit instead of a fresh chain walk.
    var page: ViewerPage? = anchor
    val step = if (index > 0) 1 else -1
    var at = 0
    while (at != index) {
        page = if (step > 0) page?.next else page?.prev
        at += step
        // Not remembered. A null here means the chain could not be followed *yet* - at a chapter
        // boundary that is the next chapter still loading, and its pages are null until the
        // loader publishes them. Caching it made the gap permanent: the render walk reported the
        // document ending there, so the state clamped the scroll back on every frame (the pages
        // ran away downwards as the reader pushed forward), and because the clamp stops the walk
        // before it can emit a page change, the anchor never moved and nothing reset the window.
        // Re-resolving each ask costs a few memoised chain steps - NeighborLink, which hardened
        // against exactly this, keys on the chapter's load state - and is the whole difference
        // between a boundary that fills in and one that stays blank.
        //
        // The hole a split leaves is still cached, by syncPageList's own reset rather than here.
        if (page == null) return null
        window.put(at, page)
    }
    return page
}

private fun WebGpuViewer.walkContinuousPage(index: Int): ViewerPage? {
    var page: ViewerPage? = currentPage ?: return null
    val step = if (index > 0) 1 else -1
    repeat(abs(index)) {
        page = (if (step > 0) page?.next else page?.prev) ?: return null
    }
    return page
}

/**
 * Gets or creates a page. Thread-safe.
 * @param referencePage The page to use as reference for eviction (defaults to currentPage)
 */
fun WebGpuViewer.getPage(page: ReaderPage, referencePage: ViewerPage? = null): ViewerPage {
    val key = PageKey.Reader(page.chapter.chapter.id, page.index)
    return synchronized(lock) {
        findInCache(key) ?: ViewerReaderPage(this, page).also { newPage ->
            pageCache[key] = newPage
            evictDownToBudget(referencePage ?: newPage)
        }
    }
}

fun WebGpuViewer.getPage(
    prevChapter: ReaderChapter?,
    nextChapter: ReaderChapter?,
    referencePage: ViewerPage? = null,
): ViewerPage {
    val key = PageKey.Transition(prevChapter?.chapter?.id, nextChapter?.chapter?.id)
    return synchronized(lock) {
        findInCache(key) ?: ViewerTransitionPage(this, prevChapter, nextChapter).also { newPage ->
            pageCache[key] = newPage
            evictDownToBudget(referencePage ?: newPage)
        }
    }
}

/**
 * Trims the cache back to [WebGpuViewer.cacheSize], evicting furthest-first.
 *
 * The iteration guard matters: [evictFarthestPage] declines to take the anchor, the live current
 * page, or anything drawn last frame, so a cache made entirely of those cannot shrink and the loop
 * would otherwise spin forever.
 */
private fun WebGpuViewer.evictDownToBudget(referencePage: ViewerPage?) {
    val budget = cacheSize.coerceAtLeast(1)
    var guard = 0
    while (pageCache.size > budget && guard++ < 16) {
        val before = pageCache.size
        evictFarthestPage(referencePage)
        if (pageCache.size == before) break
    }
}

/**
 * Queue a page for decoding. If prioritize=true, moves existing queued page to front.
 */
internal fun WebGpuViewer.preloadPage(page: ViewerPage, prioritize: Boolean = false) {
    synchronized(lock) {
        val cachedPage = findInCache(pageKey(page)) ?: return
        if (cachedPage is ViewerReaderPage) {
            queueForDecode(cachedPage, prioritize)
        }
    }
}

internal fun WebGpuViewer.preloadPages(page: ViewerPage) {
    // Must precede the rest: [page] itself can be one a split replaced, and all of it walks outward from it.
    viewerChapters?.let { chapters ->
        if (syncPageList(chapters)) return
    }

    // Get the canonical page from cache to ensure we're working with current data
    val key = pageKey(page)
    val cachedPage = synchronized(lock) { findInCache(key) } ?: return

    // Decoding follows reading order: current, then ahead nearest-first, then behind.
    //
    // queueForDecode inserts non-priority pages at the front and the worker pops from the back, so
    // within a group the iteration order is the reverse of the pop order - hence nearest-first
    // iteration, and hence the ahead group being inserted before the behind group. Inserting them
    // the other way round pops behind-before-ahead.

    // Ahead: inserted first, so it ends up nearest the back and pops first.
    val nextPages = mutableListOf<ViewerPage>()
    var p: ViewerPage? = cachedPage
    for (i in 0 until preloadAhead) {
        p = p?.next ?: break
        nextPages.add(p)
    }
    nextPages.forEach { preloadPage(it) }

    // Behind: inserted last, so it ends up nearest the front and pops last.
    val prevPages = mutableListOf<ViewerPage>()
    p = cachedPage
    for (i in 0 until preloadBehind) {
        p = p?.prev ?: break
        prevPages.add(p)
    }
    prevPages.forEach { preloadPage(it) }

    // Current spread is prioritized so it pops ahead of everything, with its partner alongside it
    // because in dual-page mode the two share the screen.
    cachedPage.next?.let { preloadPage(it, prioritize = true) }
    preloadPage(cachedPage, prioritize = true)
}

// KMK -->
// Memory-pressure shed: shrink the cache toward current + one neighbor each
// side via the hardened farthest-first evictor. Evicted pages re-decode on
// demand; the decode worker skips them while out of cache, so no wasted work.
internal fun WebGpuViewer.shrinkCacheOnTrim() {
    if (isDestroyed) return
    try {
        synchronized(lock) {
            val anchor = currentPage
            var guard = 0
            while (pageCache.size > 3 && guard++ < 16) {
                val before = pageCache.size
                evictFarthestPage(anchor)
                if (pageCache.size == before) break
            }
        }
        pager.state.invalidate()
    } catch (_: Exception) {}
}

/**
 * Whether a page at signed [distance] from the anchor belongs to the directional preload window
 * and must be kept while anything outside it exists.
 *
 * The one slack each way is for a spread partner or a transition page, which shares a slot with the
 * page either side of it. A null [distance] - no chapter relation to the anchor at all - is never
 * inside: that is what a page a split just superseded measures as once its anchor has no position,
 * and shedding those first is the safe answer because they are exactly the pages the replacement is
 * about to replace.
 */
internal fun isInsidePreloadWindow(distance: Int?, ahead: Int, behind: Int): Boolean =
    distance != null &&
        (distance == 0 || distance in 1..ahead + 1 || distance in -(behind + 1)..-1)

/**
 * Evicts the page farthest from reference. Must be called while holding lock.
 *
 * Hardened: victim selection is distance-based, computed from chapter/index math
 * instead of a cache chain-walk. The old walk needed every intermediate page
 * cached to measure distance, so any gap (chapter edge, transition page, earlier
 * eviction) broke the walk and eviction fell back to oldest-first - eating pages
 * right next to the one being read and re-showing them as placeholders (flicker,
 * and unloads when scrolling back). Distance here is gap-proof: same-chapter
 * pages use index math, adjacent-chapter pages count from the chapter edge, a
 * transition page bridging the anchor chapter sits at distance 0, and anything
 * unrelated sorts farthest. Pages inside the directional preload window are
 * never victims while anything outside it exists; in-flight decodes (non-IDLE)
 * are only taken when no IDLE victim exists. Distance ties prefer undecoded
 * placeholder shells over decoded content (dropping them is invisible), then
 * break oldest-first via insertion order. The anchor and the live current page
 * are never candidates. Pages the renderer drew on the last frame
 * (ImagePage.isOnScreen) are never victims while any undrawn candidate exists,
 * regardless of distance: the distance math is anchored on currentPage, which the
 * continuous viewer tracks through the submodule's relative +/-1 page-change
 * deltas, so any lockstep disagreement mis-centers the window and distance alone
 * would destroy visible pages (blank gaps on the next frame, since the capture
 * skips destroyed pages, plus a re-decode loop when scrolling back). The
 * last-drawn set is ground truth for visibility, so it overrules distance. The
 * drawn read may lag the render thread by a frame; keeping a stale-drawn page is
 * invisible, destroying a live one is not. Fallback order is oldest undrawn,
 * then oldest, so the cache stays bounded exactly as before.
 *
 * KMK --> Decoded-but-far reads like "the pages I already loaded unloaded themselves".
 * It is not: the continuous viewer keeps a window far larger than the screen, so scrolling out
 * and back re-enters a page that was evicted while its neighbour was still a placeholder. Both
 * halves of that cost the reader - the re-decode, and the placeholder it lands on.
 *
 * evictionCost generalises the old isCheapPlaceholder tiebreak into three tiers: a shell that
 * never decoded is free to drop, a queued or in-flight decode costs the work not yet done, and
 * decoded content costs a finished decode plus the placeholder the reader lands on. Ties on
 * distance then drop the cheapest tier first.
 *
 * A page drawn on the last frame is already excluded below (isOnScreen), which matters here:
 * the distance math is anchored on currentPage, which the continuous viewer tracks through the
 * submodule's relative page deltas, so a lockstep disagreement mis-centres the window and
 * distance alone would then destroy visible pages.
 */
internal fun WebGpuViewer.evictFarthestPage(reference: ViewerPage? = null) {
    val anchor = reference ?: currentPage ?: pageCache.values.lastOrNull() ?: return
    val liveCurrent = currentPage

    fun evictionCost(page: ViewerPage): Int = when {
        page is ViewerReaderPage && !page.isDecoded -> 0
        page.state == PageState.QUEUED || page.state == PageState.DECODING -> 1
        else -> 2
    }

    // Higher rank sorts as the farther victim; ties prefer the cheaper page, then earliest
    // insertion (strict > keeps the first encounter).
    fun beats(rank: Int, cost: Int, bestRank: Int, bestCost: Int, hasBest: Boolean): Boolean {
        if (!hasBest) return true
        if (rank != bestRank) return rank > bestRank
        return cost < bestCost
    }

    var bestIdle: ViewerPage? = null
    var bestIdleRank = Int.MIN_VALUE
    var bestIdleCost = Int.MAX_VALUE
    var bestAny: ViewerPage? = null
    var bestAnyRank = Int.MIN_VALUE
    var bestAnyCost = Int.MAX_VALUE
    var oldestIdle: ViewerPage? = null
    var oldestAny: ViewerPage? = null
    // Oldest non-anchor candidates the renderer did NOT draw last frame; preferred
    // over oldestIdle/oldestAny so the absolute fallback stays a last resort.
    var oldestIdleSafe: ViewerPage? = null
    var oldestAnySafe: ViewerPage? = null

    for (page in pageCache.values) {
        if (page === anchor) continue
        if (liveCurrent != null && page === liveCurrent) continue
        if (oldestAny == null) oldestAny = page
        val isIdle = page.state == PageState.IDLE
        if (isIdle && oldestIdle == null) oldestIdle = page
        // Drawn-page immunity (see KDoc): drawn pages only feed the absolute
        // oldest fallback and never the distance-ranked victims.
        val drawn = page.imagePage.isOnScreen
        if (!drawn) {
            if (oldestAnySafe == null) oldestAnySafe = page
            if (isIdle && oldestIdleSafe == null) oldestIdleSafe = page
        } else {
            continue
        }

        val distance = pageDistance(anchor, page)
        // Inside the directional preload window (plus one slack for a spread
        // partner or transition page): keep while anything outside it exists.
        if (!isInsidePreloadWindow(distance, preloadAhead, preloadBehind)) {
            // Unrelated chapters sort past every related page, so stale shells go first.
            // Absolute reach: a stale page far behind must shed before a fresh shell
            // just past the leading edge. Signed comparison did the opposite - every
            // prewarm shell past the window self-evicted on insert, so decode-ahead
            // never completed (its queue entry is removed with it and the worker
            // skips out-of-cache pages) and fast scrolling arrived at placeholders.
            val rank = distance?.let { abs(it) } ?: Int.MAX_VALUE
            val cost = evictionCost(page)
            if (isIdle && beats(rank, cost, bestIdleRank, bestIdleCost, bestIdle != null)) {
                bestIdleRank = rank
                bestIdleCost = cost
                bestIdle = page
            }
            if (beats(rank, cost, bestAnyRank, bestAnyCost, bestAny != null)) {
                bestAnyRank = rank
                bestAnyCost = cost
                bestAny = page
            }
        }
    }

    // Fully in-window cache (steady state plus one new shell): drop the farthest
    // page at the window edge rather than the oldest, which may be adjacent.
    // Placeholders go before decoded content at equal reach, for the same
    // no-visible-flicker reason as above.
    var victim = bestIdle ?: bestAny
    if (victim == null) {
        var edgeIdle: ViewerPage? = null
        var edgeIdleRank = Int.MIN_VALUE
        var edgeIdleCost = Int.MAX_VALUE
        var edgeAny: ViewerPage? = null
        var edgeAnyRank = Int.MIN_VALUE
        var edgeAnyCost = Int.MAX_VALUE
        for (page in pageCache.values) {
            if (page === anchor) continue
            if (liveCurrent != null && page === liveCurrent) continue
            // Drawn-page immunity (see KDoc).
            if (page.imagePage.isOnScreen) continue
            val distance = pageDistance(anchor, page) ?: continue
            val rank = abs(distance)
            val cost = evictionCost(page)
            if (page.state == PageState.IDLE &&
                beats(rank, cost, edgeIdleRank, edgeIdleCost, edgeIdle != null)
            ) {
                edgeIdleRank = rank
                edgeIdleCost = cost
                edgeIdle = page
            }
            if (beats(rank, cost, edgeAnyRank, edgeAnyCost, edgeAny != null)) {
                edgeAnyRank = rank
                edgeAnyCost = cost
                edgeAny = page
            }
        }
        victim = edgeIdle ?: edgeAny
    }

    val toRemove = victim ?: oldestIdleSafe ?: oldestAnySafe ?: oldestIdle ?: oldestAny ?: return

    pageCache.remove(pageKey(toRemove))
    decodeQueue.remove(toRemove)
    if (toRemove is ViewerReaderPage) {
        logcat(LogPriority.DEBUG) {
            "Evicted ch=${toRemove.page.chapter.chapter.id}/i=${toRemove.page.index} " +
                "state=${toRemove.state} decoded=${toRemove.isDecoded} " +
                "drawn=${toRemove.imagePage.isOnScreen} cache=${pageCache.size}"
        }
    }
    // Removing a page from the queue is what can strand an unrelated in-flight page, so the sweep
    // runs here rather than on a timer.
    stuckSignal.trySend(Unit)
    if (toRemove is ViewerReaderPage) {
        releasePageResources(toRemove)
    } else {
        toRemove.imagePage.cleanup()
    }
}

/**
 * Signed page distance from [anchor] to [page]: positive ahead, negative behind.
 * Same-chapter pages use index math; pages in the adjacent chapters count inward
 * from the shared chapter edge; a transition page bridging the anchor chapter is
 * 0 (drawn next, never a victim while anything else exists). Null when [page]
 * belongs to no chapter adjacent to the anchor - unrelated shells that should be
 * evicted first. Gap-proof by construction: no cache walk, only chapter/index
 * math, so holes from earlier evictions cannot mismeasure it.
 */
internal fun WebGpuViewer.pageDistance(anchor: ViewerReaderPage, page: ViewerPage): Int? {
    val anchorChapter = anchor.page.chapter
    val anchorPosition = anchorChapter.positionOf(anchor.page)
    return when (page) {
        is ViewerReaderPage -> {
            val chapter = page.page.chapter
            when {
                // A reference the chapter no longer lists has no position, so every same-chapter
                // distance from it would be measured against -1 and shifted by one. Reporting null
                // sorts such a reference's whole chapter to the far end, which is the safe answer:
                // those pages are the ones a split just made suspect.
                chapter === anchorChapter && anchorPosition >= 0 ->
                    chapter.positionOf(page.page) - anchorPosition
                chapter === anchor.nextChapter -> {
                    val edge = anchorChapter.pages?.size?.let { it - 1 - anchorPosition } ?: 0
                    edge + 1 + chapter.positionOf(page.page)
                }
                chapter === anchor.prevChapter -> {
                    val edge = chapter.pages?.size?.let { it - 1 - chapter.positionOf(page.page) } ?: 0
                    -(anchorChapter.positionOf(anchor.page) + 1 + edge)
                }
                else -> null
            }
        }
        is ViewerTransitionPage ->
            if (page.prevChapter === anchorChapter || page.nextChapter === anchorChapter) 0 else null
        else -> null
    }
}

/**
 * Signed page distance from a [ViewerTransitionPage] anchor to [page].
 *
 * Transition anchors sit exactly on chapter boundaries, where the continuous viewer
 * spends whole reading sessions, so they need the same gap-proof math as reader
 * anchors: without it every page measured null (outside-window), eviction fell back
 * to oldest-first, and half-visible decoded pages next to the transition were eaten
 * and re-shown as placeholders in a loop (constant flicker at chapter edges).
 * Pages in the next chapter count up from +1, pages in the previous chapter count
 * down from -1, and a transition sharing either bridge chapter sits at 0.
 */
internal fun WebGpuViewer.pageDistance(anchor: ViewerTransitionPage, page: ViewerPage): Int? {
    val prev = anchor.prevChapter
    val next = anchor.nextChapter
    return when (page) {
        is ViewerReaderPage -> {
            val chapter = page.page.chapter
            when {
                chapter === next -> chapter.positionOf(page.page) + 1
                chapter === prev -> {
                    val size = chapter.pages?.size
                    if (size == null) -1 else -(size - chapter.positionOf(page.page))
                }
                else -> null
            }
        }
        is ViewerTransitionPage ->
            if (page.prevChapter === prev || page.prevChapter === next ||
                page.nextChapter === prev || page.nextChapter === next
            ) {
                0
            } else {
                null
            }
        else -> null
    }
}

/**
 * Signed page distance from any [anchor] to [page]. Dispatches to the reader or
 * transition overload above; null when [page] belongs to no chapter adjacent to
 * the anchor - unrelated shells that should be evicted first.
 */
internal fun WebGpuViewer.pageDistance(anchor: ViewerPage, page: ViewerPage): Int? = when (anchor) {
    is ViewerReaderPage -> pageDistance(anchor, page)
    is ViewerTransitionPage -> pageDistance(anchor, page)
    else -> null
}
// KMK <--
// Mihon <--
