package com.yichao.evilgodxu.screens.settings.typography

import android.os.SystemClock
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.data.settings.LyricLayoutDefaults
import com.yichao.evilgodxu.screens.settings.settings_assembly.component.section.SettingsSection
import com.yichao.evilgodxu.ui.icons.AppIcons
import kotlin.math.roundToInt

// 卡片与排版设置：三场景独立调节字号与行数，横屏额外调节 3D 强度，迷你播放器固定不参与
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TypographySettingsAssembly(
    uiState: TypographyUiState,
    onBack: () -> Unit,
    onMusicPanelFontSizeChange: (Int) -> Unit,
    onMusicPanelLinesChange: (Int) -> Unit,
    onHomePortraitFontSizeChange: (Int) -> Unit,
    onHomePortraitLinesChange: (Int) -> Unit,
    onLandscapeFontSizeChange: (Int) -> Unit,
    onLandscapeLinesChange: (Int) -> Unit,
    onLandscape3DChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 返回按钮防抖，避免快速连点重复出栈导致崩溃
    var lastBackClickAt by remember { mutableLongStateOf(0L) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.typography_screen_title)) },
                windowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Top),
                navigationIcon = {
                    IconButton(onClick = {
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastBackClickAt > 400L) {
                            lastBackClickAt = now
                            onBack()
                        }
                    }) {
                        Icon(AppIcons.ChevronLeft, stringResource(R.string.back))
                    }
                },
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(innerPadding)
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            // 音乐面板：独立调节
            SettingsSection(title = stringResource(R.string.typography_section_music_panel)) {
                StepperRow(
                    title = stringResource(R.string.typography_font_size),
                    valueText = "${uiState.musicPanel.fontSizeSp} sp",
                    decreaseEnabled = uiState.musicPanel.fontSizeSp > LyricLayoutDefaults.FONT_SIZE_MIN_SP,
                    increaseEnabled = uiState.musicPanel.fontSizeSp < LyricLayoutDefaults.FONT_SIZE_MAX_SP,
                    onDecrease = { onMusicPanelFontSizeChange(-1) },
                    onIncrease = { onMusicPanelFontSizeChange(1) },
                )
                StepperRow(
                    title = stringResource(R.string.typography_visible_lines),
                    valueText = "${uiState.musicPanel.visibleLines}",
                    decreaseEnabled = presetCanDecrease(
                        LyricLayoutDefaults.PORTRAIT_LINE_PRESETS, uiState.musicPanel.visibleLines,
                    ),
                    increaseEnabled = presetCanIncrease(
                        LyricLayoutDefaults.PORTRAIT_LINE_PRESETS, uiState.musicPanel.visibleLines,
                    ),
                    onDecrease = { onMusicPanelLinesChange(-1) },
                    onIncrease = { onMusicPanelLinesChange(1) },
                )
            }
            // 首页竖屏：独立调节
            SettingsSection(title = stringResource(R.string.typography_section_home_portrait)) {
                StepperRow(
                    title = stringResource(R.string.typography_font_size),
                    valueText = "${uiState.homePortrait.fontSizeSp} sp",
                    decreaseEnabled = uiState.homePortrait.fontSizeSp > LyricLayoutDefaults.FONT_SIZE_MIN_SP,
                    increaseEnabled = uiState.homePortrait.fontSizeSp < LyricLayoutDefaults.FONT_SIZE_MAX_SP,
                    onDecrease = { onHomePortraitFontSizeChange(-1) },
                    onIncrease = { onHomePortraitFontSizeChange(1) },
                )
                StepperRow(
                    title = stringResource(R.string.typography_visible_lines),
                    valueText = "${uiState.homePortrait.visibleLines}",
                    decreaseEnabled = presetCanDecrease(
                        LyricLayoutDefaults.HOME_PORTRAIT_LINE_PRESETS, uiState.homePortrait.visibleLines,
                    ),
                    increaseEnabled = presetCanIncrease(
                        LyricLayoutDefaults.HOME_PORTRAIT_LINE_PRESETS, uiState.homePortrait.visibleLines,
                    ),
                    onDecrease = { onHomePortraitLinesChange(-1) },
                    onIncrease = { onHomePortraitLinesChange(1) },
                )
            }
            // 首页横屏：独立调节，含 3D 强度
            SettingsSection(title = stringResource(R.string.typography_section_home_landscape)) {
                StepperRow(
                    title = stringResource(R.string.typography_font_size),
                    valueText = "${uiState.homeLandscape.fontSizeSp} sp",
                    decreaseEnabled = uiState.homeLandscape.fontSizeSp > LyricLayoutDefaults.FONT_SIZE_MIN_SP,
                    increaseEnabled = uiState.homeLandscape.fontSizeSp < LyricLayoutDefaults.FONT_SIZE_MAX_SP,
                    onDecrease = { onLandscapeFontSizeChange(-1) },
                    onIncrease = { onLandscapeFontSizeChange(1) },
                )
                StepperRow(
                    title = stringResource(R.string.typography_visible_lines),
                    valueText = "${uiState.homeLandscape.visibleLines}",
                    decreaseEnabled = presetCanDecrease(
                        LyricLayoutDefaults.LANDSCAPE_LINE_PRESETS, uiState.homeLandscape.visibleLines,
                    ),
                    increaseEnabled = presetCanIncrease(
                        LyricLayoutDefaults.LANDSCAPE_LINE_PRESETS, uiState.homeLandscape.visibleLines,
                    ),
                    onDecrease = { onLandscapeLinesChange(-1) },
                    onIncrease = { onLandscapeLinesChange(1) },
                )
                StepperRow(
                    title = stringResource(R.string.typography_3d_intensity),
                    valueText = "${(uiState.homeLandscape.threeDIntensity * 100).roundToInt()}%",
                    decreaseEnabled = uiState.homeLandscape.threeDIntensity > LyricLayoutDefaults.THREE_D_MIN,
                    increaseEnabled = uiState.homeLandscape.threeDIntensity < LyricLayoutDefaults.THREE_D_MAX,
                    onDecrease = { onLandscape3DChange(-1) },
                    onIncrease = { onLandscape3DChange(1) },
                )
            }
        }
    }
}

// 行数是否可在预设列表中继续减小/增大
private fun presetCanDecrease(presets: List<Int>, current: Int): Boolean =
    presets.indexOf(current) > 0

private fun presetCanIncrease(presets: List<Int>, current: Int): Boolean {
    val index = presets.indexOf(current)
    return index >= 0 && index < presets.lastIndex
}

// 调节行：标题 + 减号 / 数值 / 加号
@Composable
private fun StepperRow(
    title: String,
    valueText: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    decreaseEnabled: Boolean = true,
    increaseEnabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = onDecrease,
            enabled = decreaseEnabled,
            modifier = Modifier.size(34.dp),
        ) {
            Icon(
                imageVector = AppIcons.Remove,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
        }
        Text(
            text = valueText,
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 52.dp),
        )
        IconButton(
            onClick = onIncrease,
            enabled = increaseEnabled,
            modifier = Modifier.size(34.dp),
        ) {
            Icon(
                imageVector = AppIcons.Add,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
