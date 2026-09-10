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
import com.yichao.evilgodxu.domain.music.panel.MusicPanelStateHolder
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class HomeViewModel(application: Application) : AndroidViewModel(application), KoinComponent {

    private val permissionMonitor = PermissionMonitor(getApplication())
    private val stateHolder: MusicPanelStateHolder by inject()
    private val playlistRefresher: PlaylistRefresher by inject()
    private val metadataEnricher: MetadataEnricher by inject()

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
            val state = stateHolder.state
            // 先恢复持久化歌单，避免扫描覆盖已缓存的封面/歌词
            state.restoreSavedState(context)
            playlistRefresher.refresh(context, state, restoreCurrent = true)
            // 刚完成一次全量扫描，引用集可信，允许参与孤儿缓存的窗口回收
            metadataEnricher.enrichAndCleanup(context, state, reclaimOrphans = true)
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
