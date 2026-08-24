package com.yichao.evilgodxu.navigation

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivityResultRegistryOwner
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
import com.yichao.evilgodxu.R
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
    // LocalContext 已被本地化包装，宿主 Activity 需从注册表所有者获取
    val activity = LocalActivityResultRegistryOwner.current as? Activity

    // 返回防抖：500ms 内连点只生效一次，防止回退栈被清空
    var lastBackTime by remember { mutableStateOf(0L) }
    // 首页根节点 NavDisplay 不拦截返回，需自行拦截：双击返回桌面（播放中）或退出（未播放）
    var lastHomeBackTime by remember { mutableStateOf(0L) }
    BackHandler(enabled = backStack.size <= 1) {
        val now = System.currentTimeMillis()
        if (now - lastHomeBackTime < DOUBLE_BACK_EXIT_MS) {
            if (MusicPanelStateHolder.state.isPlayerActive) {
                // 播放中：返回桌面，保留后台播放与迷你播放器
                activity?.moveTaskToBack(true)
            } else {
                MusicPanelStateHolder.releaseIfIdle()
                onExit()
            }
        } else {
            lastHomeBackTime = now
            val hint =
                if (MusicPanelStateHolder.state.isPlayerActive) context.getString(R.string.back_again_go_home)
                else context.getString(R.string.back_again_exit)
            Toast.makeText(context, hint, Toast.LENGTH_SHORT).show()
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
// 首页双击返回间隔
private const val DOUBLE_BACK_EXIT_MS = 2000L