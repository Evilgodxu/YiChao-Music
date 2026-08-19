package com.yichao.evilgodxu.ui.adaptive

import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.window.core.layout.WindowSizeClass

// CompositionLocal 用于在 Compose 树中传递窗口尺寸类
val LocalWindowSizeClass = compositionLocalOf<WindowSizeClass> {
    error("WindowSizeClass not provided")
}

// 提供窗口尺寸类给子组件，用于响应式布局适配
@Composable
fun ProvideWindowSizeClass(content: @Composable () -> Unit) {
    val windowSizeClass = currentWindowAdaptiveInfo().windowSizeClass
    CompositionLocalProvider(LocalWindowSizeClass provides windowSizeClass) {
        content()
    }
}

// 获取当前窗口尺寸类，用于判断设备类型（手机/平板/折叠屏）和屏幕方向
@Composable
fun currentWindowSizeClass(): WindowSizeClass {
    return LocalWindowSizeClass.current
}
