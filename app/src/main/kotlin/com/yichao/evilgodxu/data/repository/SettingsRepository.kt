package com.yichao.evilgodxu.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import com.yichao.evilgodxu.data.settings.AppLanguage
import com.yichao.evilgodxu.data.settings.settingsDataStore
import com.yichao.evilgodxu.data.settings.SettingsKeys
import com.yichao.evilgodxu.data.settings.SettingsState
import com.yichao.evilgodxu.data.settings.ThemeMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// 设置仓库：数据层入口，封装 DataStore 数据源
class SettingsRepository(private val context: Context) {

    // 设置状态流：主题模式驱动全局配色
    val settings: Flow<SettingsState> = context.settingsDataStore.data.map { preferences ->
        SettingsState(
            themeMode = ThemeMode.fromValue(preferences[SettingsKeys.THEME_MODE] ?: ThemeMode.SYSTEM.value),
        )
    }

    // 应用语言流：统一读 DataStore
    val appLanguage: Flow<AppLanguage> = context.settingsDataStore.data.map { preferences ->
        AppLanguage.entries.find { it.languageTag == preferences[SettingsKeys.LANGUAGE] } ?: AppLanguage.SYSTEM
    }

    suspend fun saveThemeMode(mode: ThemeMode) {
        context.settingsDataStore.edit { preferences ->
            preferences[SettingsKeys.THEME_MODE] = mode.value
        }
    }

    // 读取当前应用语言
    suspend fun getAppLanguage(): AppLanguage {
        return context.settingsDataStore.data.first().let { preferences ->
            AppLanguage.entries.find { it.languageTag == preferences[SettingsKeys.LANGUAGE] } ?: AppLanguage.SYSTEM
        }
    }

    // 设置应用语言：统一写入 DataStore，由 Compose 层驱动热切换
    suspend fun setAppLanguage(language: AppLanguage) {
        context.settingsDataStore.edit { preferences ->
            preferences[SettingsKeys.LANGUAGE] = language.languageTag.orEmpty()
        }
    }
}
