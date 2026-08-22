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
import com.yichao.evilgodxu.musicpanel.MusicScanner
import com.yichao.evilgodxu.musicpanel.MusicTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// 首页大封面：内嵌原图优先（从本地音频文件提取），其次在线原图；迷你播放器与音乐面板沿用 AlbumArt 不变
@Composable
internal fun HomeAlbumArt(track: MusicTrack?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // 按曲目音频身份在 IO 线程提取内嵌封面，避免阻塞主线程
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
        embedded != null -> Image(
            bitmap = embedded!!,
            contentDescription = track?.title,
            contentScale = ContentScale.Crop,
            modifier = modifier.background(Color.Black),
        )
        onlineUrl != null -> AsyncImage(
            model = onlineUrl,
            contentDescription = track?.title,
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
