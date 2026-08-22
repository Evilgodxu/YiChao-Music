package com.yichao.evilgodxu.musicpanel

// 本地音乐轨道
 data class MusicTrack(
    val id: Long,
    val path: String,
    val audioUri: String,
    val title: String,
    val artist: String,
    val duration: Long,
    val albumId: Long,
    val albumName: String = "",
    val neteaseId: Long = 0L,
    val neteaseCoverUrl: String = "",
    val coverCachePath: String = "",
    val lyricCachePath: String = "",
    internal val lyricLines: List<LyricLine> = emptyList(),
    val isFavorite: Boolean = false,
    // 歌词时间轴偏移(毫秒),用于微调持久化
    val lyricOffsetMs: Long = 0L,
    // 是否由在线播放产生（含已缓存为本地文件）；仅当前播放时保留，切歌后自动清理
    val isOnlinePlay: Boolean = false,
)

// 播放模式
enum class PlayMode { RepeatOne, RepeatAll, Shuffle }