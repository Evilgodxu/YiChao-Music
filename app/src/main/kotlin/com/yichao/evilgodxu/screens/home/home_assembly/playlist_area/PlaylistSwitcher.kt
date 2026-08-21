package com.yichao.evilgodxu.screens.home.home_assembly.playlist_area

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.musicpanel.MusicPlaybackState
import com.yichao.evilgodxu.musicpanel.MusicTrack
import com.yichao.evilgodxu.musicpanel.PlaylistSource
import com.yichao.evilgodxu.musicpanel.playTrackAt
import com.yichao.evilgodxu.screens.home.data.PlaylistGroup
import com.yichao.evilgodxu.screens.home.data.PlaylistStore
import com.yichao.evilgodxu.screens.home.data.SmartPlaylistType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// 切换到指定歌单队列并播放，优先保持当前曲目位置
internal fun switchToPlaylistQueue(
    context: Context,
    state: MusicPlaybackState,
    scope: CoroutineScope,
    tracks: List<MusicTrack>,
    source: PlaylistSource?,
) {
    if (tracks.isEmpty()) {
        state.playlist = tracks
        state.playlistSource = source
        return
    }
    // 首次从默认库切到歌单时备份默认列表，供快捷切回
    if (state.playlistSource == null && state.defaultPlaylistBackup == null) {
        state.defaultPlaylistBackup = state.playlist
    }
    val currentId = state.currentTrack?.id
    val index = currentId?.let { id -> tracks.indexOfFirst { it.id == id } }
        ?.coerceIn(0, tracks.size - 1)
        ?: 0
    state.playlist = tracks
    state.playlistSource = source
    state.currentIndex = index
    scope.launch { playTrackAt(context, state, index) }
}

// 播放列表副标题快捷切换歌单弹层：默认 + 智能歌单 + 自定义歌单，专辑/艺术家支持分组二级导航
@Composable
internal fun PlaylistSwitcher(
    visible: Boolean,
    playbackState: MusicPlaybackState,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showGroups by remember { mutableStateOf<SmartPlaylistType?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.72f)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showGroups != null) {
                    IconButton(onClick = { showGroups = null }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                Text(
                    text = if (showGroups == null) stringResource(R.string.playlist_switch_title)
                    else smartTypeLabel(showGroups!!),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.back),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            val type = showGroups
            if (type == null) {
                PlaylistSwitchList(
                    playbackState = playbackState,
                    onSwitch = { tracks, source ->
                        switchToPlaylistQueue(context, playbackState, scope, tracks, source)
                        onDismiss()
                    },
                    onOpenGroups = { showGroups = it },
                )
            } else {
                PlaylistSwitchGroups(
                    type = type,
                    playbackState = playbackState,
                    onSwitch = { tracks, source ->
                        switchToPlaylistQueue(context, playbackState, scope, tracks, source)
                        onDismiss()
                    },
                )
            }
        }
    }
}

// 一级列表：默认播放列表 + 常听/收藏 + 专辑/艺术家入口 + 我的歌单
@Composable
private fun PlaylistSwitchList(
    playbackState: MusicPlaybackState,
    onSwitch: (List<MusicTrack>, PlaylistSource?) -> Unit,
    onOpenGroups: (SmartPlaylistType) -> Unit,
) {
    val library = playbackState.libraryTracks
    val currentKey = playbackState.playlistSource?.key
    val recentLabel = stringResource(R.string.playlist_smart_recent)
    val favoriteLabel = stringResource(R.string.playlist_smart_favorite)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item {
            SwitchRow(
                icon = Icons.AutoMirrored.Filled.QueueMusic,
                title = stringResource(R.string.playlist_switch_default),
                subtitle = stringResource(R.string.music_panel_track_count, library.size),
                isCurrent = currentKey == null,
                onClick = { onSwitch(playbackState.defaultPlaylistBackup ?: library, null) },
            )
        }
        item {
            SwitchRow(
                icon = Icons.Filled.History,
                title = recentLabel,
                subtitle = stringResource(R.string.music_panel_track_count, smartTrackCount(library, playbackState.recentPlayedIds)),
                isCurrent = currentKey == "smart:RECENT",
                onClick = {
                    onSwitch(
                        recentTracks(library, playbackState.recentPlayedIds),
                        PlaylistSource("smart:RECENT", recentLabel),
                    )
                },
            )
        }
        item {
            SwitchRow(
                icon = Icons.Filled.Favorite,
                title = favoriteLabel,
                subtitle = stringResource(R.string.music_panel_track_count, smartTrackCount(library, playbackState.likedIds)),
                isCurrent = currentKey == "smart:FAVORITE",
                onClick = {
                    onSwitch(
                        library.filter { it.id in playbackState.likedIds },
                        PlaylistSource("smart:FAVORITE", favoriteLabel),
                    )
                },
            )
        }
        item {
            SwitchRow(
                icon = Icons.Filled.Album,
                title = stringResource(R.string.playlist_smart_album),
                subtitle = "",
                isCurrent = false,
                showChevron = true,
                onClick = { onOpenGroups(SmartPlaylistType.ALBUM) },
            )
        }
        item {
            SwitchRow(
                icon = Icons.Filled.Person,
                title = stringResource(R.string.playlist_smart_artist),
                subtitle = "",
                isCurrent = false,
                showChevron = true,
                onClick = { onOpenGroups(SmartPlaylistType.ARTIST) },
            )
        }
        item {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.playlist_section_my),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 12.dp),
            )
            Spacer(modifier = Modifier.height(6.dp))
        }
        items(PlaylistStore.playlists, key = { it.id }) { playlist ->
            SwitchRow(
                icon = Icons.AutoMirrored.Filled.QueueMusic,
                title = playlist.name,
                subtitle = stringResource(R.string.music_panel_track_count, playlist.trackIds.size),
                isCurrent = currentKey == "custom:${playlist.id}",
                onClick = {
                    onSwitch(
                        resolveTracks(library, playlist.trackIds),
                        PlaylistSource("custom:${playlist.id}", playlist.name),
                    )
                },
            )
        }
        item { Spacer(modifier = Modifier.height(12.dp)) }
    }
}

// 二级列表：专辑/艺术家分组
@Composable
private fun PlaylistSwitchGroups(
    type: SmartPlaylistType,
    playbackState: MusicPlaybackState,
    onSwitch: (List<MusicTrack>, PlaylistSource?) -> Unit,
) {
    val context = LocalContext.current
    val library = playbackState.libraryTracks
    val groups: List<PlaylistGroup> = if (type == SmartPlaylistType.ALBUM) {
        albumGroups(library, context.getString(R.string.playlist_unknown_album))
    } else {
        artistGroups(library, context.getString(R.string.music_scanner_unknown_artist))
    }
    val icon: ImageVector = if (type == SmartPlaylistType.ALBUM) Icons.Filled.Album else Icons.Filled.Person
    val currentKey = playbackState.playlistSource?.key
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(groups, key = { it.key }) { group ->
            val tracks = library.filter { track ->
                if (type == SmartPlaylistType.ALBUM) {
                    track.albumId == group.key.removePrefix("album:").toLongOrNull()
                } else {
                    track.artist == group.name
                }
            }
            SwitchRow(
                icon = icon,
                title = group.name,
                subtitle = stringResource(R.string.music_panel_track_count, group.trackIds.size),
                isCurrent = currentKey == group.key,
                onClick = { onSwitch(tracks, PlaylistSource(group.key, group.name)) },
            )
        }
    }
}

// 切换项行：图标 + 名称 + 数量 + 当前标识/下级箭头，几何与排版对齐歌单列表行
@Composable
private fun SwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    isCurrent: Boolean,
    onClick: () -> Unit,
    showChevron: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                else Color.Transparent,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 12.sp,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                )
            }
        }
        when {
            isCurrent -> Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            showChevron -> Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
