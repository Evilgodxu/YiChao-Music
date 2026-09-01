package com.yichao.evilgodxu.ui.adaptive

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.window.core.layout.computeWindowSizeClass
import androidx.window.core.layout.WindowSizeClass

// CompositionLocal 用于在 Compose 树中传递窗口尺寸类
val LocalWindowSizeClass = compositionLocalOf<WindowSizeClass> {
    error("WindowSizeClass not provided")
}

// 提供窗口尺寸类给子组件，用于响应式布局适配
// 直接用屏幕 dp 尺寸计算，避免 adaptive 在校验设备上注册窗口监听而崩溃
@Composable
fun ProvideWindowSizeClass(content: @Composable () -> Unit) {
    val config = LocalConfiguration.current
    val windowSizeClass = WindowSizeClass.BREAKPOINTS_V1.computeWindowSizeClass(
        config.screenWidthDp.toFloat(),
        config.screenHeightDp.toFloat()
    )
    CompositionLocalProvider(LocalWindowSizeClass provides windowSizeClass) {
        content()
    }
}
