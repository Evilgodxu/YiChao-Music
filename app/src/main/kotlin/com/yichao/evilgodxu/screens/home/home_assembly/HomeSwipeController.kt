package com.yichao.evilgodxu.screens.home.home_assembly

import android.content.Context
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import com.yichao.evilgodxu.musicpanel.MusicPlaybackState
import com.yichao.evilgodxu.musicpanel.playTrackAt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs

// 左右滑动切换（右滑搜索、左滑歌单）共用的回弹阈值：滑动进度达到该比例则展开，否则回弹至播放器
private const val SWIPE_OPEN_RATIO = 0.25f

/**
 * 首页滑动切换状态与手势逻辑：持有搜索/歌单面板的显隐与跟手进度，提供手势 Modifier 与结算动画。
 */
@Stable
internal class HomeSwipeController(
    private val playbackState: MusicPlaybackState,
    private val context: Context,
    private val scope: CoroutineScope,
    private val keyboardController: SoftwareKeyboardController?,
    private val swipeToChangeTrack: State<Boolean>,
) {
    // 右滑呼出的在线搜索覆盖层显隐状态
    var showOnlineSearch by mutableStateOf(false)
    // 左滑呼出的歌单面板显隐状态
    var showPlaylist by mutableStateOf(false)
    // 手势跟手进度：0=播放器页，1=对应面板展开，拖动期间随手指实时更新
    var searchProgress by mutableFloatStateOf(0f)
    var playlistProgress by mutableFloatStateOf(0f)
    // 内容区像素宽度，用于将滑动距离换算为进度比例
    var contentWidthPx by mutableFloatStateOf(0f)
    // 本次手势起始时面板的展开状态，回滑关闭时按相同比例阈值判定
    private var gestureSearchOpen by mutableStateOf(false)
    private var gesturePlaylistOpen by mutableStateOf(false)
    // 触发回弹/展开的动画：非拖动状态下将进度平滑带到目标值
    private var settleKey by mutableIntStateOf(0)

    // 结算本次滑动：两个面板进度独立结算，并行动画避免相互阻塞
    @Composable
    fun SettleEffect() {
        LaunchedEffect(showOnlineSearch, showPlaylist, settleKey) {
            launch {
                val target = if (showOnlineSearch) 1f else 0f
                if (searchProgress != target) {
                    animate(
                        initialValue = searchProgress,
                        targetValue = target,
                        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                    ) { value, _ -> searchProgress = value }
                }
            }
            launch {
                val target = if (showPlaylist) 1f else 0f
                if (playlistProgress != target) {
                    animate(
                        initialValue = playlistProgress,
                        targetValue = target,
                        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                    ) { value, _ -> playlistProgress = value }
                }
            }
        }
    }

    // 左右滑动切换面板、上下滑动切换曲目的手势
    val swipeModifier: Modifier
        get() = Modifier.pointerInput(Unit) {
            val slop = viewConfiguration.touchSlop
            awaitEachGesture {
                // 记录手势起始时的面板展开状态，回滑时按相同阈值判定关闭
                gestureSearchOpen = showOnlineSearch
                gesturePlaylistOpen = showPlaylist
                val down = awaitFirstDown(requireUnconsumed = false)
                // 阶段一：累计位移直到任一轴越过触摸阈值，据此锁定主导方向，保证左右滑动与上下滑动互斥
                var accX = 0f
                var accY = 0f
                var axis = 0 // 0=未定，1=横向(切换面板)，2=纵向(切歌)
                while (axis == 0) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    // 手指抬起或被子控件（进度条/控制栏等）消费，放弃本手势
                    if (!change.pressed || change.isConsumed) break
                    accX += change.positionChange().x
                    accY += change.positionChange().y
                    if (abs(accX) >= slop || abs(accY) >= slop) {
                        axis = if (abs(accX) > abs(accY)) 1 else 2
                    }
                }
                if (axis == 1) {
                    // 横向主导：右滑搜索、左滑歌单，拖动全程跟手；切换页面时自动收起键盘
                    keyboardController?.hide()
                    if (contentWidthPx > 0f) {
                        when {
                            searchProgress > 0f -> searchProgress =
                                (searchProgress + accX / contentWidthPx).coerceIn(0f, 1f)
                            playlistProgress > 0f -> playlistProgress =
                                (playlistProgress - accX / contentWidthPx).coerceIn(0f, 1f)
                            accX > 0f -> searchProgress =
                                (accX / contentWidthPx).coerceIn(0f, 1f)
                            else -> playlistProgress =
                                (-accX / contentWidthPx).coerceIn(0f, 1f)
                        }
                    }
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null || !change.pressed || change.isConsumed) break
                        val dx = change.positionChange().x
                        change.consume()
                        if (contentWidthPx > 0f) {
                            // 已展开的面板优先回拽；播放器页按滑动方向分配进度（右滑搜索、左滑歌单）
                            when {
                                searchProgress > 0f -> searchProgress =
                                    (searchProgress + dx / contentWidthPx).coerceIn(0f, 1f)
                                playlistProgress > 0f -> playlistProgress =
                                    (playlistProgress - dx / contentWidthPx).coerceIn(0f, 1f)
                                dx > 0f -> searchProgress =
                                    (dx / contentWidthPx).coerceIn(0f, 1f)
                                else -> playlistProgress =
                                    (-dx / contentWidthPx).coerceIn(0f, 1f)
                            }
                        }
                    }
                    // 松开：按滑动比例判定展开或回弹；已展开面板回滑按同样比例判定关闭，保证来回切换阈值统一
                    val searchOpen = if (gestureSearchOpen) {
                        searchProgress > 1f - SWIPE_OPEN_RATIO
                    } else {
                        searchProgress >= SWIPE_OPEN_RATIO
                    }
                    if (searchOpen != showOnlineSearch) {
                        showOnlineSearch = searchOpen
                        if (!searchOpen) {
                            playbackState.setSearchResultsVisible(false)
                            playbackState.setErrorMsg(null)
                        }
                    }
                    val playlistOpen = if (gesturePlaylistOpen) {
                        playlistProgress > 1f - SWIPE_OPEN_RATIO
                    } else {
                        playlistProgress >= SWIPE_OPEN_RATIO
                    }
                    if (playlistOpen != showPlaylist) showPlaylist = playlistOpen
                    settleKey++ // 结算本次滑动，非目标状态时平滑动画到目标
                } else if (axis == 2) {
                    // 纵向主导：向上切下一首、向下切上一首；仅播放器视图（无覆盖面板）生效，避免与面板内滚动冲突
                    var swipeY = accY
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null || !change.pressed || change.isConsumed) break
                        swipeY += change.positionChange().y
                        change.consume()
                    }
                    if (swipeToChangeTrack.value &&
                        searchProgress <= 0f && playlistProgress <= 0f
                    ) {
                        val next = if (swipeY < 0f) playbackState.nextIndex()
                        else playbackState.previousIndex()
                        if (next >= 0) scope.launch { playTrackAt(context, playbackState, next) }
                    }
                }
            }
        }
}

@Composable
internal fun rememberHomeSwipeController(
    playbackState: MusicPlaybackState,
    swipeToChangeTrack: Boolean,
): HomeSwipeController {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current
    // 手势协程中读取实时开关值，避免捕获过期状态
    val swipeToChangeTrackState = rememberUpdatedState(swipeToChangeTrack)
    return remember(playbackState) {
        HomeSwipeController(
            playbackState = playbackState,
            context = context,
            scope = scope,
            keyboardController = keyboardController,
            swipeToChangeTrack = swipeToChangeTrackState,
        )
    }
}