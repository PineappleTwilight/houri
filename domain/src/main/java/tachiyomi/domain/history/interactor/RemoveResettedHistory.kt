package tachiyomi.domain.history.interactor

import tachiyomi.domain.history.repository.HistoryRepository

class RemoveResettedHistory(
    private val historyRepository: HistoryRepository,
) {
    suspend fun await() {
        historyRepository.removeResettedHistory()
    }
}
