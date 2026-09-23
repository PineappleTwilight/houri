package eu.kanade.presentation.manga.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.ui.text.style.TextAlign
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
    // KMK --> crossfade loading/content/empty instead of hard-swapping; keyed on a
    // derived tri-state so chunked Success pushes don't retrigger the transition
    Crossfade(
        targetState = when {
            relatedMangas == null -> 0
            relatedMangas.isEmpty() -> 1
            else -> 2
        },
        label = "relatedMangasRow",
    ) { state ->
        when (state) {
            0 -> GlobalSearchLoadingResultItem()
            1 -> EmptyResultItem()
            else -> RelatedMangaCardRow(
                relatedMangas = relatedMangas.orEmpty(),
                getManga = { getMangaState(it) },
                onMangaClick = onMangaClick,
                onMangaLongClick = onMangaLongClick,
            )
        }
    }
    // KMK <--
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
    // KMK --> animate in when the async fetch lands instead of hard-popping;
    // exits shrink so disabled/empty collapses the row in place
    AnimatedVisibility(
        visible = shouldShowSequelPrequel(enabled, entries),
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        // KMK <--
        val shownEntries = entries.orEmpty()
        Column(modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium)) {
            Text(
                text = stringResource(KMR.strings.pref_sequel_prequel_title),
                style = MaterialTheme.typography.titleMedium,
            )
            LazyRow(
                contentPadding = PaddingValues(vertical = MaterialTheme.padding.small),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
            ) {
                items(shownEntries, key = { "sequel-prequel-${it.relation}-${it.url}" }) { entry ->
                    SequelPrequelCard(
                        entry = entry,
                        inLibrary = entry.title.lowercase() in inLibraryTitles,
                        onClick = { onEntryClick(entry) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SequelPrequelCard(entry: SequelPrequelEntry, inLibrary: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(112.dp)
            .clickable(onClick = onClick)
            .padding(vertical = MaterialTheme.padding.extraSmall),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
    ) {
        Text(
            // KMK --> UPPER_SNAKE kinds ("SIDE_STORY") render as "Side story"
            text = entry.relation.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // KMK --> cover thumbnail when the tracker supplied one; text-only card otherwise
        if (entry.coverUrl != null) {
            MangaCover.Book(
                data = entry.coverUrl,
                modifier = Modifier.width(96.dp),
                contentDescription = entry.title,
            )
        }
        // KMK <--
        Text(
            text = entry.title,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (inLibrary) {
            Text(
                text = stringResource(MR.strings.in_library),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
                textAlign = TextAlign.Center,
            )
        }
    }
}
// KMK <--
