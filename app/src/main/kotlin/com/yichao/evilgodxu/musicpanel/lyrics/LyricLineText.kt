package com.yichao.evilgodxu.musicpanel

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateTo
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp

// 普通歌词（无逐字时序）：按字均分时间整行顺序点亮，正在演唱的字高亮从左到右扫过并叠加弹簧跳动
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
    // 行内已播放时长折算成字符级进度：当前字的亮起比例由其与整数的差值决定
    val charProgress = if (isCurrent && positionMs > line.timeMs) {
        (positionMs - line.timeMs).toFloat() / perCharMs
    } else {
        -1f
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
                    LyricChar(
                        text = ch.toString(),
                        fillFraction = if (charProgress < 0f) 0f else (charProgress - idx).coerceIn(0f, 1f),
                        filling = isCurrent && idx == currentCharIdx,
                        fontSize = fontSize,
                        fontWeight = fontWeight,
                        activeColor = activeColor,
                        pendingColor = pendingColor,
                        shadowBlurRadius = LINE_CHAR_SHADOW_BLUR,
                    )
                }
            }
        }
    }
}

// 单个歌词字：未唱为待唱色，已唱为高亮色；正在演唱的字高亮按 fillFraction 从左到右
// 逐渐亮起。待唱层与高亮层共用同一文本布局绘制，逐像素对齐，避免缩放跳起时错位
@Composable
internal fun LyricChar(
    text: String,
    fillFraction: Float,
    filling: Boolean,
    fontSize: TextUnit,
    fontWeight: FontWeight,
    activeColor: Color,
    pendingColor: Color,
    shadowBlurRadius: Float,
) {
    // 跳起与落下均渐进过渡：新字柔和弹起的同时旧字缓缓回落，
    // 两者在时间上重叠,形成连续流动感，避免瞬间落下/瞬间跳起的突兀
    val emphasis = remember { Animatable(0f) }
    LaunchedEffect(filling) {
        emphasis.animateTo(
            targetValue = if (filling) 1f else 0f,
            animationSpec = if (filling) {
                spring(
                    dampingRatio = 0.55f,
                    stiffness = 420f,
                )
            } else {
                tween(
                    durationMillis = LYRIC_JUMP_DOWN_MS,
                    easing = LinearOutSlowInEasing,
                )
            },
        )
    }
    // 位置进度按采样周期跳跃推进，用线性 tween 平滑成连续亮起动画
    val highlightFraction by animateFloatAsState(
        targetValue = fillFraction,
        animationSpec = tween(
            durationMillis = LYRIC_FILL_SMOOTH_MS,
            easing = LinearEasing,
        ),
        label = "lyric_char_fill",
    )
    val density = LocalDensity.current
    val floatPx = with(density) { 0.05f * fontSize.toPx() }
    // 文本样式与 Text 组件默认行为一致（沿用 LocalTextStyle），保证布局高度与旧版逐字渲染相同
    val textMeasurer = rememberTextMeasurer()
    val currentTextStyle = LocalTextStyle.current
    val layout = remember(text, fontSize, fontWeight, currentTextStyle) {
        textMeasurer.measure(
            AnnotatedString(text),
            currentTextStyle.merge(
                TextStyle(fontSize = fontSize, fontWeight = fontWeight)
            ),
        )
    }
    val glowShadow = Shadow(activeColor.copy(alpha = 0.65f), blurRadius = shadowBlurRadius)
    Box(
        modifier = Modifier
            .graphicsLayer {
                // 跳起效果：弹簧放大带过冲 + 轻微上浮，参数与改前旧版一致
                scaleX = 1f + 0.14f * emphasis.value
                scaleY = 1f + 0.14f * emphasis.value
                translationY = -floatPx * emphasis.value
            }
            .size(
                width = with(density) { layout.size.width.toDp() },
                height = with(density) { layout.size.height.toDp() },
            )
            .drawWithContent {
                // 待唱层：整字待唱色
                drawText(layout, color = pendingColor, topLeft = Offset.Zero)
                // 高亮层：按已亮起比例从左到右裁剪露出，发光随高亮区域显示
                clipRect(right = size.width * highlightFraction.coerceIn(0f, 1f)) {
                    drawText(layout, color = activeColor, shadow = glowShadow, topLeft = Offset.Zero)
                }
            }
    )
}

private const val LINE_CHAR_SHADOW_BLUR = 5f

private const val LYRIC_FILL_SMOOTH_MS = 60

// 演唱结束的字回落用时：与下一字跳起重叠渐变,形成渐落衔接，不宜过短
private const val LYRIC_JUMP_DOWN_MS = 320

// 超过上限字符的歌词手动插入换行符强制断行，避免横屏宽幅下不触发软换行
internal fun wrapLyricText(text: String): String {
    val maxChars = lyricMaxChars(text)
    if (text.length <= maxChars) return text
    return buildString {
        var i = 0
        while (i < text.length) {
            if (i > 0) append('\n')
            val end = minOf(i + maxChars, text.length)
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

// 英文按 40 字、非英文按 20 字断行：根据英文文字占比判断语言
internal fun lyricMaxChars(text: String): Int {
    var letters = 0
    for (ch in text) if (ch.code in 'A'.code..'Z'.code || ch.code in 'a'.code..'z'.code) letters++
    return if (letters * 2 >= text.length) MAX_LYRIC_CHARS_EN else MAX_LYRIC_CHARS_INTL
}

private const val MAX_LYRIC_CHARS_EN = 40
private const val MAX_LYRIC_CHARS_INTL = 20