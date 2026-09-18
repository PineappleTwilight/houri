package eu.kanade.presentation.manga.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.browse.components.EmptyResultItem
import eu.kanade.presentation.browse.components.GlobalSearchLoadingResultItem
import eu.kanade.presentation.browse.components.MangaItem
import eu.kanade.tachiyomi.ui.manga.RelatedManga
import exh.util.isLewd
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.SequelPrequelEntry
import tachiyomi.domain.manga.model.asMangaCover
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun RelatedMangasRow(
    relatedMangas: List<RelatedManga>?,
    getMangaState: @Composable (Manga) -> State<Manga>,
    onMangaClick: (Manga) -> Unit,
    onMangaLongClick: (Manga) -> Unit,
) {
    when {
        relatedMangas == null -> {
            GlobalSearchLoadingResultItem()
        }

        relatedMangas.isNotEmpty() -> {
            RelatedMangaCardRow(
                relatedMangas = relatedMangas,
                getManga = { getMangaState(it) },
                onMangaClick = onMangaClick,
                onMangaLongClick = onMangaLongClick,
            )
        }

        else -> {
            EmptyResultItem()
        }
    }
}

@Composable
fun RelatedMangaCardRow(
    relatedMangas: List<RelatedManga>,
    getManga: @Composable (Manga) -> State<Manga>,
    onMangaClick: (Manga) -> Unit,
    onMangaLongClick: (Manga) -> Unit,
) {
    val mangas = relatedMangas.filterIsInstance<RelatedManga.Success>().map { it.mangaList }.flatten()
    val loading = relatedMangas.filterIsInstance<RelatedManga.Loading>().firstOrNull()

    LazyRow(
        contentPadding = PaddingValues(MaterialTheme.padding.small),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
    ) {
        items(mangas, key = { "related-row-${it.id}" }) {
            val manga by getManga(it)
            MangaItem(
                title = manga.title,
                cover = manga.asMangaCover(),
                isFavorite = manga.favorite,
                isLewd = manga.isLewd(),
                onClick = { onMangaClick(manga) },
                onLongClick = { onMangaLongClick(manga) },
                isSelected = false,
            )
        }
        if (loading != null) {
            item {
                RelatedMangaLoadingItem()
            }
        }
    }
}

@Composable
fun RelatedMangaLoadingItem() {
    Box(
        modifier = Modifier
            .width(96.dp)
            .aspectRatio(MangaCover.Book.ratio)
            .padding(vertical = MaterialTheme.padding.medium),
    ) {
        CircularProgressIndicator(
            modifier = Modifier
                .size(16.dp)
                .align(Alignment.Center),
            strokeWidth = 2.dp,
        )
    }
}

// KMK -->
fun shouldShowSequelPrequel(enabled: Boolean, entries: List<SequelPrequelEntry>?): Boolean =
    enabled && !entries.isNullOrEmpty()

@Composable
fun SequelPrequelRow(
    entries: List<SequelPrequelEntry>?,
    enabled: Boolean,
    onEntryClick: (SequelPrequelEntry) -> Unit,
    // KMK --> lowercase library titles; matches render an "In library" badge
    inLibraryTitles: Set<String> = emptySet(),
    // KMK <--
) {
    if (!shouldShowSequelPrequel(enabled, entries)) return
    Column(modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium)) {
        Text(
            text = stringResource(KMR.strings.pref_sequel_prequel_title),
            style = MaterialTheme.typography.titleMedium,
        )
        LazyRow(
            contentPadding = PaddingValues(vertical = MaterialTheme.padding.small),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
        ) {
            items(entries!!, key = { "sequel-prequel-${it.relation}-${it.url}" }) { entry ->
                SequelPrequelCard(
                    entry = entry,
                    inLibrary = entry.title.lowercase() in inLibraryTitles,
                    onClick = { onEntryClick(entry) },
                )
            }
        }
    }
}

@Composable
private fun SequelPrequelCard(entry: SequelPrequelEntry, inLibrary: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(160.dp)
            .clickable(onClick = onClick)
            .padding(vertical = MaterialTheme.padding.extraSmall),
    ) {
        Text(
            text = entry.relation.name.lowercase().replaceFirstChar { it.uppercase() },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = entry.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (inLibrary) {
            Text(
                text = stringResource(MR.strings.in_library),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}
// KMK <--
