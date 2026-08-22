package com.yichao.evilgodxu.screens.home.home_assembly.player_area

import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yichao.evilgodxu.musicpanel.MusicPlaybackState
import com.yichao.evilgodxu.musicpanel.seekTo

// 三合一垂直进度条：顶部标题、中部垂直进度、底部艺术家，长文本跑马灯
@Composable
internal fun VerticalProgressTrack(
    playbackState: MusicPlaybackState,
    modifier: Modifier = Modifier,
) {
    val title = playbackState.currentTrack?.title.orEmpty()
    val artist = playbackState.currentTrack?.artist.orEmpty()

    val progress by remember {
        derivedStateOf {
            if (playbackState.duration > 0) {
                (playbackState.currentPosition.toFloat() / playbackState.duration).coerceIn(0f, 1f)
            } else 0f
        }
    }
    var seekFraction by remember { mutableFloatStateOf(progress) }
    var isSeeking by remember { mutableStateOf(false) }
    // 垂直进度条 0% 在顶部，fill 自上而下
    val displayProgress = if (isSeeking) seekFraction else progress

    Column(
        modifier = modifier
            .width(120.dp)
            .fillMaxHeight()
            .padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            color = Color.White,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            textAlign = TextAlign.Start,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (title.length > 7) Modifier.basicMarquee(iterations = Int.MAX_VALUE) else Modifier),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .width(24.dp)
                .padding(vertical = 12.dp)
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val pos = event.changes.first().position.y / size.height
                            seekFraction = pos.coerceIn(0f, 1f)
                            isSeeking = true
                            if (event.changes.first().pressed) {
                                val target = (seekFraction * playbackState.duration).toLong()
                                seekTo(playbackState, target)
                                playbackState.setCurrentPosition(
                                    target.coerceIn(0L, playbackState.duration)
                                )
                            }
                            if (event.changes.all { !it.pressed }) isSeeking = false
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(4.dp)
                    .background(Color.White.copy(alpha = 0.18f), RoundedCornerShape(2.dp))
            )
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .align(Alignment.TopCenter)
                    .fillMaxHeight(displayProgress)
                    .background(Color.White, RoundedCornerShape(2.dp))
            )
        }
        Text(
            text = artist,
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            textAlign = TextAlign.End,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (artist.length > 7) Modifier.basicMarquee(iterations = Int.MAX_VALUE) else Modifier),
        )
    }
}