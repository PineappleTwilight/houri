package exh.yakuyomi

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.hippo.unifile.UniFile
import exh.log.xLogD
import exh.log.xLogE
import mihon.core.archive.archiveReader
import mihon.app.di.globalAppGraph

class TranslationWork(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val mangaId = inputData.getLong("mangaId", -1)
        val chapterId = inputData.getLong("chapterId", -1)
        if (mangaId == -1L || chapterId == -1L) return Result.failure()
        return try {
            val manager = globalAppGraph.translationManager
            val prefs = globalAppGraph.translationPreferences
            if (!manager.isEnabled()) return Result.success()
            if (manager.isGated()) {
                xLogD("TranslationWork gated: incognito/censor, skip manga=$mangaId chapter=$chapterId")
                return Result.success()
            }
            if (!prefs.autoTranslateOnDownload().get()) {
                xLogD("TranslationWork autoTranslate disabled, prewarm only manga=$mangaId chapter=$chapterId")
                return Result.success()
            }
            if (!manager.isPerMangaEnabled(mangaId)) {
                xLogD("TranslationWork per-manga disabled manga=$mangaId")
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
                    val sortedFiles = files.filter { it.isFile }.sortedBy { it.name }
                    xLogD("TranslationWork processing ${sortedFiles.size} pages manga=$mangaId chapter=$chapterId")
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
                            val entries = reader.useEntries { it.toList() }
                                .filter { it.isFile }
                                .sortedBy { it.name }
                            xLogD("TranslationWork archive chapterDir name='${chapterDir.name}' entryCount=${entries.size}")
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
                for ((index, bytes) in pages.sortedBy { it.first }) {
                    manager.translatePage(mangaId, chapterId, bytes, index)
                }
                xLogD("TranslationWork processed ${pages.size} pages manga=$mangaId chapter=$chapterId")
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
