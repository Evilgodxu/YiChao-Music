package com.yichao.evilgodxu.screens.home

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.WindowInsets
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yichao.evilgodxu.domain.music.panel.MusicPanelStateHolder
import com.yichao.evilgodxu.screens.home.compact.CompactAssembly
import com.yichao.evilgodxu.screens.home.component.panel.rememberHomePanelState
import com.yichao.evilgodxu.screens.home.expanded.ExpandedAssembly
import com.yichao.evilgodxu.theme.SystemBarAppearance
import com.yichao.evilgodxu.ui.adaptive.rememberExpandedForm
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

// 页面入口：形态分发 + 跨形态副作用，不承载布局
@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    onOpenSettings: () -> Unit,
    viewModel: HomeViewModel = koinViewModel(),
) {
    val uiState by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val stateHolder = koinInject<MusicPanelStateHolder>()
    // 冷启动恢复持久化的播放列表，并定位当前曲目
    LaunchedEffect(Unit) {
        val state = stateHolder.state
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

    val activity = LocalActivityResultRegistryOwner.current as? Activity
    val orientation = LocalConfiguration.current.orientation
    val isPortrait = orientation == Configuration.ORIENTATION_PORTRAIT

    // 竖屏沉浸式：默认隐藏状态栏，不再做状态栏变色处理；横屏由 Activity 统一隐藏系统栏
    val insetsController = activity?.window?.insetsController
    DisposableEffect(orientation, insetsController) {
        SystemBarAppearance.isHomePortraitImmersive = isPortrait
        insetsController?.hide(WindowInsets.Type.statusBars())
        onDispose {
            // 离开首页或旋转时恢复状态栏显示，避免影响后续页面
            SystemBarAppearance.isHomePortraitImmersive = false
            insetsController?.show(WindowInsets.Type.statusBars())
        }
    }
    // 进入横屏沉浸模式时隐藏系统栏
    LaunchedEffect(isPortrait) {
        if (!isPortrait) insetsController?.hide(WindowInsets.Type.systemBars())
    }
    // 横竖屏切换：按当前朝向请求目标方向
    val onToggleLandscape = {
        activity?.requestedOrientation = if (isPortrait) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // 跨形态共享状态：旋转不重建 Activity，面板显隐与后台分析需在形态切换间保持
    val panelState = rememberHomePanelState()
    val playbackState = panelState.playbackState.state
    // 在线搜索覆盖层打开时返回键：优先清空搜索结果与输入框；搜索状态已清空时才关闭覆盖层返回播放器
    BackHandler(enabled = panelState.swipeController.showOnlineSearch) {
        val hasSearchContent = playbackState.searchQuery.isNotBlank() ||
            playbackState.searchResults.isNotEmpty() ||
            playbackState.showSearchResults
        if (hasSearchContent) {
            playbackState.setSearchQuery("")
            playbackState.searchResults = emptyList()
            playbackState.searchPending = emptyList()
            playbackState.searchPendingFull = false
            playbackState.setSearchResultsVisible(false)
            playbackState.setErrorMsg(null)
        } else {
            panelState.swipeController.showOnlineSearch = false
            playbackState.setSearchResultsVisible(false)
            playbackState.setErrorMsg(null)
        }
    }
    // 歌单面板打开时返回键关闭面板
    BackHandler(enabled = panelState.swipeController.showPlaylist) {
        panelState.swipeController.showPlaylist = false
    }
    // 只有播放器真正开始播放（无错误）时才收起在线搜索覆盖层；播放失败出现错误提示时保持面板打开
    LaunchedEffect(playbackState.isPlaying) {
        if (playbackState.isPlaying && panelState.swipeController.showOnlineSearch) {
            panelState.swipeController.showOnlineSearch = false
        }
    }

    // 形态分派：旋转状态与窗口宽度尺寸类共同决定显示内容
    if (rememberExpandedForm()) {
        ExpandedAssembly(
            uiState = uiState,
            panelState = panelState,
            onOpenSettings = onOpenSettings,
            onToggleLandscape = onToggleLandscape,
            onRefreshPermissions = viewModel::refreshPermissions,
            onStartPermissionMonitor = viewModel::startPermissionMonitor,
            onStopPermissionMonitor = viewModel::stopPermissionMonitor,
            modifier = modifier,
        )
    } else {
        CompactAssembly(
            uiState = uiState,
            panelState = panelState,
            onOpenSettings = onOpenSettings,
            onToggleLandscape = onToggleLandscape,
            onRefreshPermissions = viewModel::refreshPermissions,
            onStartPermissionMonitor = viewModel::startPermissionMonitor,
            onStopPermissionMonitor = viewModel::stopPermissionMonitor,
            modifier = modifier,
        )
    }
}
