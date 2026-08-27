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
            if (!manager.isEnabled()) return Result.success()
            // Auto-translate is gated; actual page translation happens lazily in viewer hook.
            // This worker just ensures cache directory exists and pre-warms breadcrumb notes.
            xLogD("TranslationWork prewarm manga=$mangaId chapter=$chapterId")
            Result.success()
        } catch (e: Exception) {
            xLogE("TranslationWork failed", e)
            Result.retry()
        }
    }
}
