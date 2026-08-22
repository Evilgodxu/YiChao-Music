package com.yichao.evilgodxu.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yichao.evilgodxu.screens.settings.settings_assembly.SettingsAssembly
import com.yichao.evilgodxu.theme.LocalThemeTransitionController
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
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val onThemeClick: (Offset) -> Unit = LocalThemeTransitionController.current::revealAt
    SettingsAssembly(
        uiState = uiState,
        onBack = onBack,
        onThemeSelected = viewModel::setThemeMode,
        onLanguageSelected = viewModel::setLanguage,
        onThemeClick = onThemeClick,
        onMiniPlayerEnabledChange = viewModel::setMiniPlayerEnabled,
        onWordByWordRenderingChange = viewModel::setWordByWordRendering,
        onVersionClick = { updateViewModel.checkForUpdate(force = true) },
        modifier = modifier,
    )
}