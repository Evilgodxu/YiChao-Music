package com.yichao.evilgodxu.ui.music

import androidx.compose.foundation.lazy.LazyListState

// 目标项距当前可见项在此范围内时用平滑滚动，超过则直接跳转
private const val SCROLL_NEAR_ITEM_RANGE = 10

/**
 * 定位到指定曲目，使其在列表可视区居中显示：
 * 1. [forceCenter] 为 false 且目标项已在可视区内则不动，避免切歌时反复滚动造成卡顿；
 * 2. 距离近时平滑滚动，距离远时直接跳转，避免长距离动画滚动卡顿。
 */
internal suspend fun LazyListState.scrollPlaylistTo(index: Int, forceCenter: Boolean = false) {
    val layout = layoutInfo
    val visible = layout.visibleItemsInfo
    if (!forceCenter && visible.any { it.index == index }) return
    val scrollOffset = centerScrollOffset()
    val first = visible.firstOrNull()?.index
    val last = visible.lastOrNull()?.index
    if (first != null && last != null &&
        index in (first - SCROLL_NEAR_ITEM_RANGE)..(last + SCROLL_NEAR_ITEM_RANGE)
    ) {
        animateScrollToItem(index, scrollOffset = scrollOffset)
    } else {
        scrollToItem(index, scrollOffset = scrollOffset)
    }
}

// 目标项居中所需的滚动偏移：行高统一，以可见项高度估算目标项高度；列表未布局时退化为顶部对齐
private fun LazyListState.centerScrollOffset(): Int {
    val layout = layoutInfo
    val viewportHeight = layout.viewportEndOffset - layout.viewportStartOffset
    val itemHeight = layout.visibleItemsInfo.firstOrNull()?.size ?: return 0
    return -(viewportHeight - itemHeight) / 2
}
