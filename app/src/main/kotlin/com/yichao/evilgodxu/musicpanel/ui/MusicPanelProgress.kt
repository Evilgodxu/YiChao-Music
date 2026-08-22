package com.yichao.evilgodxu.musicpanel

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
internal fun ProgressSection(
    playbackState: MusicPlaybackState,
    contentColor: Color? = null,
) {
    // 进度条与时间文本颜色：默认取主题色，传入 contentColor 时（如首页）覆盖为指定色
    val activeColor = contentColor ?: MaterialTheme.colorScheme.primary
    val dimTextColor = contentColor ?: MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp, bottom = 2.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        VisualizerSection(
            isPlaying = playbackState.isPlaying,
            contentColor = contentColor,
            modifier = Modifier
                .fillMaxWidth()
                .height(14.dp)
                .alpha(0.45f)
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = formatTime(playbackState.currentPosition),
                color = dimTextColor,
                fontSize = 9.sp,
                modifier = Modifier.width(24.dp),
                textAlign = TextAlign.Start
            )

            val progress by remember {
                derivedStateOf {
                    if (playbackState.duration > 0) {
                        (playbackState.currentPosition.toFloat() / playbackState.duration).coerceIn(0f, 1f)
                    } else 0f
                }
            }
            var seekFraction by remember { mutableFloatStateOf(progress) }
            var isSeeking by remember { mutableStateOf(false) }
            val displayProgress = if (isSeeking) seekFraction else progress

            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(20.dp)
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                val pos = event.changes.first().position.x / size.width
                                seekFraction = pos.coerceIn(0f, 1f)
                                isSeeking = true
                                if (event.changes.first().pressed) {
                                    seekTo(playbackState, (seekFraction * playbackState.duration).toLong())
                                    playbackState.setCurrentPosition((seekFraction * playbackState.duration).toLong().coerceIn(0L, playbackState.duration))
                                }
                                if (event.changes.all { !it.pressed }) {
                                    isSeeking = false
                                }
                            }
                        }
                    },
                contentAlignment = Alignment.CenterStart
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(activeColor.copy(alpha = 0.08f), RoundedCornerShape(2.dp))
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth(displayProgress)
                        .height(3.dp)
                        .background(activeColor, RoundedCornerShape(2.dp))
                )
            }
            Text(
                text = formatTime(playbackState.duration),
                color = dimTextColor,
                fontSize = 9.sp,
                modifier = Modifier.width(24.dp),
                textAlign = TextAlign.End
            )
        }
    }
}

// 竖向进度条：复用横向进度条样式（圆角轨道 + 主色填充），不带时间文本
@Composable
internal fun VerticalProgressBar(
    playbackState: MusicPlaybackState,
    modifier: Modifier = Modifier,
    contentColor: Color? = null,
) {
    val activeColor = contentColor ?: MaterialTheme.colorScheme.primary
    val progress by remember {
        derivedStateOf {
            if (playbackState.duration > 0) {
                (playbackState.currentPosition.toFloat() / playbackState.duration).coerceIn(0f, 1f)
            } else 0f
        }
    }
    var seekFraction by remember { mutableFloatStateOf(progress) }
    var isSeeking by remember { mutableStateOf(false) }
    val displayProgress = if (isSeeking) seekFraction else progress

    Box(
        modifier = modifier
            .width(20.dp)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val pos = event.changes.first().position.y / size.height
                        seekFraction = (1f - pos).coerceIn(0f, 1f)
                        isSeeking = true
                        if (event.changes.first().pressed) {
                            seekTo(playbackState, (seekFraction * playbackState.duration).toLong())
                            playbackState.setCurrentPosition(
                                (seekFraction * playbackState.duration).toLong().coerceIn(0L, playbackState.duration)
                            )
                        }
                        if (event.changes.all { !it.pressed }) {
                            isSeeking = false
                        }
                    }
                }
            },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(3.dp)
                .background(activeColor.copy(alpha = 0.08f), RoundedCornerShape(2.dp))
        )
        Box(
            modifier = Modifier
                .fillMaxHeight(displayProgress)
                .width(3.dp)
                .background(activeColor, RoundedCornerShape(2.dp))
        )
    }
}

@Composable
internal fun VisualizerSection(
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    contentColor: Color? = null,
) {
    val barCount = 28
    val primary = contentColor ?: MaterialTheme.colorScheme.primary
    val inactiveColor = (contentColor ?: MaterialTheme.colorScheme.onSurface).copy(alpha = 0.06f)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(20.dp)
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.Bottom
    ) {
        repeat(barCount) { index ->
            val target = remember { mutableFloatStateOf(0.1f) }
            val animatedHeight by animateFloatAsState(
                targetValue = if (isPlaying) target.value else 0.04f,
                animationSpec = tween(120),
                label = "visualizer_$index"
            )
            LaunchedEffect(isPlaying, index) {
                while (isActive && isPlaying) {
                    target.value = 0.1f + kotlin.random.Random.nextFloat() * 0.55f
                    delay(80 + (index * 15).toLong())
                }
            }
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight(animatedHeight)
                    .background(
                        if (isPlaying) primary.copy(alpha = 0.7f) else inactiveColor,
                        RoundedCornerShape(0.dp)
                    )
            )
        }
    }
}
