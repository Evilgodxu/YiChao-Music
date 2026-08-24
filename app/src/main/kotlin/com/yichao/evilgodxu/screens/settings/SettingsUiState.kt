package com.yichao.evilgodxu.screens.settings

import com.yichao.evilgodxu.data.settings.AppLanguage
import com.yichao.evilgodxu.data.settings.ThemeMode

data class SettingsUiState(
    val themeMode: ThemeMode = ThemeMode.DARK,
    val language: AppLanguage = AppLanguage.SYSTEM,
    val miniPlayerEnabled: Boolean = false,
    val wordByWordRendering: Boolean = true,
    val swipeToChangeTrack: Boolean = true,
    val version: String = "",
)
