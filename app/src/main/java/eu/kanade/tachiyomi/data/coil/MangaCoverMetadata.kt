package eu.kanade.tachiyomi.data.coil

import android.graphics.BitmapFactory
import androidx.palette.graphics.Palette
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.coil.MangaCoverMetadata.setRatioAndColors
import eu.kanade.tachiyomi.ui.manga.MangaScreenModel
import mihon.app.di.globalAppGraph
import okio.BufferedSource
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.MangaCover
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Object that holds info about a covers size ratio + dominant colors
 * @author Jays2Kings
 */
object MangaCoverMetadata {
    private val preferences by lazy { globalAppGraph.libraryPreferences }
    private val coverCache by lazy { globalAppGraph.coverCache }

    fun load() {
        val ratios = preferences.coverRatios().get()
        MangaCover.coverRatioMap = ConcurrentHashMap(
            ratios.mapNotNull {
                val splits = it.split("|")
                val id = splits.firstOrNull()?.toLongOrNull()
                val ratio = splits.lastOrNull()?.toFloatOrNull()
                if (id != null && ratio != null) {
                    id to ratio
                } else {
                    null
                }
            }.toMap(),
        )
        val colors = preferences.coverColors().get()
        MangaCover.dominantCoverColorMap = ConcurrentHashMap(
            colors.mapNotNull {
                val splits = it.split("|")
                val id = splits.firstOrNull()?.toLongOrNull()
                val color = splits.getOrNull(1)?.toIntOrNull()
                val textColor = splits.getOrNull(2)?.toIntOrNull()
                if (id != null && color != null) {
                    id to (color to (textColor ?: 0))
                } else {
                    null
                }
            }.toMap(),
        )
    }

    /**
     * [setRatioAndColors] generate cover's color & ratio by reading cover's bitmap from [CoverCache].
     * It's called along with [MangaCoverFetcher.fetch] everytime a cover is **displayed** (anywhere).
     *
     * When called:
     *  - It removes saved colors from saved Prefs of [MangaCover.dominantCoverColorMap] if manga is not favorite.
     *  - If a favorite manga already restored [MangaCover.dominantCoverColors] then it
     * will skip actually reading bitmap, only extract ratio. Except when [MangaCover.vibrantCoverColor]
     * is not loaded then it will read bitmap & extract vibrant color.
     * => always set [force] to true so it will always re-calculate ratio & color.
     *
     * Set [MangaCover.dominantCoverColors] for favorite manga only.
     * Set [MangaCover.vibrantCoverColor] for all mangas.
     *
     * @param bufferedSource if not null then it will load bitmap from [BufferedSource], regardless of [ogFile]
     * @param ogFile if not null then it will load bitmap from [File]. If it's null then it will try to load bitmap
     *  from [CoverCache] using either [CoverCache.customCoverCacheDir] or [CoverCache.cacheDir]
     * @param force if true then it will always re-calculate ratio & color for favorite mangas.
     *
     * This is only for loading color first time it appears on Library/Browse. Any new colors caused by loading new
     * cover when open a manga detail or change cover will be updated separately on [MangaScreenModel.setPaletteColor].
     *
     * @author Jays2Kings, cuong-tran
     */
    fun setRatioAndColors(
        mangaCover: MangaCover,
        bufferedSource: BufferedSource? = null,
        ogFile: File? = null,
        onlyDominantColor: Boolean = true,
        force: Boolean = false,
    ) {
        if (!mangaCover.isMangaFavorite) {
            mangaCover.remove()
            if (mangaCover.vibrantCoverColor != null) return
        }

        if (mangaCover.isMangaFavorite && onlyDominantColor && mangaCover.dominantCoverColors != null) return

        val options = BitmapFactory.Options()

        val updateColors =
            (mangaCover.isMangaFavorite && mangaCover.dominantCoverColors == null) ||
                (!onlyDominantColor && mangaCover.vibrantCoverColor == null) ||
                force

        if (updateColors) {
            /*
             * + Manga is Favorite & doesn't have dominant color
             *   For non-favorite, it doesn't care if dominant is there or not, if it has vibrant color then it will
             *   already be returned from beginning.
             * + [onlyDominantColor] = false
             *   - Manga doesn't have vibrant color
             */
            options.inSampleSize = SUB_SAMPLE
        } else {
            /*
             * + [onlyDominantColor] = true
             *   - Manga is Favorite & already have dominant color
             * + [onlyDominantColor] = false
             *   - Manga is Favorite & already have dominant color & vibrant color
             *   - Manga is not Favorite & already have vibrant color (already skip at beginning)
             */
            // Just trying to update ratio without actual reading bitmap (bitmap will be null)
            options.inJustDecodeBounds = true
            // Don't even need to update ratio because we don't use it yet.
            return
        }

        val file = ogFile
            ?: coverCache.getCustomCoverFile(mangaCover.mangaId).takeIf { it.exists() }
            ?: coverCache.getCoverFile(mangaCover.url)

        val rawBitmap = when {
            bufferedSource != null -> BitmapFactory.decodeStream(bufferedSource.inputStream(), null, options)
            file?.exists() == true -> BitmapFactory.decodeFile(file.path, options)
            else -> return
        } ?: return

        // Palette's getPixelsFromBitmap cannot read HARDWARE bitmaps (getSkBitmap abort -> SIGABRT).
        // This happens when browsing extensions that load covers via HARDWARE (e.g. Coil with
        // hardware bitmaps). Copy to software ARGB_8888 first, or skip palette if copy fails.
        val bitmapForPalette = if (rawBitmap.config == android.graphics.Bitmap.Config.HARDWARE) {
            try {
                val software = rawBitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                if (!rawBitmap.isRecycled) rawBitmap.recycle()
                software ?: return
            } catch (_: Throwable) {
                try {
                    if (!rawBitmap.isRecycled) rawBitmap.recycle()
                } catch (_: Throwable) {}
                return
            }
        } else {
            rawBitmap
        }

        try {
            Palette.from(bitmapForPalette).generate {
                if (it == null) return@generate
                if (mangaCover.isMangaFavorite) {
                    it.dominantSwatch?.let { swatch ->
                        mangaCover.dominantCoverColors = swatch.rgb to swatch.titleTextColor
                    }
                }
                val color = it.getBestColor() ?: return@generate
                mangaCover.vibrantCoverColor = color
            }
        } catch (_: Throwable) {
            // Palette can still throw for recycled/hardware edge cases (e.g. 16KB page size
            // native load failure leaves a 1x1 placeholder). Never crash the browse flow.
        } finally {
            try {
                if (!bitmapForPalette.isRecycled) bitmapForPalette.recycle()
            } catch (_: Throwable) {}
        }
        if (mangaCover.isMangaFavorite && options.outWidth != -1 && options.outHeight != -1) {
            val raw = options.outWidth / options.outHeight.toFloat()
            mangaCover.ratio = raw.coerceIn(MangaCover.MIN_COVER_RATIO, MangaCover.MAX_COVER_RATIO)
        }
    }

    fun MangaCover.remove() {
        MangaCover.coverRatioMap.remove(mangaId)
        MangaCover.dominantCoverColorMap.remove(mangaId)
    }

    fun savePrefs() {
        val ratioCopy = MangaCover.coverRatioMap.toMap()
        if (ratioCopy.size > 2000) {
            val trimmed = ratioCopy.entries.sortedBy { it.key }.takeLast(2000).associate { it.key to it.value }
            MangaCover.coverRatioMap.clear()
            MangaCover.coverRatioMap.putAll(trimmed)
        }
        val colorCopy = MangaCover.dominantCoverColorMap.toMap()
        if (colorCopy.size > 2000) {
            val trimmed = colorCopy.entries.sortedBy { it.key }.takeLast(2000).associate { it.key to it.value }
            MangaCover.dominantCoverColorMap.clear()
            MangaCover.dominantCoverColorMap.putAll(trimmed)
        }
        preferences.coverRatios().set(MangaCover.coverRatioMap.map { "${it.key}|${it.value}" }.toSet())
        preferences.coverColors().set(MangaCover.dominantCoverColorMap.map { "${it.key}|${it.value.first}|${it.value.second}" }.toSet())
    }

    private const val SUB_SAMPLE = 4
}
