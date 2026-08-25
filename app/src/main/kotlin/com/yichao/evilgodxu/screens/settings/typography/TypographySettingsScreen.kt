package com.yichao.evilgodxu.screens.settings.typography

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yichao.evilgodxu.theme.StatusBarStyleEffect
import org.koin.androidx.compose.koinViewModel

// 页面入口：编排卡片与排版设置分区
@Composable
fun TypographySettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TypographyViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // 状态栏图标跟随主题：浅色主题深色图标，深色主题白色图标
    StatusBarStyleEffect()
    TypographySettingsAssembly(
        uiState = uiState,
        onBack = onBack,
        onMusicPanelFontSizeChange = viewModel::adjustMusicPanelFontSize,
        onMusicPanelLinesChange = viewModel::adjustMusicPanelLines,
        onHomePortraitFontSizeChange = viewModel::adjustHomePortraitFontSize,
        onHomePortraitLinesChange = viewModel::adjustHomePortraitLines,
        onLandscapeFontSizeChange = viewModel::adjustLandscapeFontSize,
        onLandscapeLinesChange = viewModel::adjustLandscapeLines,
        onLandscape3DChange = viewModel::adjustLandscape3D,
        modifier = modifier,
    )
}
