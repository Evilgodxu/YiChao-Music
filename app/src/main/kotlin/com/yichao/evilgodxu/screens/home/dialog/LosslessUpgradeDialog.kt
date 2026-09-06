package com.yichao.evilgodxu.screens.home.dialog

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import com.yichao.evilgodxu.data.music.model.NeteaseSongSearchResult
import com.yichao.evilgodxu.dialog.MetadataDialogCard
import com.yichao.evilgodxu.domain.music.MusicPanelStateHolder
import com.yichao.evilgodxu.domain.music.PlaybackController
import com.yichao.evilgodxu.domain.music.searchLosslessUpgradeCandidates
import com.yichao.evilgodxu.domain.music.upgradeTrackToLossless
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.ui.icons.AppIcons
import kotlinx.coroutines.launch

// 无损升级对话框（与刷新封面/歌词同风格）：按来源搜索在线原曲，展示封面/标题/艺术家供用户确认，
// 确认后下载该候选的无损版本替换本地文件；升级中不可关闭，失败时保留供重试
@Composable
internal fun LosslessUpgradeDialog(
    visible: Boolean,
    playbackState: PlaybackController,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ui = MusicPanelStateHolder.ui
    val track = playbackState.currentTrack
    var selectedCandidate by remember { mutableStateOf<NeteaseSongSearchResult?>(null) }

    // 打开且曲目/来源变化时自动搜索候选
    LaunchedEffect(visible, track?.id, ui.losslessUpgradeSource) {
        if (visible && track != null) {
            selectedCandidate = null
            ui.searchLosslessUpgradeCandidates(context, track, ui.losslessUpgradeSource)
        }
    }

    if (!visible || track == null) return
    MetadataDialogCard(onDismiss = { if (!ui.losslessUpgradeBusy) onDismiss() }) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 标题行：居中显示当前来源名，点击弹出来源下拉列表，右侧独立刷新按钮
            Box(Modifier.fillMaxWidth()) {
                var sourceMenuExpanded by remember { mutableStateOf(false) }
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = !ui.isLosslessUpgradeSearching && !ui.losslessUpgradeBusy) {
                            sourceMenuExpanded = true
                        }
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(ui.losslessUpgradeSource.sourceNameRes()),
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
                                    if (source != ui.losslessUpgradeSource) {
                                        ui.losslessUpgradeSource = source
                                    }
                                },
                                trailingIcon = {
                                    if (source == ui.losslessUpgradeSource) {
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
                    onClick = {
                        selectedCandidate = null
                        ui.searchLosslessUpgradeCandidates(context, track, ui.losslessUpgradeSource)
                    },
                    enabled = !ui.isLosslessUpgradeSearching && !ui.losslessUpgradeBusy,
                    modifier = Modifier.align(Alignment.CenterEnd),
                ) {
                    Icon(
                        imageVector = AppIcons.Refresh,
                        contentDescription = stringResource(R.string.home_upgrade_lossless_title),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            // 候选或状态：展示封面/标题/艺术家，点击选中
            when {
                ui.isLosslessUpgradeSearching -> {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
                ui.losslessUpgradeCandidates.isEmpty() -> {
                    Text(
                        stringResource(R.string.home_upgrade_lossless_no_candidates),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(ui.losslessUpgradeCandidates, key = { it.id }) { candidate ->
                        val selected = candidate.id == selectedCandidate?.id
                        Column(
                            modifier = Modifier
                                .width(112.dp)
                                .clickable { if (!ui.losslessUpgradeBusy) selectedCandidate = candidate },
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                border = if (selected) {
                                    BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                                } else null,
                            ) {
                                val coverUrl = candidate.coverUrl?.takeIf { it.isNotBlank() }
                                Box(
                                    modifier = Modifier
                                        .size(96.dp)
                                        .clip(RoundedCornerShape(8.dp)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (coverUrl != null) {
                                        AsyncImage(
                                            model = ImageRequest.Builder(context)
                                                .data(coverUrl)
                                                .diskCachePolicy(CachePolicy.DISABLED)
                                                .build(),
                                            contentDescription = candidate.title,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    } else {
                                        Icon(
                                            imageVector = AppIcons.MusicNote,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(32.dp),
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(1.dp))
                            Text(
                                text = candidate.title,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 11.sp,
                                lineHeight = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = candidate.artist,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 10.sp,
                                lineHeight = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            // 最近一次升级失败提示
            ui.losslessUpgradeError?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            // 取消 / 升级按钮：升级中禁用并显示进度
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = .08f),
                    onClick = { if (!ui.losslessUpgradeBusy) onDismiss() },
                ) {
                    Text(
                        stringResource(R.string.home_upgrade_cancel),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
                    )
                }
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (selectedCandidate != null && !ui.losslessUpgradeBusy) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    onClick = {
                        val candidate = selectedCandidate
                        if (candidate != null && !ui.losslessUpgradeBusy) {
                            scope.launch {
                                ui.losslessUpgradeBusy = true
                                ui.losslessUpgradeError = null
                                // 无损升级为播放核心数据编排，经全局状态执行
                                val success = upgradeTrackToLossless(context, MusicPanelStateHolder.state, track, candidate)
                                ui.losslessUpgradeBusy = false
                                if (success) {
                                    ui.losslessUpgradeError = null
                                    ui.losslessUpgradeCandidates = emptyList()
                                    onDismiss()
                                } else {
                                    ui.losslessUpgradeError =
                                        context.getString(R.string.home_upgrade_lossless_failed)
                                }
                            }
                        }
                    },
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            stringResource(R.string.home_upgrade_lossless_confirm),
                            color = if (ui.losslessUpgradeBusy) {
                                Color.Transparent
                            } else if (selectedCandidate != null) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        if (ui.losslessUpgradeBusy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        }
                    }
                }
            }
        }
    }
}
