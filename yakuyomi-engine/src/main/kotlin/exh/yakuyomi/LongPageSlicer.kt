package exh.yakuyomi

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.min

/**
 * Quality-first tall-page slicing for long-strip (manhwa/webtoon) pages.
 *
 * Houri's library [li.joye.yakuyomi.engine.Pipeline] pre-scales any bitmap with a side over
 * 4000px, so a 800x12000 strip would be crushed 3x before detection/OCR ever see it. Slicing
 * happens here in the wrapper, before that call, on the original full-resolution bitmap: tall
 * pages are cut at low-variance gutter bands into overlapping slices, each slice runs through
 * the existing pipeline sequentially, and the translated slices are stitched back to one
 * full-size output. Constants mirror the K3-evidenced
 * `ManhwaSlicerService` (MIN 3600, TARGET 4800, MAX SEARCH 6200, HARD MAX 8000, gutter band
 * >= 55px); the overlap (128px) is owned by the upper slice both when stitching (upper drawn
 * last wins) and when attributing analysis regions (center in owned core).
 */
object LongPageSlicer {
    const val MIN_SLICE_HEIGHT = 3600
    const val TARGET_SLICE_HEIGHT = 4800
    const val MAX_SEARCH_HEIGHT = 6200
    const val HARD_MAX_HEIGHT = 8000
    const val MIN_GUTTER_BAND_HEIGHT = 55
    const val OVERLAP_PX = 128
    const val GATE_MIN_ASPECT = 2.3
    const val GATE_MAX_WIDTH = 4000
    const val TAIL_MERGE_RATIO = 0.3

    /** One slice in page coordinates. Non-last slices extend [OVERLAP_PX] below their cut. */
    data class Slice(val y: Int, val height: Int)

    /**
     * Slicing gate on the original bitmap: tall enough, narrow-strip aspect, and narrow enough
     * that a slice keeps the pipeline's internal pre-scale modest.
     */
    fun shouldSlice(width: Int, height: Int): Boolean {
        if (width <= 0 || height < MIN_SLICE_HEIGHT) return false
        if (width > GATE_MAX_WIDTH) return false
        return height.toDouble() / width.toDouble() >= GATE_MIN_ASPECT
    }

    /**
     * K3 gutter-band score: widest low-variance band wins, penalized by distance to the
     * target slice height. [cutOffset] is the candidate cut measured from the slice start.
     */
    fun scoreBand(bandHeight: Int, cutOffset: Int): Double =
        bandHeight * 2.0 - abs(cutOffset - TARGET_SLICE_HEIGHT) * 0.5

    /**
     * Picks the best cut as the middle of the highest-scoring gutter [bands] (absolute page
     * coordinates). [sliceStart] is the page y where the current slice starts. Returns -1 when
     * no band qualifies and the caller should fall back to lowest-energy cutting.
     */
    fun selectBestCut(bands: List<IntRange>, sliceStart: Int): Int {
        var bestCut = -1
        var bestScore = Double.NEGATIVE_INFINITY
        for (band in bands) {
            val bandHeight = band.last - band.first + 1
            if (bandHeight < MIN_GUTTER_BAND_HEIGHT) continue
            val cut = band.first + bandHeight / 2
            val score = scoreBand(bandHeight, cut - sliceStart)
            if (score > bestScore) {
                bestScore = score
                bestCut = cut
            }
        }
        return bestCut
    }

    /**
     * Merges a tiny tail into the previous slice: when the segment after the last cut is under
     * [TAIL_MERGE_RATIO] of the previous slice's full height, the last cut is dropped and the
     * previous slice extends to the page end.
     */
    fun mergeTailCuts(cuts: List<Int>, totalHeight: Int): List<Int> {
        if (cuts.isEmpty()) return cuts
        val merged = cuts.toMutableList()
        while (merged.isNotEmpty()) {
            val lastCut = merged.last()
            if (lastCut <= 0 || lastCut >= totalHeight) {
                merged.removeLast()
                continue
            }
            val prevCut = if (merged.size >= 2) merged[merged.size - 2] else 0
            val prevFull = (lastCut - prevCut) + OVERLAP_PX
            val tail = totalHeight - lastCut
            if (tail < prevFull * TAIL_MERGE_RATIO) {
                merged.removeLast()
            } else {
                break
            }
        }
        return merged
    }

    /**
     * Converts owned-boundary [cuts] into overlapping slices: each non-last slice extends
     * [OVERLAP_PX] below its cut so the shared band is processed by both neighbors (upper owns
     * it when stitching/attributing).
     */
    fun buildSlices(totalHeight: Int, cuts: List<Int>): List<Slice> {
        val valid = cuts.filter { it > 0 && it < totalHeight }.sorted()
        if (valid.isEmpty()) return listOf(Slice(0, totalHeight))
        val out = mutableListOf<Slice>()
        for (i in 0..valid.size) {
            val start = if (i == 0) 0 else valid[i - 1]
            val end = if (i < valid.size) min(valid[i] + OVERLAP_PX, totalHeight) else totalHeight
            if (end > start) out.add(Slice(start, end - start))
        }
        return out.ifEmpty { listOf(Slice(0, totalHeight)) }
    }

    /** Owned-core start (inclusive) of slice [index]: previous slice's full end, or 0. */
    fun ownedStart(index: Int, slices: List<Slice>): Int {
        if (index <= 0) return slices.firstOrNull()?.y ?: 0
        val prev = slices[index - 1]
        return prev.y + prev.height
    }

    /** Owned-core end (exclusive) of slice [index]: its own full end. */
    fun ownedEndExclusive(index: Int, slices: List<Slice>): Int {
        val s = slices[index]
        return s.y + s.height
    }

    /** Whether a page-coordinate center y belongs to slice [index]'s owned core. */
    fun ownsCenter(index: Int, slices: List<Slice>, centerY: Float): Boolean {
        val start = ownedStart(index, slices).toFloat()
        val end = ownedEndExclusive(index, slices).toFloat()
        return if (index == slices.lastIndex) {
            centerY >= start && centerY <= slices[index].y + slices[index].height
        } else {
            centerY >= start && centerY < end
        }
    }

    /**
     * Pure cut planning over page height: cuts while the remainder exceeds the target slice
     * height, so pages just over the target (e.g., 5000px) still slice instead of taking the
     * library's 4000px prescale whole. [findCut] maps a slice start to a target-anchored cut
     * (gutter band, else energy fallback, in production). A candidate that would strand a tiny
     * tail rebalances to MIN_SLICE_HEIGHT past the slice start, so the first slice never drops
     * below the minimum while the remainder stays a healthy final slice.
     */
    fun planCutsForHeight(totalHeight: Int, findCut: (Int) -> Int): List<Int> {
        if (totalHeight <= TARGET_SLICE_HEIGHT) return emptyList()
        val cuts = mutableListOf<Int>()
        var pos = 0
        var guard = 0
        while (totalHeight - pos > TARGET_SLICE_HEIGHT && guard++ < 8) {
            var cut = findCut(pos)
            if (cut <= pos || cut >= totalHeight) break
            val tail = totalHeight - cut
            if (tail in 1..TARGET_SLICE_HEIGHT && tail < ((cut - pos) + OVERLAP_PX) * TAIL_MERGE_RATIO) {
                cut = pos + MIN_SLICE_HEIGHT
                if (cut <= pos || cut >= totalHeight) break
                cuts.add(cut)
                break
            }
            cuts.add(cut)
            pos = cut
        }
        return mergeTailCuts(cuts, totalHeight)
    }

    /**
     * Plans owned-boundary cuts for [bitmap] (must be the original full-resolution page).
     * Returns an empty list when the page is not sliceable or fits in one target slice.
     */
    fun planCuts(bitmap: Bitmap): List<Int> {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0 || bitmap.isRecycled) return emptyList()
        if (!shouldSlice(width, height)) return emptyList()
        val row = IntArray(width)
        val nextRow = IntArray(width)
        return planCutsForHeight(height) { pos -> findTargetCut(bitmap, pos, row, nextRow) }
    }

    private fun findTargetCut(bitmap: Bitmap, pos: Int, row: IntArray, nextRow: IntArray): Int {
        val height = bitmap.height
        val scanEnd = min(height - 10, pos + MAX_SEARCH_HEIGHT)
        if (scanEnd > pos + MIN_SLICE_HEIGHT) {
            val bands = collectGutterBands(bitmap, pos + MIN_SLICE_HEIGHT, scanEnd, row)
            val gutterCut = selectBestCut(bands, pos)
            if (gutterCut > pos && gutterCut < height) return gutterCut
        }
        return lowestEnergyCut(bitmap, pos, min(height - 10, pos + HARD_MAX_HEIGHT), row, nextRow)
    }

    private fun collectGutterBands(bitmap: Bitmap, fromY: Int, toY: Int, row: IntArray): List<IntRange> {
        val bands = mutableListOf<IntRange>()
        var bandStart = -1
        for (y in fromY..toY) {
            if (isGutterRow(bitmap, y, row)) {
                if (bandStart < 0) bandStart = y
            } else if (bandStart >= 0) {
                if (y - bandStart >= MIN_GUTTER_BAND_HEIGHT) bands.add(IntRange(bandStart, y - 1))
                bandStart = -1
            }
        }
        if (bandStart >= 0 && toY - bandStart + 1 >= MIN_GUTTER_BAND_HEIGHT) {
            bands.add(IntRange(bandStart, toY))
        }
        return bands
    }

    private fun isGutterRow(bitmap: Bitmap, y: Int, row: IntArray): Boolean {
        val width = bitmap.width
        bitmap.getPixels(row, 0, width, 0, y, width, 1)
        var minL = 255
        var maxL = 0
        var edgeCount = 0
        var prevLum = -1
        var x = 0
        while (x < width) {
            val c = row[x]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val lum = (r * 77 + g * 150 + b * 29) shr 8
            if (lum < minL) minL = lum
            if (lum > maxL) maxL = lum
            if (prevLum >= 0 && abs(lum - prevLum) > 18) edgeCount++
            prevLum = lum
            x += 3
        }
        if (edgeCount > width / 300) return false
        if (minL >= 235) return true
        if (maxL <= 25) return true
        return maxL - minL <= 6
    }

    private fun lowestEnergyCut(bitmap: Bitmap, sliceStart: Int, scanEnd: Int, row: IntArray, nextRow: IntArray): Int {
        val width = bitmap.width
        val scanStart = sliceStart + MIN_SLICE_HEIGHT
        if (scanEnd <= scanStart) return -1
        var bestY = -1
        var lowest = Double.MAX_VALUE
        var y = scanStart
        while (y <= scanEnd) {
            if (y + 1 >= bitmap.height) break
            bitmap.getPixels(row, 0, width, 0, y, width, 1)
            bitmap.getPixels(nextRow, 0, width, 0, y + 1, width, 1)
            var energy = 0L
            var x = 10
            while (x < width - 10) {
                val c0 = row[x]
                val c1 = nextRow[x]
                val lum0 = (((c0 shr 16) and 0xFF) * 77 + ((c0 shr 8) and 0xFF) * 150 + (c0 and 0xFF) * 29) shr 8
                val lum1 = (((c1 shr 16) and 0xFF) * 77 + ((c1 shr 8) and 0xFF) * 150 + (c1 and 0xFF) * 29) shr 8
                energy += abs(lum0 - lum1)
                x += 6
            }
            val total = energy.toDouble() + abs(y - sliceStart - TARGET_SLICE_HEIGHT) * 0.1
            if (total < lowest) {
                lowest = total
                bestY = y
            }
            y += 4
        }
        return bestY
    }
}
