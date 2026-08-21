package com.yichao.evilgodxu.musicpanel

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit

// 普通歌词（无逐字时序）：按字符占比整行平滑点亮，保持原有渲染效果
@Composable
internal fun LineFillLyricText(
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
    val segments = wrapLyricText(line.text).split('\n')

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        var acc = 0
        segments.forEach { segment ->
            val segStart = line.timeMs + duration * acc / totalLen
            val segEnd = line.timeMs + duration * (acc + segment.length) / totalLen
            acc += segment.length
            // 当前行内已唱比例：仅当前行且已进入该段才有高亮推进
            val progress = when {
                !isCurrent || positionMs <= segStart -> 0f
                positionMs >= segEnd -> 1f
                else -> ((positionMs - segStart).toFloat() / (segEnd - segStart)).coerceIn(0f, 1f)
            }
            HighlightLine(
                text = segment,
                progress = progress,
                isCurrent = isCurrent,
                fontSize = fontSize,
                fontWeight = fontWeight,
                activeColor = activeColor,
                pendingColor = pendingColor,
            )
        }
    }
}

// 单行歌词高亮：底层整行待唱色，上层按已唱比例从左往右裁剪的已唱色覆盖层。
// 覆盖层与被裁剪文字同为固有宽度，裁剪边界与字形对齐，避免居中留白导致高亮错位不可见。
@Composable
private fun HighlightLine(
    text: String,
    progress: Float,
    isCurrent: Boolean,
    fontSize: TextUnit,
    fontWeight: FontWeight,
    activeColor: Color,
    pendingColor: Color,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = text,
            textAlign = TextAlign.Center,
            fontSize = fontSize,
            fontWeight = fontWeight,
            color = pendingColor,
        )
        // 已唱覆盖层：按 progress 自右向左裁剪，只显示从左端开始的已唱部分
        if (isCurrent && progress > 0f) {
            Text(
                text = text,
                textAlign = TextAlign.Center,
                fontSize = fontSize,
                fontWeight = fontWeight,
                color = activeColor,
                style = TextStyle(
                    shadow = Shadow(activeColor.copy(alpha = 0.65f), blurRadius = 7f)
                ),
                modifier = Modifier.drawWithContent {
                    clipRect(right = size.width * progress) { this@drawWithContent.drawContent() }
                }
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

// 单行歌词超过该字符数则手动换行
private const val MAX_LYRIC_CHARS = 30