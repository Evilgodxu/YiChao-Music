package com.yichao.evilgodxu.screens.home.home_assembly.player_area

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
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
                    ?.let { MusicMetadataCache.decodeSampledBitmap(it)?.asImageBitmap() }
            }
        }
    }
    // 按曲目音频身份在 IO 线程提取内嵌封面，作为无缓存封面时的高质量回退
    val embedded by produceState<androidx.compose.ui.graphics.ImageBitmap?>(
        initialValue = null,
        key1 = track?.audioUri,
        key2 = track?.path,
    ) {
        value = track?.let { t ->
            if (t.path.isNotBlank() || t.audioUri.startsWith("content:") || t.audioUri.startsWith("file:")) {
                withContext(Dispatchers.IO) {
                    MusicScanner.loadEmbeddedCover(context, Uri.parse(t.audioUri), t.path)?.asImageBitmap()
                }
            } else null
        }
    }
    val onlineUrl = track?.neteaseCoverUrl?.takeIf { it.isNotBlank() }
    when {
        cached != null -> Image(
            bitmap = cached!!,
            contentDescription = track?.title,
            contentScale = ContentScale.Crop,
            modifier = modifier.background(Color.Black),
        )
        embedded != null -> Image(
            bitmap = embedded!!,
            contentDescription = track?.title,
            contentScale = ContentScale.Crop,
            modifier = modifier.background(Color.Black),
        )
        onlineUrl != null -> AsyncImage(
            model = onlineUrl,
            contentDescription = track.title,
            contentScale = ContentScale.Crop,
            modifier = modifier.background(Color.Black),
        )
        else -> Box(
            modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}
