package com.yichao.evilgodxu.musicpanel

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yichao.evilgodxu.data.settings.ThemeMode
import com.yichao.evilgodxu.data.settings.settingsFlow
import com.yichao.evilgodxu.theme.DarkColorScheme
import com.yichao.evilgodxu.theme.LightColorScheme
import kotlinx.coroutines.delay

@Composable
internal fun MiniPlayerOverlay(
    playbackState: MusicPlaybackState,
    barHeightPx: Int,
    barWidthPx: Int,
    playlistExpanded: Boolean,
    onPlaylistExpandedChange: (Boolean) -> Unit,
    onLayoutChanged: () -> Unit,
    onExpandPanel: () -> Unit,
    onSwipeDismiss: () -> Unit,
) {
    val density = LocalDensity.current
    val context = LocalContext.current
    val barHeight = with(density) { barHeightPx.toDp() }
    val barWidth = with(density) { barWidthPx.toDp() }

    // 左右滑动关闭：拖动时实时跟随手指，超过阈值后滑出屏幕再收起
    var swipeOffset by remember { mutableStateOf(0f) }
    var swipeDismissing by remember { mutableStateOf(false) }

    // 跟随应用主题：设置项优先，其次系统深色模式
    val settings by context.settingsFlow().collectAsStateWithLifecycle(initialValue = null)
    val isSystemDark = isSystemInDarkTheme()
    val isDarkTheme = when (settings?.themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        else -> isSystemDark
    }
    val colorScheme = if (isDarkTheme) DarkColorScheme else LightColorScheme
    // 卡片背景：收起时高透明透出下层内容，展开播放列表时降低透明度保证列表可读性
    val cardBackground = if (playlistExpanded) {
        Color(if (isDarkTheme) 0xFF161B22 else 0xFFF5F5F7).copy(alpha = 0.92f)
    } else {
        Color(if (isDarkTheme) 0xFF161B22 else 0xFFF5F5F7).copy(alpha = if (isDarkTheme) 0.55f else 0.60f)
    }

    // 屏幕旋转时自动收起播放列表并重新布局
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    LaunchedEffect(configuration.orientation) {
        if (playlistExpanded) onPlaylistExpandedChange(false)
        onLayoutChanged()
    }

    MaterialTheme(colorScheme = colorScheme) {
        if (playlistExpanded) {
            // 展开时窗口铺满屏幕，卡片以外区域点击即收起
            val cardScale = remember { Animatable(0.85f) }
            LaunchedEffect(Unit) {
                cardScale.animateTo(
                    targetValue = 1f,
                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onPlaylistExpandedChange(false) }
                    )
            ) {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .width(barWidth)
                        .graphicsLayer {
                            scaleX = cardScale.value
                            scaleY = cardScale.value
                        }
                        // 展开播放列表时用小圆角，呈现更接近窗口的方角效果
                        .background(cardBackground, RoundedCornerShape(12.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { /* 阻止点击穿透到收起区域 */ }
                        )
                ) {
                    MiniPlayerBar(
                        playbackState = playbackState,
                        barHeight = barHeight,
                        playlistExpanded = playlistExpanded,
                        onPlaylistExpandedChange = onPlaylistExpandedChange,
                        onExpandPanel = onExpandPanel,
                        swipeDismissThreshold = barWidthPx / 2f,
                        onSwipeOffsetChange = { swipeOffset = it },
                        onSwipeCommit = { swipeDismissing = true },
                        onSwipeCancel = { swipeOffset = 0f }
                    )
                    MiniPlaylistPanel(
                        playbackState = playbackState,
                        context = context,
                        onClose = { onPlaylistExpandedChange(false) }
                    )
                }
            }
        } else {
            // 拖动时跟随手指；确认关闭后向拖动方向滑出整个条宽，动画结束再触发收起
            val swipeTranslate by animateFloatAsState(
                targetValue = when {
                    swipeDismissing -> if (swipeOffset > 0f) barWidthPx.toFloat() else -barWidthPx.toFloat()
                    else -> swipeOffset
                },
                animationSpec = if (swipeDismissing) {
                    tween(durationMillis = SWIPE_DISMISS_MS, easing = LinearEasing)
                } else {
                    spring(stiffness = Spring.StiffnessMediumLow)
                },
                label = "mini_player_swipe_offset"
            )
            LaunchedEffect(swipeDismissing) {
                if (swipeDismissing) {
                    delay(SWIPE_DISMISS_MS.toLong())
                    onSwipeDismiss()
                }
            }
            Column(
                modifier = Modifier
                    .width(barWidth)
                    .graphicsLayer { translationX = swipeTranslate }
                    // 胶囊圆角：半径取条高（32dp）一半，呈椭圆轮廓
                    .background(cardBackground, RoundedCornerShape(16.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { /* 阻止点击穿透到状态栏区域 */ }
                    )
            ) {
                MiniPlayerBar(
                    playbackState = playbackState,
                    barHeight = barHeight,
                    playlistExpanded = playlistExpanded,
                    onPlaylistExpandedChange = onPlaylistExpandedChange,
                    onExpandPanel = onExpandPanel,
                    swipeDismissThreshold = barWidthPx / 2f,
                    onSwipeOffsetChange = { swipeOffset = it },
                    onSwipeCommit = { swipeDismissing = true },
                    onSwipeCancel = { swipeOffset = 0f }
                )
            }
        }
    }
}