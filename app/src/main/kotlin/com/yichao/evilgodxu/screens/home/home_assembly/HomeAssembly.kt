package com.yichao.evilgodxu.screens.home.home_assembly

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import com.yichao.evilgodxu.ui.icons.AppIcons
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.data.permission.PermissionType
import com.yichao.evilgodxu.musicpanel.MusicPanelStateHolder
import com.yichao.evilgodxu.musicpanel.SongGradientBackground
import com.yichao.evilgodxu.musicpanel.playTrackAt
import com.yichao.evilgodxu.musicpanel.MusicPlaybackState
import com.yichao.evilgodxu.musicpanel.MusicTrack
import com.yichao.evilgodxu.musicpanel.TimerDialog
import com.yichao.evilgodxu.musicpanel.swipeToChangeTrackFlow
import com.yichao.evilgodxu.screens.home.HomeUiState
import com.yichao.evilgodxu.screens.home.home_assembly.online_search.OnlineSearchPanel
import com.yichao.evilgodxu.screens.home.home_assembly.permission_area.PermissionDialog
import com.yichao.evilgodxu.screens.home.home_assembly.player_area.LandscapePlayerArea
import com.yichao.evilgodxu.screens.home.home_assembly.player_area.PlayerArea
import com.yichao.evilgodxu.screens.home.home_assembly.playlist_area.PlaylistPanel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

// 左右滑动切换（右滑搜索、左滑歌单）共用的回弹阈值：滑动进度达到该比例则展开，否则回弹至播放器
private const val SWIPE_OPEN_RATIO = 0.25f

// 首页组装器：顶部标题栏（定时/收藏/横屏/设置）+ 播放器主体 + 权限与定时对话框
@OptIn(ExperimentalMaterial3Api::class)
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
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val gestureScope = rememberCoroutineScope()
    // 播放偏好：滑动切歌开关
    val swipeToChangeTrack by context.swipeToChangeTrackFlow()
        .collectAsStateWithLifecycle(initialValue = true)
    // 手势协程中读取实时开关值，避免捕获过期状态
    val swipeToChangeTrackState = rememberUpdatedState(swipeToChangeTrack)
    var showTimer by remember { mutableStateOf(false) }
    // 横屏模式：跟随窗口宽高比，旋转时窗口重布局由 onSizeChanged 更新
    var isLandscapeMode by remember { mutableStateOf(false) }
    // 横屏下标题栏与控制栏的统一显隐状态
    var landscapeChromeVisible by remember { mutableStateOf(false) }
    // 右滑呼出的在线搜索覆盖层显隐状态
    var showOnlineSearch by remember { mutableStateOf(false) }
    // 左滑呼出的歌单面板显隐状态
    var showPlaylist by remember { mutableStateOf(false) }
    // 手势跟手进度：0=播放器页，1=对应面板展开，拖动期间随手指实时更新
    var searchProgress by remember { mutableFloatStateOf(0f) }
    var playlistProgress by remember { mutableFloatStateOf(0f) }
    // 内容区像素宽度，用于将滑动距离换算为进度比例
    var contentWidthPx by remember { mutableFloatStateOf(0f) }
    // 本次手势起始时面板的展开状态，回滑关闭时按相同比例阈值判定
    var gestureSearchOpen by remember { mutableStateOf(false) }
    var gesturePlaylistOpen by remember { mutableStateOf(false) }
    // 触发回弹/展开的动画：非拖动状态下将进度平滑带到目标值
    var settleKey by remember { mutableIntStateOf(0) }
    LaunchedEffect(showOnlineSearch, showPlaylist, settleKey) {
        // 两个面板进度独立结算，并行动画避免相互阻塞
        launch {
            val target = if (showOnlineSearch) 1f else 0f
            if (searchProgress != target) {
                animate(
                    initialValue = searchProgress,
                    targetValue = target,
                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                ) { value, _ -> searchProgress = value }
            }
        }
        launch {
            val target = if (showPlaylist) 1f else 0f
            if (playlistProgress != target) {
                animate(
                    initialValue = playlistProgress,
                    targetValue = target,
                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                ) { value, _ -> playlistProgress = value }
            }
        }
    }
    // 覆盖层打开时返回键：优先清空搜索结果与输入框；搜索状态已清空时才关闭覆盖层返回播放器
    BackHandler(enabled = showOnlineSearch) {
        val hasSearchContent = playbackState.searchQuery.isNotBlank() ||
            playbackState.searchResults.isNotEmpty() ||
            playbackState.showSearchResults
        if (hasSearchContent) {
            playbackState.setSearchQuery("")
            playbackState.searchResults = emptyList()
            playbackState.setSearchResultsVisible(false)
            playbackState.setErrorMsg(null)
        } else {
            showOnlineSearch = false
            playbackState.setSearchResultsVisible(false)
            playbackState.setErrorMsg(null)
        }
    }
    // 歌单面板打开时返回键关闭面板
    BackHandler(enabled = showPlaylist) {
        showPlaylist = false
    }
    // 只有播放器真正开始播放(无错误)时才收起在线搜索覆盖层；播放失败出现错误提示时保持面板打开
    LaunchedEffect(playbackState.isPlaying) {
        if (playbackState.isPlaying && showOnlineSearch) {
            showOnlineSearch = false
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
            activity?.let {
                WindowInsetsControllerCompat(it.window, it.window.decorView)
                    .hide(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    // 沉浸式页面背景铺满全屏，Scaffold 透明以透出背景层；右滑呼出在线搜索面板，左滑呼出歌单面板
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    // 记录手势起始时的面板展开状态，回滑时按相同阈值判定关闭
                    gestureSearchOpen = showOnlineSearch
                    gesturePlaylistOpen = showPlaylist
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // 阶段一：累计位移直到任一轴越过触摸阈值，据此锁定主导方向，保证左右滑动与上下滑动互斥
                    var accX = 0f
                    var accY = 0f
                    var axis = 0 // 0=未定，1=横向(切换面板)，2=纵向(切歌)
                    while (axis == 0) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        // 手指抬起或被子控件(进度条/控制栏等)消费，放弃本手势
                        if (!change.pressed || change.isConsumed) break
                        accX += change.positionChange().x
                        accY += change.positionChange().y
                        if (abs(accX) >= slop || abs(accY) >= slop) {
                            axis = if (abs(accX) > abs(accY)) 1 else 2
                        }
                    }
                    if (axis == 1) {
                        // 横向主导：右滑搜索、左滑歌单，拖动全程跟手；切换页面时自动收起键盘
                        keyboardController?.hide()
                        if (contentWidthPx > 0f) {
                            when {
                                searchProgress > 0f -> searchProgress =
                                    (searchProgress + accX / contentWidthPx).coerceIn(0f, 1f)
                                playlistProgress > 0f -> playlistProgress =
                                    (playlistProgress - accX / contentWidthPx).coerceIn(0f, 1f)
                                accX > 0f -> searchProgress =
                                    (accX / contentWidthPx).coerceIn(0f, 1f)
                                else -> playlistProgress =
                                    (-accX / contentWidthPx).coerceIn(0f, 1f)
                            }
                        }
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null || !change.pressed || change.isConsumed) break
                            val dx = change.positionChange().x
                            change.consume()
                            if (contentWidthPx > 0f) {
                                // 已展开的面板优先回拽；播放器页按滑动方向分配进度（右滑搜索、左滑歌单）
                                when {
                                    searchProgress > 0f -> searchProgress =
                                        (searchProgress + dx / contentWidthPx).coerceIn(0f, 1f)
                                    playlistProgress > 0f -> playlistProgress =
                                        (playlistProgress - dx / contentWidthPx).coerceIn(0f, 1f)
                                    dx > 0f -> searchProgress =
                                        (dx / contentWidthPx).coerceIn(0f, 1f)
                                    else -> playlistProgress =
                                        (-dx / contentWidthPx).coerceIn(0f, 1f)
                                }
                            }
                        }
                        // 松开：按滑动比例判定展开或回弹；已展开面板回滑按同样比例判定关闭，保证来回切换阈值统一
                        val searchOpen = if (gestureSearchOpen) {
                            searchProgress > 1f - SWIPE_OPEN_RATIO
                        } else {
                            searchProgress >= SWIPE_OPEN_RATIO
                        }
                        if (searchOpen != showOnlineSearch) {
                            showOnlineSearch = searchOpen
                            if (!searchOpen) {
                                playbackState.setSearchResultsVisible(false)
                                playbackState.setErrorMsg(null)
                            }
                        }
                        val playlistOpen = if (gesturePlaylistOpen) {
                            playlistProgress > 1f - SWIPE_OPEN_RATIO
                        } else {
                            playlistProgress >= SWIPE_OPEN_RATIO
                        }
                        if (playlistOpen != showPlaylist) showPlaylist = playlistOpen
                        settleKey++ // 结算本次滑动，非目标状态时平滑动画到目标
                    } else if (axis == 2) {
                        // 纵向主导：向上切下一首、向下切上一首；仅播放器视图(无覆盖面板)生效，避免与面板内滚动冲突
                        var swipeY = accY
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null || !change.pressed || change.isConsumed) break
                            swipeY += change.positionChange().y
                            change.consume()
                        }
                        if (swipeToChangeTrackState.value &&
                            searchProgress <= 0f && playlistProgress <= 0f
                        ) {
                            val next = if (swipeY < 0f) playbackState.nextIndex()
                            else playbackState.previousIndex()
                            if (next >= 0) gestureScope.launch { playTrackAt(context, playbackState, next) }
                        }
                    }
                }
            }
    ) {
        val contentWidth = maxWidth
        // 横屏系统栏隐藏，无需为状态栏压暗顶部
        SongGradientBackground(
            track = playbackState.currentTrack,
            darkenStatusBarArea = !isLandscapeMode,
        )
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged {
                    contentWidthPx = it.width.toFloat()
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
                    .padding(innerPadding)
                    .clipToBounds(),
            ) {
                // 播放器页：展开搜索时向右平移、展开歌单时向左平移，露出对应面板
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            translationX = contentWidth.toPx() * searchProgress -
                                    contentWidth.toPx() * playlistProgress
                        }
                ) {
                    if (isLandscapeMode) {
                        LandscapePlayerArea(
                            playbackState = playbackState,
                            chromeVisible = landscapeChromeVisible,
                            onToggleChrome = { landscapeChromeVisible = !landscapeChromeVisible },
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        PlayerArea(modifier = Modifier.fillMaxSize())
                    }
                }
                // 在线搜索页：自左侧滑入顶替播放器位置
                OnlineSearchPanel(
                    playbackState = playbackState,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            translationX = -contentWidth.toPx() * (1f - searchProgress)
                        },
                )
                // 歌单面板：自右侧滑入顶替播放器位置
                PlaylistPanel(
                    visible = showPlaylist,
                    playbackState = playbackState,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            translationX = contentWidth.toPx() * (1f - playlistProgress)
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
                    onMinutesChange = { playbackState.setTimerMinutes(it) },
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

// 顶部标题栏：定时/收藏/横屏/设置操作；横屏悬浮时不吃系统栏内边距
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeTopBar(
    playbackState: MusicPlaybackState,
    isLandscapeMode: Boolean,
    isLiked: Boolean,
    favoriteEnabled: Boolean,
    onShowTimer: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleLandscape: () -> Unit,
    onOpenSettings: () -> Unit,
    windowInsets: WindowInsets = TopAppBarDefaults.windowInsets,
) {
    CenterAlignedTopAppBar(
        title = {},
        windowInsets = windowInsets,
        navigationIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box {
                    IconButton(onClick = onShowTimer) {
                        Icon(
                            imageVector = AppIcons.Timer,
                            contentDescription = stringResource(R.string.music_panel_timer_title),
                            tint = Color.White,
                        )
                    }
                    // 剩余时间叠加在按钮下方，不占布局空间，避免顶高按钮
                    if (playbackState.timerRemaining > 0) {
                        Text(
                            text = "${playbackState.timerRemaining}m",
                            color = Color.White,
                            fontSize = 10.sp,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .offset(y = 14.dp)
                                .clickable { playbackState.stopTimer() }
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
                IconButton(
                    onClick = onToggleFavorite,
                    enabled = favoriteEnabled,
                ) {
                    Icon(
                        imageVector = if (isLiked) AppIcons.Favorite else AppIcons.FavoriteBorder,
                        contentDescription = stringResource(R.string.music_panel_favorite),
                        tint = if (isLiked) Color.White
                        else Color.White,
                    )
                }
            }
        },
        actions = {
            IconButton(onClick = onToggleLandscape) {
                Icon(
                    imageVector = AppIcons.ScreenRotation,
                    contentDescription = stringResource(R.string.home_landscape_mode),
                    tint = if (isLandscapeMode) Color.White
                    else Color.White,
                )
            }
            IconButton(onClick = onOpenSettings) {
                Icon(
                    imageVector = AppIcons.Settings,
                    contentDescription = stringResource(R.string.settings_title),
                    tint = Color.White,
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
        ),
    )
}




