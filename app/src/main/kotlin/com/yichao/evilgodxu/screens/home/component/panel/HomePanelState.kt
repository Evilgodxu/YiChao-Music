package com.yichao.evilgodxu.screens.home.component.panel

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Stable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yichao.evilgodxu.data.settings.swipeToChangeTrackFlow
import com.yichao.evilgodxu.data.music.panel.MusicPanelStateHolder
import com.yichao.evilgodxu.screens.home.component.playlist.LibraryAnalysisController
import com.yichao.evilgodxu.screens.home.component.swipe.HomeSwipeController
import com.yichao.evilgodxu.screens.home.component.swipe.rememberHomeSwipeController
import com.yichao.evilgodxu.theme.md_theme_dark_surface
import com.yichao.evilgodxu.LocalAppContainer

// 首页跨形态共享状态：在形态分派之上创建；旋转不重建 Activity，面板显隐与后台分析需跨形态保持
@Stable
internal class HomePanelState(
    val playbackState: MusicPanelStateHolder,
    // 曲库分析会话：状态与后台任务常驻首页层，关闭对话框后分析继续执行
    val libraryAnalysis: LibraryAnalysisController,
    val swipeController: HomeSwipeController,
) {
    // 首页播放列表面板（底部弹出）显隐：显示期间禁用上下滑动切歌，滚动交由播放列表处理
    var playlistVisible: Boolean
        get() = swipeController.playlistSheetVisible
        set(value) {
            swipeController.playlistSheetVisible = value
        }
    // 定时关闭对话框显隐
    var showTimer by mutableStateOf(false)
    // 首页背景代表色：供在线搜索等浮层容器复用，保持与首页底色一致
    var backgroundColor by mutableStateOf(md_theme_dark_surface)
}

@Composable
internal fun rememberHomePanelState(): HomePanelState {
    val context = LocalContext.current
    val playbackState = LocalAppContainer.current.stateHolder
    val scope = rememberCoroutineScope()
    // 播放偏好：滑动切歌开关
    val swipeToChangeTrack by context.swipeToChangeTrackFlow()
        .collectAsStateWithLifecycle(initialValue = true)
    val swipeController = rememberHomeSwipeController(playbackState.state, swipeToChangeTrack)
    return remember(context, playbackState, swipeController) {
        HomePanelState(
            playbackState = playbackState,
            libraryAnalysis = LibraryAnalysisController(context, scope),
            swipeController = swipeController,
        )
    }
}
