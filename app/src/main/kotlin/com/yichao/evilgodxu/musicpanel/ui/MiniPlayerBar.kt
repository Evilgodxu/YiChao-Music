package com.yichao.evilgodxu.musicpanel

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.ui.icons.AppIcons
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
            val lyricText = current?.let { track ->
                if (track.lyricLines.isNotEmpty()) {
                    val index = track.lyricLines.indexOfLast { it.timeMs <= lyricPosition }.coerceAtLeast(0)
                    track.lyricLines.getOrNull(index)?.text.orEmpty()
                } else {
                    track.artist
                }
            }.orEmpty()
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
                Text(
                    text = lyricText,
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 10.sp,
                    lineHeight = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

