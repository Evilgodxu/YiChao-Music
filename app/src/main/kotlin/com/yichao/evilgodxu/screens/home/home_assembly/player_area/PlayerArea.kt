package com.yichao.evilgodxu.screens.home.home_assembly.player_area

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.musicpanel.CoverContextMenu
import com.yichao.evilgodxu.musicpanel.CoverRefreshDialog
import com.yichao.evilgodxu.musicpanel.CoverReplaceDialog
import com.yichao.evilgodxu.musicpanel.DiscArt
import com.yichao.evilgodxu.musicpanel.HeaderIconButton
import com.yichao.evilgodxu.musicpanel.LocalCoverDialog
import com.yichao.evilgodxu.musicpanel.LyricsPanel
import com.yichao.evilgodxu.musicpanel.LyricsRefreshDialog
import com.yichao.evilgodxu.musicpanel.MiniContextMenu
import com.yichao.evilgodxu.musicpanel.MusicErrorBanner
import com.yichao.evilgodxu.musicpanel.MusicMetadataCache
import com.yichao.evilgodxu.musicpanel.MetadataEnricher
import com.yichao.evilgodxu.musicpanel.MusicMetadataWriter
import com.yichao.evilgodxu.musicpanel.MusicPanelStateHolder
import com.yichao.evilgodxu.musicpanel.MusicPlaybackState
import com.yichao.evilgodxu.musicpanel.NeteaseSongSearchResult
import com.yichao.evilgodxu.musicpanel.PlayMode
import com.yichao.evilgodxu.musicpanel.PlaylistRefresher
import com.yichao.evilgodxu.musicpanel.PlaylistRow
import com.yichao.evilgodxu.musicpanel.ProgressSection
import com.yichao.evilgodxu.musicpanel.RecentCover
import com.yichao.evilgodxu.musicpanel.RenameDialog
import com.yichao.evilgodxu.musicpanel.applyCoverCandidate
import com.yichao.evilgodxu.musicpanel.applyLyricsCandidate
import com.yichao.evilgodxu.musicpanel.applyLocalCover
import com.yichao.evilgodxu.musicpanel.applyPlaybackMode
import com.yichao.evilgodxu.musicpanel.copyToClipboard
import com.yichao.evilgodxu.musicpanel.loadRecentCovers
import com.yichao.evilgodxu.musicpanel.playTrackAt
import com.yichao.evilgodxu.musicpanel.searchCoverCandidates
import com.yichao.evilgodxu.musicpanel.searchLyricsCandidates
import com.yichao.evilgodxu.musicpanel.togglePlayPause
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// 首页播放器主体：旋转封面 + 歌词 + 标题与艺术家 + 底部控制栏
@Composable
fun PlayerArea(
    modifier: Modifier = Modifier,
) {
    val playbackState = MusicPanelStateHolder.state
    var playlistVisible by remember { mutableStateOf(false) }

    // 播放列表展开时，系统返回键收起面板
    BackHandler(enabled = playlistVisible) { playlistVisible = false }

    // 播放期间周期性同步播放位置，驱动进度条与时间文本
    LaunchedEffect(playbackState.isPlaying, playbackState.currentTrack) {
        while (isActive && playbackState.isPlaying) {
            playbackState.updatePosition()
            delay(200)
        }
    }

    // 歌词区固定高度：显示 DEFAULT_VISIBLE_LINES 行，但预留 9 行高度供歌词换行时展开，避免随内容变化而跳动
    val textMeasurer = rememberTextMeasurer()
    val lyricLineHeight = with(LocalDensity.current) {
        textMeasurer.measure(AnnotatedString("歌词"), TextStyle(fontSize = 16.sp)).size.height.toDp()
    }
    val lyricsAreaHeight = (lyricLineHeight + 4.dp) * 9 + 2.dp * 4 + 4.dp

    // 长按功能状态：复用音乐面板的封面/歌词刷新与标题/艺人重命名能力
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lyricsRefreshFailedMessage = stringResource(R.string.music_panel_lyrics_refresh_failed)
    var showCoverMenu by remember { mutableStateOf(false) }
    var showCoverRefresh by remember { mutableStateOf(false) }
    var showLocalCover by remember { mutableStateOf(false) }
    var showCoverReplace by remember { mutableStateOf(false) }
    var selectedCoverCandidate by remember { mutableStateOf<NeteaseSongSearchResult?>(null) }
    var selectedLocalCover by remember { mutableStateOf<RecentCover?>(null) }
    var coverSaving by remember { mutableStateOf(false) }
    var coverSaveFailed by remember { mutableStateOf(false) }
    var coverTargetId by remember { mutableStateOf<Long?>(null) }
    var showLyricsRefresh by remember { mutableStateOf(false) }
    var selectedLyricsCandidate by remember { mutableStateOf<NeteaseSongSearchResult?>(null) }
    var lyricsTargetId by remember { mutableStateOf<Long?>(null) }
    var showRename by remember { mutableStateOf(false) }
    var renameIsTitle by remember { mutableStateOf(true) }
    var renameInitValue by remember { mutableStateOf("") }
    var renameTargetId by remember { mutableStateOf<Long?>(null) }
    var showMetaMenu by remember { mutableStateOf(false) }
    var menuText by remember { mutableStateOf("") }
    var menuIsTitle by remember { mutableStateOf(true) }
    // 歌词微调按钮显示状态：点击歌词区切换
    var lyricTuneVisible by remember { mutableStateOf(false) }
    // 微调操作计数：每次调整自增以重置自动隐藏计时
    var tuneVersion by remember { mutableStateOf(0) }
    // 浮动提示：显示当前微调的毫秒数
    var tuneHintText by remember { mutableStateOf("") }
    var tuneHintVersion by remember { mutableStateOf(0) }
    // 显示后 2 秒无操作自动隐藏
    LaunchedEffect(lyricTuneVisible, tuneVersion) {
        if (lyricTuneVisible) {
            delay(2000)
            lyricTuneVisible = false
        }
    }
    // 浮动提示暂显后自动消失
    LaunchedEffect(tuneHintVersion) {
        if (tuneHintText.isNotEmpty()) {
            delay(800)
            tuneHintText = ""
        }
    }

    Box(modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Spacer(Modifier.height(16.dp))
            // 旋转专辑封面：长按复用音乐面板封面菜单
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = {},
                        onLongClick = { if (playbackState.currentTrack != null) showCoverMenu = true },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                DiscArt(
                    track = playbackState.currentTrack,
                    isPlaying = playbackState.isPlaying,
                    modifier = Modifier
                        .fillMaxWidth(0.58f)
                        .aspectRatio(1f),
                )
                CoverContextMenu(
                    visible = showCoverMenu,
                    onOnlineCover = {
                        showCoverMenu = false
                        coverTargetId = playbackState.currentTrack?.id
                        showCoverRefresh = true
                        playbackState.currentTrack?.let { track ->
                            scope.launch { searchCoverCandidates(playbackState, track) }
                        }
                    },
                    onLocalCover = {
                        showCoverMenu = false
                        coverTargetId = playbackState.currentTrack?.id
                        selectedLocalCover = null
                        showLocalCover = true
                        scope.launch { playbackState.setLocalCoverCandidates(loadRecentCovers(context)) }
                    },
                    onDismiss = { showCoverMenu = false },
                )
            }
            Spacer(Modifier.height(24.dp))
            // 歌词：固定为 5 行歌词高度，点击歌词区切换微调按钮显隐
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(lyricsAreaHeight),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (playbackState.currentTrack != null) {
                        LyricsPanel(
                            playbackState = playbackState,
                            onClick = { lyricTuneVisible = !lyricTuneVisible },
                            onLongClick = {
                                lyricsTargetId = playbackState.currentTrack?.id
                                showLyricsRefresh = true
                                playbackState.currentTrack?.let { track ->
                                    scope.launch { searchLyricsCandidates(playbackState, track) }
                                }
                            },
                            fontSize = 16.sp,
                            contentColor = Color.White,
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.home_player_empty),
                            fontSize = 14.sp,
                            color = Color.White,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 32.dp),
                        )
                    }
                }
                // 歌词微调：左-延后歌词，右+提前歌词，每次微调一个步长
                if (lyricTuneVisible && playbackState.currentTrack?.lyricLines?.isNotEmpty() == true) {
                    IconButton(
                        onClick = {
                            playbackState.adjustLyricsOffset(LyricFineTuneStepMs)
                            tuneVersion++
                            tuneHintText = "+${LyricFineTuneStepMs}ms"
                            tuneHintVersion++
                        },
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .padding(start = 16.dp)
                            .size(34.dp)
                            .background(Color.White.copy(alpha = 0.18f), CircleShape),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Remove,
                            contentDescription = stringResource(R.string.home_player_lyric_delay),
                            tint = Color.White,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    IconButton(
                        onClick = {
                            playbackState.adjustLyricsOffset(-LyricFineTuneStepMs)
                            tuneVersion++
                            tuneHintText = "-${LyricFineTuneStepMs}ms"
                            tuneHintVersion++
                        },
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 16.dp)
                            .size(34.dp)
                            .background(Color.White.copy(alpha = 0.18f), CircleShape),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = stringResource(R.string.home_player_lyric_advance),
                            tint = Color.White,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                // 浮动提示：居中显示微调的毫秒数
                if (tuneHintText.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = tuneHintText,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            // 标题与艺术家
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = playbackState.currentTrack?.title.orEmpty(),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(horizontal = 32.dp)
                        .combinedClickable(
                            onClick = {},
                            onLongClick = {
                                val track = playbackState.currentTrack
                                if (track != null) {
                                    menuText = track.title
                                    menuIsTitle = true
                                    showMetaMenu = true
                                }
                            },
                        ),
                )
                if (playbackState.currentTrack != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = playbackState.currentTrack?.artist.orEmpty(),
                        fontSize = 14.sp,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .padding(horizontal = 32.dp)
                            .combinedClickable(
                                onClick = {},
                                onLongClick = {
                                    val artist = playbackState.currentTrack?.artist
                                    if (!artist.isNullOrBlank()) {
                                        menuText = artist
                                        menuIsTitle = false
                                        showMetaMenu = true
                                    }
                                },
                            ),
                    )
                }
                MiniContextMenu(
                    visible = showMetaMenu,
                    onCopy = {
                        showMetaMenu = false
                        copyToClipboard(context, menuText)
                    },
                    onRename = {
                        showMetaMenu = false
                        renameIsTitle = menuIsTitle
                        renameInitValue = menuText
                        renameTargetId = playbackState.currentTrack?.id
                        showRename = true
                    },
                    onDismiss = { showMetaMenu = false },
                )
            }
            Spacer(Modifier.height(24.dp))
            // 律动与进度条（与音乐面板一致，宽度收窄 15%）
            Box(
                modifier = Modifier.fillMaxWidth(0.85f),
            ) {
                ProgressSection(
                    playbackState = playbackState,
                    contentColor = Color.White,
                )
            }
            Spacer(Modifier.height(24.dp))
            PlayerControls(
                playbackState = playbackState,
                onPlaylistClick = { playlistVisible = !playlistVisible },
            )
            Spacer(Modifier.height(16.dp))
        }

        PlaylistSheet(
            visible = playlistVisible,
            playbackState = playbackState,
            onDismiss = { playlistVisible = false },
        )

        RenameDialog(
            visible = showRename,
            isTitle = renameIsTitle,
            initialValue = renameInitValue,
            onConfirm = { newValue ->
                showRename = false
                val track = playbackState.currentTrack
                if (track != null && track.id == renameTargetId) {
                    val updated = if (renameIsTitle) track.copy(title = newValue)
                    else track.copy(artist = newValue)
                    playbackState.renameTrackMetadata(updated)
                    // 手动重命名标题/艺术家后写入音频文件元数据
                    scope.launch {
                        MusicMetadataWriter.writeTitleArtist(context, track, updated.title, updated.artist)
                    }
                }
            },
            onCancel = { showRename = false },
        )

        LocalCoverDialog(
            visible = showLocalCover,
            playbackState = playbackState,
            selected = selectedLocalCover,
            saving = coverSaving,
            onSelected = { selectedLocalCover = it },
            onConfirm = {
                val cover = selectedLocalCover
                val track = playbackState.currentTrack
                if (cover != null && track != null && track.id == coverTargetId) {
                    coverSaving = true
                    coverSaveFailed = false
                    scope.launch {
                        if (applyLocalCover(context, playbackState, track, cover)) {
                            showLocalCover = false
                            selectedLocalCover = null
                        } else {
                            coverSaveFailed = true
                        }
                        coverSaving = false
                    }
                }
            },
            onCancel = {
                showLocalCover = false
                selectedLocalCover = null
                playbackState.setLocalCoverCandidates(emptyList())
            },
        )

        CoverRefreshDialog(
            visible = showCoverRefresh && !showCoverReplace && !showLocalCover,
            track = playbackState.currentTrack,
            playbackState = playbackState,
            context = context,
            selectedId = selectedCoverCandidate?.id,
            saving = coverSaving,
            onCandidateSelected = { selectedCoverCandidate = it },
            onConfirm = {
                val candidate = selectedCoverCandidate
                val track = playbackState.currentTrack
                if (candidate != null && track != null && track.id == coverTargetId) {
                    val hasCover = MusicMetadataCache.isValid(track.coverCachePath) || track.neteaseCoverUrl.isNotBlank()
                    if (hasCover) {
                        showCoverReplace = true
                    } else {
                        coverSaving = true
                        coverSaveFailed = false
                        scope.launch {
                            coverSaveFailed = !applyCoverCandidate(context, playbackState, track, candidate)
                            if (!coverSaveFailed) {
                                showCoverRefresh = false
                                selectedCoverCandidate = null
                            }
                            coverSaving = false
                        }
                    }
                }
            },
            onCancel = {
                showCoverRefresh = false
                selectedCoverCandidate = null
                playbackState.setCoverCandidates(emptyList())
            },
        )

        CoverReplaceDialog(
            visible = showCoverReplace,
            track = playbackState.currentTrack,
            candidate = selectedCoverCandidate,
            saving = coverSaving,
            onConfirm = {
                val candidate = selectedCoverCandidate ?: return@CoverReplaceDialog
                val track = playbackState.currentTrack ?: return@CoverReplaceDialog
                if (track.id != coverTargetId) return@CoverReplaceDialog
                coverSaving = true
                coverSaveFailed = false
                scope.launch {
                    coverSaveFailed = !applyCoverCandidate(context, playbackState, track, candidate)
                    if (!coverSaveFailed) {
                        showCoverReplace = false
                        showCoverRefresh = false
                        selectedCoverCandidate = null
                    }
                    coverSaving = false
                }
            },
            onCancel = { showCoverReplace = false },
        )

        LyricsRefreshDialog(
            visible = showLyricsRefresh,
            track = playbackState.currentTrack,
            playbackState = playbackState,
            selectedId = selectedLyricsCandidate?.id,
            context = context,
            onCandidateSelected = { selectedLyricsCandidate = it },
            onConfirm = {
                val candidate = selectedLyricsCandidate
                val track = playbackState.currentTrack
                if (candidate != null && track != null && track.id == lyricsTargetId) scope.launch {
                    val success = applyLyricsCandidate(context, playbackState, track, candidate)
                    if (success) {
                        showLyricsRefresh = false
                        selectedLyricsCandidate = null
                        playbackState.setLyricsCandidates(emptyList())
                    } else {
                        playbackState.setLyricsRefreshError(lyricsRefreshFailedMessage)
                    }
                }
            },
            onCancel = {
                showLyricsRefresh = false
                selectedLyricsCandidate = null
                playbackState.setLyricsCandidates(emptyList())
                playbackState.setLyricsRefreshError(null)
            },
        )

        if (coverSaveFailed) {
            MusicErrorBanner(
                message = stringResource(R.string.music_panel_cover_save_failed),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                onDismiss = { coverSaveFailed = false },
            )
        }
    }
}

// 底部控制栏：与迷你播放器控件布局一致（播放模式 → 上一曲 → 播放/暂停 → 下一曲 → 播放列表）
@Composable
internal fun PlayerControls(
    playbackState: MusicPlaybackState,
    onPlaylistClick: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayerControlButton(
            icon = when (playbackState.playMode) {
                PlayMode.RepeatAll -> Icons.Default.Repeat
                PlayMode.RepeatOne -> Icons.Default.RepeatOne
                PlayMode.Shuffle -> Icons.Default.Shuffle
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
            icon = Icons.Default.SkipPrevious,
            contentDescription = stringResource(R.string.home_player_previous),
            enabled = playbackState.playlist.isNotEmpty(),
            onClick = {
                val prev = playbackState.previousIndex()
                if (prev >= 0) scope.launch { playTrackAt(context, playbackState, prev) }
            },
        )
        PlayerControlButton(
            icon = if (playbackState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
            contentDescription = stringResource(
                if (playbackState.isPlaying) R.string.home_player_pause else R.string.home_player_play
            ),
            enabled = playbackState.playlist.isNotEmpty(),
            onClick = { togglePlayPause(playbackState) },
        )
        PlayerControlButton(
            icon = Icons.Default.SkipNext,
            contentDescription = stringResource(R.string.home_player_next),
            enabled = playbackState.playlist.isNotEmpty(),
            onClick = {
                val next = playbackState.nextIndex()
                if (next >= 0) scope.launch { playTrackAt(context, playbackState, next) }
            },
        )
        PlayerControlButton(
            icon = Icons.AutoMirrored.Outlined.QueueMusic,
            contentDescription = stringResource(R.string.music_panel_playlist),
            onClick = onPlaylistClick,
        )
    }
}

@Composable
private fun PlayerControlButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(44.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(24.dp),
            tint = if (enabled) Color.White
            else Color.White.copy(alpha = 0.3f),
        )
    }
}

// 播放列表面板：点击遮罩或关闭按钮收起
@Composable
internal fun PlaylistSheet(
    visible: Boolean,
    playbackState: MusicPlaybackState,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.music_panel_playlist_title),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.music_panel_track_count, playbackState.playlist.size),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(end = 4.dp),
                        )
                        HeaderIconButton(
                            icon = Icons.Default.Refresh,
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
                                imageVector = Icons.Default.Close,
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
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
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
                                onLongClick = {},
                                onFavoriteClick = { playbackState.toggleFavorite(track.id) },
                                onPlayNextClick = { playbackState.togglePlayNext(track) },
                            )
                        }
                    }
                    LaunchedEffect(playbackState.currentIndex) {
                        if (playbackState.currentIndex >= 0 && playbackState.playlist.isNotEmpty()) {
                            listState.animateScrollToItem(
                                playbackState.currentIndex.coerceIn(0, playbackState.playlist.size - 1)
                            )
                        }
                    }
                }
            }
        }
    }
}

// 歌词微调单次步长（毫秒）
private const val LyricFineTuneStepMs = 100L
