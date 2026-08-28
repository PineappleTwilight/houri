package exh.yakuyomi

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
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
            // Prewarm breadcrumb window and ensure cache dir exists
            try {
                globalAppGraph.translationCache.pruneIfNeeded()
            } catch (_: Exception) {}
            try {
                globalAppGraph.breadcrumbNotes.buildContextPrompt(mangaId)
            } catch (_: Exception) {}
            xLogD("TranslationWork prewarm manga=$mangaId chapter=$chapterId")
            Result.success()
        } catch (e: Exception) {
            xLogE("TranslationWork failed", e)
            Result.retry()
        }
    }
}
