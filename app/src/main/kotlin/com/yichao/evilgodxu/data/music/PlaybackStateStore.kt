package com.yichao.evilgodxu.data.music

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.yichao.evilgodxu.data.settings.settingsDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

// 播放状态持久化快照：冷启动恢复当前曲目、进度、播放模式与速度
data class PlaybackSnapshot(
    val audioUri: String?,
    val position: Long,
    val mode: Int,
    val speed: Float,
)

// 播放状态存储：DataStore 键值，播放核心与恢复流程统一经此读写
object PlaybackStateStore {
    private val savedUriKey = stringPreferencesKey("music_saved_uri")
    private val savedPositionKey = longPreferencesKey("music_saved_position")
    private val savedModeKey = intPreferencesKey("music_saved_mode")
    private val savedSpeedKey = floatPreferencesKey("music_saved_speed")

    // 读取持久化播放状态：缺失字段回退默认值，由调用方按需应用
    suspend fun load(context: Context): PlaybackSnapshot = withContext(Dispatchers.IO) {
        val preferences = context.settingsDataStore.data.first()
        PlaybackSnapshot(
            audioUri = preferences[savedUriKey],
            position = preferences[savedPositionKey] ?: 0L,
            mode = preferences[savedModeKey] ?: 0,
            speed = preferences[savedSpeedKey] ?: 1f,
        )
    }

    // 保存曲目/进度/播放模式（速度独立写入 saveSpeed）
    suspend fun save(context: Context, snapshot: PlaybackSnapshot) = withContext(Dispatchers.IO) {
        context.settingsDataStore.edit { preferences ->
            snapshot.audioUri?.let { preferences[savedUriKey] = it }
            preferences[savedPositionKey] = snapshot.position
            preferences[savedModeKey] = snapshot.mode
        }
    }

    suspend fun saveSpeed(context: Context, speed: Float) = withContext(Dispatchers.IO) {
        context.settingsDataStore.edit { preferences ->
            preferences[savedSpeedKey] = speed
        }
    }

    // 清除曲目与进度（保留模式/速度），用于曲目被移除时的状态清理
    suspend fun clearSaved(context: Context) = withContext(Dispatchers.IO) {
        context.settingsDataStore.edit { preferences ->
            preferences.remove(savedUriKey)
            preferences.remove(savedPositionKey)
        }
    }

    // 仅清除播放位置（定时关闭收尾使用，保留歌曲 URI）
    suspend fun clearPosition(context: Context) = withContext(Dispatchers.IO) {
        context.settingsDataStore.edit { preferences ->
            preferences.remove(savedPositionKey)
        }
    }
}
