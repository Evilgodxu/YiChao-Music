package com.yichao.evilgodxu.screens.settings

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yichao.evilgodxu.data.permission.OverlayGrantMonitor
import com.yichao.evilgodxu.data.repository.SettingsRepository
import com.yichao.evilgodxu.data.settings.AppLanguage
import com.yichao.evilgodxu.data.settings.ThemeMode
import com.yichao.evilgodxu.musicpanel.miniPlayerEnabledFlow
import com.yichao.evilgodxu.musicpanel.saveMiniPlayerEnabled
import com.yichao.evilgodxu.musicpanel.saveWordByWordRendering
import com.yichao.evilgodxu.musicpanel.wordByWordRenderingFlow
import com.yichao.evilgodxu.musicpanel.saveSwipeToChangeTrack
import com.yichao.evilgodxu.musicpanel.swipeToChangeTrackFlow
import com.yichao.evilgodxu.utils.localization.LocalizationManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class SettingsViewModel(
    application: Application,
    private val settingsRepository: SettingsRepository,
) : AndroidViewModel(application), KoinComponent {

    private val context get() = getApplication<Application>()
    private val localizationManager: LocalizationManager by inject()

    private val _uiState = MutableStateFlow(
        SettingsUiState(version = getVersion()),
    )
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                _uiState.update { it.copy(themeMode = settings.themeMode) }
            }
        }
        viewModelScope.launch {
            _uiState.update { it.copy(language = settingsRepository.getAppLanguage()) }
        }
        viewModelScope.launch {
            context.miniPlayerEnabledFlow().collect { enabled ->
                _uiState.update { it.copy(miniPlayerEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            context.wordByWordRenderingFlow().collect { enabled ->
                _uiState.update { it.copy(wordByWordRendering = enabled) }
            }
        }
        viewModelScope.launch {
            context.swipeToChangeTrackFlow().collect { enabled ->
                _uiState.update { it.copy(swipeToChangeTrack = enabled) }
            }
        }
    }

    fun setMiniPlayerEnabled(enabled: Boolean) {
        _uiState.update { it.copy(miniPlayerEnabled = enabled) }
        viewModelScope.launch {
            context.saveMiniPlayerEnabled(enabled)
            if (enabled && !Settings.canDrawOverlays(context)) {
                // 置为待授权状态：设置页 UI 通过 ActivityResultLauncher 打开系统悬浮窗设置页，
                // 返回时（含 Activity 重建、进程回收后的结果重投递）统一由 onOverlaySettingsReturned 对账
                _uiState.update { it.copy(overlayPermissionPending = true) }
                startOverlayGrantMonitor()
            } else if (!enabled) {
                // 主动关闭开关时结束监控，避免无意义轮询
                OverlayGrantMonitor.stop()
                _uiState.update { it.copy(overlayPermissionPending = false) }
            }
        }
    }

    // 用户从系统悬浮窗设置页返回（ActivityResultLauncher 回调）
    fun onOverlaySettingsReturned() {
        _uiState.update { it.copy(overlayPermissionPending = false) }
        if (!Settings.canDrawOverlays(context)) {
            // 未授予：停止监控并回滚开关，避免界面状态与真实权限不一致
            OverlayGrantMonitor.stop()
            viewModelScope.launch {
                context.saveMiniPlayerEnabled(false)
                _uiState.update { it.copy(miniPlayerEnabled = false) }
            }
        }
    }

    // 应用级监控悬浮窗授权：授权后尝试自动将应用带回前台。
    // 监控生命周期独立于 Activity/ViewModel；若系统（如部分 ROM 的后台弹出限制）拦截后台拉起，
    // 用户手动返回时仍会走 onOverlaySettingsReturned 完成状态对账。
    private fun startOverlayGrantMonitor() {
        OverlayGrantMonitor.start(context) { appContext ->
            bringAppToFront(appContext)
        }
    }

    fun setWordByWordRendering(enabled: Boolean) {
        _uiState.update { it.copy(wordByWordRendering = enabled) }
        viewModelScope.launch {
            context.saveWordByWordRendering(enabled)
        }
    }

    fun setSwipeToChangeTrack(enabled: Boolean) {
        _uiState.update { it.copy(swipeToChangeTrack = enabled) }
        viewModelScope.launch {
            context.saveSwipeToChangeTrack(enabled)
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            settingsRepository.saveThemeMode(mode)
        }
    }

    fun setLanguage(language: AppLanguage) {
        _uiState.update { it.copy(language = language) }
        localizationManager.applyAppLocale(localizationManager.resolveLanguage(language))
        viewModelScope.launch {
            settingsRepository.setAppLanguage(language)
        }
    }

    private fun getVersion(): String {
        return context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }
}

// 将应用带回前台，使用户无需手动返回本应用
private fun bringAppToFront(context: Context) {
    val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
    launchIntent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or
            Intent.FLAG_ACTIVITY_SINGLE_TOP or
            Intent.FLAG_ACTIVITY_NEW_TASK
    context.startActivity(launchIntent)
}