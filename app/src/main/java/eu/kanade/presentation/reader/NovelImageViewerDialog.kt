// KMK -->
package eu.kanade.presentation.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toDrawable
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import eu.kanade.tachiyomi.util.system.readerBackgroundColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mihon.app.di.appGraph
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * The full-screen viewer for an illustration the reader long-pressed inside an open book.
 *
 * ### Why the tap-zoom became this
 *
 * The illustration was once zoomed *in place* — a CSS transform on the `<img>`, paid for by every
 * tap in the book in a script round trip that asked whether the tap had hit a figure. Neither half
 * of that trade was worth it, so both are gone: a tap now only ever scrolls, and the figure is
 * examined here instead, the way a cover is — full screen, pinch and double-tap through the same
 * [ReaderPageImageView] the cover preview uses, a tap on the un-zoomed image to leave.
 *
 * ### What the reader can do here
 *
 * Look, and set the picture as the entry's cover — the one action the old long-press sheet carried,
 * now behind the same [SetCoverDialog] confirmation the page-actions sheet uses, so the decision is
 * made looking at the image itself rather than at a thumbnail-sized sheet.
 *
 * The bytes are resolved through [getImageBytes] from the chapter's own lease — the URL the hit
 * test handed over stays re-readable for the dialog's whole lifetime, so a viewer rebuild between
 * the press and the load cannot orphan the request — and decoded off the main thread. A URL that
 * resolves to nothing (a lease gone with the chapter, bytes the decoder rejects) leaves the
 * surface blank rather than erroring; the reader backs out with a tap.
 */
@Composable
fun NovelImageViewerDialog(
    imageUrl: String,
    onDismissRequest: () -> Unit,
    onSetAsCover: () -> Unit,
    getImageBytes: suspend (String) -> ByteArray?,
) {
    val view = LocalView.current
    val readerPreferences = view.context.appGraph.readerPreferences

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            // Edge to edge, like the cover preview and the reader behind it; the pill then pads
            // itself out of the navigation bar. Without this the dialog window letterboxes itself
            // and the padding does nothing. https://issuetracker.google.com/issues/246909281
            decorFitsSystemWindows = false,
        ),
    ) {
        // The book's own background, not a sheet: the viewer is a lightbox over the page, and a
        // themed surface behind an illustration with transparent corners would read as a UI panel.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Color(view.context.readerBackgroundColor(readerPreferences.readerTheme().get())),
                ),
        ) {
            var bitmap by remember(imageUrl) { mutableStateOf<Bitmap?>(null) }
            // `setImage` is not free — it hands the engine a fresh tiling job — so it runs once
            // per image, not once per recomposition of everything around it.
            var installedFor by remember { mutableStateOf<String?>(null) }
            var pendingSetCover by remember { mutableStateOf(false) }

            LaunchedEffect(imageUrl) {
                val bytes = getImageBytes(imageUrl) ?: return@LaunchedEffect
                bitmap = withContext(Dispatchers.Default) {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }
            }

            AndroidView(
                factory = { ctx ->
                    ReaderPageImageView(ctx).apply {
                        clipToPadding = false
                        clipChildren = false
                        // A tap on the un-zoomed image leaves, as it does in the cover preview;
                        // while zoomed, the tap belongs to the zoom surface.
                        onViewClicked = onDismissRequest
                    }
                },
                update = { imageView ->
                    val decoded = bitmap ?: return@AndroidView
                    if (installedFor == imageUrl) return@AndroidView
                    installedFor = imageUrl
                    // The bitmap is ours alone — decoded from bytes this dialog holds — so no
                    // defensive copy is needed the way one is for a bitmap that may come back
                    // out of a shared cache.
                    imageView.setImage(
                        decoded.toDrawable(view.context.resources),
                        ReaderPageImageView.Config(zoomDuration = 400),
                    )
                },
                modifier = Modifier.fillMaxSize(),
            )

            // One pill, bottom center: the action, then the way out. Same shape, container and
            // content colour the cover preview uses for its own pills, so the two lightboxes read
            // as siblings.
            val pillColor = MaterialTheme.colorScheme.secondaryContainer
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp)
                    .clip(MaterialTheme.shapes.extraLarge)
                    .background(pillColor.copy(alpha = 0.95f)),
            ) {
                CompositionLocalProvider(
                    LocalContentColor provides contentColorFor(pillColor),
                ) {
                    IconButton(onClick = { pendingSetCover = true }) {
                        Icon(
                            imageVector = Icons.Outlined.Image,
                            contentDescription = stringResource(MR.strings.confirm_set_image_as_cover),
                        )
                    }
                    IconButton(onClick = onDismissRequest) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = stringResource(MR.strings.action_close),
                        )
                    }
                }
            }

            if (pendingSetCover) {
                SetCoverDialog(
                    onConfirm = {
                        pendingSetCover = false
                        onSetAsCover()
                    },
                    onDismiss = { pendingSetCover = false },
                )
            }
        }
    }
}
// KMK <--
