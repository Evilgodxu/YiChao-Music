package com.yichao.evilgodxu.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit
import com.yichao.evilgodxu.data.music.metadata.MusicMetadataCache
import com.yichao.evilgodxu.data.music.model.LyricLine
import com.yichao.evilgodxu.data.settings.wordByWordRenderingFlow
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState
import com.yichao.evilgodxu.R
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
internal fun LyricsPanel(
    playbackState: MusicPlaybackState,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    fontSize: TextUnit = 12.sp,
    contentColor: Color? = null,
    visibleLines: Int = DEFAULT_VISIBLE_LINES,
) {
    // 已唱 / 未唱歌词颜色：默认取主题色，传入 contentColor 时（如首页）覆盖为指定色
    val activeColor = contentColor ?: MaterialTheme.colorScheme.primary
    val pendingColor = (contentColor ?: MaterialTheme.colorScheme.onSurfaceVariant).copy(alpha = 0.72f)
    // 逐字渲染开关：关闭后整行高亮，不再逐字跳动
    val context = LocalContext.current
    val wordByWordEnabled by context.wordByWordRenderingFlow().collectAsState(initial = true)
    // 跟随当前曲目：切换歌曲时重置到曲目起点，避免沿用上一首的播放位置定位错行
    var lyricPosition by remember(playbackState.currentTrack?.id) { mutableLongStateOf(0L) }
    LaunchedEffect(playbackState.isPlaying, playbackState.currentTrack?.id) {
        var lastSyncMs = 0L
        while (isActive) {
            val candidate = playbackState.mediaController?.currentPosition
                ?.takeIf { it >= 0L }
                ?: playbackState.currentPosition
            if (playbackState.isPlaying) {
                val now = System.currentTimeMillis()
                val elapsed = if (lastSyncMs == 0L) 0L else (now - lastSyncMs).coerceAtLeast(0L)
                // 播放中以真实流逝时间推进，控制器位置仅作锚点：熄屏唤醒后控制器
                // 位置可能停滞，本地位置仍持续前进避免冻结；大幅回退视为手动拖动
                lyricPosition = when {
                    candidate >= lyricPosition -> candidate
                    lyricPosition - candidate > LYRIC_SEEK_TOLERANCE_MS -> candidate
                    else -> lyricPosition + elapsed
                }
                lastSyncMs = now
            } else {
                lyricPosition = candidate
                lastSyncMs = 0L
            }
            delay(if (playbackState.isPlaying) 50L else 200L)
        }
    }

    val lines = playbackState.currentTrack?.lyricLines.orEmpty()
    // 歌词缺失时按需补全（懒加载）：补全成功后回写 lyricLines 驱动重组显示
    LaunchedEffect(
        playbackState.currentTrack?.id,
        playbackState.currentTrack?.lyricCachePath,
        playbackState.currentTrack?.lyricLines?.size,
        playbackState.currentTrack?.lyricFailed,
    ) {
        playbackState.requestMetadata(playbackState.currentTrack)
    }
    val activeIndex = lines.indexOfLast { it.timeMs <= lyricPosition }.coerceAtLeast(0)
    // 当前行居中，上下各显示 (total-1)/2 行（total 为奇数）
    val offset = visibleLines / 2
    // 视口高度按 N 行标准高度计算：超长句换行与译文叠层会让单行高于标准，
    // 视口固定为 N 行标准高，超出部分交由边缘渐隐与裁剪处理
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // 标准单行高度（含行内上下内边距）：视口上限与窗口内占位行共用，使滚动基准线不随占位行变化
    val lyricLineHeightPx = remember(fontSize, density) {
        textMeasurer.measure(
            AnnotatedString("歌词"),
            TextStyle(fontSize = fontSize, fontWeight = FontWeight.Normal),
        ).size.height
    }
    val maxViewportHeight = remember(visibleLines, lyricLineHeightPx, density) {
        val slotPx = lyricLineHeightPx + with(density) { 4.dp.roundToPx() }
        val spacingPx = with(density) { 2.dp.roundToPx() }
        slotPx * visibleLines + spacingPx * (visibleLines - 1)
    }
    val standardSlotHeight = with(density) { (lyricLineHeightPx + 4.dp.roundToPx()).toDp() }

    // 滚动动画位置：以“行号”为单位的浮点值。当前行变化时在其上平滑过渡，布局据此整体平移内容，
    // 形成连续的自然上移；跨度过大（拖动进度/切歌）时直接定位，避免长距离滚动
    val animatedIndex = remember(playbackState.currentTrack?.id) { Animatable(0f) }
    LaunchedEffect(activeIndex, playbackState.currentTrack?.id) {
        val target = activeIndex.toFloat()
        if (abs(target - animatedIndex.value) <= LYRIC_SCROLL_MAX_STEP) {
            animatedIndex.animateTo(
                targetValue = target,
                animationSpec = tween(LYRIC_SCROLL_DURATION_MS, easing = FastOutSlowInEasing),
            )
        } else {
            animatedIndex.snapTo(target)
        }
    }
    // 动画位置与目标跨度较大时（拖动进度/切歌瞬间）动画尚未归位，本帧直接用目标行定位，
    // 避免追赶期间把窗口内的错误行居中
    val displayPosition = animatedIndex.value.let { value ->
        if (abs(value - activeIndex) > LYRIC_SCROLL_MAX_STEP) activeIndex.toFloat() else value
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(top = 4.dp, bottom = 0.dp),
        contentAlignment = Alignment.Center
    ) {
        if (lines.isEmpty()) {
            // 关联歌词缓存存在时读取挂载极快，不闪"暂无歌词"占位；仅真正缺失时提示
            val hasLyricCache = playbackState.currentTrack?.lyricCachePath
                ?.let { MusicMetadataCache.isValid(it) } == true
            if (!hasLyricCache) {
                Text(
                    stringResource(R.string.music_panel_no_lyrics),
                    color = contentColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
            }
        } else {
            // 窗口容器固定，滚动与渐变都在其内部进行：行溢出与滚出内容经过边缘即被渐隐裁剪
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clipToBounds()
                    .verticalFadeMask(fadeFraction = FADE_TOTAL_LINES / visibleLines),
            ) {
                // 以当前行为中心上下各多渲染 buffer 行：滚动时新行已在窗口内，与旧行在同一坐标系
                // 整体平移，因此只会连续上移，不会出现整块内容替换的突兀感
                val windowStart = activeIndex - offset - LYRIC_WINDOW_BUFFER
                LyricColumnLayout(
                    windowStart = windowStart,
                    animatedPosition = displayPosition,
                    maxViewportHeight = maxViewportHeight,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    repeat(visibleLines + LYRIC_WINDOW_BUFFER * 2) { row ->
                        val index = windowStart + row
                        // 以行下标为键：窗口平移时同一行的状态（高亮进度等）得以保留，不会跳变
                        key(index) {
                            val line = lines.getOrNull(index)
                            if (line == null) {
                                LyricSpacer(height = standardSlotHeight)
                            } else {
                                val isCurrent = index == activeIndex
                                val emphasis by animateFloatAsState(
                                    targetValue = if (isCurrent) 1f else 0f,
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioNoBouncy,
                                        stiffness = Spring.StiffnessMediumLow
                                    ),
                                    label = "lyric_emphasis"
                                )
                                val scale = LYRIC_ROW_SCALE_BASE + LYRIC_ROW_SCALE_AMPLITUDE * emphasis
                                // 非当前行整体降低不透明度，弱化其视觉存在感；随高亮进度平滑过渡
                                val rowAlpha = LYRIC_INACTIVE_ALPHA + (1f - LYRIC_INACTIVE_ALPHA) * emphasis
                                val nextTimeMs = lines.getOrNull(index + 1)?.timeMs ?: line.timeMs + 3000L
                                LyricText(
                                    line = line,
                                    nextTimeMs = nextTimeMs,
                                    positionMs = lyricPosition,
                                    isCurrent = isCurrent,
                                    wordByWordEnabled = wordByWordEnabled,
                                    fontSize = fontSize,
                                    fontWeight = if (isCurrent) FontWeight.Medium else FontWeight.Normal,
                                    activeColor = activeColor,
                                    pendingColor = pendingColor,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .graphicsLayer {
                                            alpha = rowAlpha
                                            scaleX = scale
                                            scaleY = scale
                                        }
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun LyricSpacer(height: Dp) {
    Spacer(modifier = Modifier.height(height))
}

// 歌词纵向布局：窗口内按真实行高逐行排布，再以“动画浮点行号”定位目标位置在内容中的中心，
// 整体平移使该中心对齐视口中线。行高随换行而不同，按固定行高偏移会使当前行偏离中线，
// 故用实测高度换算；目标中心在相邻两行中心之间线性插值，跨行切换不会跳变。
@Composable
private fun LyricColumnLayout(
    windowStart: Int,
    animatedPosition: Float,
    maxViewportHeight: Int,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val spacingPx = with(LocalDensity.current) { 2.dp.roundToPx() }
    Layout(
        modifier = modifier,
        content = content,
    ) { measurables, constraints ->
        val placeables = measurables.map {
            it.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
        }
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth
        else placeables.maxOfOrNull { it.width } ?: 0
        // 视口高度固定为 N 行标准高度：窗口内多出的 buffer 行不撑高面板，滚动基准线保持稳定
        val boundedMax = if (constraints.hasBoundedHeight) constraints.maxHeight else Int.MAX_VALUE
        val viewportHeight = minOf(maxViewportHeight, boundedMax).coerceAtLeast(1)
        if (placeables.isEmpty()) {
            layout(width, viewportHeight) {}
        } else {
            // 动画行号换算成窗口内相对行号，取整定位所在行，余数用于在相邻行中心之间插值
            val relative = (animatedPosition - windowStart)
                .coerceIn(0f, placeables.lastIndex.toFloat())
            val row = floor(relative).toInt().coerceIn(0, placeables.lastIndex)
            val fraction = relative - row
            var rowTop = 0
            for (i in 0 until row) rowTop += placeables[i].height + spacingPx
            val rowCenter = rowTop + placeables[row].height / 2f
            val rowToNextCenter = if (row < placeables.lastIndex) {
                placeables[row].height / 2f + spacingPx + placeables[row + 1].height / 2f
            } else {
                0f
            }
            // 平移量 = 视口中线 - 动画行号对应位置的中心，使该位置始终居于中线
            val shift = viewportHeight / 2f - (rowCenter + fraction * rowToNextCenter)
            layout(width, viewportHeight) {
                var y = shift
                placeables.forEachIndexed { i, placeable ->
                    placeable.placeRelative(0, y.roundToInt())
                    y += placeable.height + if (i < placeables.lastIndex) spacingPx else 0
                }
            }
        }
    }
}

@Composable
internal fun LyricText(
    line: LyricLine,
    nextTimeMs: Long,
    positionMs: Long,
    isCurrent: Boolean,
    wordByWordEnabled: Boolean,
    fontSize: TextUnit,
    fontWeight: FontWeight,
    activeColor: Color,
    pendingColor: Color,
    modifier: Modifier = Modifier,
) {
    var contentWidthPx by remember { mutableIntStateOf(0) }
    // 所有行统一按当前行的放大预留宽度分行：未唱/已唱和正在唱的行数一致，避免换行跳变
    val wrapWidthPx = if (contentWidthPx <= 0) 0 else (contentWidthPx / LYRIC_ROW_SCALE_MAX).roundToInt()
    // 各行的水平缩放余量同样全行统一，保证分行宽度与主歌词排版一致
    val reserveWidthPx = (contentWidthPx * (LYRIC_ROW_SCALE_MAX - 1f) / (2f * LYRIC_ROW_SCALE_MAX)).roundToInt()
    Column(
        modifier = modifier.onSizeChanged { contentWidthPx = it.width },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (!wordByWordEnabled) {
            WholeLineLyricText(
                line = line,
                isCurrent = isCurrent,
                fontSize = fontSize,
                fontWeight = fontWeight,
                activeColor = activeColor,
                pendingColor = pendingColor,
                reserveWidthPx = reserveWidthPx,
            )
        } else if (line.words.isNotEmpty()) {
            WordSplitLyricText(
                line = line,
                nextTimeMs = nextTimeMs,
                positionMs = positionMs,
                isCurrent = isCurrent,
                fontSize = fontSize,
                fontWeight = fontWeight,
                activeColor = activeColor,
                pendingColor = pendingColor,
                widthPx = wrapWidthPx,
            )
        } else {
            LineFillLyricText(
                line = line,
                nextTimeMs = nextTimeMs,
                positionMs = positionMs,
                isCurrent = isCurrent,
                fontSize = fontSize,
                fontWeight = fontWeight,
                activeColor = activeColor,
                pendingColor = pendingColor,
                widthPx = wrapWidthPx,
            )
        }
        line.translation?.takeIf { it.isNotBlank() }?.let { translation ->
            // 翻译行以更小字号静置展示，主歌词高亮时翻译同步使用完整高亮色与发光
            Text(
                text = translation,
                fontSize = (fontSize.value * 0.68f).sp,
                fontWeight = FontWeight.Normal,
                color = if (isCurrent) activeColor else pendingColor.copy(alpha = 0.55f),
                style = if (isCurrent) {
                    TextStyle(shadow = Shadow(activeColor.copy(alpha = 0.65f), blurRadius = 5f))
                } else {
                    TextStyle()
                },
                textAlign = TextAlign.Center,
                softWrap = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = with(LocalDensity.current) { reserveWidthPx.toDp() })
                    .padding(top = 1.dp),
            )
        }
    }
}

// 关闭逐字渲染：整行统一着色，不逐字填充也不跳动
@Composable
private fun WholeLineLyricText(
    line: LyricLine,
    isCurrent: Boolean,
    fontSize: TextUnit,
    fontWeight: FontWeight,
    activeColor: Color,
    pendingColor: Color,
    reserveWidthPx: Int,
    modifier: Modifier = Modifier,
) {
    Text(
        text = line.text,
        fontSize = fontSize,
        fontWeight = fontWeight,
        color = if (isCurrent) activeColor else pendingColor,
        textAlign = TextAlign.Center,
        softWrap = true,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = with(LocalDensity.current) { reserveWidthPx.toDp() }),
    )
}

// 播放中位置回退容差：小于该值视为控制器位置抖动，大于视为手动拖动进度条
private const val LYRIC_SEEK_TOLERANCE_MS = 1500L

// 歌词面板默认可见行数：保持奇数使当前行垂直居中（上下各 (n-1)/2 行）
private const val DEFAULT_VISIBLE_LINES = 5

// 窗口上下各多渲染的行数：滚动时新行已在窗口内、被移除的行已完全移出视口，
// 两者在同一坐标系整体平移，因此只会连续上移，不会出现边缘闪现或整块替换
private const val LYRIC_WINDOW_BUFFER = 2

// 换行滚动：时长与缓动参照自然滚动（先快后慢）；跨度大于该行数（拖动进度/切歌）直接定位
private const val LYRIC_SCROLL_DURATION_MS = 400
private const val LYRIC_SCROLL_MAX_STEP = 2

// 非当前行不透明度：弱化视觉存在感，随高亮进度平滑过渡
private const val LYRIC_INACTIVE_ALPHA = 0.55f

// 上下边缘渐变覆盖的总行数（上下各半）：随可见行数换算比例，行数增减时淡出区间保持一致
private const val FADE_TOTAL_LINES = 1.25f

// 上下边缘淡出：按纵向透明度梯度对内容做 DstIn 蒙层，使上下行渐变消失
internal fun Modifier.verticalFadeMask(fadeFraction: Float = 0.25f): Modifier = drawWithCache {
    val brush = Brush.verticalGradient(
        colorStops = arrayOf(
            0.0f to Color.Transparent,
            fadeFraction to Color.Black,
            1f - fadeFraction to Color.Black,
            1f to Color.Transparent,
        )
    )
    onDrawWithContent {
        val bounds = Rect(Offset.Zero, size)
        // 绘制级裁剪与蒙层放在同一图层：子层包边像素在进入图层前即被裁除，
        // 避免英文光晕/字形越过窗口边缘后以未蒙层原色残留
        drawIntoCanvas { canvas ->
            canvas.save()
            canvas.clipRect(bounds)
            canvas.saveLayer(bounds, Paint())
        }
        drawContent()
        drawRect(brush = brush, size = size, blendMode = BlendMode.DstIn)
        drawIntoCanvas { canvas ->
            canvas.restore()
            canvas.restore()
        }
    }
}
