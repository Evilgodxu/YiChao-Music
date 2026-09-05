package com.yichao.evilgodxu.screens.home.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

// 自定义歌单存储：JSON 持久化于 SharedPreferences，内存态驱动 Compose 重组
object PlaylistStore {
    private const val PREFS = "music_playlists_preferences"
    private const val KEY = "playlists"
    // 首次加载标记：多线程首次访问时防止重复加载或以空列表覆盖已持久化数据
    @Volatile
    private var loaded = false
    // 歌单 id 生成：时间戳高 44 位 + 进程内自增低 20 位，避免同毫秒快速创建碰撞
    private val idCounter = AtomicLong(0)
    // 持久化串行执行，避免并发写乱序覆盖
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val persistMutex = Mutex()

    var playlists by mutableStateOf<List<Playlist>>(emptyList())

    // 首次访问时读取持久化数据，避免重复加载
    fun ensureLoaded(context: Context) {
        if (loaded) return
        loaded = true
        playlists = readPlaylists(context)
    }

    // 新建空歌单，名称空白时返回 null
    fun create(context: Context, name: String): Playlist? {
        // 写前先确保已加载，避免以空列表覆盖已持久化歌单
        ensureLoaded(context)
        val trimmed = name.trim()
        if (trimmed.isBlank()) return null
        val now = System.currentTimeMillis()
        val playlist = Playlist(
            id = (now shl 20) or (idCounter.incrementAndGet() and 0xFFFFF),
            name = trimmed,
            trackIds = emptyList(),
            createdAt = now,
        )
        playlists = playlists + playlist
        persist(context)
        return playlist
    }

    fun rename(context: Context, id: Long, name: String) {
        ensureLoaded(context)
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        playlists = playlists.map { if (it.id == id) it.copy(name = trimmed) else it }
        persist(context)
    }

    fun delete(context: Context, id: Long) {
        ensureLoaded(context)
        playlists = playlists.filterNot { it.id == id }
        persist(context)
    }

    // 批量加入曲目并去重
    fun addTracks(context: Context, id: Long, trackIds: List<Long>) {
        ensureLoaded(context)
        if (trackIds.isEmpty()) return
        playlists = playlists.map { playlist ->
            if (playlist.id == id) playlist.copy(trackIds = (playlist.trackIds + trackIds).distinct()) else playlist
        }
        persist(context)
    }

    fun removeTracks(context: Context, id: Long, trackIds: List<Long>) {
        ensureLoaded(context)
        if (trackIds.isEmpty()) return
        val removed = trackIds.toSet()
        playlists = playlists.map { playlist ->
            if (playlist.id == id) playlist.copy(trackIds = playlist.trackIds.filterNot { it in removed }) else playlist
        }
        persist(context)
    }

    // 歌曲被彻底删除后，从所有自定义歌单中清除残留引用
    fun removeTrackFromAll(context: Context, trackId: Long) {
        ensureLoaded(context)
        playlists = playlists.map { playlist ->
            if (trackId in playlist.trackIds) playlist.copy(trackIds = playlist.trackIds.filterNot { it == trackId }) else playlist
        }
        persist(context)
    }

    // 按新顺序重排歌单曲目并持久化
    fun setTrackOrder(context: Context, id: Long, orderedIds: List<Long>) {
        ensureLoaded(context)
        playlists = playlists.map { playlist ->
            if (playlist.id == id) playlist.copy(trackIds = orderedIds.distinct()) else playlist
        }
        persist(context)
    }

    private fun persist(context: Context) {
        // 快照当前状态，序列化与写盘移出主线程并串行执行
        val snapshot = playlists
        ioScope.launch {
            persistMutex.withLock {
                val array = JSONArray()
                snapshot.forEach { playlist ->
                    array.put(JSONObject().apply {
                        put("id", playlist.id)
                        put("name", playlist.name)
                        put("createdAt", playlist.createdAt)
                        put("trackIds", JSONArray(playlist.trackIds.toTypedArray()))
                    })
                }
                // 同步写盘：自定义歌单为用户关键数据，apply 异步落盘存在进程被杀丢失窗口
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY, array.toString())
                    .commit()
            }
        }
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
