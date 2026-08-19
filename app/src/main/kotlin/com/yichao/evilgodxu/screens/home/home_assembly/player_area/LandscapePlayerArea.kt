package com.yichao.evilgodxu.screens.home.home_assembly.player_area

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yichao.evilgodxu.musicpanel.DiscArt
import com.yichao.evilgodxu.musicpanel.LyricsPanel
import com.yichao.evilgodxu.musicpanel.MusicPlaybackState
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

// 横屏播放器：三栏结构（封面视觉区 → 信息轴区 → 歌词透视区），标题栏与控制栏点击弹出、3 秒无操作自动隐藏
@Composable
fun LandscapePlayerArea(
    playbackState: MusicPlaybackState,
    chromeVisible: Boolean,
    onToggleChrome: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var playlistVisible by remember { mutableStateOf(false) }

    // 播放期间周期性同步播放位置，驱动进度条与歌词
    LaunchedEffect(playbackState.isPlaying, playbackState.currentTrack) {
        while (isActive && playbackState.isPlaying) {
            playbackState.updatePosition()
            delay(200)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxSize()) {
            // 左：封面视觉区
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                contentAlignment = Alignment.Center,
            ) {
                if (playbackState.currentTrack != null) {
                    DiscArt(
                        track = playbackState.currentTrack,
                        isPlaying = playbackState.isPlaying,
                        modifier = Modifier
                            .fillMaxWidth(0.68f)
                            .aspectRatio(1f),
                    )
                }
            }
            // 中：信息轴区
            MetadataAxis(
                playbackState = playbackState,
                modifier = Modifier
                    .weight(0.5f)
                    .fillMaxHeight(),
            )
            // 右：歌词透视区
            LyricsPerspectiveZone(
                playbackState = playbackState,
                modifier = Modifier
                    .weight(1.4f)
                    .fillMaxHeight(),
            )
        }

        // 全屏点击检测：点击空白处切换标题栏与控制栏显隐
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { onToggleChrome() }
                },
        )

        // 底部控制栏
        AnimatedVisibility(
            visible = chromeVisible,
            enter = slideInVertically(animationSpec = tween(300)) { it } + fadeIn(),
            exit = slideOutVertically(animationSpec = tween(300)) { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)),
            ) {
                PlayerControls(
                    playbackState = playbackState,
                    onPlaylistClick = { playlistVisible = true },
                )
            }
        }

        PlaylistSheet(
            visible = playlistVisible,
            playbackState = playbackState,
            onDismiss = { playlistVisible = false },
        )
    }
}

// 信息轴区：标题位于左上角、艺术家位于右下角，垂直文字夹住居中的竖线进度条
@Composable
private fun MetadataAxis(
    playbackState: MusicPlaybackState,
    modifier: Modifier = Modifier,
) {
    val progress by remember {
        derivedStateOf {
            if (playbackState.duration > 0) {
                (playbackState.currentPosition.toFloat() / playbackState.duration).coerceIn(0f, 1f)
            } else 0f
        }
    }
    Box(modifier = modifier.padding(vertical = 24.dp)) {
        // 进度条：居中竖线，从底部向上填充
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxHeight(0.72f)
                .width(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(progress)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
            )
        }
        // 标题：左上角竖排文字
        VerticalLabel(
            text = playbackState.currentTrack?.title.orEmpty(),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 6.dp, top = 44.dp),
        )
        // 艺术家：右下角竖排文字
        VerticalLabel(
            text = playbackState.currentTrack?.artist.orEmpty(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 6.dp, bottom = 44.dp),
        )
    }
}

// 竖排文字：逐字垂直排列，超长时截断并补省略号
@Composable
private fun VerticalLabel(text: String, modifier: Modifier = Modifier) {
    if (text.isBlank()) return
    val visible = if (text.length > MAX_VERTICAL_CHARS) {
        text.take(MAX_VERTICAL_CHARS - 1) + "…"
    } else {
        text
    }
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        visible.forEach { char ->
            Text(
                text = char.toString(),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}

private const val MAX_VERTICAL_CHARS = 7

// 歌词透视区：左高右低倾斜并放大 35%，近似 CSS skewY(5deg) perspective(800px) rotateY(15deg)
@Composable
private fun LyricsPerspectiveZone(
    playbackState: MusicPlaybackState,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .graphicsLayer {
                rotationY = 15f
                cameraDistance = 800f
                scaleX = 1.35f
                scaleY = 1.35f
            }
            .drawWithContent {
                // skewY(5deg) ≈ tan(5°)，右端下移形成左高右低
                drawContext.canvas.skew(0f, 0.0875f)
                drawContent()
            },
        contentAlignment = Alignment.Center,
    ) {
        LyricsPanel(
            playbackState = playbackState,
            onClick = {},
            fontSize = 14.sp,
        )
    }
}
