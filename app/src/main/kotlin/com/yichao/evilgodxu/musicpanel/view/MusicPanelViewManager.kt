package com.yichao.evilgodxu.musicpanel

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.log.CrashLogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// 音乐面板悬浮窗管理器
class MusicPanelViewManager(
    private val context: Context,
    private val onDismiss: () -> Unit,
    private val onShowFailed: ((WindowManager.BadTokenException) -> Unit)? = null
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var composeView: ComposeView? = null
    private var isDismissing = false
    private val managerJob = SupervisorJob()
    private val managerScope = CoroutineScope(managerJob + Dispatchers.IO)

    private val playbackState = MusicPanelStateHolder.state
    private var pendingExternalUri: android.net.Uri? = null
    private var usbRouteJob: Job? = null

    // USB 音频独占监听器
    private val usbAudioMonitor = UsbAudioMonitor(
        context = context,
        onUsbDeviceAttached = { deviceName ->
            playbackState.usbDeviceName = deviceName
            playbackState.isUsbDeviceConnected = true
            playbackState.usbError = null  // 连接成功时清除错误
            refreshSignalPathState(playbackState)
            // 根据用户偏好自动启用 USB 独占
            if (playbackState.usbExclusiveEnabled) {
                usbRouteJob?.cancel()
                usbRouteJob = managerScope.launch {
                    val success = UsbAudioMonitor.setUsbExclusive(context, true)
                    if (success && playbackState.isUsbDeviceConnected &&
                        playbackState.usbDeviceName == deviceName) {
                        withContext(Dispatchers.Main) {
                            playbackState.isUsbExclusiveMode = true
                        }
                    } else {
                        UsbAudioMonitor.setUsbExclusive(context, false)
                        withContext(Dispatchers.Main) {
                            playbackState.isUsbExclusiveMode = false
                        }
                    }
                }
            }
        },
        onUsbDeviceDetached = {
            playbackState.isUsbDeviceConnected = false
            playbackState.isUsbExclusiveMode = false
            playbackState.usbDeviceName = ""
            playbackState.usbError = null  // 断开时清除错误
            refreshSignalPathState(playbackState)
            // 移除首选设备设置，让音频回退到系统默认路由
            usbRouteJob?.cancel()
            usbRouteJob = managerScope.launch {
                UsbAudioMonitor.setUsbExclusive(context, false)
            }
        },
        onError = { message ->
            playbackState.usbError = message
        },
        // 请求权限前先关闭面板（面板悬浮窗优先级高于系统弹窗）
        onBeforeRequestPermission = { dismiss() }
    )
    // 蓝牙耳机监听器
    private val bluetoothHeadsetMonitor = BluetoothHeadsetMonitor(
        context = context,
        onHeadsetConnected = { deviceName, isNewConnection ->
            playbackState.isBluetoothHeadsetConnected = true
            deviceName?.let { playbackState.bluetoothHeadsetName = it }
            refreshSignalPathState(playbackState)
            if (isNewConnection && !playbackState.bluetoothVolumeInitialized) {
                // 单次播放会话内首次连接蓝牙耳机时自动降低媒体音量到 25%
                BluetoothHeadsetMonitor.reduceMediaVolume(context, 0.25f)
                playbackState.bluetoothVolumeInitialized = true
            }
        },
        onHeadsetDisconnected = {
            playbackState.isBluetoothHeadsetConnected = false
            playbackState.bluetoothHeadsetName = ""
            refreshSignalPathState(playbackState)
        }
    )
    private val externalTrackMutex = Mutex()
    private var initialization: Deferred<Unit>? = null
    private var mediaObserverRegistered = false
    private var refreshJob: Job? = null
    private val mediaObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            refreshJob?.cancel()
            refreshJob = managerScope.launch {
                delay(300)
                refreshPlaylist()
            }
        }
    }

    fun playExternalUri(uri: android.net.Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            CrashLogManager.logException("MusicPanelViewManager", "获取外部音频持久访问权限失败", e)
            // 外部应用可能只授予临时读取权限，仍需继续播放当前 URI
        }
        pendingExternalUri = uri
        if (composeView == null) {
            show()
        }
        loadExternalTrack()
    }

    // 后台模式播放外部音频：仅初始化播放状态，不展示全屏面板
    fun playExternalInBackground(uri: android.net.Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            CrashLogManager.logException("MusicPanelViewManager", "获取外部音频持久访问权限失败", e)
            // 外部应用可能只授予临时读取权限，仍需继续播放当前 URI
        }
        pendingExternalUri = uri
        if (initialization == null) {
            initialize()
        }
        loadExternalTrack()
    }

    // 当前是否展示全屏悬浮窗
    val hasWindow: Boolean get() = composeView != null

    private val lifecycleOwner = object : LifecycleOwner {
        private val lifecycleRegistry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = lifecycleRegistry
        fun handleLifecycleEvent(event: Lifecycle.Event) = lifecycleRegistry.handleLifecycleEvent(event)
    }

    private val viewModelStoreOwner = object : ViewModelStoreOwner {
        private val store = ViewModelStore()
        override val viewModelStore: ViewModelStore get() = store
    }

    private val savedStateRegistryOwner = object : SavedStateRegistryOwner {
        private val controller = SavedStateRegistryController.create(this)
        override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry
        override val lifecycle: Lifecycle get() = lifecycleOwner.lifecycle
        fun performAttach() = controller.performAttach()
        fun performRestore() = controller.performRestore(null)
    }

    // 显示音乐面板悬浮窗
    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (composeView != null) return

        // 清除上一次定时关闭残留的过期信号，防止面板被误关或应用被误退
        playbackState.timerAutoStopped = false
        playbackState.sleepTimerExpired = false

        val flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_BLUR_BEHIND

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            blurBehindRadius = 80
        }

        // 在 UI 渲染前同步检查已连接的蓝牙设备，确保首次显示时状态正确
        bluetoothHeadsetMonitor.checkExistingSync()

        val view = ComposeView(context).apply {
            alpha = 0f
            scaleX = 0.8f
            scaleY = 0.8f
            setContent {
                MusicPanelOverlay(
                playbackState = playbackState,
                onScan = { requestScan() },
                onDismiss = { dismiss() }
            )
            }
        }

        savedStateRegistryOwner.performAttach()
        savedStateRegistryOwner.performRestore()
        view.setViewTreeLifecycleOwner(lifecycleOwner)
        view.setViewTreeViewModelStoreOwner(viewModelStoreOwner)
        view.setViewTreeSavedStateRegistryOwner(savedStateRegistryOwner)

        view.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                dismiss()
                true
            } else {
                false
            }
        }
        view.isFocusableInTouchMode = true
        view.requestFocus()

        composeView = view
        try {
            windowManager.addView(view, params)
        } catch (e: SecurityException) {
            CrashLogManager.logException("MusicPanelViewManager", "添加音乐面板失败（缺少悬浮窗权限）", e)
            composeView = null
            return
        } catch (e: WindowManager.BadTokenException) {
            CrashLogManager.logException("MusicPanelViewManager", "添加音乐面板失败（窗口令牌失效）", e)
            composeView = null
            onShowFailed?.invoke(e)
            return
        }
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        view.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(250)
            .setInterpolator(DecelerateInterpolator())
            .start()

        initialize()
    }

    // 初始化播放状态与外部设备监听；仅执行一次，供全屏面板与后台模式共用
    private fun initialize() {
        if (initialization != null) return
        initialization = managerScope.async {
            playbackState.restoreSavedState(context)
            // MediaController 仅限主线程访问，须在 Main 线程读取其状态
            val playerActive = withContext(Dispatchers.Main) { playbackState.isPlayerActive }
            if (!playerActive) {
                playbackState.removeUnavailableExternalTracks(context)
            }
            if (playbackState.playlist.isEmpty()) {
                scanAndPlay()
            } else {
                withContext(Dispatchers.Main) {
                    restoreCurrentTrack()
                }
                // 封面后台加载，不阻塞初始化
                managerScope.launch { MetadataEnricher.enrichAndCleanup(context, playbackState) }
            }
            withContext(Dispatchers.Main) {
                playbackState.syncPlaybackState()
                playbackState.updatePosition()
            }
            registerMediaObserver()
            usbAudioMonitor.register()
            bluetoothHeadsetMonitor.register()
        }
    }

    private fun normalizedAudioUri(audioUri: String): String {
        return Uri.parse(audioUri)
            .normalizeScheme()
            .buildUpon()
            .clearQuery()
            .fragment(null)
            .build()
            .toString()
    }

    private fun resolveAudioPath(uri: Uri): String? =
        resolveLocalPath(context, uri.toString())

    private fun isSameAudioTrack(track: MusicTrack, targetUri: Uri, targetPath: String?): Boolean {
        return normalizedAudioUri(track.audioUri) == normalizedAudioUri(targetUri.toString()) ||
                (targetPath != null && track.path.isNotBlank() && track.path == targetPath)
    }

    private fun registerMediaObserver() {
        if (mediaObserverRegistered) return
        context.contentResolver.registerContentObserver(
            android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            true,
            mediaObserver
        )
        mediaObserverRegistered = true
    }

    private fun requestScan() {
        if (playbackState.isScanning || isDismissing) return
        refreshJob?.cancel()
        refreshJob = managerScope.launch {
            scanAndPlay()
        }
    }

    private suspend fun refreshPlaylist() {
        PlaylistRefresher.refresh(context, playbackState, restoreCurrent = false) {
            // 刷新后后台加载封面与歌词，完成合并后再清理孤立缓存
            managerScope.launch { MetadataEnricher.enrichAndCleanup(context, playbackState) }
        }
    }

    private fun deduplicateTracks(tracks: List<MusicTrack>): List<MusicTrack> {
        return tracks.distinctBy { normalizedAudioUri(it.audioUri) }
    }

    private fun loadExternalTrack() {
        val uri = pendingExternalUri ?: return
        managerScope.launch {
            externalTrackMutex.withLock {
                initialization?.await()
                if (pendingExternalUri != uri) return@withLock
                val track = MusicScanner.fromUri(context, uri) ?: return@withLock
                val targetUri = Uri.parse(track.audioUri).normalizeScheme()
                val targetPath = resolveAudioPath(targetUri)
                val targetIndex = withContext(Dispatchers.Main) {
                    val existingIndex = playbackState.playlist.indexOfFirst {
                        isSameAudioTrack(it, targetUri, targetPath)
                    }
                    if (existingIndex >= 0) {
                        existingIndex
                    } else {
                        playbackState.playlist = deduplicateTracks(playbackState.playlist + track)
                        playbackState.playlist.indexOfFirst {
                            isSameAudioTrack(it, targetUri, targetPath)
                        }
                    }
                }
                if (targetIndex < 0) return@withLock
                playbackState.persistPlaylist()
                withContext(Dispatchers.Main) {
                    playbackState.currentIndex = targetIndex
                    playbackState.currentTrack = playbackState.playlist[targetIndex]
                }
                withContext(Dispatchers.Main) {
                    playTrackAt(context, playbackState, targetIndex)
                }
                pendingExternalUri = null
            }
        }
    }

    private suspend fun scanAndPlay() {
        PlaylistRefresher.refresh(context, playbackState, restoreCurrent = true) {
            // 封面后台加载，不阻塞 isScanning 重置
            managerScope.launch { MetadataEnricher.enrichAndCleanup(context, playbackState) }
        }
    }

    private fun restoreCurrentTrack() {
        if (playbackState.playlist.isEmpty() || playbackState.currentTrack != null) return
        val savedUri = playbackState.pendingSavedUri
        val index = savedUri?.let { uri -> playbackState.playlist.indexOfFirst { it.audioUri == uri } }
            ?.takeIf { it >= 0 }
            ?: 0
        playbackState.currentIndex = index
        playbackState.currentTrack = playbackState.playlist[index]
    }

    // 关闭音乐面板（保留播放状态与 ExoPlayer，下次显示直接恢复）
    fun dismiss() {
        val view = composeView ?: return
        if (isDismissing) return
        isDismissing = true

        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)

        view.animate()
            .alpha(0f)
            .scaleX(0.9f)
            .scaleY(0.9f)
            .setDuration(200)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
                lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
                try {
                    if (view.windowToken != null) {
                        windowManager.removeView(view)
                    }
                } catch (e: Exception) {
                    CrashLogManager.logException("MusicPanelViewManager", "移除音乐面板失败", e)
                }
                composeView = null
                isDismissing = false
                if (mediaObserverRegistered) {
                    context.contentResolver.unregisterContentObserver(mediaObserver)
                    mediaObserverRegistered = false
                }
                usbAudioMonitor.unregister()
                usbRouteJob?.cancel()
                usbRouteJob = null
                bluetoothHeadsetMonitor.unregister()
                playbackState.updatePosition()
                if (!playbackState.isPlayerActive) {
                    playbackState.softRelease()
                }
                onDismiss()
                managerJob.cancel()
            }
            .start()
    }

    /** 刷新播放链路面板的状态行 */
    private fun refreshSignalPathState(state: MusicPlaybackState) {
        state.audioSignalPathStrategy = if (state.isUsbExclusiveMode) "Direct" else "Mixer"
        state.audioSignalPathOutputDevice = resolveOutputDeviceName(state)
        state.audioSignalPathRoute = if (state.isUsbDeviceConnected) "USB"
            else if (state.isBluetoothHeadsetConnected) "Bluetooth" else "System"
    }

    private fun resolveOutputDeviceName(state: MusicPlaybackState): String {
        if (state.isUsbDeviceConnected && state.usbDeviceName.isNotBlank()) return state.usbDeviceName
        if (state.isBluetoothHeadsetConnected && state.bluetoothHeadsetName.isNotBlank()) {
            return state.bluetoothHeadsetName
        }
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { device ->
                device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER ||
                    device.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
            }
            ?.productName
            ?.toString()
            ?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.signal_path_speaker)
    }

    companion object {
        private const val TAG = "MusicPanelViewManager"
    }
}