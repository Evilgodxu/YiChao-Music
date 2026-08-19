package com.yichao.evilgodxu.screens.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yichao.evilgodxu.musicpanel.LocalMusicPanelController
import com.yichao.evilgodxu.screens.home.home_assembly.HomeAssembly
import org.koin.androidx.compose.koinViewModel

// 页面入口：编排首页分区
@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    onOpenSettings: () -> Unit,
    viewModel: HomeViewModel = koinViewModel(),
) {
    val uiState by viewModel.state.collectAsStateWithLifecycle()
    val musicPanelController = LocalMusicPanelController.current
    HomeAssembly(
        modifier = modifier,
        onOpenSettings = onOpenSettings,
        uiState = uiState,
        onRefreshPermissions = viewModel::refreshPermissions,
        onStartPermissionMonitor = viewModel::startPermissionMonitor,
        onStopPermissionMonitor = viewModel::stopPermissionMonitor,
        onOpenMusicPanel = { musicPanelController?.openMusicPanel() },
    )
}
