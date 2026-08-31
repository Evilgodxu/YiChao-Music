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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
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

// 封面加载顺序：本地缓存（内嵌/已匹配在线）→ 在线封面 → 占位符。
// 本地音频源的内嵌封面由后台提取，提取完成前不直接回退在线封面，保证内嵌优先
private fun coverModel(track: MusicTrack?): Any? {
    val coverFile = track?.coverCachePath
        ?.takeIf { MusicMetadataCache.isValid(it) }
        ?.let { File(it) }
    if (coverFile != null) return coverFile
    if (track?.isLocalAudioSource == true) return null
    return track?.neteaseCoverUrl?.takeIf { it.isNotBlank() }
}

@Composable
internal fun AlbumArt(track: MusicTrack?, modifier: Modifier = Modifier) {
    // 仅当封面相关字段变化时重算，避免列表重组时重复文件系统 stat
    val model = remember(track?.id, track?.coverCachePath, track?.neteaseCoverUrl) {
        coverModel(track)
    }
    // 封面缺失时按需补全：幂等，补全成功后回写 coverCachePath 驱动重组重新加载
    LaunchedEffect(track?.id, track?.coverCachePath, track?.neteaseCoverUrl, track?.coverFailed) {
        track?.let { MusicPanelStateHolder.state.requestMetadata(it) }
    }
    if (model != null) {
        AsyncImage(
            model = model,
            contentDescription = track?.title,
            contentScale = ContentScale.Crop,
            // 高清渲染：mipmap 三线性过滤，3D 透视/旋转缩放均无锯齿与模糊
            filterQuality = FilterQuality.High,
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
    // 列表小图直接使用磁盘缓存或在线原图，由 Coil 按显示尺寸高质量下采样；
    // 128px CDN 缩略图在高 DPI 下列表放大显示会模糊，故不再使用
    val model = remember(track?.id, track?.coverCachePath, track?.neteaseCoverUrl) {
        coverModel(track)
    }
    // 列表项封面缺失时按需补全（懒加载）：滚入视口的曲目才触发提取
    LaunchedEffect(track?.id, track?.coverCachePath, track?.neteaseCoverUrl, track?.coverFailed) {
        track?.let { MusicPanelStateHolder.state.requestMetadata(it) }
    }
    if (model != null) {
        AsyncImage(
            model = model,
            contentDescription = track?.title,
            contentScale = ContentScale.Crop,
            // 高清渲染：mipmap 三线性过滤，列表小图缩放平滑
            filterQuality = FilterQuality.High,
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

// 长按菜单定位：水平居中于父布局，纵向紧贴父布局顶部或底部
@Composable
internal fun menuEdgePositionProvider(atTop: Boolean): PopupPositionProvider {
    val density = LocalDensity.current
    return remember(density, atTop) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset {
                val gapPx = with(density) { 2.dp.roundToPx() }
                val y = if (atTop) {
                    anchorBounds.top - popupContentSize.height - gapPx
                } else {
                    anchorBounds.bottom + gapPx
                }
                return IntOffset(
                    x = anchorBounds.left + (anchorBounds.width - popupContentSize.width) / 2,
                    y = y,
                )
            }
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
        Popup(
            onDismissRequest = onDismiss,
            properties = PopupProperties(focusable = true),
            popupPositionProvider = menuEdgePositionProvider(atTop = false),
        ) {
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
    onSearch: (() -> Unit)? = null,
) {
    if (visible) {
        Popup(
            properties = PopupProperties(
                focusable = true,
                dismissOnBackPress = true,
                dismissOnClickOutside = true
            ),
            onDismissRequest = onDismiss,
            popupPositionProvider = menuEdgePositionProvider(atTop = true),
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
                    if (onSearch != null) {
                        Spacer(modifier = Modifier.width(2.dp))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color.Transparent,
                            onClick = onSearch
                        ) {
                            Text(
                                text = stringResource(R.string.music_panel_search_title),
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
        }
        // 长按菜单锚定本列：显示在父布局顶部
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
