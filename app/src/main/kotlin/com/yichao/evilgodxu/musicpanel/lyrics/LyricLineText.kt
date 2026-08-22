package com.yichao.evilgodxu.musicpanel

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit

// 普通歌词（无逐字时序）：按字均分时间整行顺序点亮，正在演唱的字叠加弹簧跳动
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
    val perCharMs = duration / totalLen.toFloat()
    val rows = wrapLyricText(line.text).split('\n')

    // 当前正在演唱的字下标：仅当前行且已开唱才计算，唱完时停在末字
    val currentCharIdx = if (isCurrent && positionMs > line.timeMs) {
        ((positionMs - line.timeMs).toFloat() / perCharMs).toInt().coerceIn(0, totalLen - 1)
    } else {
        -1
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        var globalIdx = 0
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                row.forEach { ch ->
                    val idx = globalIdx++
                    LineChar(
                        text = ch.toString(),
                        active = currentCharIdx >= 0 && idx <= currentCharIdx,
                        filling = currentCharIdx == idx,
                        fontSize = fontSize,
                        fontWeight = fontWeight,
                        activeColor = activeColor,
                        pendingColor = pendingColor,
                    )
                }
            }
        }
    }
}

// 单行歌词单个字：已唱为高亮色、未唱为待唱色；正在演唱的字用弹簧放大带过冲 + 轻微上浮跳动
@Composable
private fun LineChar(
    text: String,
    active: Boolean,
    filling: Boolean,
    fontSize: TextUnit,
    fontWeight: FontWeight,
    activeColor: Color,
    pendingColor: Color,
) {
    val emphasis by animateFloatAsState(
        targetValue = if (filling) 1f else 0f,
        animationSpec = spring(
            dampingRatio = 0.5f,
            stiffness = 500f,
        ),
        label = "line_char_jump",
    )
    val floatPx = with(LocalDensity.current) { 0.05f * fontSize.toPx() }
    Text(
        text = text,
        fontSize = fontSize,
        fontWeight = fontWeight,
        color = if (active) activeColor else pendingColor,
        style = if (active) {
            TextStyle(shadow = Shadow(activeColor.copy(alpha = 0.65f), blurRadius = 5f))
        } else {
            TextStyle()
        },
        modifier = Modifier.graphicsLayer {
            scaleX = 1f + 0.14f * emphasis
            scaleY = 1f + 0.14f * emphasis
            translationY = -floatPx * emphasis
        }
    )
}

// 超过上限字符的歌词手动插入换行符强制断行，避免横屏宽幅下不触发软换行
internal fun wrapLyricText(text: String): String {
    if (text.length <= MAX_LYRIC_CHARS) return text
    return buildString {
        var i = 0
        while (i < text.length) {
            if (i > 0) append('\n')
            val end = minOf(i + MAX_LYRIC_CHARS, text.length)
            var breakAt = end
            // 剩余内容整段可放入当前行时不再回退断行，避免提前换行
            if (end < text.length) {
                // 行尾截断单词时回退到最近空格，避免截断完整单词
                var j = end
                while (j > i) {
                    if (text[j - 1].isWhitespace()) {
                        breakAt = j
                        break
                    }
                    j--
                }
            }
            append(text, i, breakAt)
            i = breakAt
            // 跳过下一行行首空格
            while (i < text.length && text[i].isWhitespace()) i++
        }
    }
}

// 单行歌词超过该字符数则手动换行
private const val MAX_LYRIC_CHARS = 30