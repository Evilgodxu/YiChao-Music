package com.yichao.evilgodxu.screens.home.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

// 自定义歌单存储：JSON 持久化于 SharedPreferences，内存态驱动 Compose 重组
object PlaylistStore {
    private const val PREFS = "music_playlists_preferences"
    private const val KEY = "playlists"
    private var loaded = false

    var playlists by mutableStateOf<List<Playlist>>(emptyList())

    // 首次访问时读取持久化数据，避免重复加载
    fun ensureLoaded(context: Context) {
        if (loaded) return
        loaded = true
        playlists = readPlaylists(context)
    }

    // 新建空歌单，名称空白时返回 null
    fun create(context: Context, name: String): Playlist? {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return null
        val playlist = Playlist(
            id = System.currentTimeMillis(),
            name = trimmed,
            trackIds = emptyList(),
            createdAt = System.currentTimeMillis(),
        )
        playlists = playlists + playlist
        persist(context)
        return playlist
    }

    fun rename(context: Context, id: Long, name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        playlists = playlists.map { if (it.id == id) it.copy(name = trimmed) else it }
        persist(context)
    }

    fun delete(context: Context, id: Long) {
        playlists = playlists.filterNot { it.id == id }
        persist(context)
    }

    // 批量加入曲目并去重
    fun addTracks(context: Context, id: Long, trackIds: List<Long>) {
        if (trackIds.isEmpty()) return
        playlists = playlists.map { playlist ->
            if (playlist.id == id) playlist.copy(trackIds = (playlist.trackIds + trackIds).distinct()) else playlist
        }
        persist(context)
    }

    fun removeTracks(context: Context, id: Long, trackIds: List<Long>) {
        if (trackIds.isEmpty()) return
        val removed = trackIds.toSet()
        playlists = playlists.map { playlist ->
            if (playlist.id == id) playlist.copy(trackIds = playlist.trackIds.filterNot { it in removed }) else playlist
        }
        persist(context)
    }

    // 按新顺序重排歌单曲目并持久化
    fun setTrackOrder(context: Context, id: Long, orderedIds: List<Long>) {
        playlists = playlists.map { playlist ->
            if (playlist.id == id) playlist.copy(trackIds = orderedIds.distinct()) else playlist
        }
        persist(context)
    }

    private fun persist(context: Context) {
        val array = JSONArray()
        playlists.forEach { playlist ->
            array.put(JSONObject().apply {
                put("id", playlist.id)
                put("name", playlist.name)
                put("createdAt", playlist.createdAt)
                put("trackIds", JSONArray(playlist.trackIds.toTypedArray()))
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, array.toString())
            .apply()
    }

    private fun readPlaylists(context: Context): List<Playlist> {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            List(array.length()) { index ->
                val item = array.getJSONObject(index)
                val ids = item.optJSONArray("trackIds")
                Playlist(
                    id = item.getLong("id"),
                    name = item.getString("name"),
                    trackIds = ids?.let { array -> List(array.length()) { i -> array.getLong(i) } }.orEmpty(),
                    createdAt = item.optLong("createdAt", 0L),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
