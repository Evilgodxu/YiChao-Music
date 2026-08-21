package com.yichao.evilgodxu.screens.home.home_assembly

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
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
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Timer
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.ImageResult
import coil3.toBitmap
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.data.permission.PermissionType
import com.yichao.evilgodxu.musicpanel.MusicMetadataCache
import com.yichao.evilgodxu.musicpanel.MusicPanelStateHolder
import com.yichao.evilgodxu.musicpanel.MusicPlaybackState
import com.yichao.evilgodxu.musicpanel.MusicTrack
import com.yichao.evilgodxu.musicpanel.TimerDialog
import com.yichao.evilgodxu.screens.home.HomeUiState
import com.yichao.evilgodxu.screens.home.home_assembly.online_search.OnlineSearchPanel
import com.yichao.evilgodxu.screens.home.home_assembly.permission_area.PermissionDialog
import com.yichao.evilgodxu.screens.home.home_assembly.player_area.LandscapePlayerArea
import com.yichao.evilgodxu.screens.home.home_assembly.player_area.PlayerArea
import com.yichao.evilgodxu.screens.home.home_assembly.playlist_area.PlaylistPanel
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// 左右滑动切换（右滑搜索、左滑歌单）共用的回弹阈值：滑动进度达到该比例则展开，否则回弹至播放器
private const val SWIPE_OPEN_RATIO = 0.35f

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
    // 覆盖层打开时返回键优先关闭覆盖层，而非退出应用
    BackHandler(enabled = showOnlineSearch) {
        showOnlineSearch = false
        playbackState.setSearchResultsVisible(false)
        playbackState.setErrorMsg(null)
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
                // 拖动期间实时跟手更新进度；松开时按 35% 阈值决定展开或回弹
                detectHorizontalDragGestures(
                    onDragStart = {
                        gestureSearchOpen = showOnlineSearch
                        gesturePlaylistOpen = showPlaylist
                    },
                    onDragEnd = {
                        // 展开按滑动比例判定；从已展开面板回滑时按相同的 35% 比例判定关闭，保证来回切换阈值统一、跟手
                        val searchOpen = if (gestureSearchOpen) {
                            searchProgress <= 1f - SWIPE_OPEN_RATIO
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
                            playlistProgress <= 1f - SWIPE_OPEN_RATIO
                        } else {
                            playlistProgress >= SWIPE_OPEN_RATIO
                        }
                        if (playlistOpen != showPlaylist) showPlaylist = playlistOpen
                        settleKey++ // 结算本次滑动，非目标状态时平滑动画到目标
                    },
                    onDragCancel = { settleKey++ }, // 手势被打断按回弹处理
                ) { change, dragAmount ->
                    change.consume()
                    if (contentWidthPx <= 0f) return@detectHorizontalDragGestures
                    // 已展开的面板优先回拽；播放器页按滑动方向分配进度（右滑搜索、左滑歌单）
                    when {
                        searchProgress > 0f -> searchProgress =
                            (searchProgress + dragAmount / contentWidthPx).coerceIn(0f, 1f)
                        playlistProgress > 0f -> playlistProgress =
                            (playlistProgress - dragAmount / contentWidthPx).coerceIn(0f, 1f)
                        dragAmount > 0f -> searchProgress =
                            (dragAmount / contentWidthPx).coerceIn(0f, 1f)
                        else -> playlistProgress =
                            (-dragAmount / contentWidthPx).coerceIn(0f, 1f)
                    }
                }
            }
    ) {
        val contentWidth = maxWidth
        HomeImmersiveBackground(track = playbackState.currentTrack)
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
                            imageVector = Icons.Default.Timer,
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
                        imageVector = if (isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
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
                    imageVector = Icons.Filled.ScreenRotation,
                    contentDescription = stringResource(R.string.home_landscape_mode),
                    tint = if (isLandscapeMode) Color.White
                    else Color.White,
                )
            }
            IconButton(onClick = onOpenSettings) {
                Icon(
                    painter = painterResource(R.drawable.ic_settings),
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

// 沉浸式页面背景：由封面提取上下半区主色并向下渐变，替代拉伸全屏图片
@Composable
private fun HomeImmersiveBackground(track: MusicTrack?) {
    val model = homeCoverModel(track)
    val context = LocalContext.current
    val defaultGradient = defaultHomeGradient()
    var gradient by remember { mutableStateOf(defaultGradient) }
    LaunchedEffect(model) {
        gradient = if (model != null) {
            coverGradient(context, model) ?: defaultGradient
        } else {
            defaultGradient
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(gradient)
    )
}

@Composable
private fun defaultHomeGradient(): Brush =
    Brush.verticalGradient(
        listOf(
            MaterialTheme.colorScheme.surface,
            MaterialTheme.colorScheme.surfaceVariant,
        )
    )

// 以小尺寸解码封面，取上下半区平均色组成向下渐变
private suspend fun coverGradient(context: Context, model: Any): Brush? = withContext(Dispatchers.IO) {
    val result = context.imageLoader.execute(
        ImageRequest.Builder(context)
            .data(model)
            .size(32)
            .build()
    )
    val source = result.image?.toBitmap() ?: return@withContext null
    val bitmap = if (source.config == Bitmap.Config.HARDWARE) {
        source.copy(Bitmap.Config.ARGB_8888, false) ?: return@withContext null
    } else source
    Brush.verticalGradient(
        listOf(
            bitmap.avgColor(topHalf = true).darkenIfNearWhite(),
            bitmap.avgColor(topHalf = false).darkenIfNearWhite(),
        )
    )
}

// 与白色前景（按钮标题/歌词）亮度相近时轻微压暗，保证文字可读
private fun Color.darkenIfNearWhite(): Color {
    val luminance = 0.299f * red + 0.587f * green + 0.114f * blue
    return if (luminance > 0.8f) lerp(this, Color.Black, 0.2f) else this
}

private fun Bitmap.avgColor(topHalf: Boolean): Color {
    val startY = if (topHalf) 0 else height / 2
    val endY = if (topHalf) height / 2 else height
    var r = 0L
    var g = 0L
    var b = 0L
    var count = 0L
    for (y in startY until endY) {
        for (x in 0 until width) {
            val c = getPixel(x, y)
            r += (c shr 16) and 0xFF
            g += (c shr 8) and 0xFF
            b += c and 0xFF
            count++
        }
    }
    return Color(
        red = (r / count).toFloat() / 255f,
        green = (g / count).toFloat() / 255f,
        blue = (b / count).toFloat() / 255f,
    )
}

// 当前歌曲封面来源：磁盘缓存优先，其次在线封面 URL
private fun homeCoverModel(track: MusicTrack?): Any? {
    val coverFile = track?.coverCachePath
        ?.takeIf { MusicMetadataCache.isValid(it) }
        ?.let { File(it) }
    return coverFile ?: track?.neteaseCoverUrl?.takeIf { it.isNotBlank() }
}


