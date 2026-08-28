package com.yichao.evilgodxu.musicpanel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit

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
    // 故按整行时长对词均分时间片，保证逐词连续高亮与跳动
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
    // 当前词内已演唱的字符级进度：正在演唱的字按其与整数的差值从左到右逐渐亮起
    val charProgressInWord = if (currentWordIdx >= 0) {
        ((positionMs - line.timeMs) - currentWordIdx * perWordMs) / perWordMs * wordCharCount
    } else {
        -1f
    }

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
                    // 已唱的词整体高亮，未唱为待唱色；当前词内逐字从左到右亮起
                    val isWordSung = isCurrent && index < currentWordIdx
                    val isWordCurrent = isCurrent && index == currentWordIdx
                    // 词保持整体排版，词内逐字渲染以支持单字亮起与跳动；词间间距由词文本自带空格保留
                    Row(
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        word.text.forEachIndexed { charIdx, ch ->
                            // 仅当前演唱词中当前正在演唱的字触发跳动，其余字保持静态
                            val isFilling = isCurrent && index == currentWordIdx && charIdx == currentCharIdx
                            LyricChar(
                                text = ch.toString(),
                                fillFraction = when {
                                    !isCurrent -> 0f
                                    isWordSung -> 1f
                                    !isWordCurrent -> 0f
                                    else -> (charProgressInWord - charIdx).coerceIn(0f, 1f)
                                },
                                filling = isFilling,
                                fontSize = fontSize,
                                fontWeight = fontWeight,
                                activeColor = activeColor,
                                pendingColor = pendingColor,
                                shadowBlurRadius = WORD_CHAR_SHADOW_BLUR,
                            )
                        }
                    }
                }
            }
        }
    }
}

private const val WORD_CHAR_SHADOW_BLUR = 7f

// 逐字歌词按字符上限分行的辅助函数：把整行词分成多行，且不把单个词截断到两行
private fun wrapLyricWords(words: List<LyricWord>): List<List<LyricWord>> {
    val maxChars = lyricMaxChars(words.joinToString(separator = " ") { it.text })
    val rows = mutableListOf<List<LyricWord>>()
    val row = mutableListOf<LyricWord>()
    var count = 0
    words.forEach { word ->
        // 当前行加上下一个词会超过上限时提前换行，单词保持完整不截断
        if (row.isNotEmpty() && count + word.text.length > maxChars) {
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