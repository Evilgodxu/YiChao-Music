package com.yichao.evilgodxu.screens.home.home_assembly.player_area

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
                    HomeAlbumArt(
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

        // 封面与歌词区域中间的三合一进度块：艺术家(上/右) + 垂直进度条 + 歌曲标题(下/左)
        LandscapeThreeInOneProgress(
            playbackState = playbackState,
            modifier = Modifier.align(Alignment.Center),
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

// 横屏封面与歌词区域中间的三合一进度块：
// 垂直进度条居中；艺术家与歌曲标题纵向旋转 90° 贴合进度条轴线，
// 艺术家在进度条右侧、歌曲标题在左侧，文本与进度条间距 1dp
@Composable
private fun LandscapeThreeInOneProgress(
    playbackState: MusicPlaybackState,
    modifier: Modifier = Modifier,
) {
    val track = playbackState.currentTrack
    val dimColor = Color.White.copy(alpha = 0.7f)
    // 旋转后文本沿进度条轴线纵向排布，长度上限约 7 个字符，超长启用跑马灯；
    // 文本内容首尾（相对自身横向的首末边缘）恒做渐变淡出
    val textWidth = 80.dp
    // 旋转后的单行纸面厚度（对应未旋转文本的行高）
    val textThickness = 14.dp
    val progress by remember {
        derivedStateOf {
            if (playbackState.duration > 0) {
                (playbackState.currentPosition.toFloat() / playbackState.duration).coerceIn(0f, 1f)
            } else 0f
        }
    }
    val artist = track?.artist ?: ""
    val title = track?.title ?: ""

    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        // 旋转后进度条长度：取当前可用高度的 80%
        val barHeight = maxHeight * 0.8f
        // 组合整体限定为进度条范围，艺术家/标题均不越出进度条上下界
        Box(
            modifier = Modifier
                .width(textWidth)
                .height(barHeight),
        ) {
            // 垂直进度条：样式对齐竖屏进度条（细条 + 高透明度轨道 + 圆角）
            // 填充自下而上（反转水平左→右的填充方向）
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .width(4.dp)
                    .fillMaxHeight(),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(50)),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(progress)
                        .background(Color.White, RoundedCornerShape(50)),
                )
            }

            // 进度条半径与文本条贴进度条的间距
            val barHalf = 2.dp
            val textGap = 1.dp
            // 文本条中心相对进度条中心（外框中心）的水平偏移：进度条半径 + 间距 + 文本条半厚
            val textOffset = barHalf + textGap + textThickness / 2
            // 视觉条为 80dp 高后，需把上/下对齐所依的布局轴心（条中心）补偿回边缘
            val textShift = (textWidth - textThickness) / 2

            // 艺术家：进度条右侧上部，文字以 80dp 宽排版后旋转 90° 纵向呈现，距进度条 1dp
            Text(
                text = artist,
                color = dimColor,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(x = textOffset, y = textShift)
                    .graphicsLayer { rotationZ = 90f }
                    .width(textWidth)
                    .height(textThickness)
                    .then(if (artist.length > 7) Modifier.basicMarquee(iterations = Int.MAX_VALUE) else Modifier)
                    .horizontalFadeMask(),
            )

            // 歌曲标题：进度条左侧下部，文字以 80dp 宽排版后旋转 90° 纵向呈现，距进度条 1dp
            Text(
                text = title,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset(x = -textOffset, y = -textShift)
                    .graphicsLayer { rotationZ = 90f }
                    .width(textWidth)
                    .height(textThickness)
                    .then(if (title.length > 7) Modifier.basicMarquee(iterations = Int.MAX_VALUE) else Modifier)
                    .horizontalFadeMask(),
            )
        }
    }
}

// 水平边缘淡出：与歌词纵向淡出同款的 DstIn 蒙层，对文本内容自身横向的首末两端同时渐变淡出
private fun Modifier.horizontalFadeMask(fadeFraction: Float = 0.2f): Modifier = drawWithCache {
    val brush = Brush.horizontalGradient(
        colorStops = arrayOf(
            0.0f to Color.Transparent,
            fadeFraction to Color.Black,
            1f - fadeFraction to Color.Black,
            1f to Color.Transparent,
        )
    )
    onDrawWithContent {
        drawIntoCanvas { canvas -> canvas.saveLayer(Rect(Offset.Zero, size), Paint()) }
        drawContent()
        drawRect(brush = brush, size = size, blendMode = BlendMode.DstIn)
        drawIntoCanvas { canvas -> canvas.restore() }
    }
}
