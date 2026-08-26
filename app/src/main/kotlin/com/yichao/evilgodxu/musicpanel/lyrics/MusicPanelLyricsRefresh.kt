package com.yichao.evilgodxu.musicpanel

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Context
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import com.yichao.evilgodxu.R

@Composable
internal fun LyricsRefreshOverlay(
    visible: Boolean,
    track: MusicTrack?,
    playbackState: MusicPlaybackState,
    selectedId: Long?,
    context: Context,
    onCandidateSelected: (NeteaseSongSearchResult) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut()
    ) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface.copy(alpha = .97f)).clickable { onCancel() }, contentAlignment = Alignment.Center) {
            playbackState.lyricsRefreshError?.let { error ->
                MusicErrorBanner(
                    message = error,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    onDismiss = { playbackState.setLyricsRefreshError(null) }
                )
            }
            LyricsRefreshContent(
                playbackState = playbackState,
                selectedId = selectedId,
                context = context,
                onCandidateSelected = onCandidateSelected,
                onConfirm = onConfirm,
                onCancel = onCancel,
                modifier = Modifier.clickable { }.padding(16.dp),
            )
        }
    }
}

@Composable
internal fun LyricsRefreshDialog(
    visible: Boolean,
    track: MusicTrack?,
    playbackState: MusicPlaybackState,
    selectedId: Long?,
    context: Context,
    onCandidateSelected: (NeteaseSongSearchResult) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    if (visible && track != null) {
        MetadataDialogCard(onDismiss = onCancel) {
            LyricsRefreshContent(
                playbackState = playbackState,
                selectedId = selectedId,
                context = context,
                onCandidateSelected = onCandidateSelected,
                onConfirm = onConfirm,
                onCancel = onCancel,
                modifier = Modifier.padding(16.dp),
            )
            playbackState.lyricsRefreshError?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        }
    }
}

// 歌词刷新共享主体：标题 + 候选 / 状态 + 按钮，供全屏蒙层与对话框复用
@Composable
private fun LyricsRefreshContent(
    playbackState: MusicPlaybackState,
    selectedId: Long?,
    context: Context,
    onCandidateSelected: (NeteaseSongSearchResult) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(R.string.music_panel_refresh_lyrics),
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 16.sp
        )
        if (playbackState.isLyricsSearching || playbackState.isLyricsRefreshing) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        } else if (playbackState.lyricsCandidates.isEmpty()) {
            Text(
                stringResource(R.string.music_panel_lyrics_no_candidates),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(playbackState.lyricsCandidates, key = { it.id }) { candidate ->
                    val selected = candidate.id == selectedId
                    Column(
                        Modifier
                            .width(112.dp)
                            .clickable { onCandidateSelected(candidate) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(1.dp)
                    ) {
                        Surface(shape = RoundedCornerShape(8.dp), border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null) {
                            val coverUrl = candidate.coverUrl?.takeIf { it.isNotBlank() }
                            Box(Modifier.size(96.dp).clip(RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                                if (coverUrl != null) {
                                    AsyncImage(model = ImageRequest.Builder(context).data(coverUrl).diskCachePolicy(CachePolicy.DISABLED).build(), contentDescription = candidate.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                } else {
                                    Text(
                                        stringResource(candidate.source.sourceNameRes()),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 12.sp,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                        Text(
                            text = candidate.title,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant, onClick = onCancel) { Text(stringResource(R.string.music_panel_rename_cancel), Modifier.padding(horizontal = 24.dp, vertical = 10.dp)) }
            Surface(shape = RoundedCornerShape(10.dp), color = if (selectedId != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant, onClick = { if (selectedId != null) onConfirm() }) { Text(stringResource(R.string.music_panel_rename_confirm), Modifier.padding(horizontal = 24.dp, vertical = 10.dp)) }
        }
    }
}

// 无封面候选的平台名占位符资源
private fun MusicSearchSource.sourceNameRes(): Int = when (this) {
    MusicSearchSource.NETEASE -> R.string.music_panel_search_source
    MusicSearchSource.QQ -> R.string.music_panel_search_source_qq
    MusicSearchSource.KUGOU -> R.string.music_panel_search_source_kugou
    MusicSearchSource.KUWO -> R.string.music_panel_search_source_kuwo
    MusicSearchSource.MIGU -> R.string.music_panel_search_source_migu
}