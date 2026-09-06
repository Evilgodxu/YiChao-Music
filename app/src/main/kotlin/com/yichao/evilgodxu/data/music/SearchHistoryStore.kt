package com.yichao.evilgodxu.data.music

import android.content.Context

// 搜索历史存储：SharedPreferences 换行分隔的关键词列表
object SearchHistoryStore {
    private const val PREFERENCES = "music_search_history_preferences"
    private const val KEY = "music_search_history"

    fun load(context: Context): List<String> =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(KEY, "")
            ?.split("\n")
            ?.filter(String::isNotBlank)
            .orEmpty()

    fun save(context: Context, history: List<String>) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, history.joinToString("\n"))
            .commit()
    }
}
