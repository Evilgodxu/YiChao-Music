package com.yichao.evilgodxu.screens.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yichao.evilgodxu.screens.settings.settings_assembly.SettingsAssembly
import com.yichao.evilgodxu.theme.LocalThemeTransitionController
import com.yichao.evilgodxu.theme.StatusBarStyleEffect
import com.yichao.evilgodxu.update.UpdateViewModel
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

// 页面入口：编排设置页分区
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = koinViewModel(),
    updateViewModel: UpdateViewModel = koinInject(),
    onOpenTypography: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val onThemeClick: (Offset) -> Unit = LocalThemeTransitionController.current::revealAt
    val context = LocalContext.current

    // 用 ActivityResultLauncher 打开悬浮窗系统设置页：结果由 ActivityResultRegistry 托管，
    // 配置变更或进程被回收后重建 Activity 时仍会重新投递，保证授权状态能可靠对账
    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.onOverlaySettingsReturned()
    }

    // 需要悬浮窗授权时跳转系统设置（仅由迷你播放器开关触发）
    LaunchedEffect(uiState.overlayPermissionPending) {
        if (uiState.overlayPermissionPending) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            )
            runCatching { overlayPermissionLauncher.launch(intent) }
                .onFailure { viewModel.onOverlaySettingsReturned() }
        }
    }

    // 状态栏图标跟随主题：浅色主题深色图标，深色主题白色图标
    StatusBarStyleEffect()
    SettingsAssembly(
        uiState = uiState,
        onBack = onBack,
        onThemeSelected = viewModel::setThemeMode,
        onLanguageSelected = viewModel::setLanguage,
        onThemeClick = onThemeClick,
        onMiniPlayerEnabledChange = viewModel::setMiniPlayerEnabled,
        onWordByWordRenderingChange = viewModel::setWordByWordRendering,
        onSwipeToChangeTrackChange = viewModel::setSwipeToChangeTrack,
        onVersionClick = { updateViewModel.checkForUpdate(force = true) },
        onOpenTypography = onOpenTypography,
        modifier = modifier,
    )
}