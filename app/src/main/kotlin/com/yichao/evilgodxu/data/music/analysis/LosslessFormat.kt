package com.yichao.evilgodxu.data.music.analysis

import com.yichao.evilgodxu.data.music.playback.AudioSignalPathFormat

// 无损格式集合：命中的格式已无需再升级
private val LOSSLESS_FORMATS = setOf(
    "FLAC", "WAV", "WAVE", "ALAC", "APE", "AIFF", "AIF", "PCM", "DSD", "DSF", "DFF",
)

// 判定展示格式是否已达到无损
internal fun isLosslessFormat(format: AudioSignalPathFormat): Boolean =
    isLosslessFormatName(format.format.removePrefix("audio/"))

// 按格式名判定是否已达到无损
internal fun isLosslessFormatName(name: String): Boolean {
    val normalized = name.uppercase().trim()
    return normalized in LOSSLESS_FORMATS || normalized.endsWith("LOSSLESS")
}
