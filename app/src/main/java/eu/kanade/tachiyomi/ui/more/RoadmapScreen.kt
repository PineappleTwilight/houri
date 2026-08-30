package eu.kanade.tachiyomi.ui.more

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.util.Screen
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import logcat.LogPriority
import mihon.app.di.globalAppGraph
import okhttp3.Request
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * "What's coming" screen: fetches the repo's TODO.md from GitHub and lists the items that
 * are still unchecked (planned but not yet done).
 */
class RoadmapScreen : Screen() {
    @Composable
    override fun Content() {
        val screenModel = rememberScreenModel { RoadmapScreenModel() }
        val state by collectAsState(screenModel.state)
        eu.kanade.presentation.more.RoadmapScreen(
            state = state,
            onBack = LocalNavigator.currentOrThrow::pop,
            onRetry = screenModel::refresh,
        )
    }
}

class RoadmapScreenModel : ScreenModel {

    @Immutable
    data class RoadmapItem(
        val section: String,
        val text: String,
    )

    @Immutable
    data class State(
        val isLoading: Boolean = true,
        val items: ImmutableList<RoadmapItem> = persistentListOf(),
        val error: String? = null,
        val fetchedAt: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    init {
        refresh()
    }

    fun refresh() {
        _state.update { it.copy(isLoading = true, error = null) }
        screenModelScope.launch {
            try {
                val items = withIOContext { fetchTodo() }
                val fetchedAt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("MMM d, HH:mm"))
                _state.update {
                    it.copy(
                        isLoading = false,
                        items = items.toImmutableList(),
                        error = null,
                        fetchedAt = fetchedAt,
                    )
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                logcat(LogPriority.ERROR, e) { "Failed to fetch TODO.md" }
                _state.update { it.copy(isLoading = false, error = e.message ?: "Unknown error") }
            }
        }
    }

    private suspend fun fetchTodo(): List<RoadmapItem> {
        val url = "https://raw.githubusercontent.com/PineappleTwilight/komikku-pineapple/master/TODO.md"
        val request = Request.Builder().url(url).get().build()
        globalAppGraph.networkHelper.client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("HTTP ${resp.code}")
            }
            val body = resp.body?.string() ?: ""
            return parseTodo(body)
        }
    }

    companion object {
        private fun parseTodo(markdown: String): List<RoadmapItem> {
            val items = mutableListOf<RoadmapItem>()
            var section = "General"
            for (line in markdown.lineSequence()) {
                val trimmed = line.trim()
                when {
                    trimmed.startsWith("## ") -> {
                        section = trimmed.removePrefix("## ").trim().ifBlank { "General" }
                    }
                    trimmed.startsWith("- [ ]") -> {
                        val text = clean(trimmed.removePrefix("- [ ]").trim())
                        if (text.isNotBlank()) {
                            items.add(RoadmapItem(section, text))
                        }
                    }
                }
            }
            return items
        }

        /** Strips markdown emphasis markers so the plain text reads cleanly. */
        private fun clean(text: String): String = text.replace("**", "").trim()
    }
}
