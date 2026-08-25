package com.yichao.evilgodxu.screens.settings

import com.yichao.evilgodxu.data.settings.AppLanguage
import com.yichao.evilgodxu.data.settings.ThemeMode

data class SettingsUiState(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val language: AppLanguage = AppLanguage.SYSTEM,
    val miniPlayerEnabled: Boolean = false,
    // 迷你播放器开关已打开但悬浮窗权限尚未授予，正在等待用户从系统设置页返回
    val overlayPermissionPending: Boolean = false,
    val wordByWordRendering: Boolean = true,
    val swipeToChangeTrack: Boolean = true,
    val version: String = "",
)
