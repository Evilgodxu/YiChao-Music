package com.yichao.evilgodxu.musicpanel

// 播放链路的音频格式与输出参数（USB 独占路由展示）
data class AudioSignalPathFormat(
    val format: String,
    val sampleRate: Int,
    val outputRate: Int,
    val bitDepth: Int,
    val channels: Int,
    val bitrate: Int,
)

// 播放列表来源歌单：key 标识来源，name 为副标题显示名
data class PlaylistSource(
    val key: String,
    val name: String,
)

// 单次播放记录：曲目 ID + 播放时间戳（毫秒）
data class PlayEvent(
    val trackId: Long,
    val timestamp: Long,
)