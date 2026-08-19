package com.yichao.evilgodxu.screens.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.yichao.evilgodxu.data.permission.PermissionMonitor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val permissionMonitor = PermissionMonitor(getApplication())

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init {
        refreshPermissions()
    }

    // 刷新全部权限状态，从系统设置页返回时调用
    fun refreshPermissions() {
        _state.update {
            it.copy(
                overlayGranted = permissionMonitor.isOverlayGranted(),
                allFilesGranted = permissionMonitor.isAllFilesGranted(),
                mediaAudioGranted = permissionMonitor.isMediaAudioGranted(),
                bluetoothGranted = permissionMonitor.isBluetoothGranted(),
            )
        }
    }
}
