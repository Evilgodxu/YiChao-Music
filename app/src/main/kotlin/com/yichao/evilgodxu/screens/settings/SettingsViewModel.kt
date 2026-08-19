package com.yichao.evilgodxu.screens.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yichao.evilgodxu.data.repository.SettingsRepository
import com.yichao.evilgodxu.data.settings.AppLanguage
import com.yichao.evilgodxu.data.settings.ThemeMode
import com.yichao.evilgodxu.musicpanel.miniPlayerEnabledFlow
import com.yichao.evilgodxu.musicpanel.saveMiniPlayerEnabled
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
    }

    fun setMiniPlayerEnabled(enabled: Boolean) {
        _uiState.update { it.copy(miniPlayerEnabled = enabled) }
        viewModelScope.launch {
            context.saveMiniPlayerEnabled(enabled)
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