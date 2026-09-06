package com.yichao.evilgodxu.data.music

import android.content.Context
import com.yichao.evilgodxu.domain.music.PlayEvent

// 常听播放记录存储：SharedPreferences 逗号分隔的「曲目ID:时间戳」记录
object RecentPlayedStore {
    private const val PREFERENCES = "music_recent_played_preferences"
    private const val KEY = "music_recent_played_events"

    fun load(context: Context): List<PlayEvent> {
        val raw = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(KEY, "") ?: ""
        return raw.split(",").mapNotNull { token ->
            val idx = token.lastIndexOf(':')
            if (idx <= 0) return@mapNotNull null
            val id = token.substring(0, idx).toLongOrNull() ?: return@mapNotNull null
            val ts = token.substring(idx + 1).toLongOrNull() ?: return@mapNotNull null
            PlayEvent(id, ts)
        }
    }

    // 同步写盘：播放记录为统计依据，apply 异步落盘在进程被杀时可能丢失近几次记录
    fun save(context: Context, events: List<PlayEvent>) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, events.joinToString(",") { "${it.trackId}:${it.timestamp}" })
            .commit()
    }
}
