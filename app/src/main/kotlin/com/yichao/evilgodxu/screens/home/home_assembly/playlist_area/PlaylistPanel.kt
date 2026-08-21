package com.yichao.evilgodxu.screens.home.home_assembly.playlist_area

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.musicpanel.MusicPlaybackState
import com.yichao.evilgodxu.musicpanel.MusicTrack
import com.yichao.evilgodxu.musicpanel.PlaylistArt
import com.yichao.evilgodxu.screens.home.data.Playlist
import com.yichao.evilgodxu.screens.home.data.PlaylistGroup
import com.yichao.evilgodxu.screens.home.data.PlaylistStore
import com.yichao.evilgodxu.screens.home.data.SmartPlaylistType

// 首页左滑呼出的歌单面板：智能歌单 + 自定义歌单，支持页面栈导航
@Composable
internal fun PlaylistPanel(
    visible: Boolean,
    playbackState: MusicPlaybackState,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { PlaylistStore.ensureLoaded(context) }
    var backStack by remember { mutableStateOf(listOf<PlaylistPage>(PlaylistPage.Overview)) }
    LaunchedEffect(visible) { if (!visible) backStack = listOf(PlaylistPage.Overview) }
    val page = backStack.last()
    // 二级/三级详情页系统返回键逐级回退；顶层页面由首页 BackHandler 关闭面板
    BackHandler(enabled = visible && backStack.size > 1) {
        backStack = backStack.dropLast(1)
    }
    // 新建/重命名/删除歌单弹窗
    var showCreate by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<Playlist?>(null) }
    var deleteTarget by remember { mutableStateOf<Playlist?>(null) }

    Box(modifier = modifier) {
        // 透明全屏布局，与在线搜索面板一致，透出首页沉浸渐变背景
        Column(modifier = Modifier.fillMaxSize()) {
            PanelHeader(
                title = page.title(),
                showBack = backStack.size > 1,
                onBack = { backStack = backStack.dropLast(1) },
            )
            when (page) {
                is PlaylistPage.Overview -> PlaylistOverview(
                    playbackState = playbackState,
                    onOpenSmart = { type ->
                        backStack = backStack + if (type == SmartPlaylistType.ALBUM || type == SmartPlaylistType.ARTIST) {
                            PlaylistPage.Groups(type)
                        } else {
                            PlaylistPage.SmartTracks(type)
                        }
                    },
                    onOpenCustom = { playlist -> backStack = backStack + PlaylistPage.Tracks(playlist) },
                    onCreatePlaylist = { showCreate = true },
                    onRename = { renameTarget = it },
                    onDelete = { deleteTarget = it },
                )
                is PlaylistPage.Groups -> PlaylistGroupsPage(
                    type = page.type,
                    playbackState = playbackState,
                    onOpenGroup = { group -> backStack = backStack + PlaylistPage.GroupTracks(page.type, group) },
                )
                is PlaylistPage.SmartTracks -> PlaylistSmartTracksPage(
                    type = page.type,
                    playbackState = playbackState,
                )
                is PlaylistPage.Tracks -> PlaylistTracksPage(playlist = page.playlist, playbackState = playbackState)
                is PlaylistPage.GroupTracks -> PlaylistGroupTracksPage(
                    type = page.type,
                    group = page.group,
                    playbackState = playbackState,
                )
            }
        }
    }
    CreatePlaylistDialog(
        visible = showCreate,
        onCreated = { playlist ->
            showCreate = false
            // 创建后直接进入新歌单，便于立即添加歌曲
            backStack = backStack + PlaylistPage.Tracks(playlist)
        },
        onDismiss = { showCreate = false },
    )
    RenamePlaylistDialog(playlist = renameTarget, onDismiss = { renameTarget = null })
    DeletePlaylistDialog(playlist = deleteTarget, onDismiss = { deleteTarget = null })
}

// 面板页面：总览 / 智能分组列表 / 智能曲目 / 自定义歌单曲目 / 智能分组曲目
private sealed interface PlaylistPage {
    data object Overview : PlaylistPage
    data class Groups(val type: SmartPlaylistType) : PlaylistPage
    data class SmartTracks(val type: SmartPlaylistType) : PlaylistPage
    data class Tracks(val playlist: Playlist) : PlaylistPage
    data class GroupTracks(val type: SmartPlaylistType, val group: PlaylistGroup) : PlaylistPage
}

@Composable
private fun PlaylistPage.title(): String = when (this) {
    is PlaylistPage.Overview -> stringResource(R.string.playlist_title)
    is PlaylistPage.Groups -> smartTypeLabel(type)
    is PlaylistPage.SmartTracks -> smartTypeLabel(type)
    is PlaylistPage.Tracks -> playlist.name
    is PlaylistPage.GroupTracks -> group.name
}

// 面板顶部栏：返回按钮 + 标题，关闭统一走系统返回键
@Composable
private fun PanelHeader(
    title: String,
    showBack: Boolean,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showBack) {
            IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Text(
            text = title,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

// 总览页：智能歌单卡片 + 我的歌单列表 + 新建歌单入口
@Composable
private fun PlaylistOverview(
    playbackState: MusicPlaybackState,
    onOpenSmart: (SmartPlaylistType) -> Unit,
    onOpenCustom: (Playlist) -> Unit,
    onCreatePlaylist: () -> Unit,
    onRename: (Playlist) -> Unit,
    onDelete: (Playlist) -> Unit,
) {
    val allTracks = playbackState.libraryTracks
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item {
            SectionLabel(text = stringResource(R.string.playlist_section_smart))
            Spacer(modifier = Modifier.height(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SmartPlaylistCard(
                        type = SmartPlaylistType.RECENT,
                        countText = stringResource(R.string.music_panel_track_count, smartTrackCount(allTracks, playbackState.recentPlayedIds)),
                        onClick = { onOpenSmart(SmartPlaylistType.RECENT) },
                        modifier = Modifier.weight(1f),
                    )
                    SmartPlaylistCard(
                        type = SmartPlaylistType.FAVORITE,
                        countText = stringResource(R.string.music_panel_track_count, smartTrackCount(allTracks, playbackState.likedIds)),
                        onClick = { onOpenSmart(SmartPlaylistType.FAVORITE) },
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SmartPlaylistCard(
                        type = SmartPlaylistType.ALBUM,
                        countText = stringResource(R.string.playlist_album_count, distinctAlbumCount(allTracks)),
                        onClick = { onOpenSmart(SmartPlaylistType.ALBUM) },
                        modifier = Modifier.weight(1f),
                        coverTrack = allTracks.firstOrNull(),
                    )
                    SmartPlaylistCard(
                        type = SmartPlaylistType.ARTIST,
                        countText = stringResource(R.string.playlist_artist_count, distinctArtistCount(allTracks)),
                        onClick = { onOpenSmart(SmartPlaylistType.ARTIST) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        item {
            Spacer(modifier = Modifier.height(12.dp))
            SectionLabel(text = stringResource(R.string.playlist_section_my))
            Spacer(modifier = Modifier.height(6.dp))
        }
        items(PlaylistStore.playlists, key = { it.id }) { playlist ->
            PlaylistListRow(
                playlist = playlist,
                count = resolveTracks(allTracks, playlist.trackIds).size,
                coverTrack = resolveTracks(allTracks, playlist.trackIds).firstOrNull(),
                onClick = { onOpenCustom(playlist) },
                onRename = { onRename(playlist) },
                onDelete = { onDelete(playlist) },
            )
        }
        item {
            Spacer(modifier = Modifier.height(8.dp))
            CreatePlaylistRow(onClick = onCreatePlaylist)
            Spacer(modifier = Modifier.height(14.dp))
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        color = Color.White,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
    )
}

// 智能歌单卡片：白色描边圆角卡片 + 白色图标与文字，与在线搜索输入框风格一致
@Composable
private fun SmartPlaylistCard(
    type: SmartPlaylistType,
    countText: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    coverTrack: MusicTrack? = null,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.45f),
                shape = RoundedCornerShape(24.dp),
            )
            .clickable(onClick = onClick)
            .padding(14.dp),
    ) {
        if (coverTrack != null) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(RoundedCornerShape(6.dp))
            ) {
                PlaylistArt(track = coverTrack, modifier = Modifier.fillMaxSize())
            }
        } else {
            Icon(
                imageVector = smartTypeIcon(type),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = smartTypeLabel(type),
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = countText,
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 11.sp,
        )
    }
}

// 自定义歌单行：无背景 + 小圆角 + 封面或图标与主次文字，对齐在线搜索结果行
@Composable
private fun PlaylistListRow(
    playlist: Playlist,
    count: Int,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    coverTrack: MusicTrack? = null,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
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
                    imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = playlist.name,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.music_panel_track_count, count),
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 10.sp,
            )
        }
        Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = stringResource(R.string.playlist_more),
                    tint = Color.White,
                )
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.playlist_rename)) },
                    onClick = {
                        menuExpanded = false
                        onRename()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.playlist_delete)) },
                    onClick = {
                        menuExpanded = false
                        onDelete()
                    },
                )
            }
        }
    }
}

// 新建歌单入口：白色描边圆角卡片，与智能歌单卡片一致
@Composable
private fun CreatePlaylistRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.45f),
                shape = RoundedCornerShape(24.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Add,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.playlist_create),
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

// 智能歌单名称文案
@Composable
internal fun smartTypeLabel(type: SmartPlaylistType): String = when (type) {
    SmartPlaylistType.RECENT -> stringResource(R.string.playlist_smart_recent)
    SmartPlaylistType.FAVORITE -> stringResource(R.string.playlist_smart_favorite)
    SmartPlaylistType.ALBUM -> stringResource(R.string.playlist_smart_album)
    SmartPlaylistType.ARTIST -> stringResource(R.string.playlist_smart_artist)
}

private fun smartTypeIcon(type: SmartPlaylistType): ImageVector = when (type) {
    SmartPlaylistType.RECENT -> Icons.Filled.History
    SmartPlaylistType.FAVORITE -> Icons.Filled.Favorite
    SmartPlaylistType.ALBUM -> Icons.Filled.Album
    SmartPlaylistType.ARTIST -> Icons.Filled.Person
}
