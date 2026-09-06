package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import mihon.app.di.globalAppGraph
import tachiyomi.presentation.core.components.material.padding

object SettingsTranslationSessionsScreen : Screen {
    @Composable
    override fun Content() {
        val status = remember { globalAppGraph.translationStatus }
        val manager = remember { globalAppGraph.translationManager }
        val chapters by status.chapters.collectAsState()
        val navigator = LocalNavigator.currentOrThrow

        Column(
            modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = "Active Translation Sessions", style = MaterialTheme.typography.titleLarge)
            Text(
                text = "Manage on-the-fly and download translations. Kill cancels pending pages, Pause keeps queue, Retry re-queues failed pages.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { manager.clearAllChapters() }) { Text("Clear All") }
                TextButton(onClick = { navigator.pop() }) { Text("Back") }
            }
            if (chapters.isEmpty()) {
                Text(text = "No active sessions", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 24.dp))
                return@Column
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(chapters.values.toList().sortedByDescending { it.lastUpdated }) { ch ->
                    val total = ch.totalPages
                    val done = ch.translatedCount
                    val err = ch.errorCount
                    val isTranslating = ch.isTranslating
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(text = "Manga ${ch.mangaId} · Chapter ${ch.chapterId}", style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = "Progress: $done/$total · Errors: $err · ${if (isTranslating) "Translating" else "Idle"}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (total > 0) {
                                LinearProgressIndicator(
                                    progress = { (done.toFloat() / total.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            if (!ch.lastError.isNullOrBlank()) {
                                Text(text = ch.lastError ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { manager.cancelChapter(ch.mangaId, ch.chapterId) }) {
                                    Icon(Icons.Filled.Close, contentDescription = "Kill")
                                }
                                IconButton(onClick = { manager.pauseChapter(ch.mangaId, ch.chapterId) }) {
                                    Icon(Icons.Filled.Pause, contentDescription = "Pause")
                                }
                                IconButton(onClick = { manager.resumeChapter(ch.mangaId, ch.chapterId) }) {
                                    Icon(Icons.Filled.PlayArrow, contentDescription = "Resume")
                                }
                                IconButton(onClick = { manager.retryChapter(ch.mangaId, ch.chapterId) }) {
                                    Icon(Icons.Filled.Refresh, contentDescription = "Retry")
                                }
                                TextButton(onClick = { manager.cancelChapter(ch.mangaId, ch.chapterId) }) { Text("Kill") }
                            }
                        }
                    }
                }
            }
        }
    }
}
