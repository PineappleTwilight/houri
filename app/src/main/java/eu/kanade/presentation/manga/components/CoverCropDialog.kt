package eu.kanade.presentation.manga.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AdaptiveSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource
import java.io.File
import kotlin.math.max
import kotlin.math.min

// KMK -->
private const val MAX_BITMAP_DIM = 2048

/** Aspect options for the crop frame, expressed as width / height. */
private val COVER_CROP_RATIOS = listOf(
    2f / 3f,
    1f,
    3f / 2f,
)

private class CropGeometry(
    val baseScale: Float,
    val frameWidthPx: Float,
    val frameHeightPx: Float,
)

/**
 * Lets the user pan/zoom an image inside a fixed crop frame before it is used
 * as a manga cover. The cropped result is written to cache storage and handed
 * back as a [Uri] via [onCropped]; [onUseOriginal] skips cropping entirely.
 */
@Composable
fun CoverCropDialog(
    sourceUri: Uri,
    onDismissRequest: () -> Unit,
    onCropped: (Uri) -> Unit,
    onUseOriginal: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    var bitmap by remember(sourceUri) { mutableStateOf<Bitmap?>(null) }
    var rotationSteps by remember(sourceUri) { mutableIntStateOf(0) }
    var isRotating by remember(sourceUri) { mutableStateOf(false) }
    var frameRatio by remember(sourceUri) { mutableFloatStateOf(COVER_CROP_RATIOS.first()) }
    var scale by remember(sourceUri) { mutableFloatStateOf(1f) }
    var offset by remember(sourceUri) { mutableStateOf(Offset.Zero) }

    // Assigned inside the crop viewport where the geometry values live
    var exportAction by remember(sourceUri) { mutableStateOf<(() -> Unit)?>(null) }

    LaunchedEffect(sourceUri) {
        bitmap = withContext(Dispatchers.IO) { decodeDownscaledBitmap(context, sourceUri) }
    }

    LaunchedEffect(rotationSteps) {
        val current = bitmap ?: return@LaunchedEffect
        if (rotationSteps % 4 == 0 || isRotating) return@LaunchedEffect
        isRotating = true
        bitmap = withContext(Dispatchers.IO) {
            Bitmap.createBitmap(
                current,
                0,
                0,
                current.width,
                current.height,
                Matrix().apply { postRotate(90f) },
                true,
            )
        }
        isRotating = false
    }

    // A new source or frame invalidates the previous framing
    LaunchedEffect(bitmap, frameRatio) {
        scale = 1f
        offset = Offset.Zero
    }

    AdaptiveSheet(onDismissRequest = onDismissRequest) {
        Column(modifier = Modifier.padding(vertical = 16.dp)) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(420.dp),
            ) {
                val containerWpx = with(density) { maxWidth.toPx() }
                val containerHpx = with(density) { maxHeight.toPx() }
                val frameWpx = min(containerWpx, containerHpx * frameRatio)
                val frameHpx = frameWpx / frameRatio
                val frameLeftPx = (containerWpx - frameWpx) / 2f
                val frameTopPx = (containerHpx - frameHpx) / 2f
                val baseScale = bitmap
                    ?.takeIf { it.width > 0 && it.height > 0 }
                    ?.let { max(frameWpx / it.width, frameHpx / it.height) }
                    ?: 1f
                val totalScale = baseScale * scale
                val geometry by rememberUpdatedState(
                    CropGeometry(baseScale = baseScale, frameWidthPx = frameWpx, frameHeightPx = frameHpx),
                )

                exportAction = export@{
                    val bmp = bitmap ?: return@export
                    val srcRect = computeCropRect(
                        bmp = bmp,
                        baseScale = baseScale,
                        totalScale = totalScale,
                        offset = offset,
                        containerWpx = containerWpx,
                        containerHpx = containerHpx,
                        frameWpx = frameWpx,
                        frameHpx = frameHpx,
                    ) ?: return@export
                    scope.launch {
                        val outUri = withContext(Dispatchers.IO) { writeCroppedBitmap(context, bmp, srcRect) }
                        if (outUri != null) onCropped(outUri)
                    }
                }

                bitmap?.let { bmp ->
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                // Read the transform states here so gestures apply immediately
                                scaleX = baseScale * scale
                                scaleY = baseScale * scale
                                translationX = offset.x
                                translationY = offset.y
                            }
                            .pointerInput(bmp, frameRatio, containerWpx, containerHpx) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    val geo = geometry
                                    val total = geo.baseScale * scale
                                    val dispW = bmp.width * total
                                    val dispH = bmp.height * total
                                    val maxX = max(0f, (dispW - geo.frameWidthPx) / 2f)
                                    val maxY = max(0f, (dispH - geo.frameHeightPx) / 2f)
                                    scale = (scale * zoom).coerceAtMost(8f)
                                    offset = Offset(
                                        (offset.x + pan.x).coerceIn(-maxX, maxX),
                                        (offset.y + pan.y).coerceIn(-maxY, maxY),
                                    )
                                }
                            },
                    )
                }

                // Dim everything outside of the crop frame
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawBehind {
                            drawRect(Color.Black.copy(alpha = 0.55f))
                            drawRect(
                                Color.Black,
                                topLeft = Offset(frameLeftPx, frameTopPx),
                                size = Size(frameWpx, frameHpx),
                                blendMode = BlendMode.Clear,
                            )
                        },
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .border(2.dp, Color.White)
                        .size(with(density) { frameWpx.toDp() }, with(density) { frameHpx.toDp() }),
                )

                if (bitmap == null) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            ) {
                COVER_CROP_RATIOS.forEachIndexed { index, ratio ->
                    FilterChip(
                        selected = frameRatio == ratio,
                        onClick = { frameRatio = ratio },
                        label = {
                            Text(
                                text = when (index) {
                                    0 -> stringResource(KMR.strings.crop_ratio_portrait)
                                    1 -> stringResource(KMR.strings.crop_ratio_square)
                                    else -> stringResource(KMR.strings.crop_ratio_wide)
                                },
                            )
                        },
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onUseOriginal, enabled = bitmap != null) {
                    Text(text = stringResource(KMR.strings.action_crop_use_original))
                }
                Box(modifier = Modifier.weight(1f))
                IconButton(onClick = { rotationSteps++ }, enabled = bitmap != null && !isRotating) {
                    Icon(imageVector = Icons.Filled.RotateRight, contentDescription = null)
                }
                TextButton(
                    onClick = { exportAction?.invoke() },
                    enabled = bitmap != null,
                ) {
                    Text(text = stringResource(MR.strings.action_save))
                }
            }
        }
    }
}

private class CropRect(val left: Int, val top: Int, val width: Int, val height: Int)

private fun computeCropRect(
    bmp: Bitmap,
    baseScale: Float,
    totalScale: Float,
    offset: Offset,
    containerWpx: Float,
    containerHpx: Float,
    frameWpx: Float,
    frameHpx: Float,
): CropRect? {
    if (totalScale <= 0f) return null
    val dispW = bmp.width * totalScale
    val dispH = bmp.height * totalScale
    val imgLeft = containerWpx / 2f + offset.x - dispW / 2f
    val imgTop = containerHpx / 2f + offset.y - dispH / 2f
    val frameLeft = (containerWpx - frameWpx) / 2f
    val frameTop = (containerHpx - frameHpx) / 2f

    val left = ((frameLeft - imgLeft) / totalScale).coerceIn(0f, bmp.width.toFloat())
    val top = ((frameTop - imgTop) / totalScale).coerceIn(0f, bmp.height.toFloat())
    val right = ((frameLeft + frameWpx - imgLeft) / totalScale).coerceIn(0f, bmp.width.toFloat())
    val bottom = ((frameTop + frameHpx - imgTop) / totalScale).coerceIn(0f, bmp.height.toFloat())

    val width = (right - left).toInt()
    val height = (bottom - top).toInt()
    if (width < 8 || height < 8) return null
    return CropRect(left.toInt(), top.toInt(), width, height)
}

private suspend fun writeCroppedBitmap(context: Context, source: Bitmap, rect: CropRect): Uri? {
    return runCatching {
        val cropped = Bitmap.createBitmap(source, rect.left, rect.top, rect.width, rect.height)
        val dir = File(context.cacheDir, "cover_crop").apply { mkdirs() }
        File(dir, "crop_${System.currentTimeMillis()}.jpg").also { file ->
            file.outputStream().use { out ->
                cropped.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }
            cropped.recycle()
        }.let(Uri::fromFile)
    }.getOrNull()
}

private fun decodeDownscaledBitmap(context: Context, uri: Uri): Bitmap? = runCatching {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
    var sampleSize = 1
    while (max(bounds.outWidth, bounds.outHeight) / sampleSize > MAX_BITMAP_DIM) {
        sampleSize *= 2
    }
    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
}.getOrNull()
// KMK <--
