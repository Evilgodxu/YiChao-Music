package com.yichao.evilgodxu.screens.home.component.panel

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.yichao.evilgodxu.screens.home.component.panel.HomePanelState
import com.yichao.evilgodxu.screens.home.component.playlist.PlaylistPanel
import com.yichao.evilgodxu.screens.home.component.search.OnlineSearchPanel

// 首页滑动覆盖层：在线搜索自左侧滑入、歌单面板自右侧滑入，均顶替播放器位置
@Composable
internal fun HomePanels(
    panelState: HomePanelState,
    // 内容区宽度：滑动距离换算为位移的基准
    contentWidth: Dp,
    // 标题栏高度：面板内容仍从标题栏下方开始
    topInset: Dp,
) {
    val swipeController = panelState.swipeController
    OnlineSearchPanel(
        playbackState = panelState.playbackState.state,
        menuBackgroundColor = panelState.backgroundColor,
        modifier = Modifier
            .fillMaxSize()
            .padding(top = topInset)
            .graphicsLayer {
                translationX = -contentWidth.toPx() * (1f - swipeController.searchProgress)
            },
    )
    PlaylistPanel(
        visible = swipeController.showPlaylist,
        playbackState = panelState.playbackState.state,
        menuBackgroundColor = panelState.backgroundColor,
        modifier = Modifier
            .fillMaxSize()
            .padding(top = topInset)
            .graphicsLayer {
                translationX = contentWidth.toPx() * (1f - swipeController.playlistProgress)
            },
    )
}
