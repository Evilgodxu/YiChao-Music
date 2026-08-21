package com.yichao.evilgodxu.musicpanel

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp

// 逐字歌词：按单词时序逐个点亮，每个词独立由待唱色过渡到已唱色；
// 正在演唱的词叠加弹簧动画（放大带过冲 + 轻微上浮），模拟示例的强调跳动效果
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
    // 当前正在演唱的词下标：所唱位置命中的最后一个词；词全部唱完时即末词
    val currentWordIdx = line.words.indexOfLast { positionMs >= it.startMs }
    val floatPx = with(LocalDensity.current) { 0.05f * fontSize.toPx() }

    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        maxItemsInEachRow = 20,
    ) {
        line.words.forEachIndexed { index, word ->
            val progress = when {
                !isCurrent || positionMs <= word.startMs -> 0f
                positionMs >= word.startMs + word.durationMs -> 1f
                else -> ((positionMs - word.startMs).toFloat() / word.durationMs).coerceIn(0f, 1f)
            }
            // 仅正在演唱的词触发跳动，其余词保持静态
            val isFilling = isCurrent && index == currentWordIdx
            val emphasis by animateFloatAsState(
                targetValue = if (isFilling) 1f else 0f,
                animationSpec = spring(
                    dampingRatio = 0.5f,
                    stiffness = 500f,
                ),
                label = "word_jump",
            )
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
                modifier = Modifier
                    .padding(end = 4.dp)
                    .graphicsLayer {
                        // 带过冲的放大：emphasis 达峰时超过目标值再回落，形成弹簧跳动感
                        scaleX = 1f + 0.14f * emphasis
                        scaleY = 1f + 0.14f * emphasis
                        // 演唱时轻微上浮，模拟示例的浮起效果
                        translationY = -floatPx * emphasis
                    }
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