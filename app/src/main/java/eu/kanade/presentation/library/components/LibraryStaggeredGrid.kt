package eu.kanade.presentation.library.components

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import eu.kanade.tachiyomi.ui.library.LibraryItem
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.presentation.core.util.selectedBackground
import tachiyomi.domain.manga.model.MangaCover as MangaCoverModel

// KMK -->
internal object LibraryStaggeredGridDefaults {
    val HORIZONTAL_SPACER = 4.dp
    val VERTICAL_SPACER = 4.dp

    /** Keeps extreme covers from breaking the masonry flow. */
    const val MIN_COVER_RATIO = 0.5f
    const val MAX_COVER_RATIO = 3f
    const val DEFAULT_COVER_RATIO = 2f / 3f
}

/**
 * Masonry-style library layout: covers keep their intrinsic aspect ratios so
 * cells have varying heights.
 */
@Composable
internal fun LibraryStaggeredGrid(
    items: List<LibraryItem>,
    columns: Int,
    contentPadding: PaddingValues,
    selection: Set<Long>,
    onClick: (LibraryManga) -> Unit,
    onLongClick: (LibraryManga) -> Unit,
    searchQuery: String?,
    onGlobalSearchClicked: () -> Unit,
) {
    LazyVerticalStaggeredGrid(
        columns = if (columns == 0) StaggeredGridCells.Adaptive(128.dp) else StaggeredGridCells.Fixed(columns),
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalItemSpacing = LibraryStaggeredGridDefaults.VERTICAL_SPACER,
        horizontalArrangement = Arrangement.spacedBy(LibraryStaggeredGridDefaults.HORIZONTAL_SPACER),
    ) {
        if (!searchQuery.isNullOrEmpty()) {
            item(
                span = StaggeredGridItemSpan.FullLine,
                contentType = { "library_global_search_item" },
            ) {
                GlobalSearchItem(
                    searchQuery = searchQuery,
                    onClick = onGlobalSearchClicked,
                )
            }
        }

        items(
            items = items,
            key = { it.id },
            contentType = { "library_staggered_item" },
        ) { libraryItem ->
            val manga = libraryItem.libraryManga.manga
            val coverData = remember(manga.id) {
                MangaCoverModel(
                    mangaId = manga.id,
                    sourceId = manga.source,
                    isMangaFavorite = manga.favorite,
                    ogUrl = manga.thumbnailUrl,
                    lastModified = manga.coverLastModified,
                )
            }
            LibraryStaggeredGridItem(
                item = libraryItem,
                coverData = coverData,
                isSelected = manga.id in selection,
                onClick = { onClick(libraryItem.libraryManga) },
                onLongClick = { onLongClick(libraryItem.libraryManga) },
            )
        }
    }
}

@Composable
private fun LibraryStaggeredGridItem(
    item: LibraryItem,
    coverData: MangaCoverModel,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val manga = item.libraryManga.manga
    var coverRatio by remember(manga.id) { mutableFloatStateOf(LibraryStaggeredGridDefaults.DEFAULT_COVER_RATIO) }

    Column(
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .selectedBackground(isSelected)
            .padding(4.dp),
    ) {
        SubcomposeAsyncImage(
            model = coverData,
            contentDescription = manga.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(coverRatio)
                .clip(MaterialTheme.shapes.extraSmall),
            loading = {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            },
            onSuccess = { state ->
                val size = state.painter.intrinsicSize
                if (size.width > 0f && size.height > 0f) {
                    coverRatio = (size.width / size.height)
                        .coerceIn(LibraryStaggeredGridDefaults.MIN_COVER_RATIO, LibraryStaggeredGridDefaults.MAX_COVER_RATIO)
                }
            },
        )

        Text(
            text = manga.title,
            modifier = Modifier.padding(top = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        Row(
            modifier = Modifier.padding(top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DownloadsBadge(count = item.downloadCount)
            UnreadBadge(count = item.unreadCount)
            LanguageBadge(
                isLocal = item.isLocal,
                sourceLanguage = item.sourceLanguage,
                useLangIcon = item.useLangIcon,
            )
            SourceIconBadge(source = item.source)
        }
    }
}
// KMK <--
