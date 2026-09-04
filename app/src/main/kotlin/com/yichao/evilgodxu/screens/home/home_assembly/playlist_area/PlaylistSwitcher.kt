package com.yichao.evilgodxu.screens.home.home_assembly.playlist_area

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.yichao.evilgodxu.data.music.metadata.MetadataEnricher
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.domain.music.MusicPlaybackState
import com.yichao.evilgodxu.domain.music.PlaylistSource
import com.yichao.evilgodxu.domain.music.playTrackAt
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.screens.home.data.PlaylistGroup
import com.yichao.evilgodxu.screens.home.data.PlaylistStore
import com.yichao.evilgodxu.screens.home.data.SmartPlaylistType
import com.yichao.evilgodxu.ui.icons.AppIcons
import com.yichao.evilgodxu.ui.music.cover.PlaylistArt
import kotlinx.coroutines.launch

// 切换到指定歌单队列并播放该歌单第一首歌曲
internal fun switchToPlaylistQueue(
    context: Context,
    state: MusicPlaybackState,
    tracks: List<MusicTrack>,
    source: PlaylistSource?,
) {
    if (tracks.isEmpty()) {
        state.playlist = tracks
        state.playlistSource = source
        state.persistPlaylist()
        return
    }
    // 首次从默认库切到歌单时备份默认列表，供快捷切回
    if (state.playlistSource == null && state.defaultPlaylistBackup == null) {
        state.defaultPlaylistBackup = state.playlist
    }
    state.playlist = tracks
    state.playlistSource = source
    state.currentIndex = 0
    // 仅加载新队列并暂停，不自动播放；在播放器全局作用域执行，避免弹层关闭取消协程导致队列未加载
    state.playbackScope.launch { playTrackAt(context, state, 0, autoPlay = false) }
    state.persistPlaylist()
    // 切换歌单后后台补全新歌单缺失的封面/歌词，缓存已就绪的歌曲直接命中不重复加载
    state.playbackScope.launch { MetadataEnricher.enrichAndCleanup(context, state) }
}

// 播放列表副标题快捷切换歌单弹层：默认 + 系统歌单 + 自定义歌单，专辑/艺术家支持分组二级导航
@Composable
internal fun PlaylistSwitcher(
    visible: Boolean,
    playbackState: MusicPlaybackState,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    val context = LocalContext.current
    var showGroups by remember { mutableStateOf<SmartPlaylistType?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.36f)
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
                            imageVector = AppIcons.ArrowBack,
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
                        imageVector = AppIcons.Close,
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
                        switchToPlaylistQueue(context, playbackState, tracks, source)
                        onDismiss()
                    },
                    onOpenGroups = { showGroups = it },
                )
            } else {
                PlaylistSwitchGroups(
                    type = type,
                    playbackState = playbackState,
                    onSwitch = { tracks, source ->
                        switchToPlaylistQueue(context, playbackState, tracks, source)
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
                icon = AppIcons.QueueMusic,
                title = stringResource(R.string.playlist_switch_default),
                subtitle = stringResource(R.string.music_panel_track_count, library.size),
                isCurrent = currentKey == null,
                onClick = { onSwitch(playbackState.defaultPlaylistBackup ?: library, null) },
            )
        }
        item {
            SwitchRow(
                icon = AppIcons.History,
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
                icon = AppIcons.Favorite,
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
                icon = AppIcons.Album,
                title = stringResource(R.string.playlist_smart_album),
                subtitle = "",
                isCurrent = false,
                showChevron = true,
                onClick = { onOpenGroups(SmartPlaylistType.ALBUM) },
            )
        }
        item {
            SwitchRow(
                icon = AppIcons.Person,
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
                icon = AppIcons.QueueMusic,
                title = playlist.name,
                subtitle = stringResource(R.string.music_panel_track_count, playlist.trackIds.size),
                isCurrent = currentKey == "custom:${playlist.id}",
                coverTrack = library.firstOrNull { it.id == playlist.trackIds.firstOrNull() },
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
    val library = playbackState.libraryTracks
    val groups: List<PlaylistGroup> = if (type == SmartPlaylistType.ALBUM) {
        albumGroups(library, stringResource(R.string.playlist_unknown_album))
    } else {
        artistGroups(library, stringResource(R.string.music_scanner_unknown_artist))
    }
    val icon: ImageVector = if (type == SmartPlaylistType.ALBUM) AppIcons.Album else AppIcons.Person
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
                coverTrack = library.firstOrNull { it.id == group.trackIds.firstOrNull() },
                onClick = { onSwitch(tracks, PlaylistSource(group.key, group.name)) },
            )
        }
    }
}

// 切换项行：图标/封面 + 名称 + 数量 + 当前标识/下级箭头，几何与排版对齐歌单列表行
@Composable
private fun SwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    isCurrent: Boolean,
    onClick: () -> Unit,
    showChevron: Boolean = false,
    coverTrack: MusicTrack? = null,
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
            if (coverTrack != null) {
                PlaylistArt(track = coverTrack, modifier = Modifier.fillMaxSize())
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
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
                imageVector = AppIcons.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            showChevron -> Icon(
                imageVector = AppIcons.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
