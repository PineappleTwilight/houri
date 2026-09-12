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
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.tachiyomi.util.system.toast
import java.io.File

object SettingsMangaTranslatorCacheScreen : Screen {
    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        var entries by remember { mutableStateOf<List<File>>(emptyList()) }
        var refreshTick by remember { mutableStateOf(0) }

        LaunchedEffect(refreshTick) {
            entries = listSavedMangaDirs(context)
        }

        Column(
            modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = "Per-Manga Translation Cache", style = MaterialTheme.typography.titleLarge)
            Text(
                text = "Wipe translated pages for a specific manga — works even after removing it from library. MangaTranslator pages are cached permanently under filesDir/yakuyomi_saved/<mangaId>/<chapterId>/page_*.webp. Tap Wipe to delete all translated pages for that manga.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    File(context.filesDir, "yakuyomi_saved").deleteRecursively()
                    context.toast("All translation cache cleared")
                    refreshTick++
                }) { Text("Clear All") }
                TextButton(onClick = { navigator.pop() }) { Text("Back") }
            }
            if (entries.isEmpty()) {
                Text(text = "No cached translations", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 24.dp))
                return@Column
            }
            Text(text = "${entries.size} manga with cached translations", style = MaterialTheme.typography.bodyMedium)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(entries) { mangaDir ->
                    val mangaId = mangaDir.name
                    val chapters = mangaDir.listFiles()?.filter { it.isDirectory }?.size ?: 0
                    val pages = mangaDir.listFiles()?.flatMap { it.listFiles()?.toList() ?: emptyList() }?.size ?: 0
                    val sizeKb = mangaDir.listFiles()?.flatMap { it.listFiles()?.toList() ?: emptyList() }?.sumOf { it.length() }?.div(1024) ?: 0
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(text = "Manga $mangaId", style = MaterialTheme.typography.titleSmall)
                            Text(text = "$chapters chapters · $pages pages · $sizeKb KB", style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = {
                                    mangaDir.deleteRecursively()
                                    context.toast("Wiped manga $mangaId")
                                    refreshTick++
                                }) { Text("Wipe") }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun listSavedMangaDirs(context: android.content.Context): List<File> {
        val base = File(context.filesDir, "yakuyomi_saved")
        if (!base.exists()) return emptyList()
        return base.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }
}
