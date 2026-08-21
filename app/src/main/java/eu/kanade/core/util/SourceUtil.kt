package eu.kanade.core.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import mihon.app.di.globalAppGraph
import tachiyomi.domain.source.service.SourceManager

@Composable
fun ifSourcesLoaded(): Boolean {
    return remember { globalAppGraph.sourceManager.isInitialized }.collectAsState().value
}
