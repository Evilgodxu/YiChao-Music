package com.yichao.evilgodxu.screens.home.component

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yichao.evilgodxu.data.permission.PermissionType
import com.yichao.evilgodxu.dialog.TimerDialog
import com.yichao.evilgodxu.domain.music.MusicPanelStateHolder
import com.yichao.evilgodxu.domain.music.performSearch
import com.yichao.evilgodxu.floatingwindow.mini_player.swipeToChangeTrackFlow
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.screens.home.component.online_search.OnlineSearchPanel
import com.yichao.evilgodxu.screens.home.component.permission_area.PermissionDialog
import com.yichao.evilgodxu.screens.home.expanded.player_area.LandscapePlayerArea
import com.yichao.evilgodxu.screens.home.compact.player_area.PlayerArea
import com.yichao.evilgodxu.screens.home.component.playlist.LibraryAnalysisController
import com.yichao.evilgodxu.screens.home.component.playlist.PlaylistPanel
import com.yichao.evilgodxu.screens.home.component.title_area.HomeTopBar
import com.yichao.evilgodxu.screens.home.HomeUiState
import com.yichao.evilgodxu.theme.md_theme_dark_surface
import com.yichao.evilgodxu.ui.music.SongGradientBackground
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// 首页组装器：顶部标题栏（定时/收藏/横屏/设置）+ 播放器主体 + 权限与定时对话框
@Composable
fun HomeAssembly(
    modifier: Modifier = Modifier,
    onOpenSettings: () -> Unit = {},
    uiState: HomeUiState = HomeUiState(),
    onRefreshPermissions: () -> Unit = {},
    onStartPermissionMonitor: (PermissionType, Activity) -> Unit = { _, _ -> },
    onStopPermissionMonitor: () -> Unit = {},
) {
    val playbackState = MusicPanelStateHolder.state
    val ui = MusicPanelStateHolder.ui
    val context = LocalContext.current
    // 播放偏好：滑动切歌开关
    val swipeToChangeTrack by context.swipeToChangeTrackFlow()
        .collectAsStateWithLifecycle(initialValue = true)
    // 首页标题/艺术家在线搜索等协程作用域
    val scope = rememberCoroutineScope()
    // 曲库分析会话：状态与后台任务常驻首页层，关闭对话框后分析继续执行
    val libraryAnalysis = remember(context) { LibraryAnalysisController(context, scope) }
    // 左右滑动切换面板与上下滑动切歌的手势状态
    val swipeController = rememberHomeSwipeController(playbackState, swipeToChangeTrack)
    swipeController.SettleEffect()
    // 首页播放列表面板显隐：显示期间禁用上下滑动切歌，滚动交由播放列表处理
    var playlistVisible by remember { mutableStateOf(false) }
    swipeController.playlistSheetVisible = playlistVisible
    var showTimer by remember { mutableStateOf(false) }
    // 横屏模式：跟随窗口宽高比，旋转时窗口重布局由 onSizeChanged 更新
    var isLandscapeMode by remember { mutableStateOf(false) }
    // 横屏下标题栏与控制栏的统一显隐状态
    var landscapeChromeVisible by remember { mutableStateOf(false) }
    // 首页背景代表色：供在线搜索等浮层容器复用，保持与首页底色一致
    var homeBackgroundColor by remember { mutableStateOf(md_theme_dark_surface) }
    // 覆盖层打开时返回键：优先清空搜索结果与输入框；搜索状态已清空时才关闭覆盖层返回播放器
    BackHandler(enabled = swipeController.showOnlineSearch) {
        val hasSearchContent = ui.searchQuery.isNotBlank() ||
            ui.searchResults.isNotEmpty() ||
            ui.showSearchResults
        if (hasSearchContent) {
            ui.setSearchQuery("")
            ui.searchResults = emptyList()
            ui.searchPending = emptyList()
            ui.searchPendingFull = false
            ui.setSearchResultsVisible(false)
            playbackState.updateErrorMsg(null)
        } else {
            swipeController.showOnlineSearch = false
            ui.setSearchResultsVisible(false)
            playbackState.updateErrorMsg(null)
        }
    }
    // 歌单面板打开时返回键关闭面板
    BackHandler(enabled = swipeController.showPlaylist) {
        swipeController.showPlaylist = false
    }
    // 只有播放器真正开始播放（无错误）时才收起在线搜索覆盖层；播放失败出现错误提示时保持面板打开
    LaunchedEffect(playbackState.isPlaying) {
        if (playbackState.isPlaying && swipeController.showOnlineSearch) {
            swipeController.showOnlineSearch = false
        }
    }
    // LocalContext 为本地化包装 context，宿主 Activity 需从注册表所有者获取
    val activity = LocalActivityResultRegistryOwner.current as? Activity
    val currentTrackId = playbackState.currentTrack?.id
    val isLiked = currentTrackId?.let { playbackState.likedIds.contains(it) } ?: false

    // 横屏模式：按当前朝向切换设备方向，布局由配置变化驱动
    fun toggleLandscapeMode() {
        activity?.requestedOrientation = if (isLandscapeMode) {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } else {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
    }

    // 横屏下标题栏与控制栏显示 3 秒后自动隐藏
    LaunchedEffect(isLandscapeMode, landscapeChromeVisible) {
        if (isLandscapeMode && landscapeChromeVisible) {
            delay(3000)
            landscapeChromeVisible = false
        }
    }

    // 进入横屏沉浸模式时隐藏系统栏
    LaunchedEffect(isLandscapeMode) {
        if (isLandscapeMode) {
            activity?.window?.insetsController?.hide(android.view.WindowInsets.Type.systemBars())
        }
    }

    // 沉浸式页面背景铺满全屏，Scaffold 透明以透出背景层；右滑呼出在线搜索面板，左滑呼出歌单面板
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .then(swipeController.swipeModifier)
    ) {
        val contentWidth = maxWidth
        // 对话框收起后的后台分析进度：在标题区居中展示
        val analysisCenterTitle = if (!libraryAnalysis.visible && libraryAnalysis.analyzing) {
            libraryAnalysis.checkingProgress?.let { (checked, total) ->
                stringResource(R.string.library_analysis_check_progress, checked, total)
            } ?: stringResource(R.string.library_analysis_checking)
        } else {
            null
        }
        // 横屏系统栏隐藏，无需为状态栏压暗顶部
        SongGradientBackground(
            track = playbackState.currentTrack,
            darkenStatusBarArea = !isLandscapeMode,
            onBackgroundColor = { homeBackgroundColor = it },
        )
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged {
                    swipeController.contentWidthPx = it.width.toFloat()
                    isLandscapeMode = it.width > it.height
                },
            containerColor = Color.Transparent,
            topBar = {
                // 竖屏标题栏常驻；横屏下改为悬浮控制栏，不占布局空间
                if (!isLandscapeMode) {
                    HomeTopBar(
                        playbackState = playbackState,
                        isLandscapeMode = isLandscapeMode,
                        isLiked = isLiked,
                        favoriteEnabled = currentTrackId != null,
                        centerTitle = analysisCenterTitle,
                        onShowTimer = { showTimer = true },
                        onToggleFavorite = { currentTrackId?.let { playbackState.toggleFavorite(it) } },
                        onToggleLandscape = { toggleLandscapeMode() },
                        onOpenSettings = onOpenSettings,
                    )
                }
            },
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .consumeWindowInsets(innerPadding)
                    .clipToBounds(),
            ) {
                // 播放器页：铺满全屏（含标题栏区域），竖屏沉浸封面嵌入标题栏后方
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            translationX = contentWidth.toPx() * swipeController.searchProgress -
                                    contentWidth.toPx() * swipeController.playlistProgress
                        }
                ) {
                    if (isLandscapeMode) {
                        LandscapePlayerArea(
                            playbackState = playbackState,
                            chromeVisible = landscapeChromeVisible,
                            onToggleChrome = { landscapeChromeVisible = !landscapeChromeVisible },
                            playlistVisible = playlistVisible,
                            onPlaylistVisibilityChange = { playlistVisible = it },
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        // 长按标题/艺术家菜单“在线搜索”：切到在线搜索面板并自动按当前菜单文本搜索
                        PlayerArea(
                            modifier = Modifier.fillMaxSize(),
                            topBarInset = innerPadding.calculateTopPadding(),
                            swipePreviewText = swipeController.trackSwitchPreviewText,
                            libraryAnalysis = libraryAnalysis,
                            playlistVisible = playlistVisible,
                            onPlaylistVisibilityChange = { playlistVisible = it },
                            onOpenOnlineSearch = { query ->
                                ui.setSearchQuery(query)
                                ui.setSearchResultsVisible(true)
                                swipeController.showOnlineSearch = true
                                scope.launch { performSearch(ui, playbackState, context) }
                            },
                        )
                    }
                }
                // 在线搜索页：自左侧滑入顶替播放器位置；面板内容仍从标题栏下方开始
                OnlineSearchPanel(
                    playbackState = playbackState,
                    menuBackgroundColor = homeBackgroundColor,
                    isLandscape = isLandscapeMode,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = innerPadding.calculateTopPadding())
                        .graphicsLayer {
                            translationX = -contentWidth.toPx() * (1f - swipeController.searchProgress)
                        },
                )
                // 歌单面板：自右侧滑入顶替播放器位置；面板内容仍从标题栏下方开始
                PlaylistPanel(
                    visible = swipeController.showPlaylist,
                    playbackState = playbackState,
                    menuBackgroundColor = homeBackgroundColor,
                    isLandscape = isLandscapeMode,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = innerPadding.calculateTopPadding())
                        .graphicsLayer {
                            translationX = contentWidth.toPx() * (1f - swipeController.playlistProgress)
                        },
                )
                // 横屏标题栏悬浮于内容顶部，随控制栏一起显隐，不挤压播放器布局
                if (isLandscapeMode) {
                    AnimatedVisibility(
                        visible = landscapeChromeVisible,
                        modifier = Modifier.align(Alignment.TopCenter),
                        enter = slideInVertically(animationSpec = tween(300)) { -it } + fadeIn(),
                        exit = slideOutVertically(animationSpec = tween(300)) { -it } + fadeOut(),
                    ) {
                        HomeTopBar(
                            playbackState = playbackState,
                            isLandscapeMode = isLandscapeMode,
                            isLiked = isLiked,
                            favoriteEnabled = currentTrackId != null,
                            windowInsets = WindowInsets(0, 0, 0, 0),
                            onShowTimer = { showTimer = true },
                            onToggleFavorite = { currentTrackId?.let { playbackState.toggleFavorite(it) } },
                            onToggleLandscape = { toggleLandscapeMode() },
                            onOpenSettings = onOpenSettings,
                        )
                    }
                }
                PermissionDialog(
                    uiState = uiState,
                    onRefresh = onRefreshPermissions,
                    onStartPermissionMonitor = onStartPermissionMonitor,
                    onStopPermissionMonitor = onStopPermissionMonitor,
                )
                TimerDialog(
                    visible = showTimer,
                    minutes = playbackState.timerMinutes,
                    onMinutesChange = { playbackState.updateTimerMinutes(it) },
                    onConfirm = {
                        playbackState.startTimer(playbackState.timerMinutes)
                        showTimer = false
                    },
                    onCancel = { showTimer = false },
                )
            }
        }
    }
}
