package eu.kanade.presentation.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import mihon.app.di.globalAppGraph
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Bottom overlay that gives visual feedback about the MTL (machine translation) pipeline
 * for the current chapter: in-progress, done, or failed-with-retry. It observes
 * [exh.yakuyomi.TranslationStatus.chapters], so it reflects live per-page state.
 */
@Composable
fun MtlTranslationOverlay(
    mangaId: Long?,
    chapterId: Long?,
    totalPages: Int,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (mangaId == null || chapterId == null || chapterId == 0L) return
    val manager = remember { globalAppGraph.translationManager }
    // Hide the whole overlay when MTL is disabled or per-manga translation is off.
    if (!manager.isEnabled() || !manager.isPerMangaEnabled(mangaId)) return

    val status = remember { globalAppGraph.translationStatus }
    val chapters by status.chapters.collectAsState()
    val chapterStatus = chapters[mangaId to chapterId]

    // Transient "translated" state that auto-hides after a couple of seconds.
    var showTranslated by remember { mutableStateOf(false) }
    LaunchedEffect(chapterStatus?.isTranslating, chapterStatus?.lastCompletedAt) {
        if (chapterStatus?.isTranslating == true) {
            showTranslated = false
            return@LaunchedEffect
        }
        if ((chapterStatus?.lastCompletedAt ?: 0L) > 0L) {
            showTranslated = true
            delay(2_500)
            showTranslated = false
        }
    }

    val isTranslating = chapterStatus?.isTranslating == true
    val errorCount = chapterStatus?.errorCount ?: 0
    val translatedCount = chapterStatus?.translatedCount ?: 0
    val skippedCount = chapterStatus?.skippedCount ?: 0

    val visible = isTranslating || (errorCount > 0 && !isTranslating) || showTranslated
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 4.dp,
            shadowElevation = 4.dp,
        ) {
            when {
                isTranslating -> TranslatingChip(doneCount = translatedCount, totalPages = totalPages)
                errorCount > 0 -> ErrorChip(
                    errorCount = errorCount,
                    reason = chapterStatus?.lastError,
                    onRetry = onRetry,
                )
                skippedCount > 0 && translatedCount == 0 -> SkippedChip(skippedCount = skippedCount)
                showTranslated -> TranslatedChip()
                else -> {}
            }
        }
    }
}

@Composable
private fun TranslatingChip(doneCount: Int, totalPages: Int) {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (totalPages > 0) {
                val progress = (doneCount.coerceAtMost(totalPages)).toFloat() / totalPages.toFloat()
                CircularProgressIndicator(
                    progress = { progress },
                    strokeWidth = 2.dp,
                    modifier = Modifier.padding(2.dp),
                )
                Text(
                    text = stringResource(KMR.strings.mtl_translating_progress, doneCount.coerceAtMost(totalPages), totalPages),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.padding(2.dp),
                )
                Text(
                    text = stringResource(KMR.strings.mtl_translating),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        if (totalPages > 0) {
            val stage = when {
                doneCount == 0 -> "Detecting · OCR"
                doneCount < totalPages / 2 -> "Translating"
                doneCount < totalPages -> "Inpainting · Typesetting"
                else -> "Finalizing"
            }
            Text(
                text = stage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SkippedChip(skippedCount: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = stringResource(
                if (skippedCount > 1) KMR.strings.mtl_skipped_pages else KMR.strings.mtl_skipped_single,
                skippedCount,
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun TranslatedChip() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(KMR.strings.mtl_translated),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun ErrorChip(errorCount: Int, reason: String?, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
            Text(
                text = stringResource(KMR.strings.mtl_translation_failed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (errorCount > 1) {
                Text(
                    text = "($errorCount)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onRetry) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = stringResource(KMR.strings.mtl_retry),
                    modifier = Modifier.padding(end = 4.dp),
                )
                Text(text = stringResource(KMR.strings.mtl_retry))
            }
        }
        if (!reason.isNullOrBlank()) {
            Text(
                text = reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
    }
}
