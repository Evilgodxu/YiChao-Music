package com.yichao.evilgodxu.screens.home.home_assembly.player_area

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.musicpanel.MusicPlaybackState
import com.yichao.evilgodxu.musicpanel.PlayMode
import com.yichao.evilgodxu.musicpanel.SpeedDialog
import com.yichao.evilgodxu.musicpanel.applyPlaybackMode
import com.yichao.evilgodxu.musicpanel.playTrackAt
import com.yichao.evilgodxu.musicpanel.togglePlayPause
import com.yichao.evilgodxu.ui.icons.AppIcons
import kotlinx.coroutines.launch

// 底部控制栏：与迷你播放器控件布局一致（播放模式 → 上一曲 → 播放/暂停 → 下一曲 → 播放列表）
@Composable
internal fun PlayerControls(
    playbackState: MusicPlaybackState,
    onPlaylistClick: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 调速对话框显隐：长按上一曲/下一曲按钮弹出
    var showSpeedDialog by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayerControlButton(
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
            },
        )
        PlayerControlButton(
            icon = AppIcons.SkipPrevious,
            contentDescription = stringResource(R.string.home_player_previous),
            enabled = playbackState.playlist.isNotEmpty(),
            onClick = {
                val prev = playbackState.previousIndex()
                if (prev >= 0) scope.launch { playTrackAt(context, playbackState, prev) }
            },
            onLongClick = { showSpeedDialog = true },
        )
        PlayerControlButton(
            icon = if (playbackState.isPlaying) AppIcons.Pause else AppIcons.PlayArrow,
            contentDescription = stringResource(
                if (playbackState.isPlaying) R.string.home_player_pause else R.string.home_player_play
            ),
            enabled = playbackState.playlist.isNotEmpty(),
            onClick = { togglePlayPause(playbackState) },
        )
        PlayerControlButton(
            icon = AppIcons.SkipNext,
            contentDescription = stringResource(R.string.home_player_next),
            enabled = playbackState.playlist.isNotEmpty(),
            onClick = {
                val next = playbackState.nextIndex()
                if (next >= 0) scope.launch { playTrackAt(context, playbackState, next) }
            },
            onLongClick = { showSpeedDialog = true },
        )
        PlayerControlButton(
            icon = AppIcons.QueueMusic,
            contentDescription = stringResource(R.string.music_panel_playlist),
            onClick = onPlaylistClick,
        )
    }
    SpeedDialog(
        visible = showSpeedDialog,
        speed = playbackState.playbackSpeed,
        onSpeedChange = { playbackState.setPlaybackSpeed(it) },
        onDismiss = { showSpeedDialog = false },
    )
}

@Composable
private fun PlayerControlButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    val tint = if (enabled) Color.White else Color.White.copy(alpha = 0.3f)
    val iconContent = @Composable {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(32.dp),
            tint = tint,
        )
    }
    if (onLongClick == null) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.size(48.dp),
        ) {
            iconContent()
        }
    } else {
        // 长按支持：单击保留原行为，长按弹出调速对话框
        Box(
            modifier = Modifier
                .size(48.dp)
                .combinedClickable(
                    enabled = enabled,
                    onClick = onClick,
                    onLongClick = onLongClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            iconContent()
        }
    }
}