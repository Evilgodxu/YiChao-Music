package com.yichao.evilgodxu.data.music

import android.content.Context
import com.yichao.evilgodxu.data.music.metadata.MusicMetadataCache
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.domain.music.PlaylistSource
import com.yichao.evilgodxu.log.CrashLogManager
import org.json.JSONArray
import org.json.JSONObject

// 播放列表缓存存储：SharedPreferences JSON，保存当前播放列表、来源歌单与默认库备份
object PlaylistCacheStore {
    private const val PREFERENCES = "music_playlist_cache_preferences"
    private const val KEY_PLAYLIST = "music_playlist_cache"
    private const val KEY_SOURCE = "music_playlist_source_key"
    private const val KEY_SOURCE_NAME = "music_playlist_source_name"
    private const val KEY_BACKUP = "music_default_playlist_cache"

    fun loadPlaylist(context: Context): List<MusicTrack> =
        loadTrackList(context, KEY_PLAYLIST)

    fun loadBackup(context: Context): List<MusicTrack> =
        loadTrackList(context, KEY_BACKUP)

    fun loadSource(context: Context): PlaylistSource? {
        val prefs = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        return prefs.getString(KEY_SOURCE, null)?.let { key ->
            PlaylistSource(key, prefs.getString(KEY_SOURCE_NAME, "") ?: "")
        }
    }

    // 同步写盘：播放列表缓存为用户关键数据，apply 异步落盘存在进程被杀丢失窗口
    fun save(
        context: Context,
        playlist: List<MusicTrack>,
        source: PlaylistSource?,
        backup: List<MusicTrack>?,
    ) {
        val editor = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
        writeTrackList(editor, KEY_PLAYLIST, playlist)
        editor.putString(KEY_SOURCE, source?.key)
        editor.putString(KEY_SOURCE_NAME, source?.name)
        if (backup != null) {
            writeTrackList(editor, KEY_BACKUP, backup)
        } else {
            editor.remove(KEY_BACKUP)
        }
        editor.commit()
    }

    private fun loadTrackList(context: Context, key: String): List<MusicTrack> {
        val json = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(key, null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            List(array.length()) { index ->
                val item = array.getJSONObject(index)
                val savedLyricPath = item.optString("lyricCachePath", "")
                val lyricOffset = item.optLong("lyricOffsetMs", 0L)
                // 歌词内容延迟到显示时按需从缓存文件读取（含偏移），
                // 冷启动不逐首解析歌词，避免大歌单的数百次文件读取拖慢所有界面首帧
                val lyricCachePath = savedLyricPath.takeIf { MusicMetadataCache.isValid(it) }.orEmpty()
                MusicTrack(
                    id = item.getLong("id"),
                    path = item.getString("path"),
                    audioUri = item.getString("audioUri"),
                    title = item.getString("title"),
                    artist = item.getString("artist"),
                    duration = item.getLong("duration"),
                    albumId = item.getLong("albumId"),
                    albumName = item.optString("albumName", ""),
                    neteaseId = item.optLong("neteaseId", 0L),
                    neteaseCoverUrl = item.optString("neteaseCoverUrl", ""),
                    coverCachePath = item.optString("coverCachePath", ""),
                    isFavorite = item.optBoolean("isFavorite", false),
                    isOnlinePlay = item.optBoolean("isOnlinePlay", false),
                    lyricCachePath = lyricCachePath,
                    lyricLines = emptyList(),
                    lyricOffsetMs = lyricOffset,
                    coverFailed = item.optBoolean("coverFailed", false),
                    lyricFailed = item.optBoolean("lyricFailed", false),
                )
            }
        } catch (e: Exception) {
            CrashLogManager.logException("PlaylistCacheStore", "读取缓存的播放列表失败", e)
            emptyList()
        }
    }

    private fun writeTrackList(
        editor: android.content.SharedPreferences.Editor,
        key: String,
        tracks: List<MusicTrack>,
    ) {
        val array = JSONArray()
        tracks.forEach { track ->
            array.put(JSONObject().apply {
                put("id", track.id)
                put("path", track.path)
                put("audioUri", track.audioUri)
                put("title", track.title)
                put("artist", track.artist)
                put("duration", track.duration)
                put("albumId", track.albumId)
                put("albumName", track.albumName)
                put("neteaseId", track.neteaseId)
                put("neteaseCoverUrl", track.neteaseCoverUrl)
                put("coverCachePath", track.coverCachePath)
                put("lyricCachePath", track.lyricCachePath)
                put("isOnlinePlay", track.isOnlinePlay)
                put("isFavorite", track.isFavorite)
                put("lyricOffsetMs", track.lyricOffsetMs)
                put("coverFailed", track.coverFailed)
                put("lyricFailed", track.lyricFailed)
            })
        }
        editor.putString(key, array.toString())
    }
}
