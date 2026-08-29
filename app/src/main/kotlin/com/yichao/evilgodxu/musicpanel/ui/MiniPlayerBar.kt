package com.yichao.evilgodxu.musicpanel

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.ui.icons.AppIcons
import kotlin.math.min
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
internal fun MiniPlayerBar(
    playbackState: MusicPlaybackState,
    barHeight: Dp,
    playlistExpanded: Boolean,
    onPlaylistExpandedChange: (Boolean) -> Unit,
    onExpandPanel: () -> Unit,
    swipeDismissThreshold: Float,
    onSwipeOffsetChange: (Float) -> Unit,
    onSwipeCommit: () -> Unit,
    onSwipeCancel: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val current = playbackState.currentTrack
    val coverDesc = stringResource(R.string.mini_player_cover)

    // 控件自动隐藏：3 秒无操作后隐藏控制按钮，改为显示歌曲名与歌词；任意触摸即可还原
    var controlsVisible by remember { mutableStateOf(true) }
    var interactionTick by remember { mutableStateOf(0) }
    fun resetAutoHide() {
        controlsVisible = true
        interactionTick++
    }
    LaunchedEffect(interactionTick, playlistExpanded) {
        if (playlistExpanded) {
            controlsVisible = true
            return@LaunchedEffect
        }
        delay(3000)
        controlsVisible = false
    }
    // 隐藏控件期间跟随播放进度刷新当前歌词；跟随当前曲目，切换歌曲时重置到曲目起点
    var lyricPosition by remember(playbackState.currentTrack?.id) { mutableStateOf(0L) }
    LaunchedEffect(controlsVisible, playbackState.currentTrack?.id) {
        if (controlsVisible) return@LaunchedEffect
        var lastSyncMs = 0L
        while (isActive) {
            val candidate = playbackState.mediaController?.currentPosition
                ?.takeIf { it >= 0L } ?: playbackState.currentPosition
            if (playbackState.isPlaying) {
                val now = System.currentTimeMillis()
                val elapsed = if (lastSyncMs == 0L) 0L else (now - lastSyncMs).coerceAtLeast(0L)
                lyricPosition = if (candidate >= lyricPosition) candidate else lyricPosition + elapsed
                lastSyncMs = now
            } else {
                lyricPosition = candidate
                lastSyncMs = 0L
            }
            delay(if (playbackState.isPlaying) 50L else 200L)
        }
    }

    // 左右滑动关闭：拖动时跟随手指，超过阈值后滑出（播放列表展开时不响应滑动）
    var totalDx by remember { mutableStateOf(0f) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(barHeight)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        if (awaitPointerEvent().changes.any { it.pressed }) resetAutoHide()
                    }
                }
            }
            .pointerInput(playlistExpanded) {
                totalDx = 0f
                detectHorizontalDragGestures(
                    onDragStart = { totalDx = 0f },
                    onDragEnd = {
                        if (!playlistExpanded && kotlin.math.abs(totalDx) >= swipeDismissThreshold) {
                            onSwipeCommit()
                        } else {
                            onSwipeCancel()
                        }
                        totalDx = 0f
                    },
                    onDragCancel = {
                        onSwipeCancel()
                        totalDx = 0f
                    }
                ) { change, drag ->
                    change.consume()
                    totalDx += drag
                    onSwipeOffsetChange(totalDx)
                }
            }
            .padding(horizontal = MINI_PADDING_H_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(0.dp, Alignment.CenterHorizontally)
    ) {
        // 专辑封面：旋转 + 黑胶质感
        Box(
            modifier = Modifier
                .size(MINI_COVER_DP.dp)
                .clip(CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onExpandPanel
                ),
            contentAlignment = Alignment.Center,
        ) {
            DiscArt(
                track = current,
                isPlaying = playbackState.isPlaying,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (controlsVisible) {
            // 循环模式
            MiniControlButton(
                icon = when (playbackState.playMode) {
                    PlayMode.RepeatAll -> AppIcons.Repeat
                    PlayMode.RepeatOne -> AppIcons.RepeatOne
                    PlayMode.Shuffle -> AppIcons.Shuffle
                },
                contentDescription = stringResource(R.string.music_panel_play_mode),
                onClick = {
                    playbackState.setPlayMode(
                        when (playbackState.playMode) {
                            PlayMode.RepeatAll -> PlayMode.RepeatOne
                            PlayMode.RepeatOne -> PlayMode.Shuffle
                            PlayMode.Shuffle -> PlayMode.RepeatAll
                        }
                    )
                    playbackState.mediaController?.let { controller ->
                        applyPlaybackMode(controller, playbackState.playMode)
                    }
                    playbackState.persistState()
                }
            )
            // 上一曲
            MiniControlButton(
                icon = AppIcons.SkipPrevious,
                contentDescription = stringResource(R.string.mini_player_previous),
                enabled = playbackState.playlist.isNotEmpty(),
                onClick = {
                    val prev = playbackState.previousIndex()
                    if (prev >= 0) scope.launch { playTrackAt(context, playbackState, prev) }
                }
            )
            // 暂停 / 播放
            MiniControlButton(
                icon = if (playbackState.isPlaying) AppIcons.Pause else AppIcons.PlayArrow,
                contentDescription = stringResource(
                    if (playbackState.isPlaying) R.string.music_panel_pause else R.string.music_panel_play
                ),
                onClick = { togglePlayPause(playbackState) }
            )
            // 下一曲
            MiniControlButton(
                icon = AppIcons.SkipNext,
                contentDescription = stringResource(R.string.mini_player_next),
                enabled = playbackState.playlist.isNotEmpty(),
                onClick = {
                    val next = playbackState.nextIndex()
                    if (next >= 0) scope.launch { playTrackAt(context, playbackState, next) }
                }
            )
            // 播放列表
            MiniControlButton(
                icon = AppIcons.QueueMusic,
                contentDescription = stringResource(R.string.mini_player_playlist),
                onClick = { onPlaylistExpandedChange(!playlistExpanded) }
            )
        } else {
            // 隐藏控件：展示歌曲名与当前歌词
            val lyricLines = current?.lyricLines.orEmpty()
            val lyricIndex = if (lyricLines.isNotEmpty()) {
                lyricLines.indexOfLast { it.timeMs <= lyricPosition }.coerceAtLeast(0)
            } else -1
            val lyricLine = lyricLines.getOrNull(lyricIndex)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = current?.title.orEmpty(),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 12.sp,
                    lineHeight = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Medium,
                )
                if (lyricLine == null) {
                    // 无歌词时退化为歌手名静态展示
                    Text(
                        text = current?.artist.orEmpty(),
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 10.sp,
                        lineHeight = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.Medium,
                    )
                } else {
                    // 行内演唱进度：按本行到下一行的起止时间折算；填充时长略短于行时长，
                    // 留出余量让行尾文字在切到下一行前完整揭示
                    val lineEndMs = lyricLines
                        .getOrNull(lyricIndex + 1)
                        ?.timeMs ?: (lyricLine.timeMs + 3000L)
                    val lineDuration = (lineEndMs - lyricLine.timeMs).coerceAtLeast(1L)
                    val fillDuration = lineDuration - min(400L, lineDuration / 5)
                    val progress = ((lyricPosition - lyricLine.timeMs).toFloat() /
                        fillDuration.toFloat())
                        .coerceIn(0f, 1f)
                    MiniPlayerLyricText(
                        text = lyricLine.text,
                        progress = progress,
                        color = MaterialTheme.colorScheme.primary,
                        style = TextStyle(
                            fontSize = 10.sp,
                            lineHeight = 12.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                        lineKey = current?.id to lyricIndex,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

// 迷你条歌词行：按行内演唱进度从左向右完整揭示，唱完此行时恰好露出最后一个字。
// 文字超宽时跟随揭示边缘平移，替代省略号截断，保证整行歌词都能被看到
@Composable
private fun MiniPlayerLyricText(
    text: String,
    progress: Float,
    color: Color,
    style: TextStyle,
    lineKey: Any,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val layout = remember(text, style) { textMeasurer.measure(AnnotatedString(text), style) }
    // 揭示进度按行小幅平滑推进；换行时重置归零，避免上一行进度回卷的闪烁
    val revealProgress = remember(lineKey) { Animatable(progress) }
    LaunchedEffect(lineKey, progress) {
        revealProgress.animateTo(
            targetValue = progress,
            animationSpec = tween(durationMillis = MINI_LYRIC_REVEAL_SMOOTH_MS, easing = LinearEasing),
        )
    }
    Box(
        modifier = modifier
            .height(with(density) { layout.size.height.toDp() })
            .drawWithContent {
                val textWidth = layout.size.width.toFloat()
                if (textWidth <= 0f || size.width <= 0f) return@drawWithContent
                // 揭示边缘随进度从左向右推进；文字宽于视口时向左平移，让唱到的字始终可见
                val revealEdge = revealProgress.value.coerceIn(0f, 1f) * textWidth
                val translateX = min(0f, size.width - revealEdge)
                clipRect(left = 0f, right = translateX + revealEdge) {
                    drawText(layout, color = color, topLeft = Offset(translateX, 0f))
                }
            }
    )
}

// 歌词揭示平滑时长：行内推进无明显跳变
private const val MINI_LYRIC_REVEAL_SMOOTH_MS = 60

