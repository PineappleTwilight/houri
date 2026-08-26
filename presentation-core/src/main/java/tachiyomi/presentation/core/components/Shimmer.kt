package tachiyomi.presentation.core.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

fun Modifier.shimmer(
    baseColor: Color = Color.Unspecified,
    highlightColor: Color = Color.Unspecified,
): Modifier = composed {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val translate by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmerTranslate",
    )
    val resolvedBase = if (baseColor == Color.Unspecified) {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    } else {
        baseColor
    }
    val resolvedHighlight = if (highlightColor == Color.Unspecified) {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f)
    } else {
        highlightColor
    }
    val brush = Brush.linearGradient(
        colors = listOf(resolvedBase, resolvedHighlight, resolvedBase),
        start = Offset(translate - 500f, 0f),
        end = Offset(translate, 0f),
    )
    background(brush)
}

@Composable
fun ShimmerBox(
    modifier: Modifier = Modifier,
    height: Dp = 16.dp,
    width: Dp? = null,
    shape: RoundedCornerShape = RoundedCornerShape(8.dp),
) {
    Box(
        modifier = modifier
            .then(if (width != null) Modifier.width(width) else Modifier.fillMaxWidth())
            .height(height)
            .clip(shape)
            .shimmer(),
    )
}

@Composable
fun MangaCardShimmer(modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(4.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .clip(RoundedCornerShape(8.dp))
                .shimmer(),
        )
        Spacer(modifier = Modifier.height(8.dp))
        ShimmerBox(height = 12.dp, width = 80.dp)
        Spacer(modifier = Modifier.height(4.dp))
        ShimmerBox(height = 10.dp, width = 60.dp)
    }
}

@Composable
fun MangaListShimmer(modifier: Modifier = Modifier) {
    Row(modifier = modifier.padding(8.dp)) {
        Box(
            modifier = Modifier
                .size(56.dp, 80.dp)
                .clip(RoundedCornerShape(6.dp))
                .shimmer(),
        )
        Column(modifier = Modifier.padding(start = 12.dp)) {
            ShimmerBox(height = 14.dp, width = 140.dp)
            Spacer(modifier = Modifier.height(8.dp))
            ShimmerBox(height = 10.dp, width = 100.dp)
            Spacer(modifier = Modifier.height(6.dp))
            ShimmerBox(height = 10.dp, width = 80.dp)
        }
    }
}

@Composable
fun ChapterListShimmer(modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        repeat(5) {
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    ShimmerBox(height = 12.dp, width = 180.dp)
                    Spacer(modifier = Modifier.height(6.dp))
                    ShimmerBox(height = 10.dp, width = 100.dp)
                }
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .shimmer(),
                )
            }
        }
    }
}

@Composable
fun LibraryShimmerGrid(modifier: Modifier = Modifier) {
    androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
        columns = androidx.compose.foundation.lazy.grid.GridCells.Adaptive(128.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(8.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
    ) {
        items(12) {
            MangaCardShimmer()
        }
    }
}

@Composable
fun FeedShimmer(modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(8.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
        repeat(6) {
            MangaListShimmer()
        }
    }
}

@Composable
fun MangaDetailShimmer(modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth().padding(16.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp)) {
            Box(
                modifier = Modifier
                    .size(120.dp, 180.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .shimmer(),
            )
            Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                ShimmerBox(height = 18.dp, width = 160.dp)
                ShimmerBox(height = 12.dp, width = 100.dp)
                Spacer(modifier = Modifier.height(8.dp))
                ShimmerBox(height = 10.dp)
                ShimmerBox(height = 10.dp, width = 140.dp)
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        ShimmerBox(height = 36.dp)
        ChapterListShimmer()
    }
}
