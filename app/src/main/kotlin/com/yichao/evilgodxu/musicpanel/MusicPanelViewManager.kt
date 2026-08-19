package com.yichao.evilgodxu.musicpanel

import android.annotation.SuppressLint
import android.content.ContentResolver
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
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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

    // 封面/歌词后台提取的并发上限：限制同时进行的位图解码与网络请求数量，
    // 避免大歌单首次启动时内存与 CPU 尖峰导致面板卡顿
    private val metadataDispatcher = Dispatchers.IO.limitedParallelism(4)

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
    private val scanMutex = Mutex()
    // 封面/歌词后台提取的互斥锁：show / 刷新扫描 / 媒体变更三个入口都会并发触发 enrich，
    // 不加锁会导致在线封面在本地封面尚未提交时抢先匹配，把有内嵌封面的歌永久变成在线封面
    private val enrichMutex = Mutex()
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

        // 清除上一次定时关闭残留的过期信号，防止面板被误关
        playbackState.timerAutoStopped = false

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

        initialization = managerScope.async {
            playbackState.restoreSavedState(context)
            if (!playbackState.isPlayerActive) {
                playbackState.removeUnavailableExternalTracks(context)
            }
            if (playbackState.playlist.isEmpty()) {
                scanAndPlay()
            } else {
                withContext(Dispatchers.Main) {
                    restoreCurrentTrack()
                }
                // 封面后台加载，不阻塞初始化
                managerScope.launch { enrichAndCleanupMetadata() }
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

    private fun resolveAudioPath(uri: Uri): String? {
        if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            context.contentResolver.query(
                uri,
                arrayOf(android.provider.MediaStore.Audio.Media.DATA),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    return cursor.getString(0)
                }
            }
        }
        return uri.path
    }

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

    private suspend fun refreshPlaylist() = scanMutex.withLock {
        val started = withContext(Dispatchers.Main) {
            if (playbackState.isScanning) false else {
                playbackState.isScanning = true
                true
            }
        }
        if (!started) return@withLock
        try {
            val tracks = MusicScanner.scan(context)
            val externalTracks = withContext(Dispatchers.Main) {
                playbackState.playlist.filter { it.path.isBlank() }
            }
            val mergedTracks = mergeTrackMetadata(deduplicateTracks(tracks + externalTracks))
            withContext(Dispatchers.Main) {
                playbackState.setSortedPlaylist(mergedTracks)
                playbackState.persistPlaylist()
            }
            // 刷新后后台加载封面与歌词，完成合并后再清理孤立缓存
            managerScope.launch { enrichAndCleanupMetadata() }
        } finally {
            withContext(Dispatchers.Main + kotlinx.coroutines.NonCancellable) {
                playbackState.isScanning = false
            }
        }
    }

    private fun mergeTrackMetadata(tracks: List<MusicTrack>): List<MusicTrack> {
        val previous = playbackState.playlist.associateBy { normalizedAudioUri(it.audioUri) }
        return tracks.map { track ->
            val cached = previous[normalizedAudioUri(track.audioUri)] ?: return@map track
            track.copy(
                neteaseId = cached.neteaseId,
                neteaseCoverUrl = cached.neteaseCoverUrl,
                coverCachePath = cached.coverCachePath,
                lyricCachePath = cached.lyricCachePath,
                lyricLines = cached.lyricLines
            )
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

    private suspend fun scanAndPlay() = scanMutex.withLock {
        val started = withContext(Dispatchers.Main) {
            if (playbackState.isScanning) false else {
                playbackState.isScanning = true
                true
            }
        }
        if (!started) return@withLock
        try {
            val tracks = MusicScanner.scan(context)
            withContext(Dispatchers.Main) {
                val externalTracks = playbackState.playlist.filter { it.path.isBlank() }
                val mergedTracks = mergeTrackMetadata(deduplicateTracks(tracks + externalTracks))
                playbackState.setSortedPlaylist(mergedTracks)
                playbackState.persistPlaylist()
                restoreCurrentTrack()
            }
            // 封面后台加载，不阻塞 isScanning 重置
            managerScope.launch { enrichAndCleanupMetadata() }
        } finally {
            // 使用非取消式上下文确保 isScanning 一定被重置（防止竟态导致卡死）
            withContext(Dispatchers.Main + kotlinx.coroutines.NonCancellable) {
                playbackState.isScanning = false
            }
        }
    }

    /** 后台加载本地歌曲封面（从 MediaStore 提取），不阻塞主流程 */
    private suspend fun enrichLocalCovers() {
        val tracks = withContext(Dispatchers.Main) { playbackState.playlist.toList() }
        // 本地音频即使带自动匹配的在线封面也允许重试本地提取，修复在线封面文件缺失/损坏导致的永久在线匹配；
        // 纯在线歌曲（无本地文件）不参与本地提取，沿用在线封面
        val needCover = tracks.filter { track ->
            val path = track.coverCachePath
            val fileId = File(path).nameWithoutExtension.toLongOrNull()
            // 封面缓存按歌曲身份归属（文件名为 track.id，在线/手动刷新的封面为 neteaseId）才视为有效并跳过；
            // 旧版本按专辑共享缓存，同一专辑内不同歌曲的封面会互相覆盖，这类共享文件需重新提取为歌曲独立封面
            val coverOwned = fileId != null &&
                MusicMetadataCache.isCurrentCoverPath(path) &&
                (fileId == track.id || (track.neteaseId > 0L && fileId == track.neteaseId))
            !coverOwned && (track.path.isNotBlank() || track.neteaseCoverUrl.isBlank())
        }
        if (needCover.isEmpty()) return
        val updates = coroutineScope {
            needCover.map { track ->
                async<MusicTrack?>(metadataDispatcher) {
                    try {
                        val result = MusicScanner.loadAlbumArt(
                            context, context.contentResolver,
                            Uri.parse(track.audioUri), track.albumId, track.path
                        ) ?: return@async null
                        val cover = result.bitmap
                        try {
                            val oldPath = track.coverCachePath
                            // 封面一律按歌曲身份缓存：内嵌封面/缩略图/专辑封面统一归属单曲，
                            // 避免按专辑共享缓存文件导致同一专辑内歌曲封面互相覆盖
                            val coverPath = MusicMetadataCache.saveCover(context, track.id, cover).orEmpty()
                            // 清理旧封面文件（如旧版专辑共享缓存、covers_original 中的回退文件）
                            if (oldPath.isNotBlank() && oldPath != coverPath) {
                                MusicMetadataCache.deleteCoverFile(oldPath)
                            }
                            track.copy(coverCachePath = coverPath)
                        } finally {
                            cover.recycle()
                        }
                    } catch (e: Exception) {
                        CrashLogManager.logException("MusicPanelViewManager", "提取本地封面失败", e)
                        null
                    }
                }
            }.awaitAll().filterNotNull()
        }
        if (updates.isEmpty()) return
        withContext(Dispatchers.Main) {
            playbackState.batchUpdateTracks(updates)
        }
    }

    private suspend fun enrichAndCleanupMetadata() = enrichMutex.withLock {
        enrichPlaylistMetadata()
        val referenced = withContext(Dispatchers.Main) {
            playbackState.playlist.flatMap { listOf(it.coverCachePath, it.lyricCachePath) }.toSet()
        }
        withContext(Dispatchers.IO) {
            MusicMetadataCache.cleanupOrphanedMetadata(context, referenced)
        }
    }

    private suspend fun enrichPlaylistMetadata() {
        // 先加载本地封面
        enrichLocalCovers()
        val tracks = withContext(Dispatchers.Main) { playbackState.playlist.toList() }

        // 并行加载在线封面和歌词（两者互不依赖），合并后一次性更新
        val (coverUpdates, lyricUpdates) = coroutineScope {
            async { enrichOnlineCovers(tracks) } to
            async { enrichLyrics(tracks) }
        }.let { (c, l) -> c.await() to l.await() }

        val allUpdates = mergeCoverAndLyricUpdates(coverUpdates, lyricUpdates)
        if (allUpdates.isEmpty()) return
        withContext(Dispatchers.Main) {
            playbackState.batchUpdateTracks(allUpdates)
        }
    }

    /** 后台加载在线封面，返回封面更新列表 */
    private suspend fun enrichOnlineCovers(tracks: List<MusicTrack>): List<MusicTrack> {
        val needCover = tracks.filter { track ->
            !MusicMetadataCache.isValid(track.coverCachePath)
        }
        if (needCover.isEmpty()) return emptyList()
        return coroutineScope {
            needCover.map { track ->
                async(metadataDispatcher) {
                    try {
                        val match = NeteaseMusicApi.match(track.title, track.artist, track.duration)
                            ?: return@async null
                        val coverBytes = NeteaseMusicApi.loadCoverBytes(match.coverUrl.orEmpty())
                            ?: return@async null // 下载失败时保留已有缓存与匹配信息，避免下次重复请求
                        val oldPath = track.coverCachePath
                        val coverPath = MusicMetadataCache.saveCover(context, match.id, coverBytes).orEmpty()
                        if (coverPath.isBlank()) return@async null
                        if (oldPath.isNotBlank() && oldPath != coverPath) {
                            MusicMetadataCache.deleteCoverFile(oldPath)
                        }
                        track.copy(
                            neteaseId = match.id,
                            neteaseCoverUrl = match.coverUrl.orEmpty(),
                            coverCachePath = coverPath
                        )
                    } catch (e: Exception) {
                        CrashLogManager.logException("MusicPanelViewManager", "获取在线封面失败", e)
                        null
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }

    /** 后台加载在线歌词，返回歌词更新列表 */
    private suspend fun enrichLyrics(tracks: List<MusicTrack>): List<MusicTrack> {
        val needLyrics = tracks.filter { track ->
            track.lyricLines.isEmpty() && !MusicMetadataCache.isValid(track.lyricCachePath)
        }
        if (needLyrics.isEmpty()) return emptyList()
        return coroutineScope {
            needLyrics.map { track ->
                async(metadataDispatcher) {
                    try {
                        val match = NeteaseMusicApi.match(track.title, track.artist, track.duration)
                            ?: return@async null
                        val lyric = NeteaseMusicApi.lyric(match.id)
                        if (lyric.lines.isEmpty()) return@async null
                        val lyricPath = MusicMetadataCache.saveLyrics(context, match.id, lyric.lines).orEmpty()
                        track.copy(lyricCachePath = lyricPath, lyricLines = lyric.lines)
                    } catch (e: Exception) {
                        CrashLogManager.logException("MusicPanelViewManager", "获取在线歌词失败", e)
                        null
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }

    /** 合并封面和歌词的更新，确保同一首歌的字段不互相覆盖 */
    private fun mergeCoverAndLyricUpdates(
        coverUpdates: List<MusicTrack>,
        lyricUpdates: List<MusicTrack>
    ): List<MusicTrack> {
        val coverMap = coverUpdates.associateBy { it.id }
        val lyricMap = lyricUpdates.associateBy { it.id }
        val allIds = (coverMap.keys + lyricMap.keys).toSet()
        return allIds.mapNotNull { id ->
            val cover = coverMap[id]
            val lyric = lyricMap[id]
            when {
                cover != null && lyric != null -> cover.copy(
                    lyricCachePath = lyric.lyricCachePath.ifEmpty { cover.lyricCachePath },
                    lyricLines = lyric.lyricLines.ifEmpty { cover.lyricLines }
                )
                cover != null -> cover
                lyric != null -> lyric
                else -> null
            }
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