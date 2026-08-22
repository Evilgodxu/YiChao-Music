package com.yichao.evilgodxu.screens.home.home_assembly.playlist_area

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.animateScrollBy
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
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

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
                // 专辑/艺术家歌单封面统一采用该歌单内第一首歌曲的封面
                val coverTrack = playbackState.libraryTracks.firstOrNull { it.id == group.trackIds.firstOrNull() }
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
    val listState = rememberLazyListState()
    // 可排序的本地列表：拖拽实时重排，随数据源变化重置
    var orderedTracks by remember(tracks) { mutableStateOf(tracks) }
    // 自实现 reorderable 状态：拖拽项跟随手指，其余项使用 animateItem 平滑让位
    val reorderableState = rememberReorderableLazyListState(listState) { from, to ->
        val list = orderedTracks.toMutableList()
        val moved = list.removeAt(from)
        list.add(to, moved)
        orderedTracks = list
        // 与当前播放来源一致时实时同步播放队列顺序
        if (playbackState.playlistSource?.key == source?.key) {
            playbackState.reorderPlaylist(list)
        }
        onReorder?.invoke(list)
    }

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
                    ReorderableItem(reorderableState, key = track.audioUri) { isDragging ->
                        PlaylistTrackRow(
                            track = track,
                            isActive = isActive,
                            isPlaying = isActive && playbackState.isPlaying,
                            isLiked = track.id in playbackState.likedIds,
                            isDragging = isDragging,
                            onClick = {
                                if (isActive) {
                                    togglePlayPause(playbackState)
                                } else {
                                    playQueue(context, playbackState, scope, orderedTracks, index, source)
                                }
                            },
                            onLongClick = { onTrackLongClick(track) },
                            onFavoriteClick = { playbackState.toggleFavorite(track.id) },
                            dragHandleModifier = Modifier.longPressDraggableHandle(reorderableState, track.audioUri),
                        )
                    }
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
            .then(
                if (isDragging) Modifier.background(Color.White, RoundedCornerShape(8.dp))
                else Modifier
            )
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

// ===== 自实现 Reorderable（算法等价 sh.calvin.reorderable）=====

/**
 * 可排序列表状态：跟踪当前拖拽项，计算其相对原始位置的视觉偏移，并在互斥锁定下调用 onMove 重排。
 */
@Stable
internal class ReorderableLazyListState internal constructor(
    private val listState: LazyListState,
    private val scope: CoroutineScope,
    private val onMove: (from: Int, to: Int) -> Unit,
) {
    private val onMoveMutex = Mutex()
    private var draggingItemKey by mutableStateOf<Any?>(null)
    private var draggingItemDraggedDelta by mutableStateOf(Offset.Zero)
    private var draggingItemInitialOffset by mutableStateOf(IntOffset.Zero)
    private var oldDraggingItemIndex by mutableStateOf<Int?>(null)
    private var predictedDraggingItemOffset by mutableStateOf<IntOffset?>(null)
    // 拖拽到边缘时的常驻自动滚动协程
    private var autoScrollJob: Job? = null

    private val draggingItemInfo: LazyListItemInfo?
        get() = draggingItemKey?.let { key ->
            listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }
        }

    internal val isAnyItemDragging by derivedStateOf { draggingItemKey != null }

    /**
     * 拖拽项相对其原始布局位置的偏移：
     * 手指累计位移 - 重排/滚动引起的布局位移，二者抵消后拖拽项始终跟手。
     */
    internal val draggingItemOffset: Offset
        get() {
            val info = draggingItemInfo ?: return Offset.Zero
            val offsetPx = if (info.index != oldDraggingItemIndex || oldDraggingItemIndex == null) {
                oldDraggingItemIndex = null
                predictedDraggingItemOffset = null
                info.offset
            } else {
                predictedDraggingItemOffset?.y ?: info.offset
            }
            // 手指累计位移 - 布局位移（重排/滚动），抵消后拖拽项始终跟手
            return draggingItemDraggedDelta + Offset(0f, (draggingItemInitialOffset.y - offsetPx).toFloat())
        }

    internal fun onDragStart(key: Any) {
        draggingItemKey = key
        draggingItemInitialOffset = IntOffset(0, draggingItemInfo?.offset ?: 0)
    }

    internal fun onDragStop() {
        draggingItemDraggedDelta = Offset.Zero
        draggingItemKey = null
        oldDraggingItemIndex = null
        predictedDraggingItemOffset = null
        autoScrollJob?.cancel()
        autoScrollJob = null
    }

    internal fun onDrag(dragAmount: Offset) {
        draggingItemDraggedDelta += dragAmount
        val draggingItem = draggingItemInfo ?: return
        // 拖拽项进入可视区边缘带时启动常驻自动滚动协程
        updateAutoScroll()
        if (!onMoveMutex.tryLock()) return
        try {
            val startOffset = draggingItem.offset + draggingItemOffset.y.toInt()
            val endOffset = startOffset + draggingItem.size
            val target = listState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
                item.key != draggingItem.key &&
                    item.offset + item.size / 2 >= startOffset &&
                    item.offset + item.size / 2 < endOffset
            }
            if (target != null && target.index != draggingItem.index) {
                scope.launch { moveItems(draggingItem, target) }
            }
        } finally {
            onMoveMutex.unlock()
        }
    }

    /**
     * 拖拽项进入可视区上下边缘带内时启动常驻滚动协程；即将移出边缘时停止。
     * 循环内每帧重新判定方向，滚动同时向该方向推进拖拽项的位置，避免脱离跟随。
     */
    private fun updateAutoScroll() {
        val shouldScroll = autoScrollDirection() != 0
        val scrolling = autoScrollJob?.isActive == true
        when {
            shouldScroll && !scrolling -> {
                autoScrollJob?.cancel()
                autoScrollJob = scope.launch { autoScrollLoop() }
            }
            !shouldScroll && scrolling -> {
                autoScrollJob?.cancel()
                autoScrollJob = null
            }
        }
    }

    // 拖拽项手柄中心是否进入上/下边缘带，返回滚动方向
    private fun autoScrollDirection(): Int {
        val item = draggingItemInfo ?: return 0
        val viewportHeight = listState.layoutInfo.viewportSize.height
        // 手柄中心 = 初始布局偏移 + 手指累计位移 + 半行高，稳定不随重排/滚动波动
        val handleCenter = draggingItemInitialOffset.y + draggingItemDraggedDelta.y + item.size / 2f
        val threshold = item.size * 1.2f
        return when {
            handleCenter < threshold && listState.canScrollBackward -> -1
            handleCenter > viewportHeight - threshold && listState.canScrollForward -> 1
            else -> 0
        }
    }

    private suspend fun autoScrollLoop() {
        // 由 updateAutoScroll() 在移出边缘时取消；无需额外退出条件
        while (true) {
            val dir = autoScrollDirection()
            if (dir == 0) break
            // 以线性动画滚动半行，并向该方向推进拖拽项的排序位置
            val step = (draggingItemInfo?.size ?: 0).toFloat() / 2f
            if (step <= 0f) break
            listState.animateScrollBy(step * dir)
            moveOnScroll(dir)
            delay(AUTO_SCROLL_FRAME_MS)
        }
        autoScrollJob = null
    }

    // 滚动时把拖拽项向滚动方向相邻交换，模拟库的 moveDraggingItemToEnd
    private suspend fun moveOnScroll(dir: Int) {
        val draggingItem = draggingItemInfo ?: return
        val target = listState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
            if (dir > 0) item.index == draggingItem.index + 1
            else item.index == draggingItem.index - 1
        } ?: return
        moveItems(draggingItem, target)
    }

    private suspend fun moveItems(
        from: LazyListItemInfo,
        to: LazyListItemInfo,
    ) {
        try {
            onMoveMutex.withLock {
                if (draggingItemKey == null) return
                oldDraggingItemIndex = from.index
                onMove(from.index, to.index)
                predictedDraggingItemOffset = if (to.index > from.index) {
                    IntOffset(0, to.offset + to.size - from.size)
                } else {
                    IntOffset(0, to.offset)
                }
                try {
                    // 等待重排后的布局落定，避免视觉偏移与布局错帧导致抖动
                    withTimeout(ReorderableStateLayoutInfoUpdateMaxWaitDuration) {
                        snapshotFlow { listState.layoutInfo }.take(2).collect()
                    }
                } finally {
                    oldDraggingItemIndex = null
                    predictedDraggingItemOffset = null
                }
            }
        } catch (e: CancellationException) {
            // 重排布局更新等待被取消，属于预期
        }
    }

    internal fun isItemDragging(key: Any): State<Boolean> = derivedStateOf { key == draggingItemKey }

    companion object {
        const val ReorderableStateLayoutInfoUpdateMaxWaitDuration = 1000L
        private const val AUTO_SCROLL_FRAME_MS = 16L
    }
}

/**
 * 创建可排序列表状态；onMove 在拖拽项需移动时由内部按交集顺序调用。
 */
@Composable
internal fun rememberReorderableLazyListState(
    listState: LazyListState,
    onMove: (from: Int, to: Int) -> Unit,
): ReorderableLazyListState {
    val scope = rememberCoroutineScope()
    val currentOnMove by rememberUpdatedState(onMove)
    return remember(listState) {
        ReorderableLazyListState(
            listState = listState,
            scope = scope,
            onMove = { from, to -> currentOnMove(from, to) },
        )
    }
}

/**
 * 每个可排序项的包装：拖拽项以 zIndex + graphicsLayer 跟随手指，其余项以 animateItem 平滑让位。
 */
@Composable
internal fun LazyItemScope.ReorderableItem(
    state: ReorderableLazyListState,
    key: Any,
    content: @Composable (isDragging: Boolean) -> Unit,
) {
    val dragging by state.isItemDragging(key)
    val offsetModifier = if (dragging) {
        Modifier
            .zIndex(1f)
            .graphicsLayer {
                translationY = state.draggingItemOffset.y
            }
    } else {
        Modifier.animateItem(
            placementSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMediumLow,
            )
        )
    }
    Box(modifier = offsetModifier) {
        content(dragging)
    }
}

/**
 * 拖拽手柄：消费按下事件以阻止行级长按误触发，长按后再开始拖拽排序。
 */
internal fun Modifier.longPressDraggableHandle(
    state: ReorderableLazyListState,
    key: Any,
): Modifier = pointerInput(state, key) {
    awaitEachGesture {
        // 立即消费按下，行级 combinedClickable 不再收到该指针，避免误弹移除对话框
        val down = awaitFirstDown(requireUnconsumed = false)
        down.consume()
        val longPress = awaitLongPressOrCancellation(down.id)
        if (longPress != null) {
            state.onDragStart(key)
            try {
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    change.consume()
                    val delta = change.position - change.previousPosition
                    if (delta != Offset.Zero) state.onDrag(delta)
                }
            } finally {
                state.onDragStop()
            }
        }
    }
}
