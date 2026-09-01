package com.yichao.evilgodxu.screens.home.home_assembly.player_area

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.yichao.evilgodxu.ui.icons.AppIcons
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.data.settings.LyricLayoutDefaults
import com.yichao.evilgodxu.data.settings.LyricLayoutParams
import com.yichao.evilgodxu.data.settings.homePortraitLyricLayoutFlow
import com.yichao.evilgodxu.musicpanel.CoverContextMenu
import com.yichao.evilgodxu.musicpanel.CoverRefreshDialog
import com.yichao.evilgodxu.musicpanel.CoverReplaceDialog
import com.yichao.evilgodxu.musicpanel.LocalCoverDialog
import com.yichao.evilgodxu.musicpanel.LyricsEditDialog
import com.yichao.evilgodxu.musicpanel.LyricsPanel
import com.yichao.evilgodxu.musicpanel.LyricsRefreshDialog
import com.yichao.evilgodxu.musicpanel.MiniContextMenu
import com.yichao.evilgodxu.musicpanel.MusicErrorBanner
import com.yichao.evilgodxu.musicpanel.MusicMetadataCache
import com.yichao.evilgodxu.musicpanel.MusicMetadataWriter
import com.yichao.evilgodxu.musicpanel.MusicPanelStateHolder
import com.yichao.evilgodxu.musicpanel.MusicPlaybackState
import com.yichao.evilgodxu.musicpanel.NeteaseSongSearchResult
import com.yichao.evilgodxu.musicpanel.ProgressSection
import com.yichao.evilgodxu.musicpanel.RecentCover
import com.yichao.evilgodxu.musicpanel.RenameDialog
import com.yichao.evilgodxu.musicpanel.applyCoverCandidate
import com.yichao.evilgodxu.musicpanel.applyLocalCover
import com.yichao.evilgodxu.musicpanel.applyLocalLyrics
import com.yichao.evilgodxu.musicpanel.applyLyricsCandidate
import com.yichao.evilgodxu.musicpanel.applyLyricsLineEdit
import com.yichao.evilgodxu.musicpanel.copyToClipboard
import com.yichao.evilgodxu.musicpanel.currentTrackNeedsLosslessUpgrade
import com.yichao.evilgodxu.musicpanel.loadRecentCovers
import com.yichao.evilgodxu.musicpanel.menuEdgePositionProvider
import com.yichao.evilgodxu.musicpanel.searchCoverCandidates
import com.yichao.evilgodxu.musicpanel.searchLyricsCandidates
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// 首页播放器主体：沉浸封面 + 歌词 + 标题与艺术家 + 底部控制栏
@Composable
fun PlayerArea(
    modifier: Modifier = Modifier,
    // 标题栏区域高度：封面顶部渐隐区与错误横幅避让基准
    topBarInset: Dp = 0.dp,
    onOpenOnlineSearch: (String) -> Unit = {},
) {
    val playbackState = MusicPanelStateHolder.state
    var playlistVisible by remember { mutableStateOf(false) }

    // 播放列表展开时，系统返回键收起面板
    BackHandler(enabled = playlistVisible) { playlistVisible = false }

    // 播放进度由 MusicPlaybackState 全局 ticker 驱动，此处不再独立轮询

    // 首页竖屏歌词排版：字号与可见行数独立可调
    val context = LocalContext.current
    val homePortraitLayout by context.homePortraitLyricLayoutFlow()
        .collectAsStateWithLifecycle(
            initialValue = LyricLayoutParams(
                LyricLayoutDefaults.HOME_PORTRAIT_FONT_SIZE_SP,
                LyricLayoutDefaults.HOME_PORTRAIT_VISIBLE_LINES,
            ),
        )

    // 歌词区高度随可见行数与字号自适应：每行占行高与上下 2dp 内边距，行间 2dp 固定间距，
    // 末尾补足歌词面板顶部 4dp 内边距，使外层高度与内部歌词窗口一致
    val textMeasurer = rememberTextMeasurer()
    val lyricLineHeight = with(LocalDensity.current) {
        textMeasurer.measure(AnnotatedString("歌词"), TextStyle(fontSize = homePortraitLayout.fontSizeSp.sp)).size.height.toDp()
    }
    val lyricsAreaHeight = (lyricLineHeight + 4.dp) * homePortraitLayout.visibleLines +
        2.dp * (homePortraitLayout.visibleLines - 1) + 4.dp

    // 长按功能状态：复用音乐面板的封面/歌词刷新与标题/艺人重命名能力
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
    // 歌词长按菜单与本地歌词导入状态
    var showLyricsMenu by remember { mutableStateOf(false) }
    var lyricsImportFailed by remember { mutableStateOf(false) }
    // 歌词原文编辑对话框状态：目标行为打开编辑时定位的当前演唱行
    var showLyricsEdit by remember { mutableStateOf(false) }
    var lyricsEditIndex by remember { mutableIntStateOf(0) }
    var lyricsEditInitialText by remember { mutableStateOf("") }
    var lyricsEditFailed by remember { mutableStateOf(false) }
    // 长按歌词时定格的播放位置，编辑落点据此定位，避免菜单操作期间播放推进导致错行
    var lyricsMenuPositionMs by remember { mutableLongStateOf(0L) }
    val lyricsImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            val track = playbackState.currentTrack
            if (track != null) {
                scope.launch {
                    lyricsImportFailed = !applyLocalLyrics(context, playbackState, track, uri)
                }
            }
        }
    }
    var showRename by remember { mutableStateOf(false) }
    var renameIsTitle by remember { mutableStateOf(true) }
    var renameInitValue by remember { mutableStateOf("") }
    var renameTargetId by remember { mutableStateOf<Long?>(null) }
    var showMetaMenu by remember { mutableStateOf(false) }
    var menuText by remember { mutableStateOf("") }
    var menuIsTitle by remember { mutableStateOf(true) }
    // 无损升级确认对话框显隐
    var showLosslessUpgrade by remember { mutableStateOf(false) }
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

    // 外层容器：沉浸封面置顶占满屏幕宽度，其余模块从封面下方按序排列
    BoxWithConstraints(modifier = modifier) {
        // 封面为全宽正方形，占位高度即屏幕宽度
        val coverHeight = maxWidth
        // 沉浸式专辑封面：全宽置顶并嵌入标题栏区域，上下边缘渐隐为透明融入背景
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .aspectRatio(1f)
                .combinedClickable(
                    onClick = {},
                    onLongClick = { if (playbackState.currentTrack != null) showCoverMenu = true },
                ),
        ) {
            HomeImmersiveCover(
                track = playbackState.currentTrack,
                topFraction = (topBarInset + TopFadeExtra) / coverHeight,
                modifier = Modifier.fillMaxSize(),
            )
            // 长按菜单锚定封面，显示在封面底部
            CoverContextMenu(
                visible = showCoverMenu,
                onOnlineCover = {
                    showCoverMenu = false
                    coverTargetId = playbackState.currentTrack?.id
                    showCoverRefresh = true
                    playbackState.currentTrack?.let { track ->
                        scope.launch { searchCoverCandidates(playbackState, track, playbackState.coverRefreshSource) }
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
        // 歌词/标题/进度/控制栏：从封面下方按序排列
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = coverHeight),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // 封面与歌词间距
                Spacer(Modifier.height(8.dp))
                // 歌词：高度随设置的可见行数自适应（默认 5 行），点击歌词区切换微调按钮显隐
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
                                    if (playbackState.currentTrack != null) {
                                        // 长按瞬间定格播放位置，作为歌词编辑的目标行依据
                                        lyricsMenuPositionMs = playbackState.currentPosition
                                        showLyricsMenu = true
                                    }
                                },
                                fontSize = homePortraitLayout.fontSizeSp.sp,
                                visibleLines = homePortraitLayout.visibleLines,
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
                    LyricsContextMenu(
                        visible = showLyricsMenu,
                        editEnabled = playbackState.currentTrack?.lyricLines?.isNotEmpty() == true,
                        onEdit = {
                            showLyricsMenu = false
                            val track = playbackState.currentTrack
                            if (track?.lyricLines?.isNotEmpty() == true) {
                                // 按长按定格位置定位歌词行，与长按瞬间屏幕显示的当前行一致
                                val index = track.lyricLines
                                    .indexOfLast { it.timeMs <= lyricsMenuPositionMs }
                                    .coerceAtLeast(0)
                                lyricsEditIndex = index
                                // 预填该行存储的完整原文：时间戳 + 歌词(含逐字标签) + 翻译行
                                lyricsEditInitialText = MusicMetadataCache.encodeLyrics(listOf(track.lyricLines[index]))
                                showLyricsEdit = true
                            }
                        },
                        onOnlineSearch = {
                            showLyricsMenu = false
                            lyricsTargetId = playbackState.currentTrack?.id
                            showLyricsRefresh = true
                            playbackState.currentTrack?.let { track ->
                                scope.launch { searchLyricsCandidates(playbackState, track, playbackState.lyricsRefreshSource) }
                            }
                        },
                        onLocalImport = {
                            showLyricsMenu = false
                            lyricsImportLauncher.launch("*/*")
                        },
                        onDismiss = { showLyricsMenu = false },
                    )
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
                                imageVector = AppIcons.Remove,
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
                                imageVector = AppIcons.Add,
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
                // 歌词与标题间距
                Spacer(Modifier.height(8.dp))
                // 标题与艺术家：过长时跑马灯滚动并带边缘渐隐，与横屏一致
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    MarqueeInfoLine(
                        text = playbackState.currentTrack?.title.orEmpty(),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        modifier = Modifier
                            .padding(horizontal = 32.dp)
                            .fillMaxWidth()
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
                        MarqueeInfoLine(
                            text = playbackState.currentTrack?.artist.orEmpty(),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Normal,
                            color = Color.White,
                            modifier = Modifier
                                .padding(horizontal = 32.dp)
                                .fillMaxWidth()
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
                        onSearch = {
                            showMetaMenu = false
                            onOpenOnlineSearch(menuText)
                        },
                        onDismiss = { showMetaMenu = false },
                    )
                }
                // 艺术家与音频格式条间距
                Spacer(Modifier.height(8.dp))
                // 音频信息与进度条（与音乐面板一致，宽度收窄 15%）
                Box(
                    modifier = Modifier.fillMaxWidth(0.85f),
                ) {
                    ProgressSection(
                        playbackState = playbackState,
                        contentColor = Color.White,
                        onFormatClick = {
                            if (currentTrackNeedsLosslessUpgrade(playbackState)) {
                                showLosslessUpgrade = true
                            }
                        },
                    )
                }
                // 进度条与控制栏间距
                Spacer(Modifier.height(8.dp))
                PlayerControls(
                    playbackState = playbackState,
                    onPlaylistClick = { playlistVisible = !playlistVisible },
                )
                // 自由窗口底部留白：避免控制按钮贴近窗口边缘
                Spacer(Modifier.height(20.dp))
                Spacer(Modifier.height(16.dp))
            }
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

        LyricsEditDialog(
            visible = showLyricsEdit,
            initialValue = lyricsEditInitialText,
            onConfirm = { newText ->
                showLyricsEdit = false
                val track = playbackState.currentTrack
                if (track != null) {
                    scope.launch {
                        lyricsEditFailed = !applyLyricsLineEdit(context, playbackState, track, lyricsEditIndex, newText)
                    }
                }
            },
            onCancel = { showLyricsEdit = false },
        )

        // 音频信息条点击触发的无损升级确认对话框
        LosslessUpgradeDialog(
            visible = showLosslessUpgrade,
            playbackState = playbackState,
            onDismiss = { showLosslessUpgrade = false },
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
            onSourceSelected = { source ->
                val track = playbackState.currentTrack
                if (track != null && track.id == coverTargetId && source != playbackState.coverRefreshSource) {
                    playbackState.setCoverRefreshSource(source)
                    selectedCoverCandidate = null
                    scope.launch { searchCoverCandidates(playbackState, track, source) }
                }
            },
            onRefresh = {
                val track = playbackState.currentTrack
                if (track != null && track.id == coverTargetId) {
                    selectedCoverCandidate = null
                    scope.launch { searchCoverCandidates(playbackState, track, playbackState.coverRefreshSource) }
                }
            },
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
            onSourceSelected = { source ->
                val track = playbackState.currentTrack
                if (track != null && track.id == lyricsTargetId && source != playbackState.lyricsRefreshSource) {
                    playbackState.setLyricsRefreshSource(source)
                    selectedLyricsCandidate = null
                    scope.launch { searchLyricsCandidates(playbackState, track, source) }
                }
            },
            onRefresh = {
                val track = playbackState.currentTrack
                if (track != null && track.id == lyricsTargetId) {
                    selectedLyricsCandidate = null
                    scope.launch { searchLyricsCandidates(playbackState, track, playbackState.lyricsRefreshSource) }
                }
            },
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
                    .padding(start = 16.dp, top = topBarInset + 10.dp, end = 16.dp),
                onDismiss = { coverSaveFailed = false },
            )
        }
        if (lyricsImportFailed) {
            MusicErrorBanner(
                message = stringResource(R.string.music_panel_local_lyrics_failed),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(start = 16.dp, top = topBarInset + 10.dp, end = 16.dp),
                onDismiss = { lyricsImportFailed = false },
            )
        }
        if (lyricsEditFailed) {
            MusicErrorBanner(
                message = stringResource(R.string.music_panel_edit_lyrics_failed),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(start = 16.dp, top = topBarInset + 10.dp, end = 16.dp),
                onDismiss = { lyricsEditFailed = false },
            )
        }
    }
}

// 歌词长按菜单：提供在线搜索、本地歌词导入与原文编辑（有歌词行时才可编辑）
@Composable
private fun LyricsContextMenu(
    visible: Boolean,
    editEnabled: Boolean,
    onEdit: () -> Unit,
    onOnlineSearch: () -> Unit,
    onLocalImport: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (visible) {
        Popup(
            properties = PopupProperties(
                focusable = true,
                dismissOnBackPress = true,
                dismissOnClickOutside = true,
            ),
            onDismissRequest = onDismiss,
            popupPositionProvider = menuEdgePositionProvider(atTop = false),
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = 4.dp,
                modifier = Modifier.padding(top = 2.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color.Transparent,
                        onClick = onOnlineSearch,
                    ) {
                        Text(
                            text = stringResource(R.string.music_panel_search_title),
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color.Transparent,
                        onClick = onLocalImport,
                    ) {
                        Text(
                            text = stringResource(R.string.music_panel_local_lyrics),
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                        )
                    }
                    if (editEnabled) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color.Transparent,
                            onClick = onEdit,
                        ) {
                            Text(
                                text = stringResource(R.string.music_panel_edit),
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
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

// 封面顶部渐隐区在标题栏高度之外的延伸距离
private val TopFadeExtra = 20.dp
