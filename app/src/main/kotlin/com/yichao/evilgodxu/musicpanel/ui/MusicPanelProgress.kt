package com.yichao.evilgodxu.musicpanel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun ProgressSection(
    playbackState: MusicPlaybackState,
    contentColor: Color? = null,
    onFormatClick: (() -> Unit)? = null,
) {
    // 进度条与时间文本颜色：默认取主题色，传入 contentColor 时（如首页）覆盖为指定色
    val activeColor = contentColor ?: MaterialTheme.colorScheme.primary
    val dimTextColor = contentColor ?: MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp, bottom = 2.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        TrackFormatInfoSection(
            playbackState = playbackState,
            contentColor = contentColor,
            onClick = onFormatClick,
            modifier = Modifier
                .fillMaxWidth()
                .alpha(0.6f),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = formatTime(playbackState.currentPosition),
                color = dimTextColor,
                fontSize = 9.sp,
                modifier = Modifier.width(24.dp),
                textAlign = TextAlign.Start
            )

            val progress by remember {
                derivedStateOf {
                    if (playbackState.duration > 0) {
                        (playbackState.currentPosition.toFloat() / playbackState.duration).coerceIn(0f, 1f)
                    } else 0f
                }
            }
            var seekFraction by remember { mutableFloatStateOf(progress) }
            var isSeeking by remember { mutableStateOf(false) }
            val displayProgress = if (isSeeking) seekFraction else progress

            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(20.dp)
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                // 消耗进度条上的指针事件，与全局左右滑动互斥，拖动进度条时不触发切换面板
                                event.changes.forEach { if (!it.isConsumed) it.consume() }
                                val pos = event.changes.first().position.x / size.width
                                seekFraction = pos.coerceIn(0f, 1f)
                                isSeeking = true
                                if (event.changes.first().pressed) {
                                    seekTo(playbackState, (seekFraction * playbackState.duration).toLong())
                                    playbackState.setCurrentPosition((seekFraction * playbackState.duration).toLong().coerceIn(0L, playbackState.duration))
                                }
                                if (event.changes.all { !it.pressed }) {
                                    isSeeking = false
                                }
                            }
                        }
                    },
                contentAlignment = Alignment.CenterStart
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(activeColor.copy(alpha = 0.08f), RoundedCornerShape(2.dp))
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth(displayProgress)
                        .height(3.dp)
                        .background(activeColor, RoundedCornerShape(2.dp))
                )
            }
            Text(
                text = formatTime(playbackState.duration),
                color = dimTextColor,
                fontSize = 9.sp,
                modifier = Modifier.width(24.dp),
                textAlign = TextAlign.End
            )
        }
    }
}

// 竖向进度条：复用横向进度条样式（圆角轨道 + 主色填充），不带时间文本
@Composable
internal fun VerticalProgressBar(
    playbackState: MusicPlaybackState,
    modifier: Modifier = Modifier,
    contentColor: Color? = null,
) {
    val activeColor = contentColor ?: MaterialTheme.colorScheme.primary
    val progress by remember {
        derivedStateOf {
            if (playbackState.duration > 0) {
                (playbackState.currentPosition.toFloat() / playbackState.duration).coerceIn(0f, 1f)
            } else 0f
        }
    }
    var seekFraction by remember { mutableFloatStateOf(progress) }
    var isSeeking by remember { mutableStateOf(false) }
    val displayProgress = if (isSeeking) seekFraction else progress

    Box(
        modifier = modifier
            .width(20.dp)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        // 消耗进度条上的指针事件，与全局左右滑动互斥，拖动进度条时不触发左右切换面板
                        event.changes.forEach { if (!it.isConsumed) it.consume() }
                        val pos = event.changes.first().position.y / size.height
                        seekFraction = (1f - pos).coerceIn(0f, 1f)
                        isSeeking = true
                        if (event.changes.first().pressed) {
                            seekTo(playbackState, (seekFraction * playbackState.duration).toLong())
                            playbackState.setCurrentPosition(
                                (seekFraction * playbackState.duration).toLong().coerceIn(0L, playbackState.duration)
                            )
                        }
                        if (event.changes.all { !it.pressed }) {
                            isSeeking = false
                        }
                    }
                }
            },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(3.dp)
                .background(activeColor.copy(alpha = 0.08f), RoundedCornerShape(2.dp))
        )
        Box(
            modifier = Modifier
                .fillMaxHeight(displayProgress)
                .width(3.dp)
                .background(activeColor, RoundedCornerShape(2.dp))
        )
    }
}

// 音频信息条：展示当前曲目格式、位深/采样率与比特率，信息未就绪时留空；
// 传入 onClick 时整条可点击（首页用于触发无损升级）
@Composable
internal fun TrackFormatInfoSection(
    playbackState: MusicPlaybackState,
    modifier: Modifier = Modifier,
    contentColor: Color? = null,
    onClick: (() -> Unit)? = null,
) {
    // 仅展示属于当前曲目的格式信息，避免后台切歌后错配残留
    val format = playbackState.audioSignalPathFormat
        .takeIf { playbackState.audioSignalPathTrackId == playbackState.currentTrack?.id }
    val text = format?.let { formatDisplayLabel(it) }
    // 信息未就绪时渲染空文本，占位保持单行高度，避免底部控制栏随信息条显隐而跳变
    Text(
        text = text.orEmpty(),
        color = contentColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 10.sp,
        maxLines = 1,
        textAlign = TextAlign.Center,
        modifier = modifier.clickable(enabled = onClick != null) { onClick?.invoke() },
    )
}

// 格式信息展示文本：格式 · 位深/采样率 · 比特率；无有效信息时返回 null
internal fun formatDisplayLabel(format: AudioSignalPathFormat): String? {
    if (format.sampleRate <= 0 && format.bitrate <= 0) return null
    val formatName = format.format.removePrefix("audio/")
    val bitRate = if (format.sampleRate > 0) {
        "${format.bitDepth}bit/${formatKhz(format.sampleRate)}kHz"
    } else "${format.bitDepth}bit"
    val bitrate = format.bitrate.takeIf { it > 0 }?.let { "${it}kbps" }
    return listOfNotNull(formatName, bitRate, bitrate).joinToString(" · ")
}

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

// 当前曲目是否触发无损升级：展示格式低于无损且该曲目可升级
internal fun currentTrackNeedsLosslessUpgrade(playbackState: MusicPlaybackState): Boolean {
    val format = playbackState.audioSignalPathFormat
        .takeIf { playbackState.audioSignalPathTrackId == playbackState.currentTrack?.id }
        ?: return false
    if (isLosslessFormat(format)) return false
    return playbackState.currentTrack?.isUpgradableToLossless() == true
}

// 采样率转 kHz 文本：整数值不带小数点，非整数值保留一位小数
private fun formatKhz(rate: Int): String {
    val khz = rate / 1000.0
    return String.format(java.util.Locale.US, "%.1f", khz).trimEnd('0').trimEnd('.')
}
