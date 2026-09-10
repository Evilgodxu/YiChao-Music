package com.yichao.evilgodxu.screens.typography

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yichao.evilgodxu.screens.typography.compact.CompactAssembly
import com.yichao.evilgodxu.screens.typography.expanded.ExpandedAssembly
import com.yichao.evilgodxu.theme.StatusBarStyleEffect
import com.yichao.evilgodxu.ui.adaptive.rememberExpandedForm
import org.koin.androidx.compose.koinViewModel

// 页面入口：形态分发 + 跨形态副作用，不承载布局
@Composable
fun TypographyScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TypographyViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // 状态栏图标跟随主题：浅色主题深色图标，深色主题白色图标
    StatusBarStyleEffect()

    // 形态分派：旋转状态与窗口宽度尺寸类共同决定显示内容
    if (rememberExpandedForm()) {
        ExpandedAssembly(
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
    } else {
        CompactAssembly(
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
}
