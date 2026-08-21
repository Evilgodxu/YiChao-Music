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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp

// 逐字歌词：按词时序卡拉OK式点亮——已唱的词连续高亮，仅当前正在演唱的词
// 叠加弹簧跳动（放大带过冲 + 轻微上浮）；与单行整句按字均分跳动的样式明显区分
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
    // 已开唱即命中首个词，保证演唱中至少首个词处于高亮，避免整行停留在待唱色
    val reachedFirst = isCurrent && positionMs >= line.words.first().startMs
    // 当前演唱词下标：位置命中的最后一个已开唱词；未开唱时为 -1
    val currentWordIdx = if (reachedFirst) {
        line.words.indexOfLast { positionMs >= it.startMs }.coerceAtLeast(0)
    } else {
        -1
    }
    val floatPx = with(LocalDensity.current) { 0.05f * fontSize.toPx() }
    // 当前词演唱持续窗口终点 = 起始 + 时长，超出即视为该词已唱完，停止强调
    val fillingEndMs = line.words.getOrNull(currentWordIdx)?.let { it.startMs + it.durationMs }

    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        maxItemsInEachRow = 7,
    ) {
        line.words.forEachIndexed { index, word ->
            // 已唱（含当前演唱词）为高亮色，未唱为待唱色；用纯色保证对比度可见
            val isSung = reachedFirst && index <= currentWordIdx
            // 仅当前正在演唱（词景点亮窗口内）的词触发跳动，其余词保持静态
            val isFilling = isSung && currentWordIdx == index && fillingEndMs != null && positionMs < fillingEndMs
            val emphasis by animateFloatAsState(
                targetValue = if (isFilling) 1f else 0f,
                animationSpec = spring(
                    dampingRatio = 0.5f,
                    stiffness = 500f,
                ),
                label = "word_jump",
            )
            Text(
                text = word.text,
                color = if (isSung) activeColor else pendingColor,
                style = TextStyle(
                    shadow = if (isSung) Shadow(activeColor.copy(alpha = 0.65f), blurRadius = 7f) else null
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