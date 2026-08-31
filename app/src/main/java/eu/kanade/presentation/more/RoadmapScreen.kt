package eu.kanade.presentation.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AppBar
import eu.kanade.tachiyomi.ui.more.RoadmapScreenModel
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

private const val TODO_URL = "https://github.com/PineappleTwilight/komikku-pineapple/blob/master/TODO.md"

/**
 * "What's coming" screen: fetches the repo's TODO.md and lists the unchecked items
 * (grouped by their section) so users can see what's planned next.
 */
@Composable
fun RoadmapScreen(
    state: RoadmapScreenModel.State,
    onBack: () -> Unit,
    onRetry: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    Scaffold(
        topBar = { scrollBehavior ->
            AppBar(
                title = stringResource(KMR.strings.whats_coming),
                navigateUp = onBack,
                scrollBehavior = scrollBehavior,
            )
        },
    ) { contentPadding ->
        when {
            state.isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(contentPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            state.error != null -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(contentPadding)
                        .padding(horizontal = MaterialTheme.padding.medium),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stringResource(KMR.strings.whats_coming_error, state.error),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = onRetry) {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 4.dp),
                        )
                        Text(text = stringResource(KMR.strings.whats_coming_retry))
                    }
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = contentPadding,
                ) {
                    if (state.items.isEmpty()) {
                        item {
                            Text(
                                text = stringResource(KMR.strings.whats_coming_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(MaterialTheme.padding.medium),
                            )
                        }
                    } else {
                        state.fetchedAt?.let { fetchedAt ->
                            item {
                                Text(
                                    text = stringResource(KMR.strings.whats_coming_fetched, fetchedAt),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(
                                        horizontal = MaterialTheme.padding.medium,
                                        vertical = MaterialTheme.padding.small,
                                    ),
                                )
                            }
                        }
                        var lastSection: String? = null
                        state.items.forEachIndexed { index, item ->
                            if (item.section != lastSection) {
                                lastSection = item.section
                                item(key = "section-$lastSection") {
                                    Text(
                                        text = lastSection,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(
                                            start = MaterialTheme.padding.medium,
                                            end = MaterialTheme.padding.medium,
                                            top = MaterialTheme.padding.medium,
                                            bottom = 4.dp,
                                        ),
                                    )
                                    HorizontalDivider()
                                }
                            }
                            item(key = "todo-$index") {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(
                                            horizontal = MaterialTheme.padding.medium,
                                            vertical = 10.dp,
                                        ),
                                ) {
                                    Text(
                                        text = item.text,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                        item {
                            TextButton(
                                onClick = { uriHandler.openUri(TODO_URL) },
                                modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium),
                            ) {
                                Text(text = stringResource(KMR.strings.whats_coming_open_full))
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                                    contentDescription = null,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
