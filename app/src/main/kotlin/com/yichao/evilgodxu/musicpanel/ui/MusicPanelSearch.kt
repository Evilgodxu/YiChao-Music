package com.yichao.evilgodxu.musicpanel

import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import com.yichao.evilgodxu.ui.icons.AppIcons
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import com.yichao.evilgodxu.R
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SearchOverlay(
    playbackState: MusicPlaybackState,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    Column(
        modifier = modifier.pointerInput(Unit) {
            detectHorizontalDragGestures { _, dragAmount ->
                if (dragAmount < -50f) {
                    playbackState.setSearchMode(false)
                    playbackState.setSearchResultsVisible(false)
                }
            }
        }
    ) {
        Text(
            text = stringResource(R.string.music_panel_search_title),
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(12.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
                    shape = RoundedCornerShape(24.dp)
                ),
            contentAlignment = Alignment.CenterStart
        ) {
            Icon(
                imageVector = AppIcons.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(start = 16.dp)
                    .size(20.dp)
            )
            BasicTextField(
                value = playbackState.searchQuery,
                onValueChange = { playbackState.searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 48.dp, end = 44.dp),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = {
                        val query = playbackState.searchQuery.trim()
                        if (query.isNotBlank()) {
                            scope.launch {
                                performSearch(playbackState, context)
                            }
                        }
                    }
                ),
                decorationBox = { innerTextField ->
                    Box {
                        if (playbackState.searchQuery.isEmpty()) {
                            Text(
                                text = stringResource(R.string.music_panel_search_placeholder),
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                fontSize = 14.sp
                            )
                        }
                        innerTextField()
                    }
                }
            )
            if (playbackState.searchQuery.isNotEmpty()) {
                IconButton(
                    onClick = { playbackState.setSearchQuery("") },
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .size(32.dp)
                ) {
                    Icon(
                        imageVector = AppIcons.Close,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        if (playbackState.searchHistory.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.music_panel_search_history),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                IconButton(
                    onClick = { playbackState.clearSearchHistory() },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = AppIcons.Delete,
                        contentDescription = stringResource(R.string.music_panel_search_history_clear),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(playbackState.searchHistory, key = { it }) { query ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                playbackState.setSearchQuery(query)
                                scope.launch { performSearch(playbackState, context) }
                            }
                            .padding(start = 8.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = query,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { playbackState.removeSearchHistory(query) },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = AppIcons.Close,
                                contentDescription = stringResource(R.string.music_panel_search_history_delete),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SearchResultsOverlay(
    visible: Boolean,
    playbackState: MusicPlaybackState,
    context: Context,
    onClose: () -> Unit,
    onRefresh: () -> Unit,
    onTrackSelected: (NeteaseSongSearchResult) -> Unit,
) {
    AnimatedContent(
        targetState = visible,
        transitionSpec = {
            (slideInVertically { it } + fadeIn()).togetherWith(slideOutVertically { it } + fadeOut())
        },
        label = "search_results"
    ) { show ->
        if (show) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClose
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.music_panel_search_title),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.music_panel_track_count, playbackState.searchResults.size),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 10.sp
                        )
                        HeaderIconButton(
                            icon = AppIcons.Refresh,
                            onClick = { if (!playbackState.isSearching) onRefresh() },
                            modifier = Modifier.size(24.dp),
                            enabled = !playbackState.isSearching
                        )
                        HeaderIconButton(
                            icon = AppIcons.Close,
                            onClick = onClose,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                val errorMsg = playbackState.errorMsg
                if (errorMsg != null) {
                    MusicErrorBanner(
                        message = errorMsg,
                        onDismiss = { playbackState.setErrorMsg(null) }
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }

                if (playbackState.isSearching) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                } else if (playbackState.searchResults.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = stringResource(R.string.music_panel_search_no_results),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp
                        )
                    }
                } else {
                    val listState = rememberLazyListState()
                    // 滚动接近列表末尾时加载下一页；仅在滚动位置或列表长度变化时求值，避免持续自动加载
                    LaunchedEffect(listState) {
                        snapshotFlow {
                            val info = listState.layoutInfo
                            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
                            lastVisible to info.totalItemsCount
                        }
                            .distinctUntilChanged()
                            .collect { (lastVisible, total) ->
                                val nearEnd = total > 0 && lastVisible >= total - 3
                                if (nearEnd && !playbackState.isSearching && playbackState.hasMoreSearchResults) {
                                    loadMoreSearchResults(playbackState, context)
                                }
                            }
                    }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        itemsIndexed(
                            items = playbackState.searchResults,
                            // 聚合两种来源后 id 可能重复，key 需结合来源保证唯一
                            key = { _, result -> "${result.source}-${result.id}" }
                        ) { index, result ->
                            SearchResultRow(
                                result = result,
                                onClick = { onTrackSelected(result) }
                            )
                        }
                        // 底部脚注：加载中或全部加载完成后展示
                        if (playbackState.isLoadingMore || !playbackState.hasMoreSearchResults) {
                            item(key = "load-more-footer") {
                                SearchLoadMoreFooter(playbackState)
                            }
                        }
                    }
                }
            }
        } else {
            Box(modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
internal fun SearchResultRow(
    result: NeteaseSongSearchResult,
    onClick: () -> Unit,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            val coverModel = (result.coverThumbUrl ?: result.coverUrl)?.takeIf { it.isNotBlank() }
            if (coverModel != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(coverModel)
                        .diskCachePolicy(CachePolicy.DISABLED)
                        .build(),
                    contentDescription = result.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    imageVector = AppIcons.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = result.title,
                color = titleColor,
                fontSize = 12.sp,
                lineHeight = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = result.artist,
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 10.sp,
                lineHeight = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Text(
            text = stringResource(
                when (result.source) {
                    MusicSearchSource.QQ -> R.string.music_panel_search_source_qq
                    MusicSearchSource.KUGOU -> R.string.music_panel_search_source_kugou
                    MusicSearchSource.KUWO -> R.string.music_panel_search_source_kuwo
                    MusicSearchSource.MIGU -> R.string.music_panel_search_source_migu
                    MusicSearchSource.NETEASE -> R.string.music_panel_search_source
                }
            ),
            color = Color.White,
            fontSize = 9.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .background(
                    Color.White.copy(alpha = 0.10f),
                    RoundedCornerShape(4.dp)
                )
                .padding(horizontal = 5.dp, vertical = 2.dp)
        )
    }
}

// 搜索结果列表底部脚注：加载更多时显示进度，全部加载完成时显示提示
@Composable
internal fun SearchLoadMoreFooter(
    playbackState: MusicPlaybackState,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    when {
        playbackState.isLoadingMore -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = tint
            )
            Spacer(modifier = Modifier.size(8.dp))
            Text(
                text = stringResource(R.string.music_panel_search_loading_more),
                color = tint,
                fontSize = 11.sp
            )
        }
        !playbackState.hasMoreSearchResults && playbackState.searchResults.isNotEmpty() -> Text(
            text = stringResource(R.string.music_panel_search_load_all),
            color = tint.copy(alpha = 0.7f),
            fontSize = 11.sp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            textAlign = TextAlign.Center
        )
    }
}
