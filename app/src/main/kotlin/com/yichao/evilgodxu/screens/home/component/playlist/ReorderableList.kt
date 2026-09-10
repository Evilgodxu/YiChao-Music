package com.yichao.evilgodxu.screens.home.home_assembly.playlist_area

import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * 可排序列表状态：跟踪当前拖拽项，计算其相对原始位置的视觉偏移，并在互斥锁定下调用 onMove 重排。
 */
@Stable
internal class ReorderableLazyListState internal constructor(
    private val listState: LazyListState,
    private val scope: CoroutineScope,
    private val onMove: (from: Int, to: Int) -> Unit,
) {
    private val onMoveMutex = Mutex()
    private var draggingItemKey by mutableStateOf<Any?>(null)
    private var draggingItemDraggedDelta by mutableStateOf(Offset.Zero)
    private var draggingItemInitialOffset by mutableStateOf(IntOffset.Zero)
    private var oldDraggingItemIndex by mutableStateOf<Int?>(null)
    private var predictedDraggingItemOffset by mutableStateOf<IntOffset?>(null)
    // 拖拽到边缘时的常驻自动滚动协程
    private var autoScrollJob: Job? = null

    private val draggingItemInfo: LazyListItemInfo?
        get() = draggingItemKey?.let { key ->
            listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }
        }

    internal val isAnyItemDragging by derivedStateOf { draggingItemKey != null }

    /**
     * 拖拽项相对其原始布局位置的偏移：
     * 手指累计位移 - 重排/滚动引起的布局位移，二者抵消后拖拽项始终跟手。
     */
    internal val draggingItemOffset: Offset
        get() {
            val info = draggingItemInfo ?: return Offset.Zero
            val offsetPx = if (info.index != oldDraggingItemIndex || oldDraggingItemIndex == null) {
                oldDraggingItemIndex = null
                predictedDraggingItemOffset = null
                info.offset
            } else {
                predictedDraggingItemOffset?.y ?: info.offset
            }
            // 手指累计位移 - 布局位移（重排/滚动），抵消后拖拽项始终跟手
            return draggingItemDraggedDelta + Offset(0f, (draggingItemInitialOffset.y - offsetPx).toFloat())
        }

    internal fun onDragStart(key: Any) {
        draggingItemKey = key
        draggingItemInitialOffset = IntOffset(0, draggingItemInfo?.offset ?: 0)
    }

    internal fun onDragStop() {
        draggingItemDraggedDelta = Offset.Zero
        draggingItemKey = null
        oldDraggingItemIndex = null
        predictedDraggingItemOffset = null
        autoScrollJob?.cancel()
        autoScrollJob = null
    }

    internal fun onDrag(dragAmount: Offset) {
        draggingItemDraggedDelta += dragAmount
        val draggingItem = draggingItemInfo ?: return
        // 拖拽项进入可视区边缘带时启动常驻自动滚动协程
        updateAutoScroll()
        // 自动滚动期间由滚动协程把拖拽项归位到边缘，跳过交集重排避免冲突
        if (autoScrollJob?.isActive == true) return
        if (!onMoveMutex.tryLock()) return
        try {
            val startOffset = draggingItem.offset + draggingItemOffset.y.toInt()
            val endOffset = startOffset + draggingItem.size
            val target = listState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
                item.key != draggingItem.key &&
                    item.offset + item.size / 2 >= startOffset &&
                    item.offset + item.size / 2 < endOffset
            }
            if (target != null && target.index != draggingItem.index) {
                scope.launch { moveItems(draggingItem, target) }
            }
        } finally {
            onMoveMutex.unlock()
        }
    }

    /**
     * 拖拽项进入可视区上下边缘带内时启动常驻滚动协程；即将移出边缘时停止。
     * 循环内每帧重新判定方向，滚动同时向该方向推进拖拽项的位置，避免脱离跟随。
     */
    private fun updateAutoScroll() {
        val shouldScroll = autoScrollDirection() != 0
        val scrolling = autoScrollJob?.isActive == true
        when {
            shouldScroll && !scrolling -> {
                autoScrollJob?.cancel()
                autoScrollJob = scope.launch { autoScrollLoop() }
            }
            !shouldScroll && scrolling -> {
                autoScrollJob?.cancel()
                autoScrollJob = null
            }
        }
    }

    // 拖拽项手柄中心是否进入上/下边缘带，返回滚动方向
    private fun autoScrollDirection(): Int {
        val item = draggingItemInfo ?: return 0
        val viewportHeight = listState.layoutInfo.viewportSize.height
        // 手柄中心 = 拖拽项当前布局偏移 + 拖拽位移 + 手柄中心偏移
        val handleCenter = item.offset + (draggingItemOffset.y + item.size / 2f)
        val threshold = item.size * 1.2f
        return when {
            handleCenter < threshold && listState.canScrollBackward -> -1
            handleCenter > viewportHeight - threshold && listState.canScrollForward -> 1
            else -> 0
        }
    }

    // 自动滚动每帧推进量：越贴近边缘带越深滚动越快（0.5x..1.5x 行高）
    private fun autoScrollStep(): Float {
        val item = draggingItemInfo ?: return 0f
        val viewportHeight = listState.layoutInfo.viewportSize.height
        val handleCenter = item.offset + (draggingItemOffset.y + item.size / 2f)
        val threshold = item.size * 1.2f
        val depth = when {
            handleCenter < threshold -> (threshold - handleCenter) / threshold
            handleCenter > viewportHeight - threshold -> (handleCenter - (viewportHeight - threshold)) / threshold
            else -> 0.5f
        }.coerceIn(0f, 1f)
        return item.size * (0.5f + depth)
    }

    private suspend fun autoScrollLoop() {
        // 由 updateAutoScroll() 在移出边缘时取消；无需额外退出条件
        while (true) {
            val dir = autoScrollDirection()
            if (dir == 0) break
            // 先把拖拽项归位到滚动边缘再滚动，避免被滚出可视区导致拖拽中断
            moveDraggingItemToEnd(dir)
            listState.animateScrollBy(autoScrollStep() * dir)
            delay(AUTO_SCROLL_FRAME_MS)
        }
        autoScrollJob = null
    }

    // 滚动时把拖拽项重排到滚动方向的可视区边缘，使其始终跟随滚动而不会被滚出屏幕
    private suspend fun moveDraggingItemToEnd(dir: Int) {
        val dragged = draggingItemInfo ?: return
        val visible = listState.layoutInfo.visibleItemsInfo
        if (visible.isEmpty()) return
        val lead = if (dir < 0) visible.minBy { it.index } else visible.maxBy { it.index }
        // 已处于滚动方向的边缘，无需再移动
        if (dragged.index == lead.index) return
        // 仅允许朝滚动方向推进，避免反向
        if (dir < 0 && lead.index > dragged.index) return
        if (dir > 0 && lead.index < dragged.index) return
        moveItems(dragged, lead)
    }

    private suspend fun moveItems(
        from: LazyListItemInfo,
        to: LazyListItemInfo,
    ) {
        try {
            onMoveMutex.withLock {
                if (draggingItemKey == null) return
                // 重排触及首个可见项时重新锚定滚动位置，避免拖拽项被顶出可视区
                if (from.index == listState.firstVisibleItemIndex ||
                    to.index == listState.firstVisibleItemIndex
                ) {
                    listState.requestScrollToItem(
                        listState.firstVisibleItemIndex,
                        listState.firstVisibleItemScrollOffset,
                    )
                }
                oldDraggingItemIndex = from.index
                onMove(from.index, to.index)
                predictedDraggingItemOffset = if (to.index > from.index) {
                    IntOffset(0, to.offset + to.size - from.size)
                } else {
                    IntOffset(0, to.offset)
                }
                try {
                    // 等待重排后的布局落定，避免视觉偏移与布局错帧导致抖动
                    withTimeout(ReorderableStateLayoutInfoUpdateMaxWaitDuration) {
                        snapshotFlow { listState.layoutInfo }.take(2).collect()
                    }
                } finally {
                    oldDraggingItemIndex = null
                    predictedDraggingItemOffset = null
                }
            }
        } catch (e: CancellationException) {
            // 重排布局更新等待被取消，属于预期
        }
    }

    internal fun isItemDragging(key: Any): State<Boolean> = derivedStateOf { key == draggingItemKey }

    companion object {
        const val ReorderableStateLayoutInfoUpdateMaxWaitDuration = 1000L
        private const val AUTO_SCROLL_FRAME_MS = 16L
    }
}

/**
 * 创建可排序列表状态；onMove 在拖拽项需移动时由内部按交集顺序调用。
 */
@Composable
internal fun rememberReorderableLazyListState(
    listState: LazyListState,
    onMove: (from: Int, to: Int) -> Unit,
): ReorderableLazyListState {
    val scope = rememberCoroutineScope()
    val currentOnMove by rememberUpdatedState(onMove)
    return remember(listState) {
        ReorderableLazyListState(
            listState = listState,
            scope = scope,
            onMove = { from, to -> currentOnMove(from, to) },
        )
    }
}

/**
 * 每个可排序项的包装：拖拽项以 zIndex + graphicsLayer 跟随手指，其余项以 animateItem 平滑让位。
 */
@Composable
internal fun LazyItemScope.ReorderableItem(
    state: ReorderableLazyListState,
    key: Any,
    content: @Composable (isDragging: Boolean) -> Unit,
) {
    val dragging by state.isItemDragging(key)
    val offsetModifier = if (dragging) {
        Modifier
            .zIndex(1f)
            .graphicsLayer {
                translationY = state.draggingItemOffset.y
            }
    } else {
        Modifier.animateItem(
            placementSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMediumLow,
            )
        )
    }
    Box(modifier = offsetModifier) {
        content(dragging)
    }
}

/**
 * 拖拽手柄：消费按下事件以阻止行级长按误触发，长按后再开始拖拽排序。
 */
internal fun Modifier.longPressDraggableHandle(
    state: ReorderableLazyListState,
    key: Any,
): Modifier = pointerInput(state, key) {
    awaitEachGesture {
        // 立即消费按下，行级 combinedClickable 不再收到该指针，避免误弹移除对话框
        val down = awaitFirstDown(requireUnconsumed = false)
        down.consume()
        val longPress = awaitLongPressOrCancellation(down.id)
        if (longPress != null) {
            state.onDragStart(key)
            try {
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    change.consume()
                    val delta = change.position - change.previousPosition
                    if (delta != Offset.Zero) state.onDrag(delta)
                }
            } finally {
                state.onDragStop()
            }
        }
    }
}
