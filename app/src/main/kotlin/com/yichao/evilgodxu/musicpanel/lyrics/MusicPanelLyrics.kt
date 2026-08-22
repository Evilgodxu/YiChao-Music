package com.yichao.evilgodxu.musicpanel

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit
import com.yichao.evilgodxu.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
internal fun LyricsPanel(
    playbackState: MusicPlaybackState,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    fontSize: TextUnit = 12.sp,
    contentColor: Color? = null,
    visibleLines: Int = DEFAULT_VISIBLE_LINES,
) {
    // 已唱 / 未唱歌词颜色：默认取主题色，传入 contentColor 时（如首页）覆盖为指定色
    val activeColor = contentColor ?: MaterialTheme.colorScheme.primary
    val pendingColor = (contentColor ?: MaterialTheme.colorScheme.onSurfaceVariant).copy(alpha = 0.72f)
    var lyricPosition by remember { mutableLongStateOf(playbackState.currentPosition) }
    LaunchedEffect(playbackState.isPlaying, playbackState.currentTrack?.id) {
        var lastSyncMs = 0L
        while (isActive) {
            val candidate = playbackState.mediaController?.currentPosition
                ?.takeIf { it >= 0L }
                ?: playbackState.currentPosition
            if (playbackState.isPlaying) {
                val now = System.currentTimeMillis()
                val elapsed = if (lastSyncMs == 0L) 0L else (now - lastSyncMs).coerceAtLeast(0L)
                // 播放中以真实流逝时间推进，控制器位置仅作锚点：熄屏唤醒后控制器
                // 位置可能停滞，本地位置仍持续前进避免冻结；大幅回退视为手动拖动
                lyricPosition = when {
                    candidate >= lyricPosition -> candidate
                    lyricPosition - candidate > LYRIC_SEEK_TOLERANCE_MS -> candidate
                    else -> lyricPosition + elapsed
                }
                lastSyncMs = now
            } else {
                lyricPosition = candidate
                lastSyncMs = 0L
            }
            delay(if (playbackState.isPlaying) 50L else 200L)
        }
    }

    val lines = playbackState.currentTrack?.lyricLines.orEmpty()
    val activeIndex = lines.indexOfLast { it.timeMs <= lyricPosition }.coerceAtLeast(0)
    // 当前行居中，上下各显示 (total-1)/2 行（total 为奇数）
    val offset = visibleLines / 2

    Box(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(top = 4.dp, bottom = 0.dp)
            .verticalFadeMask(),
        contentAlignment = Alignment.Center
    ) {
        if (lines.isEmpty()) {
            Text(stringResource(R.string.music_panel_no_lyrics), color = contentColor ?: MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        } else {
            AnimatedContent(
                targetState = activeIndex,
                transitionSpec = {
                    val movingForward = targetState > initialState
                    val distance = { height: Int -> (height / 4).coerceAtLeast(1) }
                    if (movingForward) {
                        (slideInVertically(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) { it }
                            + fadeIn(animationSpec = tween(180))) togetherWith
                            (slideOutVertically(animationSpec = tween(260)) { -distance(it) }
                                + fadeOut(animationSpec = tween(180)))
                    } else {
                        (slideInVertically(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) { -distance(it) }
                            + fadeIn(animationSpec = tween(180))) togetherWith
                            (slideOutVertically(animationSpec = tween(260)) { it }
                                + fadeOut(animationSpec = tween(180)))
                    }
                },
                label = "lyric_column_scroll"
            ) { renderedActiveIndex ->
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    repeat(visibleLines) { row ->
                        val index = renderedActiveIndex - offset + row
                        val line = lines.getOrNull(index)
                        if (line == null) {
                            LyricSpacer()
                            return@repeat
                        }
                        val isCurrent = index == activeIndex
                        val emphasis by animateFloatAsState(
                            targetValue = if (isCurrent) 1f else 0f,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioNoBouncy,
                                stiffness = Spring.StiffnessMediumLow
                            ),
                            label = "lyric_emphasis"
                        )
                        val scale = 0.98f + 0.16f * emphasis
                        val nextTimeMs = lines.getOrNull(index + 1)?.timeMs ?: line.timeMs + 3000L
                        LyricText(
                            line = line,
                            nextTimeMs = nextTimeMs,
                            positionMs = lyricPosition,
                            isCurrent = isCurrent,
                            fontSize = fontSize,
                            fontWeight = if (isCurrent) FontWeight.Medium else FontWeight.Normal,
                            activeColor = activeColor,
                            pendingColor = pendingColor,
                            modifier = Modifier
                                .fillMaxWidth()
                                .graphicsLayer {
                                    scaleX = scale
                                    scaleY = scale
                                }
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun LyricSpacer() {
    Spacer(modifier = Modifier.height(18.dp))
}

@Composable
internal fun LyricText(
    line: LyricLine,
    nextTimeMs: Long,
    positionMs: Long,
    isCurrent: Boolean,
    fontSize: TextUnit,
    fontWeight: FontWeight,
    activeColor: Color,
    pendingColor: Color,
    modifier: Modifier = Modifier,
) {
    if (line.words.isNotEmpty()) {
        WordSplitLyricText(
            line = line,
            nextTimeMs = nextTimeMs,
            positionMs = positionMs,
            isCurrent = isCurrent,
            fontSize = fontSize,
            fontWeight = fontWeight,
            activeColor = activeColor,
            pendingColor = pendingColor,
            modifier = modifier,
        )
    } else {
        LineFillLyricText(
            line = line,
            nextTimeMs = nextTimeMs,
            positionMs = positionMs,
            isCurrent = isCurrent,
            fontSize = fontSize,
            fontWeight = fontWeight,
            activeColor = activeColor,
            pendingColor = pendingColor,
            modifier = modifier,
        )
    }
}

internal fun splitLyricText(text: String): List<String> {
    if (text.isBlank()) return listOf(text)
    val result = mutableListOf<String>()
    var index = 0
    while (index < text.length) {
        val start = index
        val isSpace = text[index].isWhitespace()
        if (isSpace) {
            while (index < text.length && text[index].isWhitespace()) index++
        } else if (text[index].isLetterOrDigit() && text[index].code < 128) {
            while (index < text.length && text[index].isLetterOrDigit() && text[index].code < 128) index++
        } else {
            index++
        }
        result += text.substring(start, index)
    }
    return result
}

// 播放中位置回退容差：小于该值视为控制器位置抖动，大于视为手动拖动进度条
private const val LYRIC_SEEK_TOLERANCE_MS = 1500L

// 歌词面板默认可见行数
private const val DEFAULT_VISIBLE_LINES = 5

// 上下边缘淡出：按纵向透明度梯度对内容做 DstIn 蒙层，使上下行渐变消失
private fun Modifier.verticalFadeMask(fadeFraction: Float = 0.25f): Modifier = drawWithCache {
    val brush = Brush.verticalGradient(
        colorStops = arrayOf(
            0.0f to Color.Transparent,
            fadeFraction to Color.Black,
            1f - fadeFraction to Color.Black,
            1f to Color.Transparent,
        )
    )
    onDrawWithContent {
        drawIntoCanvas { canvas -> canvas.saveLayer(Rect(Offset.Zero, size), Paint()) }
        drawContent()
        drawRect(brush = brush, size = size, blendMode = BlendMode.DstIn)
        drawIntoCanvas { canvas -> canvas.restore() }
    }
}