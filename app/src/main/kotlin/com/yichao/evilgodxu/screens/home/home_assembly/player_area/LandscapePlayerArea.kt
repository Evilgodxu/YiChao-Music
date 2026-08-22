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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yichao.evilgodxu.musicpanel.AlbumArt
import com.yichao.evilgodxu.musicpanel.LyricsPanel
import com.yichao.evilgodxu.musicpanel.MusicPlaybackState
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

// 横屏播放器：双栏结构（封面视觉区 → 歌词透视区）左右居中，标题栏与控制栏点击弹出
@Composable
fun LandscapePlayerArea(
    playbackState: MusicPlaybackState,
    chromeVisible: Boolean,
    onToggleChrome: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var playlistVisible by remember { mutableStateOf(false) }

    // 播放期间周期性同步播放位置，驱动歌词滚动
    LaunchedEffect(playbackState.isPlaying, playbackState.currentTrack) {
        while (isActive && playbackState.isPlaying) {
            playbackState.updatePosition()
            delay(200)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxSize()) {
            // 左：封面视觉区，圆角矩形封面，水平居中
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(start = 24.dp, end = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (playbackState.currentTrack != null) {
                    AlbumArt(
                        track = playbackState.currentTrack,
                        modifier = Modifier
                            .fillMaxWidth(0.68f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(24.dp)),
                    )
                }
            }
            // 右：歌词透视区，水平居中
            LyricsPerspectiveZone(
                playbackState = playbackState,
                modifier = Modifier
                    .weight(1f)
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
                    .background(Color.Transparent),
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

// 歌词透视参数：绕 Y 轴角度与相机距离系数；cameraDistance 越小透视越强，
// 大视角下取值过大会让旋转透视近乎消失，故用较小的宽度比例以获得明显左远右近；
// rotationY 取负使左缘向前、右缘后退
private const val ROTATION_Y_DEGREES = -45f
private const val CAMERA_DISTANCE_FACTOR = 0.15f
// 横屏歌词可见行数（当前行居中，上下各 4 行）
private const val LYRICS_VISIBLE_LINES = 9

// 歌词透视区：rotationY 绕 Y 轴旋转，配合随宽度缩放的 cameraDistance 产生近大远小的真实 3D 透视
@Composable
private fun LyricsPerspectiveZone(
    playbackState: MusicPlaybackState,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .padding(start = 12.dp, end = 24.dp, top = 12.dp, bottom = 12.dp)
            .clipToBounds()
            .graphicsLayer {
                rotationY = ROTATION_Y_DEGREES
                // 官方推荐：cameraDistance 不小于视图宽度，透视才自然且不畸变
                cameraDistance = size.width * CAMERA_DISTANCE_FACTOR
            },
        contentAlignment = Alignment.Center,
    ) {
        LyricsPanel(
            playbackState = playbackState,
            onClick = {},
            fontSize = 14.sp,
            contentColor = Color.White,
            // 横屏视野更宽，上下各多显示两行（共 9 行）
            visibleLines = LYRICS_VISIBLE_LINES,
        )
    }
}
