package eu.kanade.presentation.manga.components

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.updatePadding
import ca.mpreg.webgpuviewer.renderer.Image
import ca.mpreg.webgpuviewer.renderer.Image.Companion.invoke
import ca.mpreg.webgpuviewer.viewer.ImagePage
import ca.mpreg.webgpuviewer.viewer.ImageViewer
import ca.mpreg.webgpuviewer.viewer.ImageViewerState
import coil3.asDrawable
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.size.Size
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.DropdownMenu
import eu.kanade.presentation.manga.EditCoverAction
import eu.kanade.tachiyomi.data.coil.NewImageDecoder
import eu.kanade.tachiyomi.data.coil.newDecoder
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.launch
import logcat.LogPriority
import mihon.app.di.appGraph
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.clickableNoIndication
import tachiyomi.presentation.core.util.collectAsState
import java.util.concurrent.atomic.AtomicReference

@Composable
fun MangaCoverDialog(
    manga: Manga,
    isCustomCover: Boolean,
    snackbarHostState: SnackbarHostState,
    onShareClick: () -> Unit,
    onSaveClick: () -> Unit,
    onEditClick: ((EditCoverAction) -> Unit)?,
    onDismissRequest: () -> Unit,
    // KMK -->
    modifier: Modifier = Modifier,
    // KMK <--
) {
    // KMK -->
    val iconColor = contentColorFor(MaterialTheme.colorScheme.secondaryContainer)
    val dropdownBgColor = MaterialTheme.colorScheme.surfaceVariant
    // KMK <--
    // Mihon -->
    val view = LocalView.current
    val useNewRenderer by view.context.appGraph.basePreferences.highQualityRenderer().collectAsState()
    // Mihon <--
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false, // Doesn't work https://issuetracker.google.com/issues/246909281
        ),
    ) {
        Scaffold(
            // KMK -->
            modifier = modifier,
            // KMK <--
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            containerColor = Color.Transparent,
            bottomBar = {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(4.dp)
                        .navigationBarsPadding(),
                ) {
                    ActionsPill {
                        IconButton(onClick = onDismissRequest) {
                            Icon(
                                imageVector = Icons.Outlined.Close,
                                contentDescription = stringResource(MR.strings.action_close),
                                // KMK -->
                                tint = iconColor,
                                // KMK <--
                            )
                        }
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    ActionsPill {
                        AppBarActions(
                            actions = persistentListOf(
                                AppBar.Action(
                                    title = stringResource(MR.strings.action_share),
                                    icon = Icons.Outlined.Share,
                                    onClick = onShareClick,
                                    // KMK -->
                                    iconTint = iconColor,
                                    // KMK <--
                                ),
                                AppBar.Action(
                                    title = stringResource(MR.strings.action_save),
                                    icon = Icons.Outlined.Save,
                                    onClick = onSaveClick,
                                    // KMK -->
                                    iconTint = iconColor,
                                    // KMK <--
                                ),
                            ),
                        )
                        if (onEditClick != null && manga.favorite) {
                            Box {
                                var expanded by remember { mutableStateOf(false) }
                                IconButton(
                                    onClick = {
                                        if (isCustomCover) {
                                            expanded = true
                                        } else {
                                            onEditClick(EditCoverAction.EDIT)
                                        }
                                    },
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Edit,
                                        contentDescription = stringResource(MR.strings.action_edit_cover),
                                        // KMK -->
                                        tint = iconColor,
                                        // KMK <--
                                    )
                                }
                                DropdownMenu(
                                    expanded = expanded,
                                    onDismissRequest = { expanded = false },
                                    offset = DpOffset(8.dp, 0.dp),
                                    // KMK -->
                                    modifier = Modifier.background(dropdownBgColor),
                                    // KMK <--
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(text = stringResource(MR.strings.action_edit)) },
                                        onClick = {
                                            onEditClick(EditCoverAction.EDIT)
                                            expanded = false
                                        },
                                        // KMK -->
                                        colors = MenuDefaults.itemColors().copy(
                                            textColor = contentColorFor(dropdownBgColor),
                                        ),
                                        // KMK <--
                                    )
                                    DropdownMenuItem(
                                        text = { Text(text = stringResource(MR.strings.action_delete)) },
                                        onClick = {
                                            onEditClick(EditCoverAction.DELETE)
                                            expanded = false
                                        },
                                        // KMK -->
                                        colors = MenuDefaults.itemColors().copy(
                                            textColor = contentColorFor(dropdownBgColor),
                                        ),
                                        // KMK <--
                                    )
                                }
                            }
                        }
                    }
                }
            },
        ) { contentPadding ->
            // Mihon -->
            if (useNewRenderer) {
                // KMK --> Re-keyed on the cover, not only on the manga id. The crop editor stacks on
                // top of this dialog, so setting a custom cover replaces the image behind an id that
                // never changes while the dialog is still composed - and keyed on the id alone the
                // request was never re-issued, so the dialog went on drawing the cover the user had
                // just replaced. `coverLastModified` is the same signal MangaKeyer keys the cache on,
                // so this cannot drift from what Coil considers a different image.
                val coverKey = manga.id to manga.coverLastModified
                // The renderer is keyed on the manga ONLY - never on coverKey. ViewerSurface's
                // onSurface callback fires when the SurfaceView is created or resized, not when the
                // state changes, so a state rebuilt on a cover change is never init()ed: its render
                // loop never starts, fetchPage yields nothing, and the swapchain keeps the frame it
                // already had - which is the previous cover, indefinitely. The cover is data; the
                // state is the renderer, and swapping the data must not swap the renderer.
                val state = remember(manga.id) { ImageViewerState() }
                val scope = rememberCoroutineScope()
                // The page the renderer is fed, held here rather than captured in a fetch closure so
                // a re-keyed request can free the one it replaces: an ImagePage owns uploaded GPU
                // textures, and dropping the last reference leaks them. Keyed on the manga for the
                // same reason as the state above - a per-cover reference orphans the page it held,
                // which is exactly the leak this holder exists to prevent.
                val installedPage = remember(manga.id) { AtomicReference<ImagePage.ImageSingle?>(null) }
                DisposableEffect(state) {
                    state.fetchPage = { index -> if (index == 0) installedPage.get() else null }
                    onDispose {
                        state.fetchPage = { null }
                        installedPage.getAndSet(null)?.cleanup()
                    }
                }
                LaunchedEffect(coverKey) {
                    ImageRequest.Builder(view.context)
                        .data(manga)
                        .size(Size.ORIGINAL)
                        .memoryCachePolicy(CachePolicy.DISABLED)
                        .newDecoder(true)
                        .target { result ->
                            // Coil's target lambda is an ordinary callback, not a coroutine, so
                            // the suspend Image(...) cannot be called here directly. It used to be
                            // wrapped in runBlocking, which froze the main thread for the whole
                            // mipmap chain - past 2048px it resizes each level on Default and
                            // uploads it to the GPU. Launching onto the composition's scope
                            // instead keeps the UI thread free; see WebGpuDecode for the same call
                            // made from a plain suspend fun.
                            scope.launch {
                                // KMK --> The request asks for the new decoder specifically, and
                                // only that decoder hands back the raw RGBA pixels this GPU path
                                // needs. Anything else Coil picks would be a coil3.BitmapImage, so
                                // the old unchecked cast was a ClassCastException on the main
                                // thread the moment a cover's bytes let TachiyomiImageDecoder claim
                                // the request first (AVIF/JXL/HEIF - see TachiyomiImageDecoder.Factory).
                                // Safe cast: the wrong image leaves the viewer empty rather than
                                // taking the whole app down with the cover dialog.
                                val decoded = result as? NewImageDecoder.DecodeResultImage
                                if (decoded == null) {
                                    logcat(LogPriority.ERROR) {
                                        "Cover dialog expected NewImageDecoder pixels, got " +
                                            "${result::class.java.simpleName} - leaving the viewer empty"
                                    }
                                    return@launch
                                }
                                val page = ImagePage.ImageSingle(
                                    Image(
                                        decoded.res.image,
                                        decoded.res.width,
                                        decoded.res.height,
                                        createMipMaps = true,
                                        backgroundColor = 0,
                                    ),
                                )
                                installedPage.getAndSet(page)?.cleanup()
                                state.invalidate()
                                // KMK <--
                            }
                        }
                        .build()
                        .let(view.context.imageLoader::enqueue)
                }

                ImageViewer(state = state)
                return@Scaffold
            }
            // Mihon <--

            // KMK -->
            val statusBarPaddingPx = with(LocalDensity.current) { contentPadding.calculateTopPadding().roundToPx() }
            val bottomPaddingPx = with(LocalDensity.current) { contentPadding.calculateBottomPadding().roundToPx() }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickableNoIndication(onClick = onDismissRequest),
            ) {
                AndroidView(
                    factory = {
                        ReaderPageImageView(it).apply {
                            onViewClicked = onDismissRequest
                            clipToPadding = false
                            clipChildren = false
                        }
                    },
                    update = { view ->
                        val request = ImageRequest.Builder(view.context)
                            .data(manga)
                            .size(Size.ORIGINAL)
                            .memoryCachePolicy(CachePolicy.DISABLED)
                            .target { image ->
                                val drawable = image.asDrawable(view.context.resources)

                                // Copy bitmap in case it came from memory cache
                                // Because SSIV needs to thoroughly read the image
                                // KMK -->
                                val src = (drawable as? BitmapDrawable)?.bitmap
                                val config = src?.config?.takeIf { it != Bitmap.Config.HARDWARE } ?: Bitmap.Config.ARGB_8888
                                // KMK <--
                                val copy = src?.copy(config, false)
                                    ?.toDrawable(view.context.resources)
                                    ?: drawable
                                view.setImage(copy, ReaderPageImageView.Config(zoomDuration = 500))
                            }
                            .build()
                        view.context.imageLoader.enqueue(request)

                        view.updatePadding(top = statusBarPaddingPx, bottom = bottomPaddingPx)
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun ActionsPill(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .clip(MaterialTheme.shapes.extraLarge)
            // KMK -->
            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.95f)),
        // KMK <--
    ) {
        content()
    }
}
