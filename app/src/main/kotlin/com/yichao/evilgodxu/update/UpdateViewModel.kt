package com.yichao.evilgodxu.update

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

// 更新检查与下载状态的统一管理，供主页与设置页共用，
// 以单例形式注册，保证两处读写同一状态，对话框由 Activity 全局弹出
class UpdateViewModel(application: Application) : AndroidViewModel(application) {

    private val context get() = getApplication<Application>().applicationContext

    private val _updateInfo = MutableStateFlow<UpdateInfo?>(null)
    val updateInfo: StateFlow<UpdateInfo?> = _updateInfo.asStateFlow()

    private val _showUpdateDialog = MutableStateFlow(false)
    val showUpdateDialog: StateFlow<Boolean> = _showUpdateDialog.asStateFlow()

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    // 手动检查后的结果提示（已是最新 / 检查失败）
    private val _checkFeedback = MutableStateFlow<CheckFeedback?>(null)
    val checkFeedback: StateFlow<CheckFeedback?> = _checkFeedback.asStateFlow()

    /** 手动检查结果的提示类型 */
    enum class CheckFeedback { UP_TO_DATE, ERROR }

    // 检查更新：有新版本时弹出更新对话框，否则手动检查时给出"已是最新"或"失败"提示
    fun checkForUpdate(force: Boolean = false) {
        // 手动强制检查时清空上次提示，避免旧结果误弹
        _checkFeedback.value = null
        viewModelScope.launch {
            var checkFailed = false
            val result = UpdateManager.checkForUpdate(
                context,
                force = force,
                onError = { checkFailed = true }
            )
            if (result != null) {
                _updateInfo.value = result
                _showUpdateDialog.value = true
            } else if (checkFailed) {
                _checkFeedback.value = CheckFeedback.ERROR
            } else {
                _checkFeedback.value = CheckFeedback.UP_TO_DATE
            }
        }
    }

    // 下载并安装当前待更新版本
    fun downloadAndInstall() {
        val info = _updateInfo.value ?: return
        _downloadState.value = DownloadState.Downloading(0f)
        viewModelScope.launch {
            val success = UpdateManager.downloadAndInstall(context, info) { progress ->
                _downloadState.value = if (progress < 0f) {
                    DownloadState.Failed("download_failed")
                } else {
                    DownloadState.Downloading(progress)
                }
            }
            if (success) {
                _downloadState.value = DownloadState.Success
                _showUpdateDialog.value = false
            } else if (_downloadState.value !is DownloadState.Failed) {
                _downloadState.value = DownloadState.Failed("download_failed")
            }
        }
    }

    // 关闭更新对话框并清理待更新信息
    fun dismissUpdateDialog() {
        _showUpdateDialog.value = false
        _downloadState.value = DownloadState.Idle
        viewModelScope.launch { UpdateManager.clearPendingUpdate(context) }
    }

    // 清除手动检查结果提示
    fun clearCheckFeedback() {
        _checkFeedback.value = null
    }
}
