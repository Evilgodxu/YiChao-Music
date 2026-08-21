package com.yichao.evilgodxu.screens.home

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yichao.evilgodxu.musicpanel.MusicPanelStateHolder
import com.yichao.evilgodxu.screens.home.home_assembly.HomeAssembly
import com.yichao.evilgodxu.theme.LocalIsDarkTheme
import org.koin.androidx.compose.koinViewModel

// 页面入口：编排首页播放器页面
@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    onOpenSettings: () -> Unit,
    viewModel: HomeViewModel = koinViewModel(),
) {
    val uiState by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val view = LocalView.current
    val isDarkTheme = LocalIsDarkTheme.current
    // 首页背景为沉浸式偏暗封面渐变，浅色主题下也保持状态栏文字为白色；离开时恢复主题默认
    DisposableEffect(isDarkTheme) {
        val window = (view.context as? Activity)?.window
        window?.let {
            WindowCompat.getInsetsController(it, view).isAppearanceLightStatusBars = false
        }
        onDispose {
            window?.let {
                WindowCompat.getInsetsController(it, view).isAppearanceLightStatusBars = !isDarkTheme
            }
        }
    }
    // 冷启动恢复持久化的播放列表，并定位当前曲目
    LaunchedEffect(Unit) {
        val state = MusicPanelStateHolder.state
        state.restoreSavedState(context)
        if (state.playlist.isNotEmpty() && state.currentTrack == null) {
            val index = state.pendingSavedUri
                ?.let { uri -> state.playlist.indexOfFirst { it.audioUri == uri } }
                ?.takeIf { it >= 0 }
                ?: 0
            state.currentIndex = index
            state.currentTrack = state.playlist[index]
        }
    }
    HomeAssembly(
        modifier = modifier,
        onOpenSettings = onOpenSettings,
        uiState = uiState,
        onRefreshPermissions = viewModel::refreshPermissions,
        onStartPermissionMonitor = viewModel::startPermissionMonitor,
        onStopPermissionMonitor = viewModel::stopPermissionMonitor,
    )
}
