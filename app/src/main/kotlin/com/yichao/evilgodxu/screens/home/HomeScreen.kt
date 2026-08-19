package com.yichao.evilgodxu.screens.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.yichao.evilgodxu.screens.home.home_assembly.HomeAssembly

// 页面入口：编排首页分区
@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    onOpenSettings: () -> Unit,
) {
    HomeAssembly(
        modifier = modifier,
        onOpenSettings = onOpenSettings,
    )
}