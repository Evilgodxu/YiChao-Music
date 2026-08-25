package com.yichao.evilgodxu.musicpanel

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.toBitmap
import com.yichao.evilgodxu.theme.md_theme_dark_surface
import com.yichao.evilgodxu.theme.md_theme_dark_surfaceVariant
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// 歌曲封面沉浸式背景：以小尺寸解码封面，取上下半区平均色组成向下渐变。
// 首页与 3D 封面轮播共用，随传入曲目实时变化。
@Composable
internal fun SongGradientBackground(
    track: MusicTrack?,
    modifier: Modifier = Modifier,
    darkenStatusBarArea: Boolean = true,
) {
    val context = LocalContext.current
    val defaultGradient = defaultSongGradient()
    var gradient by remember { mutableStateOf(defaultGradient) }
    val model = songCoverModel(track)
    LaunchedEffect(model, darkenStatusBarArea) {
        gradient = if (model != null) {
            songGradient(context, model, darkenStatusBarArea) ?: defaultGradient
        } else {
            defaultGradient
        }
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(gradient),
    )
}

// 首页默认背景固定深色，不随主题变化
private fun defaultSongGradient(): Brush =
    Brush.verticalGradient(
        listOf(
            md_theme_dark_surface,
            md_theme_dark_surfaceVariant,
        )
    )

// 当前歌曲封面来源：磁盘缓存优先，其次在线封面 URL；
// 本地音频源在后台提取内嵌封面完成前不触发在线封面请求
private fun songCoverModel(track: MusicTrack?): Any? {
    val coverFile = track?.coverCachePath
        ?.takeIf { MusicMetadataCache.isValid(it) }
        ?.let { File(it) }
    if (coverFile != null) return coverFile
    if (track?.isLocalAudioSource == true) return null
    return track?.neteaseCoverUrl?.takeIf { it.isNotBlank() }
}

// 以小尺寸解码封面，取上下半区平均色组成向下渐变；
// 竖屏时顶部压暗保证状态栏区域足够深，横屏系统栏隐藏时跳过该处理
private suspend fun songGradient(context: Context, model: Any, darkenStatusBarArea: Boolean): Brush? = withContext(Dispatchers.IO) {
    val result = context.imageLoader.execute(
        ImageRequest.Builder(context)
            .data(model)
            .size(32)
            .build()
    )
    val source = result.image?.toBitmap() ?: return@withContext null
    val bitmap = if (source.config == Bitmap.Config.HARDWARE) {
        source.copy(Bitmap.Config.ARGB_8888, false) ?: return@withContext null
    } else source
    val topColor = bitmap.avgColor(topHalf = true).darkenIfNearWhite()
    Brush.verticalGradient(
        colorStops = arrayOf(
            0f to if (darkenStatusBarArea) topColor.darkenedForStatusBar() else topColor,
            0.12f to topColor,
            1f to bitmap.avgColor(topHalf = false).darkenIfNearWhite(),
        )
    )
}

// 顶部压暗封面色：保留封面色调又足够深，保证状态栏白色图标始终可见
private fun Color.darkenedForStatusBar(): Color = lerp(this, md_theme_dark_surface, 0.3f)

// 与白色前景（按钮标题/歌词）亮度相近时轻微压暗，保证文字可读
private fun Color.darkenIfNearWhite(): Color {
    val luminance = 0.299f * red + 0.587f * green + 0.114f * blue
    return if (luminance > 0.8f) lerp(this, Color.Black, 0.2f) else this
}

private fun Bitmap.avgColor(topHalf: Boolean): Color {
    val startY = if (topHalf) 0 else height / 2
    val endY = if (topHalf) height / 2 else height
    var r = 0L
    var g = 0L
    var b = 0L
    var count = 0L
    for (y in startY until endY) {
        for (x in 0 until width) {
            val c = getPixel(x, y)
            r += (c shr 16) and 0xFF
            g += (c shr 8) and 0xFF
            b += c and 0xFF
            count++
        }
    }
    if (count == 0L) return Color.Black
    return Color(
        red = (r / count).toFloat() / 255f,
        green = (g / count).toFloat() / 255f,
        blue = (b / count).toFloat() / 255f,
    )
}