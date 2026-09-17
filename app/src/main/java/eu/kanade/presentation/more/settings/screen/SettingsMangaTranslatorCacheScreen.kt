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
import exh.yakuyomi.TranslatedPageStore
import mihon.app.di.globalAppGraph
import tachiyomi.core.common.util.lang.withIOContext
import java.io.File

object SettingsMangaTranslatorCacheScreen : Screen {
    // KMK --> Cache dirs are keyed by manga id; the title sidecar (written at save
    // time) names them even after library removal, raw id is the last resort.
    private data class CacheEntry(val dir: File, val title: String?)
    // KMK <--

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        var entries by remember { mutableStateOf<List<CacheEntry>>(emptyList()) }
        var refreshTick by remember { mutableStateOf(0) }

        LaunchedEffect(refreshTick) {
            entries = withIOContext {
                listSavedMangaDirs(context).map { dir -> CacheEntry(dir, resolveTitle(context, dir.name)) }
            }
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
                items(entries) { entry ->
                    val mangaDir = entry.dir
                    val mangaId = mangaDir.name
                    val chapters = mangaDir.listFiles()?.filter { it.isDirectory }?.size ?: 0
                    val pages = mangaDir.listFiles()?.flatMap { it.listFiles()?.toList() ?: emptyList() }?.size ?: 0
                    val sizeKb = mangaDir.listFiles()?.flatMap { it.listFiles()?.toList() ?: emptyList() }?.sumOf { it.length() }?.div(1024) ?: 0
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            // KMK --> Sidecar title survives library removal; raw id is the last resort.
                            Text(text = entry.title ?: "Manga $mangaId", style = MaterialTheme.typography.titleSmall)
                            // KMK <--
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

    // KMK --> Sidecar first (survives library removal), then the database (covers caches
    // saved before sidecars existed), then null so the caller falls back to the raw id.
    private suspend fun resolveTitle(context: android.content.Context, mangaIdRaw: String): String? {
        val id = mangaIdRaw.toLongOrNull() ?: return null
        runCatching {
            File(context.filesDir, "yakuyomi_saved/$id/${TranslatedPageStore.TITLE_FILE}")
                .takeIf { it.isFile }
                ?.readText()
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()?.let { return it }
        return runCatching { globalAppGraph.getManga.await(id)?.title }.getOrNull()
    }
    // KMK <--

    private fun listSavedMangaDirs(context: android.content.Context): List<File> {
        val base = File(context.filesDir, "yakuyomi_saved")
        if (!base.exists()) return emptyList()
        return base.listFiles()
            ?.filter { it.isDirectory && it.listFiles()?.any { child -> child.isDirectory } == true }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }
}
