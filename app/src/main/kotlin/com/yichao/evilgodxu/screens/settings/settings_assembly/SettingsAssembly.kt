package com.yichao.evilgodxu.screens.settings.settings_assembly

import android.os.SystemClock
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.data.settings.AppLanguage
import com.yichao.evilgodxu.data.settings.ThemeMode
import com.yichao.evilgodxu.screens.settings.SettingsUiState
import com.yichao.evilgodxu.screens.settings.dialog.LanguageSelectionDialog
import com.yichao.evilgodxu.screens.settings.dialog.ThemeSelectionDialog
import com.yichao.evilgodxu.screens.settings.settings_assembly.app_info_area.AppInfoArea
import com.yichao.evilgodxu.screens.settings.settings_assembly.appearance_area.AppearanceArea
import com.yichao.evilgodxu.screens.settings.settings_assembly.language_area.LanguageArea
import com.yichao.evilgodxu.screens.settings.settings_assembly.player_area.PlayerArea

// 设置页分区组装器：编排外观、语言与关于分区
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsAssembly(
    uiState: SettingsUiState,
    onBack: () -> Unit,
    onThemeSelected: (ThemeMode) -> Unit,
    onLanguageSelected: (AppLanguage) -> Unit,
    onThemeClick: (Offset) -> Unit,
    onMiniPlayerEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showThemeDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var pendingLanguage by remember { mutableStateOf<AppLanguage?>(null) }
    var pendingThemeClickPosition by remember { mutableStateOf(Offset.Zero) }
    // 返回按钮防抖，避免快速连点重复出栈导致崩溃
    var lastBackClickAt by remember { mutableLongStateOf(0L) }

    // 先关闭对话框，下一帧再切语言，避免切换瞬间闪现旧语言
    LaunchedEffect(pendingLanguage) {
        val language = pendingLanguage ?: return@LaunchedEffect
        pendingLanguage = null
        onLanguageSelected(language)
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = {
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastBackClickAt > 400L) {
                            lastBackClickAt = now
                            onBack()
                        }
                    }) {
                        Icon(painterResource(R.drawable.ic_chevron_left), stringResource(R.string.back))
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
            AppearanceArea(
                themeMode = uiState.themeMode,
                onThemeClick = { position ->
                    pendingThemeClickPosition = position
                    showThemeDialog = true
                },
            )
            LanguageArea(uiState.language, onLanguageSelected, onShowDialog = { showLanguageDialog = true })
            PlayerArea(
                miniPlayerEnabled = uiState.miniPlayerEnabled,
                onMiniPlayerEnabledChange = onMiniPlayerEnabledChange,
            )
            AppInfoArea(uiState.version)
        }
    }

    if (showThemeDialog) {
        ThemeSelectionDialog(
            currentTheme = uiState.themeMode,
            onDismiss = { showThemeDialog = false },
            onThemeSelected = { mode ->
                onThemeClick(pendingThemeClickPosition)
                onThemeSelected(mode)
                showThemeDialog = false
            },
        )
    }

    if (showLanguageDialog) {
        LanguageSelectionDialog(
            currentLanguage = uiState.language,
            onDismiss = { showLanguageDialog = false },
            onLanguageSelected = { language ->
                showLanguageDialog = false
                pendingLanguage = language
            },
        )
    }
}
