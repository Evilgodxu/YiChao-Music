package com.yichao.evilgodxu.screens.home.home_assembly

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Process
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.data.permission.PermissionType
import com.yichao.evilgodxu.musicpanel.MusicPanelStateHolder
import com.yichao.evilgodxu.musicpanel.TimerOverlay
import com.yichao.evilgodxu.screens.home.HomeUiState
import com.yichao.evilgodxu.screens.home.home_assembly.permission_area.PermissionDialog
import com.yichao.evilgodxu.screens.home.home_assembly.player_area.LandscapePlayerArea
import com.yichao.evilgodxu.screens.home.home_assembly.player_area.PlayerArea
import java.util.Locale
import kotlinx.coroutines.delay

// 首页组装器：顶部标题栏（定时/收藏/内存/横屏/设置）+ 播放器主体 + 权限与定时对话框
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
    // 横屏下标题栏与控制栏的统一显隐状态
    var landscapeChromeVisible by remember { mutableStateOf(false) }
    val activity = LocalActivity.current
    // 横屏模式以实际窗口方向为准，旋转未生效时 UI 不会与设备方向脱节
    val isLandscapeMode = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val currentTrackId = playbackState.currentTrack?.id
    val isLiked = currentTrackId?.let { playbackState.likedIds.contains(it) } ?: false

    // 横屏模式：切换三栏布局并强制设备横屏，退出时恢复系统默认方向
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

    Scaffold(
        modifier = modifier,
        topBar = {
            // 横屏下标题栏随控制栏一起自动隐藏，点击屏幕弹出
            AnimatedVisibility(
                visible = !isLandscapeMode || landscapeChromeVisible,
                enter = slideInVertically(animationSpec = tween(300)) { -it } + fadeIn(),
                exit = slideOutVertically(animationSpec = tween(300)) { -it } + fadeOut(),
            ) {
                CenterAlignedTopAppBar(
                    title = { MemoryUsageText() },
                navigationIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            IconButton(onClick = { showTimer = true }) {
                                Icon(
                                    imageVector = Icons.Default.Timer,
                                    contentDescription = stringResource(R.string.music_panel_timer_title),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (playbackState.timerRemaining > 0) {
                                Text(
                                    text = "${playbackState.timerRemaining}m",
                                    color = MaterialTheme.colorScheme.primary,
                                    fontSize = 10.sp,
                                    modifier = Modifier
                                        .clickable { playbackState.stopTimer() }
                                        .padding(horizontal = 8.dp, vertical = 2.dp),
                                )
                            }
                        }
                        IconButton(
                            onClick = { currentTrackId?.let { playbackState.toggleFavorite(it) } },
                            enabled = currentTrackId != null,
                        ) {
                            Icon(
                                imageVector = if (isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                contentDescription = stringResource(R.string.music_panel_favorite),
                                tint = if (isLiked) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { toggleLandscapeMode() }) {
                        Icon(
                            imageVector = Icons.Filled.ScreenRotation,
                            contentDescription = stringResource(R.string.home_landscape_mode),
                            tint = if (isLandscapeMode) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            painter = painterResource(R.drawable.ic_settings),
                            contentDescription = stringResource(R.string.settings_title),
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
            }
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(innerPadding)
                .padding(innerPadding),
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
            PermissionDialog(
                uiState = uiState,
                onRefresh = onRefreshPermissions,
                onStartPermissionMonitor = onStartPermissionMonitor,
                onStopPermissionMonitor = onStopPermissionMonitor,
            )
            TimerOverlay(
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

// 顶部应用内存占用显示，半秒刷新
@Composable
private fun MemoryUsageText() {
    val context = LocalContext.current
    var usage by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (true) {
            val memoryInfo = (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
                .getProcessMemoryInfo(intArrayOf(Process.myPid()))[0]
            val pssMB = memoryInfo.getTotalPss() / 1024f
            usage = String.format(Locale.getDefault(), "%.0f MB", pssMB)
            delay(500)
        }
    }
    Text(
        text = stringResource(R.string.home_memory_usage, usage),
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.Medium,
    )
}
