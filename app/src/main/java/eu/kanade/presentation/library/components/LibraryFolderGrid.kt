package eu.kanade.presentation.library.components

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.tachiyomi.ui.library.LibraryItem
import exh.util.isLewd
import mihon.app.di.globalAppGraph
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.presentation.core.util.collectAsState
import tachiyomi.domain.manga.model.MangaCover as MangaCoverModel

// KMK -->
/**
 * Home-screen-style library layout: each subcategory of the current category is
 * rendered as a folder tile showing a few cover previews, while manga not assigned
 * to any subcategory are mixed in alongside the folders.
 *
 * Loose manga follow the category's display mode; folder tiles stay folder tiles
 * in every mode (stacked full-width when List is selected).
 */
@Composable
internal fun LibraryFolderGrid(
    folders: List<Pair<Category, List<LibraryItem>>>,
    looseItems: List<LibraryItem>,
    columns: Int,
    displayMode: LibraryDisplayMode,
    contentPadding: PaddingValues,
    selection: Set<Long>,
    searchQuery: String?,
    onGlobalSearchClicked: () -> Unit,
    onClickFolder: (Category) -> Unit,
    onClickManga: (LibraryManga) -> Unit,
    onLongClickManga: (LibraryManga) -> Unit,
    onClickContinueReading: ((LibraryManga) -> Unit)?,
) {
    LazyLibraryGrid(
        modifier = Modifier.fillMaxSize(),
        columns = if (displayMode == LibraryDisplayMode.List) 1 else columns,
        contentPadding = contentPadding,
    ) {
        globalSearchItem(searchQuery, onGlobalSearchClicked)

        items(
            items = folders,
            key = { "library_folder_${it.first.id}" },
            contentType = { "library_folder_item" },
        ) { (category, items) ->
            LibraryFolderItem(
                category = category,
                items = items,
                onClick = { onClickFolder(category) },
            )
        }

        items(
            items = looseItems,
            key = { it.id },
            contentType = { "library_folder_loose_item" },
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
            val badges: @Composable () -> Unit = {
                DownloadsBadge(count = libraryItem.downloadCount)
                UnreadBadge(count = libraryItem.unreadCount)
            }
            when (displayMode) {
                LibraryDisplayMode.List -> {
                    MangaListItem(
                        isSelected = manga.id in selection,
                        title = manga.title,
                        isLewd = manga.isLewd(),
                        coverData = coverData,
                        badge = {
                            badges()
                            LanguageBadge(
                                isLocal = libraryItem.isLocal,
                                sourceLanguage = libraryItem.sourceLanguage,
                                useLangIcon = libraryItem.useLangIcon,
                            )
                            SourceIconBadge(source = libraryItem.source)
                        },
                        onLongClick = { onLongClickManga(libraryItem.libraryManga) },
                        onClick = { onClickManga(libraryItem.libraryManga) },
                        onClickContinueReading = if (onClickContinueReading != null && libraryItem.unreadCount > 0) {
                            { onClickContinueReading(libraryItem.libraryManga) }
                        } else {
                            null
                        },
                    )
                }

                LibraryDisplayMode.ComfortableGrid, LibraryDisplayMode.StaggeredGrid -> {
                    MangaComfortableGridItem(
                        isSelected = manga.id in selection,
                        title = manga.title,
                        isLewd = manga.isLewd(),
                        coverData = coverData,
                        coverBadgeStart = { badges() },
                        coverBadgeEnd = {
                            LanguageBadge(
                                isLocal = libraryItem.isLocal,
                                sourceLanguage = libraryItem.sourceLanguage,
                                useLangIcon = libraryItem.useLangIcon,
                            )
                            SourceIconBadge(source = libraryItem.source)
                        },
                        usePanoramaCover = displayMode == LibraryDisplayMode.ComfortableGridPanorama,
                        onLongClick = { onLongClickManga(libraryItem.libraryManga) },
                        onClick = { onClickManga(libraryItem.libraryManga) },
                        onClickContinueReading = if (onClickContinueReading != null && libraryItem.unreadCount > 0) {
                            { onClickContinueReading(libraryItem.libraryManga) }
                        } else {
                            null
                        },
                    )
                }

                else -> {
                    MangaCompactGridItem(
                        isSelected = manga.id in selection,
                        title = manga.title.takeIf { displayMode == LibraryDisplayMode.CompactGrid },
                        isLewd = manga.isLewd(),
                        coverData = coverData,
                        coverBadgeStart = { badges() },
                        coverBadgeEnd = {
                            LanguageBadge(
                                isLocal = libraryItem.isLocal,
                                sourceLanguage = libraryItem.sourceLanguage,
                                useLangIcon = libraryItem.useLangIcon,
                            )
                            SourceIconBadge(source = libraryItem.source)
                        },
                        onLongClick = { onLongClickManga(libraryItem.libraryManga) },
                        onClick = { onClickManga(libraryItem.libraryManga) },
                        onClickContinueReading = if (onClickContinueReading != null && libraryItem.unreadCount > 0) {
                            { onClickContinueReading(libraryItem.libraryManga) }
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun LibraryFolderItem(
    category: Category,
    items: List<LibraryItem>,
    onClick: () -> Unit,
) {
    // Mirrors MangaCompactGridItem structure so folder tiles match manga entry heights
    Box(
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .combinedClickable(onClick = onClick)
            .padding(4.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(MangaCover.Book.ratio)
                .clip(MaterialTheme.shapes.large)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            val previews = remember(items) { items.take(FOLDER_PREVIEW_COUNT) }
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth(0.82f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                previews.chunked(FOLDER_PREVIEW_COLUMNS).forEach { rowItems ->
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        rowItems.forEach { item ->
                            val manga = item.libraryManga.manga
                            val coverData = remember(manga.id) {
                                MangaCoverModel(
                                    mangaId = manga.id,
                                    sourceId = manga.source,
                                    isMangaFavorite = manga.favorite,
                                    ogUrl = manga.thumbnailUrl,
                                    lastModified = manga.coverLastModified,
                                )
                            }
                            val censorPreviewEnabled by globalAppGraph.uiPreferences.censorLewdManga()
                                .collectAsState()
                            val previewShouldCensor = censorPreviewEnabled && manga.isLewd()
                            MangaCover.Square(
                                data = coverData,
                                modifier = Modifier.weight(1f)
                                    .then(if (previewShouldCensor) Modifier.blur(12.dp) else Modifier),
                                shape = MaterialTheme.shapes.extraSmall,
                            )
                        }
                        repeat(FOLDER_PREVIEW_COLUMNS - rowItems.size) {
                            Box(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(0.33f)
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            1f to Color(0xAA000000),
                        ),
                    ),
            )

            Text(
                text = items.size.toString(),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                    .padding(horizontal = 6.dp, vertical = 1.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Text(
                text = category.name,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp),
                style = MaterialTheme.typography.titleSmall.copy(
                    color = Color.White,
                    shadow = Shadow(
                        color = Color.Black,
                        blurRadius = 4f,
                    ),
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private const val FOLDER_PREVIEW_COLUMNS = 2
private const val FOLDER_PREVIEW_COUNT = FOLDER_PREVIEW_COLUMNS * 2
// KMK <--
