package com.yichao.evilgodxu.musicpanel

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Environment
import android.os.Build
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.core.content.ContextCompat
import com.yichao.evilgodxu.data.permission.mediaAudioPermission
import com.yichao.evilgodxu.data.permission.mediaImagePermission
import com.yichao.evilgodxu.log.CrashLogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

// 向 Compose 层暴露音乐面板控制器的组合局部
val LocalMusicPanelController = staticCompositionLocalOf<MusicPanelController?> { null }

// 音乐面板与迷你播放器控制器：由 Activity 生命周期驱动悬浮窗的显示与隐藏
class MusicPanelController(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var panelManager: MusicPanelViewManager? = null
    private var miniPlayerManager: MiniPlayerViewManager? = null
    private var miniPlayerTemporarilyHidden = false
    private var miniPlayerEnabled = true
    private var appInForeground = true
    private var prefsJob: Job? = null
    private var pendingExternalPlayUri: android.net.Uri? = null
    private var externalBackgroundPlaying = false

    init {
        // 监听设置页悬浮播放开关，关闭时移除迷你播放器
        prefsJob = scope.launch {
            context.miniPlayerEnabledFlow().collect { enabled ->
                miniPlayerEnabled = enabled
                if (!enabled) dismissMiniPlayer()
            }
        }
    }

    // 展开完整音乐面板；已显示时再次调用收起
    fun openMusicPanel() {
        if (panelManager?.hasWindow == true) {
            panelManager?.dismiss()
            panelManager = null
            return
        }
        showPanelInternal()
    }

    // 从外部打开/分享的音频 URI 后台播放：不展示全屏面板，经迷你播放器播放并保持后台运行
    fun playExternalInBackground(uri: android.net.Uri) {
        val manager = panelManager ?: MusicPanelViewManager(
            context = context,
            onDismiss = {
                panelManager = null
                maybeShowMiniPlayer()
            }
        ).also { panelManager = it }
        manager.playExternalInBackground(uri)
        if (miniPlayerEnabled) {
            externalBackgroundPlaying = true
            showMiniPlayer()
        }
    }

    private fun showPanelInternal() {
        dismissMiniPlayer()
        miniPlayerTemporarilyHidden = false

        val showPanel = {
            val manager = panelManager ?: MusicPanelViewManager(
                context = context,
                onDismiss = {
                    panelManager = null
                    maybeShowMiniPlayer()
                }
            ).also { panelManager = it }
            manager.show()
            val pendingUri = pendingExternalPlayUri
            if (pendingUri != null) {
                pendingExternalPlayUri = null
                manager.playExternalUri(pendingUri)
            }
            Unit
        }

        if (hasRequiredPermissions()) {
            showPanel()
        } else {
            MusicPanelPermissionBridge.pendingShowAction = showPanel
            val intent = Intent(context, MusicPanelPermissionActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            try {
                context.startActivity(intent)
            } catch (e: Exception) {
                MusicPanelPermissionBridge.clearPendingShowAction()
                pendingExternalPlayUri = null
                CrashLogManager.logException("MusicPanelController", "启动音乐面板权限页面失败", e)
            }
        }
    }

    fun showMiniPlayer() {
        if (miniPlayerManager != null) return
        // 未授予悬浮窗权限时无法添加窗口，跳过本次显示
        if (!android.provider.Settings.canDrawOverlays(context)) return
        miniPlayerManager = MiniPlayerViewManager(
            context = context,
            onExpandPanel = { openMusicPanel() },
            onSwipedDismiss = {
                miniPlayerTemporarilyHidden = true
                miniPlayerManager = null
            }
        ).also { it.show() }
    }

    fun dismissMiniPlayer() {
        miniPlayerManager?.dismiss()
        miniPlayerManager = null
    }

    // 应用进入后台：播放中且开关开启时显示迷你播放器
    fun onAppBackgrounded() {
        appInForeground = false
        externalBackgroundPlaying = false
        maybeShowMiniPlayer()
    }

    // 应用回到前台：移除迷你播放器，避免遮挡应用界面
    fun onAppForegrounded() {
        appInForeground = true
        // 外部触发后台播放冷启动时，先保留迷你播放器，不因本次瞬时前台而移除
        if (externalBackgroundPlaying) {
            externalBackgroundPlaying = false
        } else {
            dismissMiniPlayer()
        }
    }

    fun release() {
        prefsJob?.cancel()
        scope.cancel()
        dismissMiniPlayer()
        panelManager?.dismiss()
        panelManager = null
    }

    // 完整面板关闭后，后台播放时恢复迷你播放器
    private fun maybeShowMiniPlayer() {
        if (appInForeground) return
        if (!miniPlayerEnabled || miniPlayerTemporarilyHidden) return
        if (panelManager != null) return
        if (miniPlayerManager != null) return
        if (!MusicPanelStateHolder.state.isPlaying) return
        showMiniPlayer()
    }

    private fun hasRequiredPermissions(): Boolean =
        ContextCompat.checkSelfPermission(context, mediaAudioPermission()) ==
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, mediaImagePermission()) ==
            PackageManager.PERMISSION_GRANTED &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED) &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager())
}
