package com.yichao.evilgodxu.screens.home.home_assembly.playlist_area

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.musicpanel.MetadataDialogCard
import com.yichao.evilgodxu.musicpanel.MusicMetadataWriter
import com.yichao.evilgodxu.musicpanel.MusicPlaybackState
import com.yichao.evilgodxu.musicpanel.MusicTrack
import com.yichao.evilgodxu.musicpanel.PlaylistArt
import com.yichao.evilgodxu.musicpanel.PlaylistSource
import com.yichao.evilgodxu.musicpanel.playTrackAt
import com.yichao.evilgodxu.musicpanel.togglePlayPause
import com.yichao.evilgodxu.screens.home.data.Playlist
import com.yichao.evilgodxu.screens.home.data.PlaylistGroup
import com.yichao.evilgodxu.screens.home.data.PlaylistStore
import com.yichao.evilgodxu.screens.home.data.SmartPlaylistType
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// 专辑/艺术家分组列表页
@Composable
internal fun PlaylistGroupsPage(
    type: SmartPlaylistType,
    playbackState: MusicPlaybackState,
    onOpenGroup: (PlaylistGroup) -> Unit,
) {
    val context = LocalContext.current
    val groups = when (type) {
        SmartPlaylistType.ALBUM -> albumGroups(
            playbackState.libraryTracks,
            context.getString(R.string.playlist_unknown_album),
        )
        SmartPlaylistType.ARTIST -> artistGroups(
            playbackState.libraryTracks,
            context.getString(R.string.music_scanner_unknown_artist),
        )
        else -> emptyList()
    }
    val icon: ImageVector = if (type == SmartPlaylistType.ALBUM) Icons.Filled.Album else Icons.Filled.Person
    if (groups.isEmpty()) {
        EmptyHint(text = stringResource(R.string.playlist_empty))
    } else {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(groups, key = { it.key }) { group ->
                // 专辑歌单封面统一采用该歌单内第一首歌曲的封面
                val coverTrack = if (type == SmartPlaylistType.ALBUM) {
                    playbackState.libraryTracks.firstOrNull { it.id == group.trackIds.firstOrNull() }
                } else null
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onOpenGroup(group) }
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
                            text = group.name,
                            color = Color.White,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = stringResource(R.string.music_panel_track_count, group.trackIds.size),
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 10.sp,
                        )
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

// 自定义歌单曲目页：支持添加歌曲与移除
@Composable
internal fun PlaylistTracksPage(
    playlist: Playlist,
    playbackState: MusicPlaybackState,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 实时读取最新歌单，添加/移除歌曲后立即刷新列表
    val currentPlaylist = PlaylistStore.playlists.find { it.id == playlist.id } ?: playlist
    val tracks = remember(playbackState.libraryTracks, currentPlaylist.trackIds) {
        resolveTracks(playbackState.libraryTracks, currentPlaylist.trackIds)
    }
    var showPicker by remember { mutableStateOf(false) }
    var removeTrack by remember { mutableStateOf<MusicTrack?>(null) }
    TracksContent(
        tracks = tracks,
        source = PlaylistSource("custom:${playlist.id}", currentPlaylist.name),
        playbackState = playbackState,
        scope = scope,
        trailingAction = {
            TextButton(onClick = { showPicker = true }) {
                Text(
                    text = stringResource(R.string.playlist_add_songs),
                    color = Color.White,
                    fontWeight = FontWeight.Medium,
                )
            }
        },
        onTrackLongClick = { removeTrack = it },
        onReorder = { ordered ->
            PlaylistStore.setTrackOrder(context, playlist.id, ordered.map { it.id })
        },
    )
    AddSongsPicker(
        visible = showPicker,
        playlistName = currentPlaylist.name,
        candidateTracks = playbackState.libraryTracks,
        existingIds = currentPlaylist.trackIds.toSet(),
        onConfirm = { ids ->
            PlaylistStore.addTracks(context, playlist.id, ids)
            showPicker = false
        },
        onDismiss = { showPicker = false },
    )
    RemoveTrackDialog(
        track = removeTrack,
        onConfirm = { track ->
            PlaylistStore.removeTracks(context, playlist.id, listOf(track.id))
            removeTrack = null
        },
        onDismiss = { removeTrack = null },
    )
}

// 系统歌单曲目页（常听/收藏）
@Composable
internal fun PlaylistSmartTracksPage(
    type: SmartPlaylistType,
    playbackState: MusicPlaybackState,
) {
    val scope = rememberCoroutineScope()
    val library = playbackState.libraryTracks
    val tracks = remember(library, playbackState.recentPlayedIds, playbackState.likedIds) {
        when (type) {
            SmartPlaylistType.RECENT -> recentTracks(library, playbackState.recentPlayedIds)
            SmartPlaylistType.FAVORITE -> library.filter { it.id in playbackState.likedIds }
            else -> emptyList()
        }
    }
    TracksContent(
        tracks = tracks,
        source = PlaylistSource("smart:${type.name}", smartTypeLabel(type)),
        playbackState = playbackState,
        scope = scope,
        trailingAction = {},
        onTrackLongClick = {},
    )
}

// 智能分组曲目页（专辑/艺术家下的曲目列表）
@Composable
internal fun PlaylistGroupTracksPage(
    type: SmartPlaylistType,
    group: PlaylistGroup,
    playbackState: MusicPlaybackState,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val tracks = remember(playbackState.libraryTracks, group.key) {
        if (type == SmartPlaylistType.ALBUM) {
            val albumId = group.key.removePrefix("album:").toLongOrNull()
            playbackState.libraryTracks.filter { it.albumId == albumId }
        } else {
            playbackState.libraryTracks.filter { it.artist == group.name }
        }
    }
    // 专辑视图长按歌曲：编辑该歌曲的专辑元数据
    var editAlbumTrack by remember { mutableStateOf<MusicTrack?>(null) }
    TracksContent(
        tracks = tracks,
        source = PlaylistSource(group.key, group.name),
        playbackState = playbackState,
        scope = scope,
        trailingAction = {},
        onTrackLongClick = { track ->
            if (type == SmartPlaylistType.ALBUM) editAlbumTrack = track
        },
    )
    EditAlbumDialog(
        track = editAlbumTrack,
        onConfirm = { track, albumName ->
            playbackState.updateTrack(track.copy(albumName = albumName))
            editAlbumTrack = null
            // 将专辑名写回音频文件标签
            scope.launch {
                MusicMetadataWriter.writeAlbum(context, track, albumName)
            }
        },
        onDismiss = { editAlbumTrack = null },
    )
}

// 曲目页共享主体：播放全部 + 操作栏 + 曲目列表（支持长按手柄拖拽排序）
@Composable
private fun TracksContent(
    tracks: List<MusicTrack>,
    source: PlaylistSource?,
    playbackState: MusicPlaybackState,
    scope: CoroutineScope,
    trailingAction: @Composable () -> Unit,
    onTrackLongClick: (MusicTrack) -> Unit,
    onReorder: ((List<MusicTrack>) -> Unit)? = null,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val listState = rememberLazyListState()
    // 可排序的本地列表：拖拽实时重排，随数据源变化重置
    var orderedTracks by remember(tracks) { mutableStateOf(tracks) }
    var draggingIndex by remember { mutableStateOf<Int?>(null) }
    // 拖拽项相对其槽位的像素位移，用于 graphicsLayer 跟随手指
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val rowHeightPx = with(density) { 42.dp.toPx() }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = { playQueue(context, playbackState, scope, orderedTracks, 0, source) },
                enabled = orderedTracks.isNotEmpty(),
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.playlist_play_all),
                    color = Color.White,
                    fontWeight = FontWeight.Medium,
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            trailingAction()
            Text(
                text = stringResource(R.string.music_panel_track_count, orderedTracks.size),
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 10.sp,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        if (orderedTracks.isEmpty()) {
            EmptyHint(text = stringResource(R.string.playlist_empty))
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                itemsIndexed(orderedTracks, key = { _, track -> track.audioUri }) { index, track ->
                    val isActive = track.id == playbackState.currentTrack?.id
                    val isDragging = draggingIndex == index
                    // 拖拽项背景变白并随手指平移，其余项以 animateItem 平滑换位
                    val itemModifier = if (isDragging) {
                        Modifier
                            .zIndex(1f)
                            .background(Color.White, RoundedCornerShape(8.dp))
                            .graphicsLayer { translationY = dragOffset }
                    } else {
                        Modifier.animateItem()
                    }
                    val dragModifier = Modifier.pointerInput(track.audioUri, orderedTracks.size) {
                        awaitEachGesture {
                            // 手柄按下即消费事件，避免与行级长按冲突；长按后进入垂直拖拽排序
                            val down = awaitFirstDown(requireUnconsumed = false)
                            down.consume()
                            val startIndex = orderedTracks.indexOfFirst { it.audioUri == track.audioUri }
                            if (startIndex < 0) return@awaitEachGesture
                            val longPress = awaitLongPressOrCancellation(down.id)
                            if (longPress != null) {
                                draggingIndex = startIndex
                                // 以手柄内坐标跟踪手指：重排/滚动引起的布局位移会在 position.y 中抵消，保证拖拽项始终跟手
                                val grabOffset = longPress.position.y
                                dragOffset = 0f
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) break
                                    change.consume()
                                    dragOffset = change.position.y - grabOffset
                                    val current = draggingIndex ?: break
                                    val target = (current + (dragOffset / rowHeightPx).roundToInt())
                                        .coerceIn(0, orderedTracks.lastIndex)
                                    if (target != current) {
                                        val list = orderedTracks.toMutableList()
                                        val moved = list.removeAt(current)
                                        list.add(target, moved)
                                        orderedTracks = list
                                        draggingIndex = target
                                        // 与当前播放来源一致时实时同步播放队列顺序
                                        if (playbackState.playlistSource?.key == source?.key) {
                                            playbackState.reorderPlaylist(list)
                                        }
                                        onReorder?.invoke(list)
                                    }
                                    // 拖拽越过可视区上下边缘时逐行自动滚动
                                    val visible = listState.layoutInfo.visibleItemsInfo
                                    val firstVisible = visible.firstOrNull()?.index ?: 0
                                    val lastVisible = visible.lastOrNull()?.index ?: 0
                                    val cur = draggingIndex ?: break
                                    when {
                                        cur <= firstVisible && firstVisible > 0 ->
                                            if (!listState.isScrollInProgress) scope.launch {
                                                listState.animateScrollToItem((firstVisible - 1).coerceAtLeast(0))
                                            }
                                        cur >= lastVisible && lastVisible < orderedTracks.lastIndex ->
                                            if (!listState.isScrollInProgress) scope.launch {
                                                listState.animateScrollToItem(lastVisible + 1)
                                            }
                                    }
                                }
                            }
                            draggingIndex = null
                            dragOffset = 0f
                        }
                    }
                    PlaylistTrackRow(
                        track = track,
                        isActive = isActive,
                        isPlaying = isActive && playbackState.isPlaying,
                        isLiked = track.id in playbackState.likedIds,
                        isDragging = isDragging,
                        modifier = itemModifier,
                        onClick = {
                            if (isActive) {
                                togglePlayPause(playbackState)
                            } else {
                                playQueue(context, playbackState, scope, orderedTracks, index, source)
                            }
                        },
                        onLongClick = { onTrackLongClick(track) },
                        onFavoriteClick = { playbackState.toggleFavorite(track.id) },
                        dragHandleModifier = dragModifier,
                    )
                }
            }
        }
    }
}

// 歌单曲目行：对齐在线搜索结果行样式，封面 + 主次文字 + 排序手柄 + 收藏
@Composable
private fun PlaylistTrackRow(
    track: MusicTrack,
    isActive: Boolean,
    isPlaying: Boolean,
    isLiked: Boolean,
    isDragging: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onFavoriteClick: () -> Unit,
    dragHandleModifier: Modifier = Modifier,
) {
    // 拖拽时背景变白，前景文字同步切换为深色保证可读性
    val fg = if (isDragging) Color(0xFF1A1A1A) else Color.White
    val fgDim = if (isDragging) Color(0xFF1A1A1A).copy(alpha = 0.6f) else Color.White.copy(alpha = 0.6f)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            PlaylistArt(track = track, modifier = Modifier.fillMaxSize())
            if (isActive) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                color = if (isDragging) fg else if (isActive) MaterialTheme.colorScheme.primary else Color.White,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = track.artist,
                color = fgDim,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // 排序调整手柄：长按后垂直拖拽实时变更歌单内播放次序，位于收藏按钮左侧
        Box(
            modifier = Modifier
                .size(30.dp)
                .then(dragHandleModifier),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.DragHandle,
                contentDescription = stringResource(R.string.playlist_sort_handle),
                tint = fgDim,
                modifier = Modifier.size(20.dp),
            )
        }
        IconButton(onClick = onFavoriteClick, modifier = Modifier.size(30.dp)) {
            Icon(
                imageVector = if (isLiked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                contentDescription = stringResource(R.string.music_panel_favorite),
                tint = if (isDragging) fg else if (isLiked) MaterialTheme.colorScheme.primary else Color.White,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

// 空态提示
@Composable
private fun EmptyHint(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 12.sp,
        )
    }
}

// 将歌单曲目设为当前播放队列并切入指定曲目，同时记录来源歌单
private fun playQueue(
    context: android.content.Context,
    state: MusicPlaybackState,
    scope: CoroutineScope,
    tracks: List<MusicTrack>,
    startIndex: Int,
    source: PlaylistSource?,
) {
    if (tracks.isEmpty()) return
    // 首次从默认库切到歌单时备份默认列表，供快捷切回
    if (state.playlistSource == null && state.defaultPlaylistBackup == null) {
        state.defaultPlaylistBackup = state.playlist
    }
    state.playlist = tracks
    state.playlistSource = source
    val index = startIndex.coerceIn(0, tracks.size - 1)
    state.currentIndex = index
    scope.launch { playTrackAt(context, state, index) }
}

// 从歌单移除曲目的确认弹窗
@Composable
internal fun RemoveTrackDialog(
    track: MusicTrack?,
    onConfirm: (MusicTrack) -> Unit,
    onDismiss: () -> Unit,
) {
    if (track == null) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.playlist_remove_track_title)) },
        confirmButton = {
            TextButton(onClick = { onConfirm(track) }) {
                Text(stringResource(R.string.playlist_remove_track_confirm), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.music_panel_rename_cancel))
            }
        },
    )
}

// 专辑视图长按歌曲：编辑该歌曲的专辑元数据
@Composable
internal fun EditAlbumDialog(
    track: MusicTrack?,
    onConfirm: (MusicTrack, String) -> Unit,
    onDismiss: () -> Unit,
) {
    if (track == null) return
    MetadataDialogCard(onDismiss = onDismiss) {
        var value by remember(track.id) { mutableStateOf(track.albumName) }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.playlist_edit_album_title),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.widthIn(max = 200.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                    onClick = onDismiss,
                ) {
                    Text(
                        text = stringResource(R.string.music_panel_rename_cancel),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 10.dp),
                    )
                }
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primary,
                    onClick = {
                        val trimmed = value.trim()
                        if (trimmed.isNotEmpty()) onConfirm(track, trimmed)
                    },
                ) {
                    Text(
                        text = stringResource(R.string.music_panel_rename_confirm),
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 10.dp),
                    )
                }
            }
        }
    }
}
