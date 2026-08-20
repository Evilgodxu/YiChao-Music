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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
        while (isActive) {
            lyricPosition = playbackState.mediaController?.currentPosition
                ?.takeIf { it >= 0L }
                ?: playbackState.currentPosition
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
            .padding(top = 4.dp, bottom = 0.dp),
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
    val duration = (nextTimeMs - line.timeMs).coerceAtLeast(1L)
    val totalLen = line.text.length.coerceAtLeast(1)
    // 手动换行后的各行，时间按字符数占比分配，后一行在前一行渲染完成后才高亮
    val segments = wrapLyricText(line.text).split('\n')

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        var acc = 0
        segments.forEach { segment ->
            val startMs = line.timeMs + duration * acc / totalLen
            val endMs = line.timeMs + duration * (acc + segment.length) / totalLen
            acc += segment.length
            val progress = when {
                !isCurrent || positionMs <= startMs -> 0f
                positionMs >= endMs -> 1f
                else -> ((positionMs - startMs).toFloat() / (endMs - startMs)).coerceIn(0f, 1f)
            }
            val lyricBrush = when {
                progress <= 0f -> Brush.horizontalGradient(listOf(pendingColor, pendingColor))
                progress >= 1f -> Brush.horizontalGradient(listOf(activeColor, activeColor))
                else -> Brush.horizontalGradient(
                    colorStops = arrayOf(
                        0f to activeColor,
                        progress to activeColor,
                        progress to pendingColor,
                        1f to pendingColor
                    )
                )
            }
            Text(
                text = segment,
                style = TextStyle(
                    brush = lyricBrush,
                    shadow = if (progress > 0f) Shadow(
                        activeColor.copy(alpha = 0.65f),
                        blurRadius = 7f
                    ) else null
                ),
                fontSize = fontSize,
                softWrap = false,
                textAlign = TextAlign.Center,
                fontWeight = fontWeight,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

// 超过上限字符的歌词手动插入换行符强制断行，避免横屏宽幅下不触发软换行
private fun wrapLyricText(text: String): String {
    if (text.length <= MAX_LYRIC_CHARS) return text
    return buildString {
        var i = 0
        while (i < text.length) {
            if (i > 0) append('\n')
            append(text, i, minOf(i + MAX_LYRIC_CHARS, text.length))
            i += MAX_LYRIC_CHARS
        }
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

// 单行歌词超过该字符数则手动换行
private const val MAX_LYRIC_CHARS = 30

// 歌词面板默认可见行数
private const val DEFAULT_VISIBLE_LINES = 5