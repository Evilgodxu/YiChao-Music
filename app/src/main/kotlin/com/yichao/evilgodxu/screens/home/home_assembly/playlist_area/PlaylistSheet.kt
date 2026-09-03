package com.yichao.evilgodxu.screens.home.home_assembly.playlist_area

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yichao.evilgodxu.data.music.metadata.MetadataEnricher
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.data.music.PlaylistRefresher
import com.yichao.evilgodxu.domain.music.MusicPlaybackState
import com.yichao.evilgodxu.domain.music.playTrackAt
import com.yichao.evilgodxu.domain.music.togglePlayPause
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.ui.icons.AppIcons
import com.yichao.evilgodxu.ui.music.HeaderIconButton
import com.yichao.evilgodxu.ui.music.PlaylistRow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// 播放列表面板：点击遮罩或关闭按钮收起
@Composable
internal fun PlaylistSheet(
    visible: Boolean,
    playbackState: MusicPlaybackState,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 歌单副标题点击后的快捷切换弹层
    var showSwitcher by remember { mutableStateOf(false) }
    // 长按删除目标：非空时显示确认弹窗
    var deleteTrack by remember { mutableStateOf<MusicTrack?>(null) }
    // 后台预取整个播放列表缩略图：曲目集合变化即触发，不等面板展开逐行懒加载，
    // 展开时封面已就绪；幂等，已缓存/补全中/全量补全中的曲目自动跳过
    val playlistTrackIds = remember(playbackState.playlist) { playbackState.playlist.map { it.id } }
    LaunchedEffect(playlistTrackIds) {
        val currentId = playbackState.currentTrack?.id
        playbackState.playlist
            .sortedBy { it.id != currentId }
            .forEach { playbackState.requestMetadata(it) }
    }
    Box(Modifier.fillMaxSize()) {
        // 遮罩，点击收起
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
            )
        }
        // 从底部滑入的面板
        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(animationSpec = tween(300)) { it } + fadeIn(),
            exit = slideOutVertically(animationSpec = tween(300)) { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.7f)
                    .background(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                // 展开就绪：等面板滑入动画完成后再定位当前曲目，避免滚动与展开动画叠加卡顿
                var playlistSettled by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    playlistSettled = false
                    delay(PLAYLIST_EXPAND_ANIM_MS)
                    playlistSettled = true
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.music_panel_playlist_title),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.width(8.dp))
                    // 歌单副标题：浅色小字常驻显示，点击快捷切换歌单；默认列表显示默认播放列表
                    Text(
                        text = playbackState.playlistSource?.name
                            ?: stringResource(R.string.playlist_switch_default),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { showSwitcher = true }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.music_panel_track_count, playbackState.playlist.size),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(end = 4.dp),
                        )
                        HeaderIconButton(
                            icon = AppIcons.Refresh,
                            onClick = {
                                if (!playbackState.isScanning) {
                                    scope.launch {
                                        PlaylistRefresher.refresh(
                                            context, playbackState, restoreCurrent = true
                                        ) {
                                            // 刷新后后台加载封面与歌词
                                            scope.launch { MetadataEnricher.enrichAndCleanup(context, playbackState) }
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.size(28.dp),
                            enabled = !playbackState.isScanning,
                        )
                        IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                            Icon(
                                imageVector = AppIcons.Close,
                                contentDescription = stringResource(R.string.home_player_close_playlist),
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                if (playbackState.isScanning) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                } else if (playbackState.playlist.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.home_player_empty),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    val listState = rememberLazyListState()
                    // 滚动到顶部后继续下拉：累计下拉距离超过阈值即收起面板
                    val density = LocalDensity.current
                    val dismissOverscrollPx = with(density) { PLAYLIST_DISMISS_OVERSCROLL_DP.toPx() }
                    val dismissNestedScroll = remember(listState) {
                        object : NestedScrollConnection {
                            private var overscrollAccum = 0f
                            private var dismissed = false
                            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                                if (dismissed || source != NestedScrollSource.UserInput) return Offset.Zero
                                val dy = available.y
                                val atTop = listState.firstVisibleItemIndex == 0 &&
                                    listState.firstVisibleItemScrollOffset == 0
                                if (dy > 0f && atTop) {
                                    overscrollAccum += dy
                                    if (overscrollAccum > dismissOverscrollPx) {
                                        dismissed = true
                                        onDismiss()
                                    }
                                } else {
                                    overscrollAccum = 0f
                                }
                                return Offset.Zero
                            }
                        }
                    }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .nestedScroll(dismissNestedScroll),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        itemsIndexed(
                            items = playbackState.playlist,
                            key = { _, track -> track.audioUri },
                        ) { index, track ->
                            val isActive = index == playbackState.currentIndex
                            PlaylistRow(
                                track = track,
                                isActive = isActive,
                                isPlaying = isActive && playbackState.isPlaying,
                                isQueued = playbackState.isInPlayNext(track.id),
                                onClick = {
                                    if (isActive) {
                                        togglePlayPause(playbackState)
                                    } else {
                                        scope.launch { playTrackAt(context, playbackState, index) }
                                    }
                                    onDismiss()
                                },
                                onLongClick = { deleteTrack = track },
                                onFavoriteClick = { playbackState.toggleFavorite(track.id) },
                                onPlayNextClick = { playbackState.togglePlayNext(track) },
                            )
                        }
                    }
                    // 面板展开动画完成（playlistSettled）后再定位当前曲目；切歌时立即定位
                    LaunchedEffect(playlistSettled, playbackState.currentTrack?.id) {
                        if (playlistSettled && playbackState.currentIndex >= 0 && playbackState.playlist.isNotEmpty()) {
                            listState.animateScrollToItem(
                                playbackState.currentIndex.coerceIn(0, playbackState.playlist.size - 1)
                            )
                        }
                    }
                }
            }
        }
        PlaylistSwitcher(
            visible = showSwitcher,
            playbackState = playbackState,
            onDismiss = { showSwitcher = false },
        )
        RemoveTrackDialog(
            track = deleteTrack,
            titleRes = R.string.music_panel_delete_title,
            messageRes = R.string.music_panel_delete_message,
            confirmRes = R.string.music_panel_delete_confirm,
            onConfirm = { track ->
                scope.launch { playbackState.deleteSongPermanently(context, track) }
                deleteTrack = null
            },
            onDismiss = { deleteTrack = null },
        )
    }
}

// 播放列表展开进入动画时长：等动画完成后才滚动定位当前曲目，避免动画叠加卡顿
private const val PLAYLIST_EXPAND_ANIM_MS = 300L
// 列表顶部继续下拉的收起阈值：累计下拉超过该距离即收起面板
private val PLAYLIST_DISMISS_OVERSCROLL_DP = 64.dp
