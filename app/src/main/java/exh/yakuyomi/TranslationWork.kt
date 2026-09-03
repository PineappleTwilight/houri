package exh.yakuyomi

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import exh.log.xLogD
import exh.log.xLogE
import mihon.app.di.globalAppGraph
import mihon.core.archive.archiveReader
import tachiyomi.core.common.util.system.ImageUtil

class TranslationWork(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    companion object {
        private const val MAX_ATTEMPTS = 3
    }

    override suspend fun doWork(): Result {
        val mangaId = inputData.getLong("mangaId", -1)
        val chapterId = inputData.getLong("chapterId", -1)
        if (mangaId == -1L || chapterId == -1L) return Result.failure()
        return try {
            val manager = globalAppGraph.translationManager
            val prefs = globalAppGraph.translationPreferences
            if (!manager.isEnabled()) return Result.success()
            if (!globalAppGraph.yakuyomiEngine.isHardwareSupported()) {
                xLogD("TranslationWork skipped: device has too little RAM manga=$mangaId chapter=$chapterId")
                return Result.success()
            }
            if (manager.isGated()) {
                xLogD("TranslationWork gated: incognito/censor, skip manga=$mangaId chapter=$chapterId")
                return Result.success()
            }
            if (!manager.isPerMangaEnabled(mangaId)) {
                xLogD("TranslationWork per-manga disabled manga=$mangaId")
                return Result.success()
            }
            // Prewarm native components so the background run (and later reading) is not cold.
            globalAppGraph.yakuyomiEngine.prewarm()
            if (!prefs.autoTranslateOnDownload().get()) {
                xLogD("TranslationWork autoTranslate disabled, prewarmed only manga=$mangaId chapter=$chapterId")
                return Result.success()
            }
            val manga = globalAppGraph.getManga.await(mangaId) ?: return Result.success()
            val chapter = globalAppGraph.getChapter.await(chapterId) ?: return Result.success()
            // Prewarm breadcrumb and cache
            try {
                globalAppGraph.translationCache.pruneIfNeeded()
            } catch (_: Exception) {}
            try {
                globalAppGraph.breadcrumbNotes.buildContextPrompt(mangaId)
            } catch (_: Exception) {}

            // Translate pages from downloaded chapter dir
            val provider = globalAppGraph.downloadProvider
            val sourceManager = globalAppGraph.sourceManager
            val source = sourceManager.getOrStub(manga.source)
            val mangaTitle = manga.ogTitle.ifBlank { manga.title }
            val chapterDir: UniFile? = provider.findChapterDir(chapter.name, chapter.scanlator, chapter.url, mangaTitle, source)
            xLogD("TranslationWork lookup mangaTitle='$mangaTitle' source=${source.id} chapter='${chapter.name}' scanlator='${chapter.scanlator}' url='${chapter.url}' dir='${chapterDir?.toString()}' exists=${chapterDir?.exists()} isDir=${chapterDir?.isDirectory}")
            if (chapterDir != null && chapterDir.exists()) {
                val pages = mutableListOf<Pair<Int, ByteArray>>()
                if (chapterDir.isDirectory) {
                    val files = chapterDir.listFiles().orEmpty()
                    xLogD("TranslationWork chapterDir name='${chapterDir.name}' fileCount=${files.size}")
                    val sortedFiles = files.filter { !it.isDirectory && ImageUtil.isImage(it.name) { it.openInputStream() } }
                        .sortedWith { f1, f2 -> f1.name.orEmpty().compareToCaseInsensitiveNaturalOrder(f2.name.orEmpty()) }
                    xLogD("TranslationWork processing ${sortedFiles.size} image pages manga=$mangaId chapter=$chapterId (skipped ${files.size - sortedFiles.size} non-images)")
                    for ((index, file) in sortedFiles.withIndex()) {
                        try {
                            val bytes = file.openInputStream().use { it.readBytes() }
                            pages.add(index to bytes)
                        } catch (e: Exception) {
                            xLogE("TranslationWork page $index failed", e)
                        }
                    }
                } else if (chapterDir.isFile) {
                    try {
                        chapterDir.archiveReader(applicationContext).use { reader ->
                            val allEntries = reader.useEntries { it.toList() }
                            val entries = allEntries.filter { it.isFile && ImageUtil.isImage(it.name) { reader.getInputStream(it.name)!! } }
                                .sortedWith { a, b -> a.name.compareToCaseInsensitiveNaturalOrder(b.name) }
                            xLogD("TranslationWork archive chapterDir name='${chapterDir.name}' entryCount=${allEntries.size} imageCount=${entries.size}")
                            for ((index, entry) in entries.withIndex()) {
                                try {
                                    val bytes = reader.getInputStream(entry.name)?.use { it.readBytes() } ?: continue
                                    pages.add(index to bytes)
                                } catch (e: Exception) {
                                    xLogE("TranslationWork archive page $index failed", e)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        xLogE("TranslationWork archive read failed", e)
                    }
                }
                // Translate collected pages in order
                var translated = 0
                var cached = 0
                var errored = 0
                val orderedPages = pages.sortedBy { it.first }
                // Declare the page count up front so the chapter list shows live progress instead of 0%.
                globalAppGraph.translationStatus.setTotalPages(mangaId, chapterId, orderedPages.size)
                for ((index, bytes) in orderedPages) {
                    try {
                        val before = globalAppGraph.translationStatus.chapterStatus(mangaId, chapterId)?.pages?.get(index)
                        val result = manager.translatePage(mangaId, chapterId, bytes, index)
                        val after = globalAppGraph.translationStatus.chapterStatus(mangaId, chapterId)?.pages?.get(index)
                        if (result != null) {
                            translated++
                        } else {
                            when (after?.state) {
                                TranslationStatus.PageState.CACHED,
                                TranslationStatus.PageState.DONE,
                                TranslationStatus.PageState.SKIPPED,
                                -> cached++
                                TranslationStatus.PageState.ERROR,
                                -> errored++
                                else -> {}
                            }
                        }
                        xLogD("TranslationWork page $index result=${if (result != null) "ok" else "null"} before=$before after=$after")
                    } catch (e: Exception) {
                        errored++
                        xLogE("TranslationWork translate page $index failed", e)
                    }
                }
                xLogD("TranslationWork processed ${pages.size} pages manga=$mangaId chapter=$chapterId translated=$translated cached/skipped=$cached errored=$errored")
                // If every page failed (e.g. transient LLM/network error), retry with backoff a
                // bounded number of times instead of silently marking the chapter "done".
                if (errored > 0 && translated == 0 && runAttemptCount < MAX_ATTEMPTS) {
                    xLogD("TranslationWork retrying manga=$mangaId chapter=$chapterId attempt=${runAttemptCount + 1} errored=$errored")
                    return Result.retry()
                }
            } else {
                xLogD("TranslationWork chapter dir not found manga=$mangaId chapter=$chapterId")
            }
            Result.success()
        } catch (e: Exception) {
            xLogE("TranslationWork failed", e)
            Result.retry()
        }
    }
}
