package com.yichao.evilgodxu.screens.home

// 首页 UI 状态
data class HomeUiState(
    val isLoading: Boolean = false,
    val overlayGranted: Boolean = false,
    val allFilesGranted: Boolean = false,
    val mediaAudioGranted: Boolean = false,
    val bluetoothGranted: Boolean = false,
)
