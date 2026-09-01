package com.yichao.evilgodxu.screens.home

import android.app.Activity
import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yichao.evilgodxu.data.music.metadata.MetadataEnricher
import com.yichao.evilgodxu.data.music.PlaylistRefresher
import com.yichao.evilgodxu.data.permission.PermissionMonitor
import com.yichao.evilgodxu.data.permission.PermissionType
import com.yichao.evilgodxu.domain.music.MusicPanelStateHolder
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val permissionMonitor = PermissionMonitor(getApplication())

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    // 正在等待授权的系统特殊权限监控任务
    private var permissionMonitorJob: Job? = null

    init {
        refreshPermissions()
    }

    // 刷新全部权限状态，从系统设置页返回时调用
    fun refreshPermissions() {
        val wasAllGranted = _state.value.allPermissionsGranted
        _state.update {
            it.copy(
                allFilesGranted = permissionMonitor.isAllFilesGranted(),
                mediaAudioGranted = permissionMonitor.isMediaAudioGranted(),
                mediaImageGranted = permissionMonitor.isMediaImageGranted(),
            )
        }
        // 权限从未全部授权变为全部授权时，自动扫描歌曲并补全封面/歌词
        if (!wasAllGranted && _state.value.allPermissionsGranted) {
            autoScanAfterPermissionGranted()
        }
    }

    private var autoScanStarted = false

    private fun autoScanAfterPermissionGranted() {
        if (autoScanStarted) return
        autoScanStarted = true
        viewModelScope.launch {
            val context = getApplication<Application>()
            val state = MusicPanelStateHolder.state
            // 先恢复持久化歌单，避免扫描覆盖已缓存的封面/歌词
            state.restoreSavedState(context)
            PlaylistRefresher.refresh(context, state, restoreCurrent = true)
            MetadataEnricher.enrichAndCleanup(context, state)
        }
    }

    // 开始监控系统特殊权限，授权后自动带回应用前台
    fun startPermissionMonitor(permissionType: PermissionType, activity: Activity) {
        permissionMonitorJob?.cancel()
        permissionMonitorJob = viewModelScope.launch {
            permissionMonitor.monitorPermission(permissionType)
                .collect { granted ->
                    if (granted) {
                        refreshPermissions()
                        bringAppToFront(activity)
                        permissionMonitorJob?.cancel()
                    }
                }
        }
    }

    // 停止权限监控，从系统设置页返回或页面销毁时调用
    fun stopPermissionMonitor() {
        permissionMonitorJob?.cancel()
        permissionMonitorJob = null
    }

    // 将应用带回前台，使用户无需手动返回本应用
    private fun bringAppToFront(activity: Activity) {
        val intent = activity.packageManager.getLaunchIntentForPackage(activity.packageName)
        intent?.let {
            it.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_NEW_TASK
            activity.startActivity(it)
        }
    }
}
