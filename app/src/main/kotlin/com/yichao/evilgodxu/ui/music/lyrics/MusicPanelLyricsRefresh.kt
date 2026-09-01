package com.yichao.evilgodxu.ui.music.lyrics

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import com.yichao.evilgodxu.data.music.api.sourceNameRes
import com.yichao.evilgodxu.data.music.model.MusicSearchSource
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.data.music.model.NeteaseSongSearchResult
import com.yichao.evilgodxu.dialog.MetadataDialogCard
import com.yichao.evilgodxu.domain.music.MusicErrorBanner
import com.yichao.evilgodxu.domain.music.MusicPlaybackState
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.ui.icons.AppIcons

@Composable
internal fun LyricsRefreshOverlay(
    visible: Boolean,
    track: MusicTrack?,
    playbackState: MusicPlaybackState,
    selectedId: Long?,
    context: Context,
    onCandidateSelected: (NeteaseSongSearchResult) -> Unit,
    onSourceSelected: (MusicSearchSource) -> Unit,
    onRefresh: () -> Unit,
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
                onSourceSelected = onSourceSelected,
                onRefresh = onRefresh,
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
    onSourceSelected: (MusicSearchSource) -> Unit,
    onRefresh: () -> Unit,
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
                onSourceSelected = onSourceSelected,
                onRefresh = onRefresh,
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

// 歌词刷新共享主体：标题行(点击切换来源+刷新按钮) + 候选 / 状态 + 按钮，供全屏蒙层与对话框复用
@Composable
private fun LyricsRefreshContent(
    playbackState: MusicPlaybackState,
    selectedId: Long?,
    context: Context,
    onCandidateSelected: (NeteaseSongSearchResult) -> Unit,
    onSourceSelected: (MusicSearchSource) -> Unit,
    onRefresh: () -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val searching = playbackState.isLyricsSearching || playbackState.isLyricsRefreshing
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 标题行：居中显示当前来源名，点击弹出来源下拉列表，右侧独立刷新按钮
        Box(Modifier.fillMaxWidth()) {
            var sourceMenuExpanded by remember { mutableStateOf(false) }
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = !searching) { sourceMenuExpanded = true }
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(playbackState.lyricsRefreshSource.sourceNameRes()),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 16.sp,
                    )
                    Icon(
                        imageVector = AppIcons.ArrowDropDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(18.dp),
                    )
                }
                DropdownMenu(
                    expanded = sourceMenuExpanded,
                    onDismissRequest = { sourceMenuExpanded = false },
                ) {
                    MusicSearchSource.entries.forEach { source ->
                        DropdownMenuItem(
                            text = { Text(stringResource(source.sourceNameRes())) },
                            onClick = {
                                sourceMenuExpanded = false
                                onSourceSelected(source)
                            },
                            trailingIcon = {
                                if (source == playbackState.lyricsRefreshSource) {
                                    Icon(
                                        imageVector = AppIcons.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            },
                        )
                    }
                }
            }
            IconButton(
                onClick = onRefresh,
                enabled = !searching,
                modifier = Modifier.align(Alignment.CenterEnd),
            ) {
                Icon(
                    imageVector = AppIcons.Refresh,
                    contentDescription = stringResource(R.string.music_panel_refresh_lyrics),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        if (searching) {
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
