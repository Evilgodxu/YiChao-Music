package com.yichao.evilgodxu.musicpanel

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.yichao.evilgodxu.ui.icons.AppIcons
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.log.CrashLogManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yichao.evilgodxu.data.settings.ThemeMode
import com.yichao.evilgodxu.data.settings.settingsFlow
import com.yichao.evilgodxu.theme.DarkColorScheme
import com.yichao.evilgodxu.theme.LightColorScheme
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// 迷你条紧凑布局常量（窗口宽度与 Compose 布局共用，调整尺寸时需同步）
private const val MINI_COVER_DP = 32
private const val MINI_BUTTON_DP = 32
private const val MINI_PADDING_H_DP = 2
private const val MINI_BUTTON_COUNT = 5
// 左右滑动关闭时条滑出屏幕的动画时长
private const val SWIPE_DISMISS_MS = 160

// 迷你播放器浮动窗管理器：状态栏下方的紧凑播放条，支持展开完整面板与下拉播放列表
class MiniPlayerViewManager(
    private val context: Context,
    private val onExpandPanel: () -> Unit,
    private val onSwipedDismiss: () -> Unit,
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var composeView: ComposeView? = null
    private var isDismissing = false
    private val playbackState = MusicPanelStateHolder.state

    // 播放列表展开状态（Compose 状态 + 窗口布局共用）
    private val playlistExpanded = mutableStateOf(false)
    private var statusBarHeight = getStatusBarHeight()

    private val mainHandler = Handler(Looper.getMainLooper())

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) collapsePlaylist()
        }
    }

    val isShowing: Boolean get() = composeView != null

    private val lifecycleOwner = object : LifecycleOwner {
        private val lifecycleRegistry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = lifecycleRegistry
        fun handleLifecycleEvent(event: Lifecycle.Event) = lifecycleRegistry.handleLifecycleEvent(event)
    }

    private val viewModelStoreOwner = object : ViewModelStoreOwner {
        private val store = ViewModelStore()
        override val viewModelStore: ViewModelStore get() = store
    }

    private val savedStateRegistryOwner = object : SavedStateRegistryOwner {
        private val controller = SavedStateRegistryController.create(this)
        override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry
        override val lifecycle: Lifecycle get() = lifecycleOwner.lifecycle
        fun performAttach() = controller.performAttach()
        fun performRestore() = controller.performRestore(null)
    }

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    fun show() {
        if (composeView != null) return
        statusBarHeight = currentTopInset()
        playlistExpanded.value = false

        val barH = barHeightPx()
        val flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE

        val params = WindowManager.LayoutParams(
            barWidthPx(),
            barH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = topOffsetPx()
        }

        val view = ComposeView(context).apply {
            translationY = (-barH).toFloat()
            setContent {
                MiniPlayerOverlay(
                    playbackState = playbackState,
                    barHeightPx = barH,
                    barWidthPx = barWidthPx(),
                    playlistExpanded = playlistExpanded.value,
                    onPlaylistExpandedChange = { expanded -> setPlaylistExpanded(expanded) },
                    onLayoutChanged = { applyWindowLayout() },
                    onExpandPanel = onExpandPanel,
                    onSwipeDismiss = { temporaryDismiss() }
                )
            }
        }

        savedStateRegistryOwner.performAttach()
        savedStateRegistryOwner.performRestore()
        view.setViewTreeLifecycleOwner(lifecycleOwner)
        view.setViewTreeViewModelStoreOwner(viewModelStoreOwner)
        view.setViewTreeSavedStateRegistryOwner(savedStateRegistryOwner)

        // 实时监听系统状态栏高度变化（刘海屏/分屏/折叠屏等动态调整）
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            if (top != statusBarHeight) {
                statusBarHeight = top
                applyWindowLayout()
            }
            ViewCompat.dispatchApplyWindowInsets(v, insets)
        }

        // 点击迷你播放器窗口以外的区域：收起播放列表
        view.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                collapsePlaylist()
                true
            } else false
        }
        // 系统返回键：收起播放列表
        view.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                collapsePlaylist()
                true
            } else false
        }
        view.isFocusableInTouchMode = true

        composeView = view
        try {
            windowManager.addView(view, params)
        } catch (e: SecurityException) {
            CrashLogManager.logException("MiniPlayerViewManager", "添加迷你播放器失败（缺少悬浮窗权限）", e)
            composeView = null
            return
        } catch (e: WindowManager.BadTokenException) {
            CrashLogManager.logException("MiniPlayerViewManager", "添加迷你播放器失败（窗口令牌失效）", e)
            composeView = null
            return
        }
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        view.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(260)
            .setInterpolator(DecelerateInterpolator())
            .start()

        context.registerReceiver(
            screenOffReceiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            Context.RECEIVER_NOT_EXPORTED
        )
    }

    private fun setPlaylistExpanded(expanded: Boolean) {
        if (playlistExpanded.value == expanded) return
        playlistExpanded.value = expanded
        // 展开播放列表时移除 NOT_FOCUSABLE，使系统返回键可收起列表
        val view = composeView
        val params = view?.layoutParams as? WindowManager.LayoutParams
        if (params != null) {
            params.flags = if (expanded) {
                params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            } else {
                params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            }
            // params 非空即 view 非空（params 由 view.layoutParams 派生），此处无需再断言
            runCatching { windowManager.updateViewLayout(view, params) }
        }
        applyWindowLayout()
        if (expanded) {
            view?.let {
                it.isFocusableInTouchMode = true
                it.requestFocus()
            }
        }
    }

    private fun collapsePlaylist() {
        if (!playlistExpanded.value) return
        setPlaylistExpanded(false)
    }

    // 设置窗口尺寸与位置：展开时铺满屏幕，收起时恢复紧凑条（不做逐帧动画，避免卡顿）
    private fun applyWindowLayout() {
        val view = composeView ?: return
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        val targetWidth: Int
        val targetHeight: Int
        val targetY: Int
        if (playlistExpanded.value) {
            targetWidth = WindowManager.LayoutParams.MATCH_PARENT
            targetHeight = WindowManager.LayoutParams.MATCH_PARENT
            targetY = 0
        } else {
            targetWidth = barWidthPx()
            targetHeight = barHeightPx()
            targetY = topOffsetPx()
        }
        if (targetWidth != params.width || targetHeight != params.height || targetY != params.y) {
            params.width = targetWidth
            params.height = targetHeight
            params.y = targetY
            runCatching { windowManager.updateViewLayout(view, params) }
        }
    }

    private fun barHeightPx(): Int = dpToPx(BAR_HEIGHT_DP)

    // 迷你条宽度 = 左右内边距 + 封面 + 全部按钮
    private fun barWidthPx(): Int =
        dpToPx(MINI_PADDING_H_DP * 2 + MINI_COVER_DP + MINI_BUTTON_COUNT * MINI_BUTTON_DP)

    @SuppressLint("DiscouragedApi")
    private fun getStatusBarHeight(): Int {
        val res = context.resources
        val id = res.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) res.getDimensionPixelSize(id) else 0
    }

    // 横屏时状态栏位于屏幕侧边，顶部偏移为 0
    private fun isLandscape(): Boolean =
        context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    // 实时获取当前窗口顶部状态栏 inset（横屏时状态栏位于侧边，顶部为 0）
    private fun currentTopInset(): Int = runCatching {
        windowManager.currentWindowMetrics.windowInsets
            .getInsets(WindowInsets.Type.statusBars()).top
    }.getOrElse { if (isLandscape()) 0 else getStatusBarHeight() }

    // 迷你播放器纵向位置：横屏状态栏在侧边，顶部仅保留 1dp 间距；
    // 竖屏位于状态栏下方，状态栏高度未刷新（横屏遗留）时回退到系统标准高度，避免嵌入状态栏
    private fun topOffsetPx(): Int =
        if (isLandscape()) dpToPx(LANDSCAPE_TOP_GAP_DP)
        else max(statusBarHeight, getStatusBarHeight())

    private fun dpToPx(value: Int): Int = (value * context.resources.displayMetrics.density).roundToInt()

    // 滑动临时关闭：仅收起并回调给上层记录临时隐藏状态
    private fun temporaryDismiss() {
        dismiss(notifySwiped = true)
    }

    fun dismiss(notifySwiped: Boolean = false) {
        val view = composeView ?: return
        if (isDismissing) return
        isDismissing = true

        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        view.animate()
            .translationY((-view.height).toFloat())
            .alpha(0f)
            .setDuration(240)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
                lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
                try {
                    if (view.windowToken != null) windowManager.removeView(view)
                } catch (e: Exception) {
                    CrashLogManager.logException("MiniPlayerViewManager", "移除迷你播放器失败", e)
                }
                mainHandler.post {
                    try {
                        context.unregisterReceiver(screenOffReceiver)
                    } catch (e: Exception) {
                        CrashLogManager.logException("MiniPlayerViewManager", "注销熄屏监听失败", e)
                    }
                }
                composeView = null
                isDismissing = false
                if (notifySwiped) onSwipedDismiss()
            }
            .start()
    }

    companion object {
        // 迷你播放器条高度：紧凑容纳两行文本与 32dp 触控热区
        private const val BAR_HEIGHT_DP = 32
        // 横屏时顶部保留的间距
        private const val LANDSCAPE_TOP_GAP_DP = 1
        const val MAX_VISIBLE_ROWS = 5
    }
}

@Composable
private fun MiniPlayerOverlay(
    playbackState: MusicPlaybackState,
    barHeightPx: Int,
    barWidthPx: Int,
    playlistExpanded: Boolean,
    onPlaylistExpandedChange: (Boolean) -> Unit,
    onLayoutChanged: () -> Unit,
    onExpandPanel: () -> Unit,
    onSwipeDismiss: () -> Unit,
) {
    val density = LocalDensity.current
    val context = LocalContext.current
    val barHeight = with(density) { barHeightPx.toDp() }
    val barWidth = with(density) { barWidthPx.toDp() }

    // 左右滑动关闭：拖动时实时跟随手指，超过阈值后滑出屏幕再收起
    var swipeOffset by remember { mutableStateOf(0f) }
    var swipeDismissing by remember { mutableStateOf(false) }

    // 跟随应用主题：设置项优先，其次系统深色模式
    val settings by context.settingsFlow().collectAsStateWithLifecycle(initialValue = null)
    val isSystemDark = isSystemInDarkTheme()
    val isDarkTheme = when (settings?.themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        else -> isSystemDark
    }
    val colorScheme = if (isDarkTheme) DarkColorScheme else LightColorScheme
    // 卡片背景：收起时高透明透出下层内容，展开播放列表时降低透明度保证列表可读性
    val cardBackground = if (playlistExpanded) {
        Color(if (isDarkTheme) 0xFF161B22 else 0xFFF5F5F7).copy(alpha = 0.92f)
    } else {
        Color(if (isDarkTheme) 0xFF161B22 else 0xFFF5F5F7).copy(alpha = if (isDarkTheme) 0.55f else 0.60f)
    }

    // 屏幕旋转时自动收起播放列表并重新布局
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    LaunchedEffect(configuration.orientation) {
        if (playlistExpanded) onPlaylistExpandedChange(false)
        onLayoutChanged()
    }

    MaterialTheme(colorScheme = colorScheme) {
        if (playlistExpanded) {
            // 展开时窗口铺满屏幕，卡片以外区域点击即收起
            val cardScale = remember { Animatable(0.85f) }
            LaunchedEffect(Unit) {
                cardScale.animateTo(
                    targetValue = 1f,
                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onPlaylistExpandedChange(false) }
                    )
            ) {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .width(barWidth)
                        .graphicsLayer {
                            scaleX = cardScale.value
                            scaleY = cardScale.value
                        }
                        // 展开播放列表时用小圆角，呈现更接近窗口的方角效果
                        .background(cardBackground, RoundedCornerShape(12.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { /* 阻止点击穿透到收起区域 */ }
                        )
                ) {
                    MiniPlayerBar(
                        playbackState = playbackState,
                        barHeight = barHeight,
                        playlistExpanded = playlistExpanded,
                        onPlaylistExpandedChange = onPlaylistExpandedChange,
                        onExpandPanel = onExpandPanel,
                        swipeDismissThreshold = barWidthPx / 2f,
                        onSwipeOffsetChange = { swipeOffset = it },
                        onSwipeCommit = { swipeDismissing = true },
                        onSwipeCancel = { swipeOffset = 0f }
                    )
                    MiniPlaylistPanel(
                        playbackState = playbackState,
                        context = context,
                        onClose = { onPlaylistExpandedChange(false) }
                    )
                }
            }
        } else {
            // 拖动时跟随手指；确认关闭后向拖动方向滑出整个条宽，动画结束再触发收起
            val swipeTranslate by animateFloatAsState(
                targetValue = when {
                    swipeDismissing -> if (swipeOffset > 0f) barWidthPx.toFloat() else -barWidthPx.toFloat()
                    else -> swipeOffset
                },
                animationSpec = if (swipeDismissing) {
                    tween(durationMillis = SWIPE_DISMISS_MS, easing = LinearEasing)
                } else {
                    spring(stiffness = Spring.StiffnessMediumLow)
                },
                label = "mini_player_swipe_offset"
            )
            LaunchedEffect(swipeDismissing) {
                if (swipeDismissing) {
                    delay(SWIPE_DISMISS_MS.toLong())
                    onSwipeDismiss()
                }
            }
            Column(
                modifier = Modifier
                    .width(barWidth)
                    .graphicsLayer { translationX = swipeTranslate }
                    // 胶囊圆角：半径取条高（32dp）一半，呈椭圆轮廓
                    .background(cardBackground, RoundedCornerShape(16.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { /* 阻止点击穿透到状态栏区域 */ }
                    )
            ) {
                MiniPlayerBar(
                    playbackState = playbackState,
                    barHeight = barHeight,
                    playlistExpanded = playlistExpanded,
                    onPlaylistExpandedChange = onPlaylistExpandedChange,
                    onExpandPanel = onExpandPanel,
                    swipeDismissThreshold = barWidthPx / 2f,
                    onSwipeOffsetChange = { swipeOffset = it },
                    onSwipeCommit = { swipeDismissing = true },
                    onSwipeCancel = { swipeOffset = 0f }
                )
            }
        }
    }
}

@Composable
private fun MiniPlayerBar(
    playbackState: MusicPlaybackState,
    barHeight: androidx.compose.ui.unit.Dp,
    playlistExpanded: Boolean,
    onPlaylistExpandedChange: (Boolean) -> Unit,
    onExpandPanel: () -> Unit,
    swipeDismissThreshold: Float,
    onSwipeOffsetChange: (Float) -> Unit,
    onSwipeCommit: () -> Unit,
    onSwipeCancel: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val current = playbackState.currentTrack
    val coverDesc = stringResource(R.string.mini_player_cover)

    // 控件自动隐藏：3 秒无操作后隐藏控制按钮，改为显示歌曲名与歌词；任意触摸即可还原
    var controlsVisible by remember { mutableStateOf(true) }
    var interactionTick by remember { mutableStateOf(0) }
    fun resetAutoHide() {
        controlsVisible = true
        interactionTick++
    }
    LaunchedEffect(interactionTick, playlistExpanded) {
        if (playlistExpanded) {
            controlsVisible = true
            return@LaunchedEffect
        }
        delay(3000)
        controlsVisible = false
    }
    // 隐藏控件期间跟随播放进度刷新当前歌词；跟随当前曲目，切换歌曲时重置到曲目起点
    var lyricPosition by remember(playbackState.currentTrack?.id) { mutableStateOf(0L) }
    LaunchedEffect(controlsVisible, playbackState.currentTrack?.id) {
        if (controlsVisible) return@LaunchedEffect
        var lastSyncMs = 0L
        while (isActive) {
            val candidate = playbackState.mediaController?.currentPosition
                ?.takeIf { it >= 0L } ?: playbackState.currentPosition
            if (playbackState.isPlaying) {
                // 播放中以真实流逝时间推进，控制器位置仅作锚点，避免熄屏唤醒后位置停滞
                val now = System.currentTimeMillis()
                val elapsed = if (lastSyncMs == 0L) 0L else (now - lastSyncMs).coerceAtLeast(0L)
                lyricPosition = if (candidate >= lyricPosition) candidate else lyricPosition + elapsed
                lastSyncMs = now
            } else {
                lyricPosition = candidate
                lastSyncMs = 0L
            }
            delay(if (playbackState.isPlaying) 50L else 200L)
        }
    }

    // 左右滑动关闭：拖动时跟随手指，超过阈值后滑出（播放列表展开时不响应滑动）
    var totalDx by remember { mutableStateOf(0f) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(barHeight)
            .pointerInput(Unit) {
                // 任意触摸都算操作：还原控件并重置自动隐藏计时
                awaitPointerEventScope {
                    while (true) {
                        if (awaitPointerEvent().changes.any { it.pressed }) resetAutoHide()
                    }
                }
            }
            .pointerInput(playlistExpanded) {
                totalDx = 0f
                detectHorizontalDragGestures(
                    onDragStart = { totalDx = 0f },
                    onDragEnd = {
                        if (!playlistExpanded && kotlin.math.abs(totalDx) >= swipeDismissThreshold) {
                            onSwipeCommit()
                        } else {
                            onSwipeCancel()
                        }
                        totalDx = 0f
                    },
                    onDragCancel = {
                        onSwipeCancel()
                        totalDx = 0f
                    }
                ) { change, drag ->
                    change.consume()
                    totalDx += drag
                    onSwipeOffsetChange(totalDx)
                }
            }
            .padding(horizontal = MINI_PADDING_H_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(0.dp, Alignment.CenterHorizontally)
    ) {
        // 专辑封面：旋转 + 黑胶质感
        Box(
            modifier = Modifier
                .size(MINI_COVER_DP.dp)
                .clip(CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onExpandPanel
                )
                .semantics { contentDescription = coverDesc }
        ) {
            DiscArt(
                track = current,
                isPlaying = playbackState.isPlaying,
                modifier = Modifier.fillMaxSize()
            )
        }

        if (controlsVisible) {
            // 循环模式
            MiniControlButton(
                icon = when (playbackState.playMode) {
                    PlayMode.RepeatAll -> AppIcons.Repeat
                    PlayMode.RepeatOne -> AppIcons.RepeatOne
                    PlayMode.Shuffle -> AppIcons.Shuffle
                },
                contentDescription = stringResource(R.string.music_panel_play_mode),
                onClick = {
                    playbackState.setPlayMode(
                        when (playbackState.playMode) {
                            PlayMode.RepeatAll -> PlayMode.RepeatOne
                            PlayMode.RepeatOne -> PlayMode.Shuffle
                            PlayMode.Shuffle -> PlayMode.RepeatAll
                        }
                    )
                    playbackState.mediaController?.let { controller ->
                        applyPlaybackMode(controller, playbackState.playMode)
                    }
                    playbackState.persistState()
                }
            )
            // 上一曲
            MiniControlButton(
                icon = AppIcons.SkipPrevious,
                contentDescription = stringResource(R.string.mini_player_previous),
                enabled = playbackState.playlist.isNotEmpty(),
                onClick = {
                    val prev = playbackState.previousIndex()
                    if (prev >= 0) scope.launch { playTrackAt(context, playbackState, prev) }
                }
            )
            // 暂停 / 播放（无背景色）
            MiniControlButton(
                icon = if (playbackState.isPlaying) AppIcons.Pause else AppIcons.PlayArrow,
                contentDescription = stringResource(
                    if (playbackState.isPlaying) R.string.music_panel_pause else R.string.music_panel_play
                ),
                onClick = { togglePlayPause(playbackState) }
            )
            // 下一曲
            MiniControlButton(
                icon = AppIcons.SkipNext,
                contentDescription = stringResource(R.string.mini_player_next),
                enabled = playbackState.playlist.isNotEmpty(),
                onClick = {
                    val next = playbackState.nextIndex()
                    if (next >= 0) scope.launch { playTrackAt(context, playbackState, next) }
                }
            )
            // 播放列表
            MiniControlButton(
                icon = AppIcons.QueueMusic,
                contentDescription = stringResource(R.string.mini_player_playlist),
                onClick = { onPlaylistExpandedChange(!playlistExpanded) }
            )
        } else {
            // 隐藏控件：展示歌曲名与当前歌词（无歌词时回退到歌手名）
            val lyricText = current?.let { track ->
                if (track.lyricLines.isNotEmpty()) {
                    val index = track.lyricLines.indexOfLast { it.timeMs <= lyricPosition }.coerceAtLeast(0)
                    track.lyricLines.getOrNull(index)?.text.orEmpty()
                } else {
                    track.artist
                }
            }.orEmpty()
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = current?.title.orEmpty(),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 12.sp,
                    lineHeight = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Medium,
                    modifier = if ((current?.title?.length ?: 0) > 12) Modifier.basicMarquee(iterations = Int.MAX_VALUE) else Modifier
                )
                // 当前歌词主色加粗高亮，不带背景色
                Text(
                    text = lyricText,
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 10.sp,
                    lineHeight = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = if (lyricText.length > 18) Modifier.basicMarquee(iterations = Int.MAX_VALUE) else Modifier
                )
            }
        }
    }
}

@Composable
internal fun DiscArt(
    track: MusicTrack?,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    coverArt: @Composable (MusicTrack?) -> Unit = { AlbumArt(it, Modifier.fillMaxSize()) },
) {
    // 以曲目 id 为 key：切歌时旋转角归零并从新曲目重新旋转
    val rotation = remember(track?.id) { Animatable(0f) }
    LaunchedEffect(isPlaying, track?.id) {
        if (isPlaying) {
            while (isActive) {
                rotation.animateTo(
                    targetValue = rotation.value + 360f,
                    animationSpec = tween(durationMillis = 12_000, easing = LinearEasing)
                )
            }
        }
    }
    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { rotationZ = rotation.value }
                .clip(CircleShape)
        ) {
            // 专辑封面仅覆盖中间区域，外圈边缘留出透明材质
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxSize(0.85f)
                    .clip(CircleShape)
            ) {
                coverArt(track)
            }
            Canvas(modifier = Modifier.fillMaxSize()) {
                val r = size.minDimension / 2f
                val center = this.center
                // 浅色外环：紧贴封面外缘，无间距，宽度为半径的 9%
                val ringWidth = r * 0.09f
                drawCircle(
                    color = Color.White.copy(alpha = 0.22f),
                    radius = r * 0.85f + ringWidth / 2f,
                    center = center,
                    style = Stroke(width = ringWidth)
                )
            }
        }
    }
}

@Composable
private fun MiniControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(MINI_BUTTON_DP.dp)
    ) {
        // 视觉圆环小于触控热区，避免在紧凑高度下贴满上下边缘
        Box(
            modifier = Modifier.size(28.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(20.dp),
                tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
            )
        }
    }
}

@Composable
private fun MiniPlaylistPanel(
    playbackState: MusicPlaybackState,
    context: android.content.Context,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val visibleCount = playbackState.playlist.size.coerceIn(0, MiniPlayerViewManager.MAX_VISIBLE_ROWS)
    val listState = rememberLazyListState()

    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = stringResource(R.string.mini_player_playlist_title),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(R.string.music_panel_track_count, playbackState.playlist.size),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp
            )
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .height((visibleCount * 44).dp)
        ) {
            itemsIndexed(
                items = playbackState.playlist,
                key = { _, track -> track.audioUri }
            ) { index, track ->
                val isActive = index == playbackState.currentIndex
                MiniPlaylistRow(
                    track = track,
                    isActive = isActive,
                    isPlaying = isActive && playbackState.isPlaying,
                    isQueued = playbackState.isInPlayNext(track.id),
                    onClick = {
                        scope.launch { playTrackAt(context, playbackState, index) }
                        onClose()
                    },
                    onFavoriteClick = { playbackState.toggleFavorite(track.id) },
                    onPlayNextClick = { playbackState.togglePlayNext(track) }
                )
            }
        }
        // 当前曲目不在可视区域内时，滚动定位到对应位置
        LaunchedEffect(playbackState.currentTrack?.id) {
            if (playbackState.currentIndex >= 0 && playbackState.playlist.isNotEmpty()) {
                listState.animateScrollToItem(
                    playbackState.currentIndex.coerceIn(0, playbackState.playlist.size - 1)
                )
            }
        }
    }
}

@Composable
private fun MiniPlaylistRow(
    track: MusicTrack,
    isActive: Boolean,
    isPlaying: Boolean,
    isQueued: Boolean,
    onClick: () -> Unit,
    onFavoriteClick: () -> Unit,
    onPlayNextClick: () -> Unit,
) {
    val bg by animateColorAsState(
        targetValue = if (isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
        else Color.Transparent,
        label = "mini_playlist_bg"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 与完整面板一致：显示专辑封面，播放中叠加动态指示
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(6.dp))
        ) {
            PlaylistArt(track = track, modifier = Modifier.fillMaxSize())
            if (isActive && isPlaying) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        repeat(3) { i ->
                            val height by animateFloatAsState(
                                targetValue = 0.4f + kotlin.random.Random.nextFloat() * 0.5f,
                                animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy),
                                label = "mini_wave_$i"
                            )
                            Box(
                                modifier = Modifier
                                    .width(2.dp)
                                    .height((height * 10).dp)
                                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(1.dp))
                            )
                        }
                    }
                }
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = track.title,
                color = if (isActive) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                lineHeight = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (isActive) FontWeight.Medium else FontWeight.Normal,
                modifier = if (track.title.length > 12) Modifier.basicMarquee(iterations = Int.MAX_VALUE) else Modifier
            )
            Text(
                text = track.artist,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp,
                lineHeight = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            IconButton(
                onClick = onPlayNextClick,
                modifier = Modifier.size(26.dp)
            ) {
                Icon(
                    imageVector = if (isQueued) AppIcons.Remove else AppIcons.Add,
                    contentDescription = stringResource(
                        if (isQueued) R.string.music_panel_cancel_play_next else R.string.music_panel_play_next
                    ),
                    tint = if (isQueued) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }

            IconButton(
                onClick = onFavoriteClick,
                modifier = Modifier.size(26.dp)
            ) {
                Icon(
                    imageVector = if (track.isFavorite) AppIcons.Favorite else AppIcons.FavoriteBorder,
                    contentDescription = null,
                    tint = if (track.isFavorite) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
