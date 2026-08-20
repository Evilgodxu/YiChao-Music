package com.yichao.evilgodxu.navigation

import android.widget.Toast
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
import com.yichao.evilgodxu.screens.home.HomeScreen
import com.yichao.evilgodxu.screens.settings.SettingsScreen

// 导航宿主：统一走路由栈
@Composable
fun AppNavHost(
    modifier: Modifier = Modifier,
) {
    val backStack = rememberNavBackStack(Home)

    // 首页根节点双击返回退出应用的时间窗口
    var lastExitTime by remember { mutableStateOf(0L) }
    // 返回防抖：500ms 内连点只生效一次，防止回退栈被清空
    var lastBackTime by remember { mutableStateOf(0L) }
    val context = LocalContext.current

    fun onBack() {
        val now = System.currentTimeMillis()
        // 根节点（首页）：双击返回键退出应用
        if (backStack.size <= 1) {
            if (now - lastExitTime <= EXIT_WINDOW_MS) {
                backStack.removeLastOrNull()
            } else {
                lastExitTime = now
                Toast.makeText(context, R.string.home_double_back_exit, Toast.LENGTH_SHORT).show()
            }
            return
        }
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
// 双击返回键退出应用的间隔
private const val EXIT_WINDOW_MS = 2000L