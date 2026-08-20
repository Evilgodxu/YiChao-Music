package com.yichao.evilgodxu.navigation

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.yichao.evilgodxu.musicpanel.MusicPanelStateHolder
import com.yichao.evilgodxu.screens.home.HomeScreen
import com.yichao.evilgodxu.screens.settings.SettingsScreen

// 导航宿主：统一走路由栈
@Composable
fun AppNavHost(
    modifier: Modifier = Modifier,
    onExit: () -> Unit,
) {
    val backStack = rememberNavBackStack(Home)
    val context = LocalContext.current

    // 返回防抖：500ms 内连点只生效一次，防止回退栈被清空
    var lastBackTime by remember { mutableStateOf(0L) }
    // 首页根节点 NavDisplay 不拦截返回，需自行拦截：播放中返回桌面，未播放则释放资源并退出
    BackHandler(enabled = backStack.size <= 1) {
        if (MusicPanelStateHolder.state.isPlayerActive) {
            // 播放中：仅返回桌面，保留后台播放与迷你播放器
            (context as? Activity)?.moveTaskToBack(true)
        } else {
            MusicPanelStateHolder.releaseIfIdle()
            onExit()
        }
    }
    fun onBack() {
        val now = System.currentTimeMillis()
        if (now - lastBackTime < BACK_DEBOUNCE_MS) return
        lastBackTime = now
        backStack.removeLastOrNull()
    }

    NavDisplay(
        backStack = backStack,
        onBack = { onBack() },
        modifier = modifier,
        entryProvider = { key ->
            when (key) {
                is Home -> NavEntry(key) { HomeScreen(onOpenSettings = { backStack.add(Settings) }) }
                is Settings -> NavEntry(key) {
                    SettingsScreen(onBack = { onBack() })
                }
                else -> error("Unknown NavKey: $key")
            }
        },
    )
}

// 返回按键防抖间隔
private const val BACK_DEBOUNCE_MS = 500L