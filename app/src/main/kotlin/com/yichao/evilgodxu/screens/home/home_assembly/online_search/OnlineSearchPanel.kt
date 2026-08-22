package com.yichao.evilgodxu.screens.home.home_assembly.online_search

import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.musicpanel.MusicPlaybackState
import com.yichao.evilgodxu.musicpanel.SearchResultRow
import com.yichao.evilgodxu.musicpanel.performSearch
import com.yichao.evilgodxu.musicpanel.playSearchResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// 首页专属在线搜索面板：搜索输入/历史/结果逻辑与其样式在此独立封装
@Composable
internal fun OnlineSearchPanel(
    playbackState: MusicPlaybackState,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    Column(modifier = modifier) {
        PanelHeader()
        SearchInput(playbackState = playbackState, context = context, scope = scope)
        if (playbackState.showSearchResults) {
            SearchResultList(
                playbackState = playbackState,
                context = context,
                scope = scope,
            )
        } else if (playbackState.searchHistory.isNotEmpty()) {
            SearchHistoryList(
                playbackState = playbackState,
                context = context,
                scope = scope,
            )
        }
    }
}

// 面板标题栏：仅显示标题，关闭操作通过父级手势左滑或系统返回键完成
@Composable
private fun PanelHeader() {
    Text(
        text = stringResource(R.string.music_panel_search_title),
        color = Color.White,
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 2.dp)
    )
}

// 搜索输入框
@Composable
private fun SearchInput(
    playbackState: MusicPlaybackState,
    context: Context,
    scope: CoroutineScope,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp)
            .height(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Color.Transparent)
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.45f),
                shape = RoundedCornerShape(24.dp)
            ),
        contentAlignment = Alignment.CenterStart
    ) {
        Icon(
            imageVector = Icons.Default.Search,
            contentDescription = null,
            tint = Color.White,
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
                color = Color.White,
                fontSize = 14.sp
            ),
            cursorBrush = SolidColor(Color.White),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(
                onSearch = {
                    val query = playbackState.searchQuery.trim()
                    if (query.isNotBlank()) {
                        scope.launch { performSearch(playbackState, context) }
                    }
                }
            ),
            decorationBox = { innerTextField ->
                Box {
                    if (playbackState.searchQuery.isEmpty()) {
                        Text(
                            text = stringResource(R.string.music_panel_search_placeholder),
                            color = Color.White.copy(alpha = 0.5f),
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
                    imageVector = Icons.Default.Close,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

// 搜索历史列表
@Composable
private fun SearchHistoryList(
    playbackState: MusicPlaybackState,
    context: Context,
    scope: CoroutineScope,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 15.dp)
    ) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 4.dp, top = 12.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.music_panel_search_history),
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
        IconButton(
            onClick = { playbackState.clearSearchHistory() },
            modifier = Modifier.size(28.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(R.string.music_panel_search_history_clear),
                tint = Color.White,
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
                    color = Color.White,
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
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.music_panel_search_history_delete),
                        tint = Color.White,
                        modifier = Modifier.size(15.dp)
                    )
                }
            }
        }
    }
    }
}

// 搜索状态区：结果计数/刷新/加载/空/错误/列表
@Composable
private fun SearchResultList(
    playbackState: MusicPlaybackState,
    context: Context,
    scope: CoroutineScope,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.music_panel_track_count, playbackState.searchResults.size),
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 11.sp
            )
            IconButton(
                onClick = {
                    if (!playbackState.isSearching) {
                        scope.launch { performSearch(playbackState, context) }
                    }
                },
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    tint = if (playbackState.isSearching) Color.White.copy(alpha = 0.5f)
                    else Color.White
                )
            }
        }
        val errorMsg = playbackState.errorMsg
        // 错误提示展示 2 秒后自动清除；新错误到来会重置计时
        LaunchedEffect(errorMsg) {
            if (errorMsg != null) {
                delay(2000)
                playbackState.setErrorMsg(null)
            }
        }
        if (errorMsg != null) {
            Text(
                text = errorMsg,
                color = MaterialTheme.colorScheme.error,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            )
        }
        // 加载/空/结果间淡入淡出过渡，避免搜索结果生硬插入
        AnimatedContent(
            targetState = when {
                playbackState.isSearching -> 0
                playbackState.searchResults.isEmpty() -> 1
                else -> 2
            },
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "search_state",
        ) { state ->
            when (state) {
                0 -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
                1 -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.music_panel_search_no_results),
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 12.sp
                    )
                }
                else -> {
                    LazyColumn(
                        state = rememberLazyListState(),
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        itemsIndexed(
                            items = playbackState.searchResults,
                            // 聚合多来源后 id 可能重复，key 需结合来源保证唯一
                            key = { _, result -> "${result.source}-${result.id}" }
                        ) { _, result ->
                            SearchResultRow(
                                result = result,
                                titleColor = Color.White,
                                onClick = { scope.launch { playSearchResult(result, playbackState, context, scope) } }
                            )
                        }
                    }
                }
            }
        }
    }
}