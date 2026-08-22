package com.yichao.evilgodxu.screens.home.home_assembly

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.PI
import kotlin.math.sin

// 极光动效：多层极光帷幕缓慢摆动，叠加在歌曲渐变背景之上
@Composable
internal fun AuroraEffect(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "aurora")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 16000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "auroraT",
    )
    val t2 by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 22000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "auroraT2",
    )
    Canvas(modifier = modifier.fillMaxSize()) {
        val time = t * (2 * PI).toFloat()
        val time2 = t2 * (2 * PI).toFloat()
        // 绿青主帷幕：居中偏左，幅度最大
        drawAuroraCurtain(
            baseX = size.width * 0.42f,
            phase = 0f,
            time = time,
            amplitude = size.width * 0.2f,
            width = size.width * 0.36f,
            topColor = Color(0xFF34E89E).copy(alpha = 0.28f),
        )
        // 紫蓝辅帷幕：右侧，反向节奏漂移
        drawAuroraCurtain(
            baseX = size.width * 0.72f,
            phase = 1.3f,
            time = time2,
            amplitude = size.width * 0.24f,
            width = size.width * 0.3f,
            topColor = Color(0xFF7F5FFF).copy(alpha = 0.22f),
        )
        // 粉红点缀帷幕：左侧，幅度最小
        drawAuroraCurtain(
            baseX = size.width * 0.16f,
            phase = 2.6f,
            time = time,
            amplitude = size.width * 0.16f,
            width = size.width * 0.26f,
            topColor = Color(0xFFF78CA0).copy(alpha = 0.18f),
        )
    }
}

// 单个极光帷幕：顶部收拢、底部自由摆动的柔性条带，自上而下渐隐
private fun DrawScope.drawAuroraCurtain(
    baseX: Float,
    phase: Float,
    time: Float,
    amplitude: Float,
    width: Float,
    topColor: Color,
) {
    val h = size.height
    // 幅度缓慢呼吸，模拟极光强弱起伏
    val amp = amplitude * (1f + 0.2f * sin(time * 0.5f + phase))
    val segments = 48
    val path = Path()
    // 左边缘：自顶部到底部，横向按正弦摆动
    for (i in 0..segments) {
        val y = h * i / segments
        val wave = sin(y * 0.01f + phase + time) * amp
        val x = baseX + wave - width * 0.5f
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    // 右边缘：底部回到顶部，相位错开形成飘动
    for (i in segments downTo 0) {
        val y = h * i / segments
        val wave = sin(y * 0.008f + phase + time * 1.4f) * amp
        val x = baseX + wave + width * 0.5f
        path.lineTo(x, y)
    }
    path.close()
    drawPath(
        path = path,
        brush = Brush.verticalGradient(
            colors = listOf(topColor, Color.Transparent),
            startY = 0f,
            endY = h,
        ),
    )
}
