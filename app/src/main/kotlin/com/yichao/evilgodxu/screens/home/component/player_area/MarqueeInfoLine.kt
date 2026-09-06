package com.yichao.evilgodxu.screens.home.component.player_area

import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit

// 单行信息文本：宽度超出容器时启用跑马灯并叠加歌词同款水平边缘渐变，保证全文完整显示
@Composable
internal fun MarqueeInfoLine(
    text: String,
    fontSize: TextUnit,
    fontWeight: FontWeight,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val textWidthPx = textMeasurer.measure(
            AnnotatedString(text),
            TextStyle(fontSize = fontSize, fontWeight = fontWeight),
        ).size.width
        val overflows = maxWidth > 0.dp && with(LocalDensity.current) { textWidthPx.toDp() } > maxWidth
        val textModifier = if (overflows) {
            // 跑马灯需整体剪裁在容器内，再由下方歌词同款 DstIn 蒙层提供首尾边缘渐隐
            Modifier
                .fillMaxWidth()
                .clipToBounds()
                .horizontalFadeMask()
                .basicMarquee(iterations = Int.MAX_VALUE)
        } else {
            Modifier
        }
        Text(
            text = text,
            fontSize = fontSize,
            fontWeight = fontWeight,
            color = color,
            maxLines = 1,
            softWrap = false,
            modifier = textModifier,
        )
    }
}

// 左右边缘淡出：与歌词上下边缘同款的 DstIn 蒙层，首尾渐变消失，保证文字不被截断
private fun Modifier.horizontalFadeMask(fadeFraction: Float = 0.25f): Modifier = drawWithCache {
    val brush = Brush.horizontalGradient(
        colorStops = arrayOf(
            0.0f to Color.Transparent,
            fadeFraction to Color.Black,
            1f - fadeFraction to Color.Black,
            1f to Color.Transparent,
        ),
    )
    onDrawWithContent {
        drawIntoCanvas { canvas -> canvas.saveLayer(Rect(Offset.Zero, size), Paint()) }
        drawContent()
        drawRect(brush = brush, size = size, blendMode = BlendMode.DstIn)
        drawIntoCanvas { canvas -> canvas.restore() }
    }
}
