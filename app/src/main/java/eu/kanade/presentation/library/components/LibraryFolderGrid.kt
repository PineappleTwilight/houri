package eu.kanade.presentation.library.components

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.tachiyomi.ui.library.LibraryItem
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.MangaCover as MangaCoverModel

// KMK -->
/**
 * Home-screen-style library layout: each subcategory of the current category is
 * rendered as a folder tile showing a few cover previews, while manga not assigned
 * to any subcategory are mixed in alongside the folders.
 */
@Composable
internal fun LibraryFolderGrid(
    folders: List<Pair<Category, List<LibraryItem>>>,
    looseItems: List<LibraryItem>,
    columns: Int,
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
        columns = columns,
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
            MangaCompactGridItem(
                isSelected = manga.id in selection,
                title = manga.title,
                coverData = coverData,
                coverBadgeStart = {
                    DownloadsBadge(count = libraryItem.downloadCount)
                    UnreadBadge(count = libraryItem.unreadCount)
                },
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

@Composable
private fun LibraryFolderItem(
    category: Category,
    items: List<LibraryItem>,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .clip(MaterialTheme.shapes.medium)
            .combinedClickable(onClick = onClick)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
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
                            MangaCover.Square(
                                data = coverData,
                                modifier = Modifier.weight(1f),
                                shape = MaterialTheme.shapes.extraSmall,
                            )
                        }
                        repeat(FOLDER_PREVIEW_COLUMNS - rowItems.size) {
                            Box(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }

            Text(
                text = items.size.toString(),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                    .padding(horizontal = 6.dp, vertical = 1.dp),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        Text(
            text = category.name,
            modifier = Modifier.padding(top = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private const val FOLDER_PREVIEW_COLUMNS = 2
private const val FOLDER_PREVIEW_COUNT = FOLDER_PREVIEW_COLUMNS * 2
// KMK <--
