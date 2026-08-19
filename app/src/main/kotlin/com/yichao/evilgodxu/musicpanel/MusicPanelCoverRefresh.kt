package com.yichao.evilgodxu.musicpanel

import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import com.yichao.evilgodxu.R

@Composable
internal fun CoverRefreshOverlay(
    visible: Boolean,
    track: MusicTrack?,
    playbackState: MusicPlaybackState,
    context: Context,
    selectedId: Long?,
    saving: Boolean,
    onCandidateSelected: (NeteaseSongSearchResult) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    if (!visible || track == null) return
    Box(
        modifier = Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = .97f))
            .pointerInput(Unit) { detectHorizontalDragGestures { _, amount -> if (amount > 50) onCancel() } },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.music_panel_refresh_cover), color = MaterialTheme.colorScheme.onSurface)
            if (playbackState.isCoverSearching) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            } else if (playbackState.coverCandidates.isEmpty()) {
                Text(stringResource(R.string.music_panel_cover_no_candidates), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                ) {
                    items(playbackState.coverCandidates, key = { it.id }) { candidate ->
                        val selected = candidate.id == selectedId
                        Column(
                            modifier = Modifier.width(92.dp).clickable { onCandidateSelected(candidate) },
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                                color = MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                AsyncImage(
                                    model = ImageRequest.Builder(LocalContext.current)
                                        .data(candidate.coverUrl)
                                        .diskCachePolicy(CachePolicy.DISABLED)
                                        .build(),
                                    contentDescription = candidate.title,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(84.dp).clip(RoundedCornerShape(8.dp))
                                )
                            }
                            Text(candidate.title, maxLines = 1, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .08f), onClick = onCancel) {
                    Text(stringResource(R.string.music_panel_rename_cancel), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp))
                }
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (selectedId != null && !saving) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    onClick = { if (selectedId != null && !saving) onConfirm() }
                ) {
                    Box(modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(R.string.music_panel_rename_confirm),
                            color = if (saving) Color.Transparent else if (selectedId != null) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (saving) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun CoverReplaceOverlay(
    visible: Boolean,
    track: MusicTrack?,
    candidate: NeteaseSongSearchResult?,
    saving: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    AnimatedContent(
        targetState = visible,
        transitionSpec = { (slideInVertically { it } + fadeIn()).togetherWith(slideOutVertically { it } + fadeOut()) },
        label = "cover_replace"
    ) { show ->
        if (show && track != null && candidate != null) {
            Column(
                modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface.copy(alpha = .96f))
                    .clickable(onClick = onCancel).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(stringResource(R.string.music_panel_cover_replace_title), color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(vertical = 18.dp)) {
                    AlbumArt(track = track, modifier = Modifier.size(96.dp).clip(RoundedCornerShape(10.dp)))
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(candidate.coverUrl)
                            .diskCachePolicy(CachePolicy.DISABLED)
                            .build(),
                        contentDescription = candidate.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(96.dp).clip(RoundedCornerShape(10.dp))
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .08f), onClick = onCancel) {
                        Text(stringResource(R.string.music_panel_rename_cancel), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp))
                    }
                    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.primary, onClick = { if (!saving) onConfirm() }) {
                        Box(modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                            Text(
                                stringResource(R.string.music_panel_rename_confirm),
                                color = if (saving) Color.Transparent else MaterialTheme.colorScheme.onPrimary
                            )
                            if (saving) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                            }
                        }
                    }
                }
            }
        } else Box(modifier = Modifier.fillMaxSize())
    }
}
