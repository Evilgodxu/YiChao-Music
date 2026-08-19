package com.yichao.evilgodxu.screens.home.home_assembly.player_area

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.musicpanel.DiscArt
import com.yichao.evilgodxu.musicpanel.MusicPanelStateHolder
import com.yichao.evilgodxu.musicpanel.PlayMode
import com.yichao.evilgodxu.musicpanel.applyPlaybackMode
import com.yichao.evilgodxu.musicpanel.playTrackAt
import com.yichao.evilgodxu.musicpanel.togglePlayPause
import com.yichao.evilgodxu.screens.settings.settings_assembly.component.section.SettingsSection
import kotlinx.coroutines.launch

// 首页播放器分区：展示当前曲目与基础控制，点击封面展开完整音乐面板
@Composable
fun PlayerArea(
    onOpenMusicPanel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val playbackState = MusicPanelStateHolder.state
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentTrack = playbackState.currentTrack

    SettingsSection(title = stringResource(R.string.home_player_title)) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            if (currentTrack != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onOpenMusicPanel,
                        )
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DiscArt(
                        track = currentTrack,
                        isPlaying = playbackState.isPlaying,
                        modifier = Modifier.size(56.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = currentTrack.title,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = currentTrack.artist,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = onOpenMusicPanel) {
                        Icon(
                            imageVector = Icons.Outlined.OpenInFull,
                            contentDescription = stringResource(R.string.home_player_open_panel),
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = {
                            val prev = playbackState.previousIndex()
                            if (prev >= 0) scope.launch { playTrackAt(context, playbackState, prev) }
                        },
                        enabled = playbackState.playlist.isNotEmpty(),
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipPrevious,
                            contentDescription = stringResource(R.string.home_player_previous),
                        )
                    }
                    IconButton(
                        onClick = { togglePlayPause(playbackState) },
                        enabled = playbackState.playlist.isNotEmpty(),
                    ) {
                        Icon(
                            imageVector = if (playbackState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = stringResource(
                                if (playbackState.isPlaying) R.string.home_player_pause else R.string.home_player_play
                            ),
                        )
                    }
                    IconButton(
                        onClick = {
                            val next = playbackState.nextIndex()
                            if (next >= 0) scope.launch { playTrackAt(context, playbackState, next) }
                        },
                        enabled = playbackState.playlist.isNotEmpty(),
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipNext,
                            contentDescription = stringResource(R.string.home_player_next),
                        )
                    }
                    IconButton(
                        onClick = {
                            playbackState.setPlayMode(
                                when (playbackState.playMode) {
                                    PlayMode.RepeatAll -> PlayMode.RepeatOne
                                    PlayMode.RepeatOne -> PlayMode.Shuffle
                                    PlayMode.Shuffle -> PlayMode.RepeatAll
                                }
                            )
                            playbackState.mediaController?.let { applyPlaybackMode(it, playbackState.playMode) }
                            playbackState.persistState()
                        },
                    ) {
                        Icon(
                            imageVector = when (playbackState.playMode) {
                                PlayMode.RepeatAll -> Icons.Default.Repeat
                                PlayMode.RepeatOne -> Icons.Default.RepeatOne
                                PlayMode.Shuffle -> Icons.Default.Shuffle
                            },
                            contentDescription = stringResource(R.string.music_panel_play_mode),
                        )
                    }
                }
            } else {
                Text(
                    text = stringResource(R.string.home_player_empty),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                Button(onClick = onOpenMusicPanel) {
                    Text(stringResource(R.string.home_player_open_panel))
                }
            }
        }
    }
}
