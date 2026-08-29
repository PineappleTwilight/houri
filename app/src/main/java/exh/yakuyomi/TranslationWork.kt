package exh.yakuyomi

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.hippo.unifile.UniFile
import exh.log.xLogD
import exh.log.xLogE
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
            val chapterDir: UniFile? = provider.findChapterDir(chapter.name, chapter.scanlator, chapter.url, manga.title, source)
            if (chapterDir != null && chapterDir.exists()) {
                val files = chapterDir.listFiles().orEmpty().sortedBy { it.name }
                xLogD("TranslationWork processing ${files.size} pages manga=$mangaId chapter=$chapterId")
                for ((index, file) in files.withIndex()) {
                    try {
                        if (!file.isFile) continue
                        val bytes = file.openInputStream().use { it.readBytes() }
                        manager.translatePage(mangaId, chapterId, bytes, index)
                    } catch (e: Exception) {
                        xLogE("TranslationWork page $index failed", e)
                    }
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
