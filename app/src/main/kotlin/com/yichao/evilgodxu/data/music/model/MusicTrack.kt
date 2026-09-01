package com.yichao.evilgodxu.data.music.model

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
    // 歌词时间轴偏移（毫秒），用于微调持久化
    val lyricOffsetMs: Long = 0L,
    // 封面自动匹配已尝试且失败：直接显示占位符，不再重复匹配
    val coverFailed: Boolean = false,
    // 歌词自动匹配已尝试且失败：不再重复拉取
    val lyricFailed: Boolean = false,
    // 是否由在线播放产生（含已缓存为本地文件）；仅当前播放时保留，切歌后自动清理
    val isOnlinePlay: Boolean = false,
) {
    // 是否为可读取本地音频源：本地文件路径或 MediaStore 本地文件 URI
    // 本地源的内嵌封面由后台提取，提取完成前不直接回退在线封面
    val isLocalAudioSource: Boolean
        get() = path.isNotBlank() || audioUri.startsWith("content:") || audioUri.startsWith("file:")

    // 是否可升级为无损：仅本地音频源可被匹配并替换为无损版本
    fun isUpgradableToLossless(): Boolean = isLocalAudioSource
}

// 播放模式
enum class PlayMode { RepeatOne, RepeatAll, Shuffle }
