package com.yichao.evilgodxu.screens.home.home_assembly.player_area

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import com.yichao.evilgodxu.ui.icons.AppIcons
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.yichao.evilgodxu.musicpanel.MusicMetadataCache
import com.yichao.evilgodxu.musicpanel.MusicPanelStateHolder
import com.yichao.evilgodxu.musicpanel.MusicScanner
import com.yichao.evilgodxu.musicpanel.MusicTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// 首页封面显示解码上限：与缓存保存上限对齐，避免超大图全尺寸进内存
private const val DISPLAY_MAX_EDGE = 2048

// 首页大封面：优先显示已应用的封面缓存文件（修改封面后即时重载），其次音频内嵌原图，最后在线原图
@Composable
internal fun HomeAlbumArt(track: MusicTrack?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // 以路径与封面写入版本号为 key：路径变化或重新写入新封面时均强制重载
    val cachePath = track?.coverCachePath?.takeIf { MusicMetadataCache.isValid(it) }
    val cacheRevision = MusicPanelStateHolder.state.coverRevision
    val cached by produceState<androidx.compose.ui.graphics.ImageBitmap?>(
        initialValue = null,
        key1 = cachePath,
        key2 = cacheRevision,
    ) {
        value = cachePath?.let { path ->
            withContext(Dispatchers.IO) {
                MusicMetadataCache.loadCoverBytes(path)
                    // 显示端按最长边限幅解码，避免 4K 缓存封面全尺寸进内存
                    ?.let { MusicMetadataCache.decodeSampledBitmap(it, DISPLAY_MAX_EDGE)?.asImageBitmap() }
            }
        }
    }
    // 仅当无缓存封面时按曲目音频身份在 IO 线程提取内嵌封面，避免每次显示重复元数据 I/O
    val embedded by produceState<androidx.compose.ui.graphics.ImageBitmap?>(
        initialValue = null,
        key1 = cachePath,
        key2 = track?.audioUri,
    ) {
        value = if (cachePath != null) null
        else track?.takeIf { it.isLocalAudioSource }?.let { t ->
            withContext(Dispatchers.IO) {
                MusicScanner.loadEmbeddedCover(context, Uri.parse(t.audioUri), t.path)?.asImageBitmap()
            }
        }
    }
    val onlineUrl = track?.neteaseCoverUrl?.takeIf { it.isNotBlank() }
    when {
        cached != null -> Image(
            bitmap = cached!!,
            contentDescription = track?.title,
            contentScale = ContentScale.Crop,
            // 高清渲染：mipmap 三线性过滤，缩放/旋转均无锯齿与模糊
            filterQuality = FilterQuality.High,
            modifier = modifier.background(Color.Black),
        )
        embedded != null -> Image(
            bitmap = embedded!!,
            contentDescription = track?.title,
            contentScale = ContentScale.Crop,
            // 高清渲染：mipmap 三线性过滤，缩放/旋转均无锯齿与模糊
            filterQuality = FilterQuality.High,
            modifier = modifier.background(Color.Black),
        )
        onlineUrl != null -> AsyncImage(
            model = onlineUrl,
            contentDescription = track.title,
            contentScale = ContentScale.Crop,
            // 高清渲染：mipmap 三线性过滤，缩放/旋转均无锯齿与模糊
            filterQuality = FilterQuality.High,
            modifier = modifier.background(Color.Black),
        )
        else -> Box(
            modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = AppIcons.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

// 封面底部渐隐区占封面高度的比例
private const val BOTTOM_FADE_FRACTION = 0.2f

// 首页沉浸式封面：全宽置顶，上下边缘渐隐为透明融入真实渲染背景
@Composable
internal fun HomeImmersiveCover(
    track: MusicTrack?,
    topFraction: Float,
    modifier: Modifier = Modifier,
) {
    HomeAlbumArt(
        track = track,
        modifier = modifier.verticalFadeMask(
            topFraction.coerceIn(0f, 1f - BOTTOM_FADE_FRACTION),
            BOTTOM_FADE_FRACTION,
        ),
    )
}

// 上下边缘渐隐蒙层：与跑马灯同款 DstIn 处理，封面顶部与底部渐变消失透出背景
private fun Modifier.verticalFadeMask(topFraction: Float, bottomFraction: Float): Modifier = drawWithCache {
    val brush = Brush.verticalGradient(
        colorStops = arrayOf(
            0f to Color.Transparent,
            topFraction to Color.Black,
            1f - bottomFraction to Color.Black,
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
