package com.yichao.evilgodxu.musicpanel

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

// 横屏沉浸式 3D 封面轮播视图：当前封面居中，其余封面分列左右带透视倾斜，
// 左右滑动切换并吸附居中，点击居中封面播放对应歌曲。
@Composable
internal fun CoverCarouselOverlay(
    playlist: List<MusicTrack>,
    currentIndex: Int,
    onTrackSelected: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    if (playlist.isEmpty()) return
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val startIndex = currentIndex.coerceIn(0, playlist.size - 1)
    var selectedIndex by remember { mutableIntStateOf(startIndex) }
    // 滑动过程中产生的封面偏移量（以相邻间距为单位），配合 snap 实现吸附
    var fraction by remember { mutableFloatStateOf(0f) }
    val snap = remember { Animatable(0f) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.78f))
    ) {
        val coverSize = (minOf(maxWidth.value * 0.4f, maxHeight.value * 0.55f)).dp
        val coverSizePx = with(density) { coverSize.toPx() }
        val spacingPx = with(density) { (coverSize + 72.dp).toPx() }

        // 点击非封面区域退出沉浸视图
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                )
        )

        // 滑动容器：捕获左右拖动以切换封面
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(playlist.size) {
                    detectDragGestures(
                        onDragStart = { scope.launch { snap.stop() } },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            fraction += dragAmount.x / spacingPx
                        },
                        onDragEnd = {
                            scope.launch {
                                val current = fraction + snap.value
                                val target = Math.round(current)
                                    .coerceIn(selectedIndex - (playlist.size - 1), selectedIndex)
                                snap.animateTo(target.toFloat(), animationSpec = tween(300))
                                selectedIndex = selectedIndex - target
                                fraction = 0f
                                snap.snapTo(0f)
                            }
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            // 仅渲染选中封面附近的封面，避免条目过多时过度绘制
            val windowStart = (selectedIndex - 4).coerceAtLeast(0)
            val windowEnd = (selectedIndex + 4).coerceAtMost(playlist.size - 1)
            for (i in windowStart..windowEnd) {
                val rel = (i - selectedIndex) + snap.value + fraction
                val isSelected = rel == 0f
                Box(
                    modifier = Modifier
                        .offset { IntOffset((rel * spacingPx).roundToInt(), 0) }
                        .size(coverSize)
                        .graphicsLayer {
                            rotationY = (-rel * 28f).coerceIn(-75f, 75f)
                            scaleX = (1f - kotlin.math.abs(rel) * 0.22f).coerceAtLeast(0.6f)
                            scaleY = scaleX
                            alpha = (1f - kotlin.math.abs(rel) * 0.4f).coerceIn(0.2f, 1f)
                        }
                        .clip(RoundedCornerShape(16.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {
                                if (isSelected) {
                                    onTrackSelected(i)
                                } else {
                                    // 点击未居中封面：先吸附到中心再选中
                                    scope.launch {
                                        snap.animateTo((selectedIndex - i).toFloat(), animationSpec = tween(300))
                                        selectedIndex = i
                                        fraction = 0f
                                        snap.snapTo(0f)
                                    }
                                }
                            }
                        )
                ) {
                    AlbumArt(playlist[i], Modifier.fillMaxSize())
                }
            }
        }

        // 选中封面的正下方居中显示歌曲标题与艺术家
        val track = playlist.getOrNull(selectedIndex)
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = track?.title ?: "",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = track?.artist ?: "",
                color = Color.White.copy(alpha = 0.72f),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1
            )
        }
    }
}