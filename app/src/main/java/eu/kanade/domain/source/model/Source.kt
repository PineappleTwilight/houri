package eu.kanade.domain.source.model

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import mihon.app.di.globalAppGraph
import tachiyomi.domain.source.model.Source

val Source.icon: ImageBitmap?
    get() {
        return globalAppGraph.extensionManager.getAppIconForSource(id)
            ?.toBitmap()
            ?.asImageBitmap()
    }

// AM (BROWSE) -->
// Add an extra property to Source for it to get access to ExtensionManager
val Source.installedExtension: Extension.Installed?
    get() {
        return globalAppGraph.extensionManager
            .installedExtensionsFlow
            .value
            .find { ext -> ext.sources.any { it.id == id } }
    }
// <-- AM (BROWSE)
