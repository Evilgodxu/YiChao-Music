package com.yichao.evilgodxu.screens.home.home_assembly.player_area

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit
import com.yichao.evilgodxu.musicpanel.LyricsPanel
import com.yichao.evilgodxu.musicpanel.MusicPlaybackState
import com.yichao.evilgodxu.musicpanel.VerticalProgressBar
import com.yichao.evilgodxu.musicpanel.verticalFadeMask
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

        // 封面与歌词之间的竖向进度条（白色样式，不带时间文本，拖动可跳转）；
        // 竖排标题置于进度条左上角，竖排艺术家置于右下角
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxHeight(0.7f)
                .width(80.dp),
        ) {
            VerticalProgressBar(
                playbackState = playbackState,
                contentColor = Color.White,
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxHeight(),
            )
            if (playbackState.currentTrack != null) {
                VerticalTrackText(
                    text = playbackState.currentTrack?.title.orEmpty(),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 16.dp),
                )
                VerticalTrackText(
                    text = playbackState.currentTrack?.artist.orEmpty(),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Normal,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp),
                )
            }
        }

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

// 竖排文本：标题/艺术家逐字竖排显示，最多展示 7 字；
// 超出后以跑马灯方式上下往返滚动，顶部与底部复用歌词的边缘渐变淡出效果
private const val VERTICAL_TEXT_MAX_CHARS = 7
private const val VERTICAL_MARQUEE_MS = 3500

@Composable
private fun VerticalTrackText(
    text: String,
    fontSize: TextUnit,
    fontWeight: FontWeight,
    color: Color,
    modifier: Modifier = Modifier,
) {
    if (text.isBlank()) return
    val chars = text.toCharArray().toList()
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // 以单个字测量作为行高与列宽基准，保证视口固定为 7 字高度
    val sample = remember(fontSize) {
        textMeasurer.measure(AnnotatedString("字"), TextStyle(fontSize = fontSize))
    }
    val lineHeight = with(density) { sample.size.height.toDp() }
    val charWidth = with(density) { sample.size.width.toDp() }
    val overflowChars = (chars.size - VERTICAL_TEXT_MAX_CHARS).coerceAtLeast(0)
    val overflowPx = with(density) { (overflowChars * lineHeight.value).toFloat() }

    // 跑马灯：内容超出视口时上下往返滚动，逐字循环显示
    val transition = rememberInfiniteTransition(label = "vertical_marquee_$text")
    val scrollY by if (overflowChars > 0) {
        transition.animateFloat(
            initialValue = 0f,
            targetValue = -overflowPx,
            animationSpec = infiniteRepeatable(
                animation = tween(VERTICAL_MARQUEE_MS),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "vertical_marquee_y_$text",
        )
    } else {
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 0f,
            animationSpec = infiniteRepeatable(animation = tween(1)),
            label = "vertical_marquee_y_static_$text",
        )
    }

    Box(
        modifier = modifier
            .width(charWidth)
            .height(lineHeight * VERTICAL_TEXT_MAX_CHARS)
            .clipToBounds()
            .verticalFadeMask(),
    ) {
        Column(
            modifier = Modifier.graphicsLayer { translationY = scrollY },
        ) {
            chars.forEach { ch ->
                Text(
                    text = ch.toString(),
                    fontSize = fontSize,
                    fontWeight = fontWeight,
                    color = color,
                    maxLines = 1,
                )
            }
        }
    }
}


