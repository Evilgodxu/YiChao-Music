package com.yichao.evilgodxu.screens.home

import android.app.Activity
import android.content.res.Configuration
import android.view.WindowInsets
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yichao.evilgodxu.musicpanel.MusicPanelStateHolder
import com.yichao.evilgodxu.screens.home.home_assembly.HomeAssembly
import com.yichao.evilgodxu.theme.SystemBarAppearance
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
        // 未播放时也预读当前曲目格式信息，重启后音频信息条仍能展示
        state.refreshIdleTrackFormatInfo(context)
    }
    // 首页竖屏沉浸式：默认隐藏状态栏，不再做状态栏变色处理；横屏由 Activity 统一隐藏系统栏
    val orientation = LocalConfiguration.current.orientation
    val insetsController = (LocalActivityResultRegistryOwner.current as? Activity)
        ?.window?.insetsController
    DisposableEffect(orientation, insetsController) {
        SystemBarAppearance.isHomePortraitImmersive =
            orientation == Configuration.ORIENTATION_PORTRAIT
        insetsController?.hide(WindowInsets.Type.statusBars())
        onDispose {
            // 离开首页或旋转重建时恢复状态栏显示，避免影响后续页面
            SystemBarAppearance.isHomePortraitImmersive = false
            insetsController?.show(WindowInsets.Type.statusBars())
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
