package tachiyomi.domain.history.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.history.repository.HistoryRepository

@Inject
class RemoveResettedHistory(
    private val historyRepository: HistoryRepository,
) {
    suspend fun await() {
        historyRepository.removeResettedHistory()
    }
}
