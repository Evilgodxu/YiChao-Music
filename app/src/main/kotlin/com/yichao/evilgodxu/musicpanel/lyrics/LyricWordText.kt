package com.yichao.evilgodxu.musicpanel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp

// 逐字歌词：按单词时序逐个点亮，每个词独立由待唱色过渡到已唱色
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WordSplitLyricText(
    line: LyricLine,
    positionMs: Long,
    isCurrent: Boolean,
    fontSize: TextUnit,
    fontWeight: FontWeight,
    activeColor: Color,
    pendingColor: Color,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        maxItemsInEachRow = 20,
    ) {
        line.words.forEach { word ->
            val progress = when {
                !isCurrent || positionMs <= word.startMs -> 0f
                positionMs >= word.startMs + word.durationMs -> 1f
                else -> ((positionMs - word.startMs).toFloat() / word.durationMs).coerceIn(0f, 1f)
            }
            val brush = wordBrush(progress, activeColor, pendingColor)
            Text(
                text = word.text,
                style = TextStyle(
                    brush = brush,
                    shadow = if (isCurrent && progress > 0f) Shadow(
                        activeColor.copy(alpha = 0.65f),
                        blurRadius = 7f
                    ) else null
                ),
                fontSize = fontSize,
                fontWeight = fontWeight,
                softWrap = true,
                modifier = Modifier.padding(end = 4.dp)
            )
        }
    }
}

// 生成单个显示单元的高亮笔刷：已唱为高亮色起点，待唱为暗色，中间是带软化边缘的扫光带，
// 扫光带宽度为 WORD_FADE_WIDTH，模拟示例的渐变 mask 效果
internal fun wordBrush(progress: Float, activeColor: Color, pendingColor: Color): Brush {
    if (progress <= 0f) return Brush.horizontalGradient(listOf(pendingColor, pendingColor))
    if (progress >= 1f) return Brush.horizontalGradient(listOf(activeColor, activeColor))
    val bandHalf = WORD_FADE_WIDTH / 2f
    val edgeStart = (progress - bandHalf).coerceIn(0f, progress)
    val edgeEnd = (progress + bandHalf).coerceAtMost(1f)
    return Brush.horizontalGradient(
        colorStops = arrayOf(
            0f to activeColor,
            edgeStart to activeColor,
            edgeEnd to pendingColor,
            1f to pendingColor
        )
    )
}

// 扫光带的软化宽度，取单词宽度的一定比例，形成柔和推进的高亮边缘
internal const val WORD_FADE_WIDTH = 0.2f