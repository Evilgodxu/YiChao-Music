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
import androidx.compose.ui.unit.dp

// 逐字歌词：按词时序卡拉OK式点亮——已唱的词连续高亮，仅当前正在演唱的词
// 叠加弹簧跳动（放大带过冲 + 轻微上浮）；与单行歌词一致，跳动改为词内逐字
@Composable
internal fun WordSplitLyricText(
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
    // 词起点时间戳分布往往不均匀（词间空隙大），直接按时戳点亮会长时间停在首词上，
    // 故仿照单行歌词将整行时长按词均分，保证逐词连续高亮与跳动
    val duration = (nextTimeMs - line.timeMs).coerceAtLeast(1L)
    val wordCount = line.words.size.coerceAtLeast(1)
    val perWordMs = duration / wordCount.toFloat()
    // 当前演唱词下标：将行内已播放时长折算到词序号；未开唱时为 -1
    val currentWordIdx = if (isCurrent && positionMs > line.timeMs) {
        ((positionMs - line.timeMs).toFloat() / perWordMs).toInt().coerceIn(0, wordCount - 1)
    } else {
        -1
    }
    // 词内逐字下标：将当前词已演唱的字符折算到该词的字序号；整词已唱时为 -1
    val wordCharCount = line.words.getOrNull(currentWordIdx)?.text?.length?.coerceAtLeast(1) ?: 1
    val currentCharIdx = if (currentWordIdx >= 0) {
        val elapsed = (positionMs - line.timeMs) - currentWordIdx * perWordMs
        (elapsed / perWordMs * wordCharCount).toInt().coerceIn(0, wordCharCount - 1)
    } else {
        -1
    }
    val floatPx = with(LocalDensity.current) { 0.05f * fontSize.toPx() }

    // 将整行词按字符上限手工分成多行：英文词保持完整，词不跨行截断
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        var globalIdx = 0
        wrapLyricWords(line.words).forEach { rowWords ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                rowWords.forEach { word ->
                    val index = globalIdx++
                    // 已唱（含当前演唱词）为高亮色，未唱为待唱色；用纯色保证对比度可见
                    val isSung = isCurrent && index <= currentWordIdx
                    // 词保持整体排版，词内逐字渲染以支持单字跳动；词间间距由词文本自带空格保留
                    Row(
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        word.text.forEachIndexed { charIdx, ch ->
                            // 仅当前演唱词中当前正在演唱的字触发跳动，其余字保持静态
                            val isFilling = isCurrent && index == currentWordIdx && charIdx == currentCharIdx
                            val emphasis by animateFloatAsState(
                                targetValue = if (isFilling) 1f else 0f,
                                animationSpec = spring(
                                    dampingRatio = 0.5f,
                                    stiffness = 500f,
                                ),
                                label = "word_jump",
                            )
                            Text(
                                text = ch.toString(),
                                color = if (isSung) activeColor else pendingColor,
                                style = TextStyle(
                                    shadow = if (isSung) Shadow(activeColor.copy(alpha = 0.65f), blurRadius = 7f) else null
                                ),
                                fontSize = fontSize,
                                fontWeight = fontWeight,
                                softWrap = true,
                                modifier = Modifier.graphicsLayer {
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
            }
        }
    }
}

// 逐字歌词按字符上限分行的辅助函数：把整行词分成多行，且不把单个词截断到两行
private fun wrapLyricWords(words: List<LyricWord>): List<List<LyricWord>> {
    val rows = mutableListOf<List<LyricWord>>()
    val row = mutableListOf<LyricWord>()
    var count = 0
    words.forEach { word ->
        // 当前行加上下一个词会超过 30 字时提前换行，单词保持完整不截断
        if (row.isNotEmpty() && count + word.text.length > MAX_LYRIC_CHARS) {
            rows.add(row.toList())
            row.clear()
            count = 0
        }
        row.add(word)
        count += word.text.length
    }
    if (row.isNotEmpty()) rows.add(row)
    return rows
}

// 逐字歌词每行字符数上限（中文按字计数，英文词不截断）
private const val MAX_LYRIC_CHARS = 30