package com.yichao.evilgodxu.musicpanel


import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import com.yichao.evilgodxu.ui.icons.AppIcons
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import coil3.compose.AsyncImage
import com.yichao.evilgodxu.R
import java.io.File

@Composable
internal fun CurrentCover(
    track: MusicTrack?,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onOnlineCover: () -> Unit = {},
    onLocalCover: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var showMenu by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(72.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = { if (track != null) showMenu = true }
            ),
        contentAlignment = Alignment.Center
    ) {
        // 复用光碟效果：旋转 + 黑胶质感，与迷你播放器一致
        DiscArt(
            track = track,
            isPlaying = isPlaying,
            modifier = Modifier.size(64.dp)
        )
        CoverContextMenu(
            visible = showMenu,
            onOnlineCover = {
                showMenu = false
                onOnlineCover()
            },
            onLocalCover = {
                showMenu = false
                onLocalCover()
            },
            onDismiss = { showMenu = false }
        )
    }
}

@Composable
internal fun AlbumArt(track: MusicTrack?, modifier: Modifier = Modifier) {
    // 封面缓存为绝对路径字符串，包装成 File 才能被 Coil 识别（直接传路径会丢失 scheme）
    val coverFile = track?.coverCachePath
        ?.takeIf { MusicMetadataCache.isValid(it) }
        ?.let { File(it) }
    val model: Any? = coverFile ?: track?.neteaseCoverUrl?.takeIf { it.isNotBlank() }
    if (model != null) {
        AsyncImage(
            model = model,
            contentDescription = track?.title,
            contentScale = ContentScale.Crop,
            modifier = modifier.background(Color.Black),
        )
    } else {
        Box(
            modifier = modifier
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = AppIcons.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
internal fun PlaylistArt(track: MusicTrack?, modifier: Modifier = Modifier) {
    // 列表行小图：优先磁盘缓存文件与在线缩略 URL，避免加载全尺寸封面
    val coverFile = track?.coverCachePath
        ?.takeIf { MusicMetadataCache.isValid(it) }
        ?.let { File(it) }
    val thumbUrl = track?.neteaseCoverUrl
        ?.takeIf { it.isNotBlank() }
        ?.let { NeteaseMusicApi.thumbUrl(it) }
    val model: Any? = coverFile ?: thumbUrl
    if (model != null) {
        AsyncImage(
            model = model,
            contentDescription = track?.title,
            contentScale = ContentScale.Crop,
            modifier = modifier.background(Color.Black),
        )
    } else {
        Box(
            modifier = modifier
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = AppIcons.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(12.dp)
            )
        }
    }
}

@Composable
internal fun CoverContextMenu(
    visible: Boolean,
    onOnlineCover: () -> Unit,
    onLocalCover: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (visible) {
        Popup(alignment = Alignment.BottomCenter, properties = PopupProperties(focusable = true), onDismissRequest = onDismiss) {
            Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant, tonalElevation = 4.dp) {
                Row(horizontalArrangement = Arrangement.Center) {
                    Surface(shape = RoundedCornerShape(6.dp), color = Color.Transparent, onClick = onOnlineCover) {
                        Text(
                            text = stringResource(R.string.music_panel_online_cover),
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                        )
                    }
                    Surface(shape = RoundedCornerShape(6.dp), color = Color.Transparent, onClick = onLocalCover) {
                        Text(
                            text = stringResource(R.string.music_panel_local_cover),
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun MiniContextMenu(
    visible: Boolean,
    onCopy: () -> Unit,
    onRename: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (visible) {
        Popup(
            alignment = Alignment.BottomCenter,
            properties = PopupProperties(
                focusable = true,
                dismissOnBackPress = true,
                dismissOnClickOutside = true
            ),
            onDismissRequest = onDismiss
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = 4.dp,
                modifier = Modifier.padding(top = 2.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color.Transparent,
                        onClick = onCopy
                    ) {
                        Text(
                            text = stringResource(R.string.music_panel_copy),
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(2.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color.Transparent,
                        onClick = onRename
                    ) {
                        Text(
                            text = stringResource(R.string.music_panel_rename),
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun TrackInfo(
    playbackState: MusicPlaybackState,
    onClick: () -> Unit = {},
    onRenameRequest: ((isTitle: Boolean, text: String) -> Unit)? = null,
) {
    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }
    var menuText by remember { mutableStateOf("") }
    var menuIsTitle by remember { mutableStateOf(true) }

    val onLongClickTitle: () -> Unit = {
        val track = playbackState.currentTrack
        if (track != null) {
            menuText = track.title
            menuIsTitle = true
            showMenu = true
        }
    }
    val onLongClickArtist: () -> Unit = {
        val artist = playbackState.currentTrack?.artist
        if (!artist.isNullOrBlank()) {
            menuText = artist
            menuIsTitle = false
            showMenu = true
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { if (!showMenu) onClick() },
                onLongClick = onLongClickTitle
            )
            .padding(top = 4.dp, bottom = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val title = when {
            playbackState.currentTrack != null -> playbackState.currentTrack!!.title
            playbackState.isScanning -> stringResource(R.string.music_panel_scanning)
            else -> stringResource(R.string.music_panel_empty)
        }
        Box {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                modifier = Modifier
                    .basicMarquee(iterations = Int.MAX_VALUE)
                    .combinedClickable(
                        onClick = { if (!showMenu) onClick() },
                        onLongClick = onLongClickTitle
                    )
            )
            MiniContextMenu(
                visible = showMenu && menuIsTitle,
                onCopy = {
                    showMenu = false
                    copyToClipboard(context, menuText)
                },
                onRename = {
                    showMenu = false
                    onRenameRequest?.invoke(menuIsTitle, menuText)
                },
                onDismiss = { showMenu = false }
            )
        }
        Box {
            Text(
                text = playbackState.currentTrack?.artist ?: "",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.combinedClickable(
                    onClick = { if (!showMenu) onClick() },
                    onLongClick = onLongClickArtist
                )
            )
            MiniContextMenu(
                visible = showMenu && !menuIsTitle,
                onCopy = {
                    showMenu = false
                    copyToClipboard(context, menuText)
                },
                onRename = {
                    showMenu = false
                    onRenameRequest?.invoke(menuIsTitle, menuText)
                },
                onDismiss = { showMenu = false }
            )
        }
    }
}
