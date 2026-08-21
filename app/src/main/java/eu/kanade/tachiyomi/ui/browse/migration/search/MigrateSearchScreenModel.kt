package eu.kanade.tachiyomi.ui.browse.migration.search

import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.SearchItemResult
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.SearchScreenModel
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import mihon.app.di.globalAppGraph
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.source.service.SourceManager

class MigrateSearchScreenModel(
    val mangaId: Long,
    getManga: GetManga = globalAppGraph.getManga,
    private val sourceManager: SourceManager = globalAppGraph.sourceManager,
    private val sourcePreferences: SourcePreferences = globalAppGraph.sourcePreferences,
) : SearchScreenModel() {

    private val migrationSources by lazy { sourcePreferences.migrationSources().get() }

    override val sortComparator = { map: Map<Source, SearchItemResult> ->
        compareBy<Source>(
            { (map[it] as? SearchItemResult.Success)?.isEmpty ?: true },
            { migrationSources.indexOf(it.id) },
        )
    }

    init {
        screenModelScope.launch {
            val manga = getManga.await(mangaId)!!
            mutableState.update {
                it.copy(
                    from = manga,
                    searchQuery = manga.title,
                )
            }
            search()
        }

        // KMK -->
        shouldPinnedSourcesHidden()
        // KMK <--
    }

    override fun getEnabledSources(): List<Source> {
        return migrationSources.mapNotNull { sourceManager.get(it) }
    }
}
