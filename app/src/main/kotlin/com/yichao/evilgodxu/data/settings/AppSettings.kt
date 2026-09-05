package com.yichao.evilgodxu.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.yichao.evilgodxu.log.CrashLogManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// 设置 DataStore：文件损坏时由损坏处理器重置为空配置并记录日志，
// 避免半个损坏文件导致主题/语言等全部用户偏好整体失效
val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "settings",
    corruptionHandler = ReplaceFileCorruptionHandler<Preferences> { throwable ->
        CrashLogManager.logException("SettingsDataStore", "设置数据损坏，已重置为默认值", throwable)
        emptyPreferences()
    },
)

object SettingsKeys {
    val THEME_MODE = stringPreferencesKey("theme_mode")
    // 语言统一落 DataStore，由 Compose 层驱动热切换
    val LANGUAGE = stringPreferencesKey("language")
}

// 应用主题模式
enum class ThemeMode(val value: String) {
    SYSTEM("system"),
    DARK("dark"),
    LIGHT("light");

    companion object {
        fun fromValue(value: String): ThemeMode = entries.find { it.value == value } ?: SYSTEM
    }
}

// 应用语言：中文/英文/跟随系统
enum class AppLanguage(val languageTag: String?) {
    SYSTEM(null),
    CHINESE("zh"),
    ENGLISH("en");
}

data class SettingsState(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
)

// 设置状态流：悬浮窗等非 Compose 宿主读取主题模式
fun Context.settingsFlow(): Flow<SettingsState> =
    settingsDataStore.data.map { preferences ->
        SettingsState(
            themeMode = ThemeMode.fromValue(preferences[SettingsKeys.THEME_MODE] ?: ThemeMode.SYSTEM.value),
        )
    }
