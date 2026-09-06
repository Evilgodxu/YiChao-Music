package com.yichao.evilgodxu.domain.music

import android.content.ContentResolver
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.yichao.evilgodxu.data.music.PlaybackStateStore
import com.yichao.evilgodxu.data.music.PlaybackSnapshot
import com.yichao.evilgodxu.data.music.PlaylistCacheStore
import com.yichao.evilgodxu.data.music.RecentPlayedStore
import com.yichao.evilgodxu.data.music.SearchHistoryStore
import com.yichao.evilgodxu.data.music.metadata.MetadataEnricher
import com.yichao.evilgodxu.data.music.metadata.MusicMetadataCache
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.data.music.model.PlayMode
import com.yichao.evilgodxu.data.music.trackIdentityKey
import com.yichao.evilgodxu.log.CrashLogManager
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.data.playlist.PlaylistStore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// 音乐播放器状态持有者（悬浮窗级共享状态）
class MusicPlaybackState(val ui: MusicPanelUiState) : PlaybackController {

    // 常听收录窗口：统计 3 天内完整播放次数不少于 2 次的歌曲
    companion object {
        private const val RECENT_WINDOW_DAYS = 3
        private const val RECENT_MIN_PLAYS = 2
        // 播放期间周期性持久化间隔：保证冷启动/异常退出也能恢复当前曲目与进度
        private const val STATE_PERSIST_INTERVAL_MS = 3000L
        // 单曲循环回卷判定：位置回退超过该值且曾越过曲目中部，视为一次完整播放
        private const val LOOP_RESTART_MIN_JUMP_MS = 3000L
        // 回卷检测与过渡回调记录的去重冷却：同一次循环只计入一次完整播放
        private const val AUTO_COUNT_COOLDOWN_MS = 2000L
        // 进度单调复位兜底：未触发切歌/拖动回调但位置大幅回退（如切换歌单重载同 ID 曲目）时视为重置；
        // 小幅回退仍按流媒体回锚处理，保持进度单调
        private const val MONO_REBASELINE_JUMP_MS = 3000L
    }

    // 上次持久化播放状态的时刻，用于播放期间节流写入
    private var lastStatePersistAt = 0L

    // 待落盘的播放状态快照：每次调用覆盖为最新值，写入任务按需消费，合并连续写入
    private var pendingStateSnapshot: PlaybackSnapshot? = null
    // 播放状态写入任务：在途时新调用只更新快照，由在途循环以最新快照收尾，
    // 避免取消旧任务产生"旧任务已取消、新任务未启动"的写入间隙
    private var stateWriteJob: Job? = null
    private var playlistPersistJob: Job? = null
    private val persistenceMutex = Mutex()
    var appContext: Context? = null
    var mediaController: MediaController? by mutableStateOf(null)
    private var suppressAutoNext = false
    val controllerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            syncPlaybackState()
        }

        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
            // 曲目自然播完即计一次完整播放，作为常听收录依据：
            // AUTO=自动续播/单曲结束切下一首；REPEAT=单曲循环重播当前曲目。
            // 手动切歌(SEEK)、列表变更(PLAYLIST_CHANGED)非自然结束，不计入。
            // 需在更新 currentTrack 前记录，此处 currentTrack 仍为刚播完的上一首。
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
            ) {
                currentTrack?.id?.let { recordPlayed(it) }
            }
            if (stopAfterCurrentTrack && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                // 定时关闭：当前曲目自然结束 → 停止播放
                completeSleepTimer()
                return
            }
            // 插队队列：仅自然切换时消费队列；队列播完后接续原播放位置
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                if (playNextQueue.isNotEmpty()) {
                    val queued = playNextQueue.first()
                    playNextQueue = playNextQueue.drop(1)
                    val queuedIndex = playlist.indexOfFirst { it.id == queued.id }
                    if (queuedIndex >= 0) {
                        playbackScope.launch {
                            playTrackAt(appContext ?: return@launch, this@MusicPlaybackState, queuedIndex, clearQueue = false)
                        }
                        return
                    }
                } else if (queueResumeTrackId != null) {
                    val resumeTrackId = queueResumeTrackId
                    queueResumeTrackId = null
                    val resumeIndex = playlist.indexOfFirst { it.id == resumeTrackId }
                    val next = calculateIndex(direction = 1, repeatOne = true, from = resumeIndex)
                    if (next in playlist.indices && next != currentIndex) {
                        playbackScope.launch {
                            playTrackAt(appContext ?: return@launch, this@MusicPlaybackState, next, clearQueue = false)
                        }
                        return
                    }
                }
            }
            val id = mediaItem?.mediaId?.toLongOrNull() ?: return
            val index = playlist.indexOfFirst { it.id == id }
            if (index >= 0) {
                currentIndex = index
                currentTrack = playlist[index]
                isPrepared = false
                currentPosition = 0L
                duration = 0L
                // 切歌或单曲循环重播：复位进度单调基准，允许进度回到起点
                lastMonoMediaId = null
                // 切换曲目即持久化最新 URI，确保后台自动下一首也能被冷启动恢复
                persistState()
                // 切歌后主动预读新曲源格式，避免信息条等待解码回填而长时间空白
                appContext?.let { refreshIdleTrackFormatInfo(it) }
            }
            // 切换曲目后清理未在播放的在线歌曲，避免在线播放曲目在播放列表中常驻；
            // 元数据补全只在缓存完成时执行一次，切歌不再触发，避免重复写封面并触发系统级文件扫描
            cleanupIdleOnlineTracks()
            // 再次从控制器校正当前曲目，确保 UI 与真实音频一致（在线曲目切换时尤其关键）
            syncPlaybackState()
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            // 手动拖动进度会触发 SEEK 类位置不连续：重置回卷检测基准，
            // 避免把"拖回开头"误判为单曲循环完整播放
            if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                lastTickPosition = 0L
                lastMonoMediaId = null
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val controller = mediaController ?: return
            when (playbackState) {
                Player.STATE_READY -> {
                    isPrepared = true
                    ensurePositionTicker()
                    if (ui.closeSearchResultsOnReady) {
                        ui.closeSearchResultsOnReady = false
                        ui.isSearchMode = false
                        ui.showSearchResults = false
                        ui.searchQuery = ""
                        ui.searchResults = emptyList()
                        ui.searchPending = emptyList()
                        ui.searchPendingFull = false
                        ui.pendingSearchResults = emptyList()
                    }
                    // 音质试播就绪即播放成功：关闭音质对话框并清除待确认标记
                    if (ui.pendingQualityPlayTrackId != null) {
                        ui.pendingQualityPlayTrackId = null
                        ui.qualityBusy = false
                        ui.qualityPickTrack = null
                        ui.qualityError = null
                    }
                    syncPlaybackState()
                }
                Player.STATE_ENDED -> {
                    isPlaying = false
                    currentPosition = duration
                    // REPEAT_MODE_OFF 播完整个时间线末尾（无切歌回调）时兜底计入完整播放；
                    // 常规自然播完/单曲循环已在 onMediaItemTransition 中记录，此处不会重复
                    currentTrack?.id?.let { recordPlayed(it) }
                    if (suppressAutoNext) {
                        suppressAutoNext = false
                        return
                    }
                    if (stopAfterCurrentTrack) {
                        // 定时关闭：曲目播毕停止播放
                        completeSleepTimer()
                        return
                    }
                    val next = autoNextIndex()
                    if (next >= 0) {
                        playbackScope.launch {
                            playTrackAt(appContext ?: return@launch, this@MusicPlaybackState, next, clearQueue = false)
                        }
                    }
                }
            }
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            // 音质试播失败：移除刚加入的试播曲目（不残留播放列表），保留对话框供用户换其它音质
            val pendingId = ui.pendingQualityPlayTrackId
            if (pendingId != null && ui.qualityPickTrack != null) {
                ui.pendingQualityPlayTrackId = null
                ui.qualityBusy = false
                ui.qualityError = appContext?.getString(R.string.music_panel_quality_failed)
                removeTrack(pendingId, advanceToNext = false)
            }
            // errorMsg 为可空类型，appContext 为空时置 null（播放不会发生，正常显示无错误）
            errorMsg = appContext?.getString(R.string.music_panel_play_failed)
            isPlaying = false
            isPrepared = false
            stopPositionTicker()
            ui.closeSearchResultsOnReady = false
            ui.pendingSearchResults = emptyList()
            suppressAutoNext = true
            mediaController?.stop()
        }
    }
    override var isPlaying by mutableStateOf(false)
    override var isPrepared by mutableStateOf(false)
    override val isPlayerActive: Boolean
        get() = mediaController?.let { ctrl ->
            ctrl.isPlaying || ctrl.playbackState == Player.STATE_BUFFERING
        } ?: false
    override var duration by mutableLongStateOf(0L)
    override var currentPosition by mutableLongStateOf(0L)
    override val rawPosition: Long
        get() = mediaController?.currentPosition
            ?.takeIf { it >= 0L } ?: currentPosition
    private val _playlist = mutableStateOf<List<MusicTrack>>(emptyList())
    override var playlist: List<MusicTrack>
        get() = _playlist.value
        set(value) {
            _playlist.value = value
            cachedMediaItems = null
            mediaItemsDirty = true
        }
    /** 缓存 playlist 对应的 MediaItem 列表，避免切歌时重复构建 */
    var cachedMediaItems by mutableStateOf<List<androidx.media3.common.MediaItem>?>(null)
    /** 封面更新后需要刷新系统媒体面板的 MediaItem，标记为脏 */
    var mediaItemsDirty by mutableStateOf(false)
    override var currentIndex by mutableIntStateOf(-1)
    override var currentTrack by mutableStateOf<MusicTrack?>(null)
    override var playMode by mutableStateOf(PlayMode.RepeatAll)
    // 播放速度：默认 1.0，调节范围 0.5~2.0
    override var playbackSpeed by mutableFloatStateOf(PlaybackController.PLAYBACK_SPEED_DEFAULT)
    override var errorMsg by mutableStateOf<String?>(null)
    override var isScanning by mutableStateOf(false)
    override var isLyricsVisible by mutableStateOf(false)

    private fun hasUriAccess(context: Context, audioUri: String): Boolean {
        val uri = Uri.parse(audioUri)
        if (context.contentResolver.persistedUriPermissions.none {
                it.uri == uri && it.isReadPermission
            }) return false
        return try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
        } catch (e: Exception) {
            CrashLogManager.logException("MusicPlaybackState", "检查媒体访问权限失败", e)
            false
        }
    }

    override suspend fun removeUnavailableExternalTracks(context: Context) {
        // 文件访问探测属 I/O 操作，在 IO 线程执行避免阻塞主线程
        val unavailableIds = withContext(Dispatchers.IO) {
            playlist
                .filter { track ->
                    track.path.isBlank() &&
                        track.audioUri.isNotBlank() &&
                        runCatching {
                            val scheme = Uri.parse(track.audioUri).scheme
                            scheme != null && scheme !in listOf("http", "https")
                        }.getOrElse { false } &&
                        runCatching { Uri.parse(track.audioUri).scheme == ContentResolver.SCHEME_CONTENT }.getOrElse { false } &&
                        !hasUriAccess(context, track.audioUri)
                }
                .map { it.id }
                .toSet()
        }
        if (unavailableIds.isEmpty()) return

        withContext(Dispatchers.Main) {
            val currentWasRemoved = currentTrack?.id in unavailableIds
            playlist = playlist.filterNot { it.id in unavailableIds }
            currentIndex = playlist.indexOfFirst { it.id == currentTrack?.id }
            if (currentWasRemoved) {
                mediaController?.stop()
                currentTrack = null
                currentIndex = -1
                isPlaying = false
                isPrepared = false
                currentPosition = 0L
                duration = 0L
                clearSavedState(context)
            }
            persistPlaylist()
        }
    }

    private suspend fun clearSavedState(context: Context) {
        PlaybackStateStore.clearSaved(context)
    }

    // 仅清除持久化的播放位置（定时关闭时使用，保留歌曲 URI）
    private suspend fun clearSavedPosition(context: Context) {
        PlaybackStateStore.clearPosition(context)
    }

    // 缓存下载进行中的曲目 ID 集合：切歌清理时保留这些曲目，等待下载完成后将索引指向本地文件
    val cacheInProgressIds: MutableSet<Long> =
        java.util.Collections.synchronizedSet(mutableSetOf())

    // 自动清理未在播放的纯在线流曲目；已缓存为本地文件或缓存进行中的曲目保留，保证离线播放不中断
    override fun cleanupIdleOnlineTracks() {
        // 以控制器实际播放项为权威来源，避免 UI 状态与真实音频脱同步
        val activeId = mediaController?.currentMediaItem?.mediaId?.toLongOrNull() ?: currentTrack?.id
        val kept = playlist.filter { track ->
            track.id == activeId || track.id in cacheInProgressIds || !isOnlineStreaming(track)
        }
        if (kept.size == playlist.size) return
        playlist = kept
        currentIndex = kept.indexOfFirst { it.id == activeId }
        persistPlaylist()
        // 列表收缩后重新从控制器校正当前曲目，保证显示与实际播放一致
        syncPlaybackState()
    }

    // 判定是否为在线流媒体曲目：无本地路径且音频地址为 http(s)
    private fun isOnlineStreaming(track: MusicTrack): Boolean {
        if (track.path.isNotBlank()) return false
        val scheme = runCatching { Uri.parse(track.audioUri).scheme }.getOrNull()
        return scheme == "http" || scheme == "https"
    }

    // advanceToNext：删除的是当前曲目时，自动递补原列表顺序中的下一首，避免播放器内容空白
    override fun removeTrack(trackId: Long, advanceToNext: Boolean) {
        if (playlist.none { it.id == trackId }) return
        val removedCurrent = currentTrack?.id == trackId
        // 记录删除前是否正在播放，决定递补后是继续播放还是仅切换显示
        val wasPlaying = mediaController?.isPlaying == true || isPlaying
        // 原列表顺序中删除曲目之后的下一首（环形取），列表仅剩自身时无递补
        val nextTrack = if (removedCurrent) {
            val oldIndex = playlist.indexOfFirst { it.id == trackId }
            playlist.getOrNull((oldIndex + 1) % playlist.size)?.takeIf { it.id != trackId }
        } else null
        playlist = playlist.filterNot { it.id == trackId }
        playNextQueue = playNextQueue.filterNot { it.id == trackId }
        if (queueResumeTrackId == trackId) queueResumeTrackId = null
        if (removedCurrent) {
            val nextIndex = nextTrack?.let { playlist.indexOfFirst { t -> t.id == it.id } } ?: -1
            if (advanceToNext && nextIndex >= 0) {
                // 先停止旧播放，避免继续播已被删除的音频源
                mediaController?.stop()
                currentIndex = nextIndex
                currentTrack = playlist[nextIndex]
                isPlaying = false
                isPrepared = false
                currentPosition = 0L
                duration = 0L
                errorMsg = null
                appContext?.let { context ->
                    playbackScope.launch {
                        playTrackAt(context, this@MusicPlaybackState, nextIndex, autoPlay = wasPlaying, clearQueue = false)
                    }
                }
            } else {
                mediaController?.stop()
                currentTrack = null
                currentIndex = -1
                isPlaying = false
                isPrepared = false
                currentPosition = 0L
                duration = 0L
            }
        } else {
            currentIndex = playlist.indexOfFirst { it.id == currentTrack?.id }
        }
        persistPlaylist()
    }

    // 彻底删除歌曲：移除音频源文件与仅该曲引用的歌词/封面缓存，并同步库、播放队列与歌单引用
    override suspend fun deleteSongPermanently(context: Context, track: MusicTrack) {
        // 删除前基于全量库+当前列表计算剩余曲目的缓存引用，作为封面/歌词清除依据：
        // 全量库覆盖本地曲目，当前列表兜底在线曲目（在线曲目只存在于当前列表，不在全量库备份）
        val remaining = (defaultPlaylistBackup.orEmpty() + playlist)
            .filterNot { it.id == track.id }
            .distinctBy { it.id }
        withContext(Dispatchers.IO) {
            deleteAudioSource(context, track)
            // 停留在自定义歌单且全量库备份缺失时，当前列表仅含歌单子集，引用集不全，
            // 跳过清理，交由后续 enrichAndCleanup 以全量库+歌单的并集统一回收，避免误删其他歌曲共享缓存
            val libraryKnown = playlistSource == null || defaultPlaylistBackup != null
            if (libraryKnown) {
                MusicMetadataCache.cleanupOrphanedMetadata(
                    context,
                    remaining.flatMap { listOfNotNull(it.coverCachePath, it.lyricCachePath) }.toSet(),
                )
            }
        }
        defaultPlaylistBackup = defaultPlaylistBackup?.filterNot { it.id == track.id }
        removeTrack(track.id, advanceToNext = true)
        likedIds = likedIds - track.id
        removeFromRecentPlayed(track.id)
        PlaylistStore.ensureLoaded(context)
        PlaylistStore.removeTrackFromAll(context, track.id)
    }

    // 删除音频源文件：先经 MediaStore 删除（同时清理媒体条目），失败则直接删本地路径并通知媒体库同步
    private fun deleteAudioSource(context: Context, track: MusicTrack) {
        val uri = track.audioUri.takeIf { it.isNotBlank() }?.let(Uri::parse)
        // 纯在线流曲目无本地文件，无需文件级删除
        if (uri?.scheme == "http" || uri?.scheme == "https") return
        val deletedViaResolver = uri != null &&
            runCatching { context.contentResolver.delete(uri, null, null) }.getOrDefault(0) > 0
        if (!deletedViaResolver && track.path.isNotBlank()) {
            val path = track.path
            if (runCatching { File(path).delete() }.getOrDefault(false)) {
                // 直删文件后触发媒体扫描，使 MediaStore 中该文件的条目失效，避免歌曲重新出现
                MediaScannerConnection.scanFile(context, arrayOf(path), null, null)
            }
        }
    }

    override var audioSignalPathFormat by mutableStateOf<AudioSignalPathFormat?>(null)
    // 音频信息所属曲目：保证格式信息始终与当前曲目对应，后台切歌后再回前台不会错配
    override var audioSignalPathTrackId by mutableStateOf<Long?>(null)

    // 收藏的歌曲 ID 集合（面板级内存状态）
    override var likedIds by mutableStateOf<Set<Long>>(emptySet())

    // 常听：3 天内完整播放次数不少于 2 次的歌曲，按最近一次播放时间倒序
    private var recentPlayEvents by mutableStateOf<List<PlayEvent>>(emptyList())
    private val recentWindowMs: Long
        get() = RECENT_WINDOW_DAYS * 24L * 60 * 60 * 1000

    // 单曲循环回卷检测状态：追踪上次 tick 的播放位置，位置大幅回退即一次完整播放
    private var lastTickTrackId: Long? = null
    private var lastTickPosition = 0L
    // 最近一次完整播放记录时间与曲目，供回卷检测与过渡回调去重
    private var lastAutoCountAtMs = 0L
    private var lastAutoCountTrackId: Long? = null
    // 进度展示单调性：连续播放期间进度与时长只增不减，规避流媒体位置回锚/时长修正导致进度条倒退；
    // 手动拖动(SEEK)、切歌、单曲循环回卷时复位基准，允许进度回落
    private var lastMonoMediaId: Long? = null
    private var lastMonoPosition = 0L

    override val recentPlayedIds: List<Long>
        get() {
            val cutoff = System.currentTimeMillis() - recentWindowMs
            val window = recentPlayEvents.filter { it.timestamp >= cutoff }
            return window.groupBy { it.trackId }
                .filterValues { it.size >= RECENT_MIN_PLAYS }
                .entries
                .sortedByDescending { it.value.maxOf { e -> e.timestamp } }
                .map { it.key }
        }

    // 当前播放列表来源歌单（null = 默认全量播放列表）
    override var playlistSource by mutableStateOf<PlaylistSource?>(null)
    // 默认全量播放列表备份：首次切到歌单时快照，供快捷切回默认
    override var defaultPlaylistBackup by mutableStateOf<List<MusicTrack>?>(null)
    // 全量库：优先备份，否则为当前播放列表
    override val libraryTracks: List<MusicTrack>
        get() = defaultPlaylistBackup ?: playlist

    // 记录一次完整播放：追加带时间戳的播放记录，并清理超出 3 天窗口的旧记录
    fun recordPlayed(trackId: Long) {
        val now = System.currentTimeMillis()
        recentPlayEvents = listOf(PlayEvent(trackId, now)) +
            recentPlayEvents.filter { it.timestamp >= now - recentWindowMs }
        persistRecentPlayed()
    }

    // 从常听手动移除：清除该曲目的播放记录，期间不再自动收录
    override fun removeFromRecentPlayed(trackId: Long) {
        recentPlayEvents = recentPlayEvents.filterNot { it.trackId == trackId }
        persistRecentPlayed()
    }

    private fun persistRecentPlayed() {
        val context = appContext ?: return
        playbackScope.launch(Dispatchers.IO) {
            RecentPlayedStore.save(context, recentPlayEvents)
        }
    }

    // 下一首播放插队队列：自然播完后依次播放队列曲目，再接续原播放位置
    override var playNextQueue by mutableStateOf<List<MusicTrack>>(emptyList())
    // 建立队列时记录的当前曲目 ID，队列播完后据此接续原播放位置
    private var queueResumeTrackId: Long? by mutableStateOf(null)

    // 曲目是否已在下一首播放队列中
    override fun isInPlayNext(trackId: Long): Boolean = playNextQueue.any { it.id == trackId }

    // 切换下一首播放：已在队列则取消插队，否则加入
    override fun togglePlayNext(track: MusicTrack) {
        if (isInPlayNext(track.id)) {
            playNextQueue = playNextQueue.filterNot { it.id == track.id }
            // 队列清空后无需再接续原播放位置
            if (playNextQueue.isEmpty()) queueResumeTrackId = null
        } else {
            if (playNextQueue.isEmpty() && queueResumeTrackId == null) {
                queueResumeTrackId = currentTrack?.id
            }
            playNextQueue = playNextQueue + track
        }
    }

    // 手动切歌时清空插队队列
    override fun clearPlayNextQueue() {
        playNextQueue = emptyList()
        queueResumeTrackId = null
    }

    // 定时关闭相关状态（后台计时）
    override var timerMinutes by mutableIntStateOf(10)
    override var timerRemaining by mutableIntStateOf(0)
    override var timerAutoStopped by mutableStateOf(false)
    // 定时关闭到点后请求真正退出应用（一次性信号，退出编排由应用外壳执行）
    override var sleepTimerExpired by mutableStateOf(false)
    private val timerJob = SupervisorJob()
    private val timerScope = CoroutineScope(timerJob + Dispatchers.Main)
    private var countdownJob: Job? = null
    private var stopAfterCurrentTrack = false

    // 播放控制协程作用域（用于曲目结束自动下一首）
    private val playbackJob = SupervisorJob()
    val playbackScope = CoroutineScope(playbackJob + Dispatchers.Main)

    // 全局进度刷新协程：播放期间由播放器状态驱动，避免多个 UI 各自轮询重复写状态
    private var positionTickerJob: Job? = null

    // 启动全局进度刷新，重复调用不重复创建
    fun ensurePositionTicker() {
        if (positionTickerJob?.isActive == true) return
        positionTickerJob = playbackScope.launch {
            while (isActive) {
                if (isPlaying) updatePosition()
                delay(200)
            }
        }
    }

    fun stopPositionTicker() {
        positionTickerJob?.cancel()
        positionTickerJob = null
    }

    // 防止手动切歌与自动切歌并发导致状态错乱
    val playTrackMutex = Mutex()

    override val hasTrack: Boolean get() = currentTrack != null

    // ===== PlaybackController 动作实现：委托全局播放辅助（playTrackAt 等）=====
    override fun playTrackAt(index: Int, autoPlay: Boolean, clearQueue: Boolean) {
        val context = appContext ?: return
        // 经应用级作用域派发：面板收起离开组合时播放命令不被协程取消
        playbackScope.launch {
            playTrackAt(context, this@MusicPlaybackState, index, autoPlay, clearQueue)
        }
    }

    override fun togglePlayPause() {
        togglePlayPause(this)
    }

    override fun seekTo(positionMs: Long) {
        seekTo(this, positionMs)
    }

    override suspend fun restoreSavedState(context: Context) {
        appContext = context.applicationContext
        ui.appContext = context.applicationContext
        ui.searchHistory = withContext(Dispatchers.IO) {
            SearchHistoryStore.load(context)
        }
        recentPlayEvents = withContext(Dispatchers.IO) {
            RecentPlayedStore.load(context)
        }
        val snapshot = PlaybackStateStore.load(context)
        val cachedPlaylist = withContext(Dispatchers.IO) {
            // 历史缓存可能残留同文件不同 URI 形态的重复条目，按真实文件路径去重，避免冷启动直接展示重名歌曲
            PlaylistCacheStore.loadPlaylist(context).distinctBy { trackIdentityKey(context, it) }
        }
        // 恢复上次选中的歌单来源与默认库备份，扫描刷新后保持选中
        val savedSource = withContext(Dispatchers.IO) {
            PlaylistCacheStore.loadSource(context)
        }
        val cachedBackup = withContext(Dispatchers.IO) {
            PlaylistCacheStore.loadBackup(context).distinctBy { trackIdentityKey(context, it) }
        }
        withContext(Dispatchers.Main) {
            // 无保存来源时处于全量播放列表
            playlistSource = savedSource
            defaultPlaylistBackup = cachedBackup.takeIf { it.isNotEmpty() }
            // 收藏合并自当前歌单与默认库备份，避免切歌单后库内收藏丢失
            likedIds = (cachedPlaylist + cachedBackup)
                .filter { it.isFavorite }
                .map { it.id }
                .toSet()
            if (playlist.isEmpty() && cachedPlaylist.isNotEmpty()) {
                playlist = cachedPlaylist.map { it.copy(isFavorite = likedIds.contains(it.id)) }
            }
            pendingSavedUri = snapshot.audioUri
            pendingResumePosition = snapshot.position
            if (currentTrack == null) {
                currentPosition = snapshot.position
            }
            playMode = PlayMode.entries.getOrElse(snapshot.mode) { PlayMode.RepeatAll }
            playbackSpeed = snapshot.speed.coerceIn(
                PlaybackController.PLAYBACK_SPEED_MIN,
                PlaybackController.PLAYBACK_SPEED_MAX,
            )
        }
    }

    // 冷启动未播放时预读当前曲目格式信息，供音频信息条展示；开始播放后由解码头覆盖
    override fun refreshIdleTrackFormatInfo(context: Context) {
        val track = currentTrack ?: return
        if (audioSignalPathTrackId == track.id) return
        playbackScope.launch(Dispatchers.IO) {
            val info = TrackAudioInfoReader.readIdleFormat(context, track) ?: return@launch
            if (currentTrack?.id == track.id) {
                audioSignalPathFormat = info
                audioSignalPathTrackId = track.id
            }
        }
    }

    // 缓存完成后以本地缓存文件为源强制补齐当前曲目格式信息，避免在线播放期间信息条空白
    override fun refreshTrackFormatInfoFromLocal(context: Context) {
        val track = currentTrack ?: return
        if (!track.isLocalAudioSource) return
        playbackScope.launch(Dispatchers.IO) {
            val info = TrackAudioInfoReader.readIdleFormat(context, track) ?: return@launch
            if (currentTrack?.id == track.id) {
                audioSignalPathFormat = info
                audioSignalPathTrackId = track.id
            }
        }
    }

    // 回到前台时校正音频信息：与当前曲目错配时清掉旧值并重新读取当前曲目源格式，
    // 保证信息条始终对应当前曲目而不依赖解码回调回填
    override fun reconcileTrackFormatInfo(context: Context) {
        val track = currentTrack ?: return
        if (audioSignalPathTrackId == track.id) return
        audioSignalPathFormat = null
        audioSignalPathTrackId = null
        refreshIdleTrackFormatInfo(context)
    }

    // 持久化播放速度，供重启后恢复
    private fun persistPlaybackSpeed() {
        val context = appContext ?: return
        playbackScope.launch {
            PlaybackStateStore.saveSpeed(context, playbackSpeed)
        }
    }

    override fun persistPlaylist() {
        val context = appContext ?: return
        // 合并连续写入：取消未开始的上一次任务，仅保留最后一次持久化
        playlistPersistJob?.cancel()
        playlistPersistJob = playbackScope.launch {
            withContext(Dispatchers.IO) {
                // 歌单来源与默认库备份随播放列表一同持久化，重启后恢复选中状态
                PlaylistCacheStore.save(context, playlist, playlistSource, defaultPlaylistBackup)
            }
        }
    }

    override fun addSearchHistory(query: String) = ui.addSearchHistory(query)

    override fun removeSearchHistory(query: String) = ui.removeSearchHistory(query)

    override fun clearSearchHistory() = ui.clearSearchHistory()

    var pendingSavedUri: String? = null
    var pendingResumePosition: Long = 0L

    override fun persistState() {
        val context = appContext ?: return
        val track = currentTrack ?: return
        // 调用时刻立即快照：release/softRelease 随后会清空播放状态，异步写入不能再回读内存态
        pendingStateSnapshot = PlaybackSnapshot(track.audioUri, currentPosition, playMode.ordinal, playbackSpeed)
        // 写入在途时仅更新快照，由在途任务以最新快照收尾，不再取消旧任务
        if (stateWriteJob?.isActive == true) return
        stateWriteJob = playbackScope.launch {
            persistenceMutex.withLock {
                while (true) {
                    val snapshot = pendingStateSnapshot ?: break
                    pendingStateSnapshot = null
                    PlaybackStateStore.save(context, snapshot)
                }
            }
        }
    }

    override fun softRelease() {
        stopPositionTicker()
        persistState()
        currentTrack?.let { track ->
            pendingSavedUri = track.audioUri
            pendingResumePosition = currentPosition
        }
        mediaController?.let { controller ->
            controller.pause()
            controller.stop()
            controller.removeListener(controllerListener)
            playbackScope.launch { controller.release() }
        }
        mediaController = null
        isPlaying = false
        // 释放播放器后复位单调基准，避免恢复播放时进度被残留基准钳到高位
        lastMonoMediaId = null
    }

    override fun release() {
        stopPositionTicker()
        persistState()
        currentTrack?.let { track ->
            pendingSavedUri = track.audioUri
            pendingResumePosition = currentPosition
        }
        mediaController?.let { controller ->
            playbackScope.launch {
                controller.stop()
                controller.removeListener(controllerListener)
                controller.release()
            }
        }

        mediaController = null
        currentPosition = 0L
        isPlaying = false
        isPrepared = false
        duration = 0L
        // 释放播放器后复位单调基准，避免恢复播放时进度被残留基准钳到高位
        lastMonoMediaId = null
        errorMsg = null
        stopTimer()
    }

    // 定时关闭到点收尾：停止播放并发出退出应用信号（退出编排由应用外壳执行）
    private fun completeSleepTimer() {
        stopAfterCurrentTrack = false
        timerAutoStopped = true
        sleepTimerExpired = true
        release()
        playbackScope.launch {
            appContext?.let { clearSavedPosition(it) }
        }
    }

    // 启动定时关闭（分钟），计时结束后停止播放并释放资源
    override fun startTimer(minutes: Int) {
        stopTimer()
        timerMinutes = minutes
        timerRemaining = minutes
        countdownJob = timerScope.launch {
            while (timerRemaining > 0) {
                delay(60_000L)
                timerRemaining--
            }
            // 计时结束：当前歌曲播放完成后停止并释放资源
            if (isPlaying) {
                stopAfterCurrentTrack = true
                withContext(Dispatchers.Main) {
                    mediaController?.let { controller ->
                        controller.repeatMode = Player.REPEAT_MODE_OFF
                        controller.shuffleModeEnabled = false
                    }
                }
            } else {
                completeSleepTimer()
            }
        }
    }

    // 取消定时关闭
    override fun stopTimer() {
        stopAfterCurrentTrack = false
        countdownJob?.cancel()
        countdownJob = null
        timerRemaining = 0
    }

    // 将原始曲目列表按默认规则排序（歌手聚合 → 专辑聚合 → 标题），并保留当前曲目索引
    override fun setSortedPlaylist(tracks: List<MusicTrack>) {
        val currentId = currentTrack?.id
        val sorted = tracks
            .map { it.copy(isFavorite = likedIds.contains(it.id)) }
            .sortedByDefaultOrder()
        playlist = sorted
        currentIndex = sorted.indexOfFirst { it.id == currentId }.coerceAtLeast(-1)
    }

    // 切换指定曲目的收藏状态：仅就地更新收藏标记，不改变列表顺序
    override fun toggleFavorite(trackId: Long) {
        val newLiked = if (likedIds.contains(trackId)) likedIds - trackId else likedIds + trackId
        likedIds = newLiked
        playlist = playlist.map { if (it.id == trackId) it.copy(isFavorite = trackId in newLiked) else it }
        persistPlaylist()
    }

    // 按新顺序重排当前播放队列，保持当前曲目与播放索引同步
    override fun reorderPlaylist(ordered: List<MusicTrack>) {
        if (ordered.isEmpty()) return
        val currentId = currentTrack?.id
        val tracks = ordered.map { it.copy(isFavorite = likedIds.contains(it.id)) }
        playlist = tracks
        currentIndex = tracks.indexOfFirst { it.id == currentId }
        persistPlaylist()
    }

    // 切换到指定歌单队列：空队列仅落盘来源；非空时备份默认列表、加载首曲（不自动播放）并后台补全元数据
    override fun switchToPlaylist(tracks: List<MusicTrack>, source: PlaylistSource?) {
        val context = appContext ?: return
        if (tracks.isEmpty()) {
            playlist = tracks
            playlistSource = source
            persistPlaylist()
            return
        }
        // 首次从默认库切到歌单时备份默认列表，供快捷切回
        if (playlistSource == null && defaultPlaylistBackup == null) {
            defaultPlaylistBackup = playlist
        }
        playlist = tracks
        playlistSource = source
        currentIndex = 0
        // 仅加载新队列并暂停，不自动播放；在播放器全局作用域执行，避免弹层关闭取消协程导致队列未加载
        playbackScope.launch {
            playTrackAt(context, this@MusicPlaybackState, 0, autoPlay = false)
        }
        persistPlaylist()
        // 切换歌单后后台补全新歌单缺失的封面/歌词，缓存已就绪的歌曲直接命中不重复加载
        playbackScope.launch {
            MetadataEnricher.enrichAndCleanup(context, this@MusicPlaybackState)
        }
    }

    // 更新播放列表中指定曲目的元数据并持久化（列表与当前曲目同步替换）
    override fun updateTrack(updated: MusicTrack) {
        playlist = playlist.map { if (it.id == updated.id) updated.copy(isFavorite = likedIds.contains(it.id)) else it }
        currentTrack = currentTrack?.let { if (it.id == updated.id) updated else it }
        persistPlaylist()
    }

    // 封面写入成功后自增，通知封面组件强制重载最新图
    override fun bumpCoverRevision() {
        ui.coverRevision++
    }

    // 批量更新曲目元数据（封面等），一次触发重组 + 一次持久化；
    // 同时回写全量库备份，保证切歌单后其他歌单的歌曲引用到最新封面
    override fun batchUpdateTracks(updates: List<MusicTrack>) {
        val updateMap = updates.associateBy { it.id }
        val applyUpdates: (List<MusicTrack>) -> List<MusicTrack> = { list ->
            list.map { orig ->
                updateMap[orig.id]?.let { it.copy(isFavorite = likedIds.contains(it.id)) } ?: orig
            }
        }
        playlist = applyUpdates(playlist)
        currentTrack = currentTrack?.let { updateMap[it.id] ?: it }
        defaultPlaylistBackup = defaultPlaylistBackup?.let(applyUpdates)
        persistPlaylist()
    }

    // 按需补全单曲封面/歌词（懒加载）：幂等，由 UI 可见项触发，
    // 已具备缓存、已标记失败或在全量补全排期中的曲目自动跳过
    override fun requestMetadata(track: MusicTrack?) {
        if (track == null) return
        val context = appContext ?: return
        playbackScope.launch {
            MetadataEnricher.ensureMetadata(context, this@MusicPlaybackState, track)
        }
    }

    override fun renameTrackMetadata(renamed: MusicTrack) {
        updateTrack(renamed)
    }

    // 微调歌词时间：stepMs 正值延后，负值提前（同时作用于逐字时间轴）
    override fun adjustLyricsOffset(stepMs: Long) {
        val track = currentTrack ?: return
        if (track.lyricLines.isEmpty()) return
        val shifted = MusicMetadataCache.shiftLyrics(track.lyricLines, stepMs)
        updateTrack(track.copy(lyricLines = shifted, lyricOffsetMs = track.lyricOffsetMs + stepMs))
    }

    fun syncPlaybackState() {
        val controller = mediaController ?: return
        val playbackState = controller.playbackState
        val isActive = playbackState == Player.STATE_READY || playbackState == Player.STATE_BUFFERING
        val mediaId = controller.currentMediaItem?.mediaId?.toLongOrNull()
        val index = mediaId?.let { id -> playlist.indexOfFirst { it.id == id } } ?: -1
        if (index >= 0) {
            currentIndex = index
            currentTrack = playlist[index]
        }
        syncPlaybackPosition(controller, isActive)
    }

    fun updatePosition() {
        val controller = mediaController ?: return
        val playbackState = controller.playbackState
        val isActive = playbackState == Player.STATE_READY || playbackState == Player.STATE_BUFFERING
        syncPlaybackPosition(controller, isActive)
        // 播放期间周期性持久化当前曲目与进度，避免冷启动/异常退出后丢失播放状态
        if (isActive && controller.isPlaying) {
            val now = System.currentTimeMillis()
            if (now - lastStatePersistAt >= STATE_PERSIST_INTERVAL_MS) {
                lastStatePersistAt = now
                persistState()
            }
            // 单曲循环播完回卷时计入一次完整播放（不受 onMediaItemTransition 触发与否影响）
            detectLoopRestart()
        }
    }

    // 单曲循环回卷检测：当前曲目播放位置从越过中部瞬间回退到开头即一次完整播放。
    // Media3 部分配置下 REPEAT_MODE_ONE 不投递 REPEAT 过渡回调，此处作为兜底收录，
    // 与过渡回调记录通过冷却去重，避免同一次循环计数两次
    private fun detectLoopRestart() {
        val track = currentTrack ?: return
        if (duration <= 0L) return
        // 以控制器原始位置检测回卷，避免被进度单调钳制掩盖导致单曲循环漏记
        val cur = mediaController?.currentPosition
            ?.coerceIn(0L, duration) ?: return
        val trackChanged = lastTickTrackId != track.id
        lastTickTrackId = track.id
        // 切歌后的首个 tick 仅建立基准，不判定
        if (trackChanged || lastTickPosition < 0L) {
            lastTickPosition = cur
            return
        }
        val prev = lastTickPosition
        lastTickPosition = cur
        if (prev - cur < LOOP_RESTART_MIN_JUMP_MS || prev <= duration / 2) return
        val now = System.currentTimeMillis()
        val deDuplicated = lastAutoCountTrackId == track.id &&
            now - lastAutoCountAtMs < AUTO_COUNT_COOLDOWN_MS
        if (deDuplicated) return
        lastAutoCountTrackId = track.id
        lastAutoCountAtMs = now
        // 回卷复位进度单调基准，让进度条回到起点
        lastMonoMediaId = null
        recordPlayed(track.id)
    }

    private fun syncPlaybackPosition(controller: MediaController, isActive: Boolean) {
        if (isActive) {
            val mediaId = controller.currentMediaItem?.mediaId?.toLongOrNull()
            val controllerDuration = controller.duration
            val controllerPosition = controller.currentPosition
            if (controllerPosition >= 0L && controllerDuration > 0L) {
                val raw = controllerPosition.coerceIn(0L, controllerDuration)
                // 换项(mediaId 变化)或位置大幅回退（如切换歌单重载同 ID 曲目未触发切歌回调）时复位单调基准；
                // 小幅回退仍按流媒体回锚处理，保持进度单调，避免进度条倒退
                val reset = mediaId != lastMonoMediaId ||
                    raw < lastMonoPosition - MONO_REBASELINE_JUMP_MS
                if (reset) {
                    lastMonoMediaId = mediaId
                    lastMonoPosition = raw
                    duration = controllerDuration
                    currentPosition = raw
                } else {
                    duration = maxOf(duration, controllerDuration)
                    currentPosition = maxOf(lastMonoPosition, raw).also { lastMonoPosition = it }
                }
            }
        }
        isPlaying = controller.isPlaying
    }

    private fun calculateIndex(direction: Int, repeatOne: Boolean, from: Int = currentIndex): Int {
        if (playlist.isEmpty()) return -1
        val validCurrentIndex = from.takeIf { it in playlist.indices } ?: 0
        return when {
            playMode == PlayMode.RepeatOne && repeatOne -> validCurrentIndex
            playMode == PlayMode.Shuffle -> {
                if (playlist.size == 1) 0
                else playlist.indices.filter { it != validCurrentIndex }.random()
            }
            direction < 0 -> (validCurrentIndex - 1 + playlist.size) % playlist.size
            else -> (validCurrentIndex + 1) % playlist.size
        }
    }

    private fun autoNextIndex(): Int = calculateIndex(direction = 1, repeatOne = true)

    // 下一首索引
    override fun nextIndex(): Int = calculateIndex(direction = 1, repeatOne = false)

    // 上一首索引
    override fun previousIndex(): Int = calculateIndex(direction = -1, repeatOne = false)

    // ===== UI 层状态写入口：悬浮窗 UI 统一通过这些方法写入状态，避免直接对 public var 赋值 =====
    override fun updatePlayMode(mode: PlayMode) {
        playMode = mode
        // 同步应用到播放器：播放模式修改即时生效，无需 UI 再经控制器下发
        mediaController?.let { applyPlaybackMode(it, mode) }
        // 播放模式持久化在状态内部收敛，UI 不直接驱动落盘
        persistState()
    }
    override fun updatePlaybackSpeed(speed: Float) {
        playbackSpeed = speed.coerceIn(
            PlaybackController.PLAYBACK_SPEED_MIN,
            PlaybackController.PLAYBACK_SPEED_MAX,
        )
        mediaController?.setPlaybackSpeed(playbackSpeed)
        persistPlaybackSpeed()
    }
    override fun updateLyricsVisible(visible: Boolean) { isLyricsVisible = visible }
    override fun updateErrorMsg(message: String?) { errorMsg = message }
    override fun updateTimerMinutes(minutes: Int) { timerMinutes = minutes }
    override fun updateTimerAutoStopped(stopped: Boolean) { timerAutoStopped = stopped }
    override fun updateSleepTimerExpired(expired: Boolean) { sleepTimerExpired = expired }
    override fun updateCurrentPosition(position: Long) {
        // 拖动进度条直接改写位置：复位单调基准，避免被钳回拖动前的位置
        lastMonoMediaId = null
        currentPosition = position
    }
}

// 歌手分隔符：顿号、中英文逗号/分号、斜杠、反斜杠、与号
private val ARTIST_SEPARATOR = Regex("""[、,，;；/\\&]""")

// 解析歌曲关联的全部歌手：按分隔符拆分并清理空白，无有效项时退回整串
private fun parseTrackArtists(artist: String): List<String> =
    artist.split(ARTIST_SEPARATOR)
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .ifEmpty { listOf(artist.trim()) }

// 按默认规则排序：优先按歌手聚合、其次按专辑聚合、专辑内再按标题排序；
// 多歌手歌曲归属到当前列表中歌曲数量最多的歌手，数量相同时取解析顺序靠前的歌手
private fun List<MusicTrack>.sortedByDefaultOrder(): List<MusicTrack> {
    // 统计各歌手参与当前列表的歌曲数量（多歌手歌曲计入每个关联歌手）
    val artistSongCounts = hashMapOf<String, Int>()
    forEach { track ->
        parseTrackArtists(track.artist).forEach { artist ->
            artistSongCounts[artist] = (artistSongCounts[artist] ?: 0) + 1
        }
    }
    // 确定每首歌的归属歌手，用于聚合分组
    val ownerByTrackId = hashMapOf<Long, String>()
    forEach { track ->
        val artists = parseTrackArtists(track.artist)
        var owner = artists.firstOrNull().orEmpty()
        if (artists.size > 1) {
            var ownerCount = artistSongCounts[owner] ?: 0
            for (candidate in artists.drop(1)) {
                val candidateCount = artistSongCounts[candidate] ?: 0
                if (candidateCount > ownerCount) {
                    owner = candidate
                    ownerCount = candidateCount
                }
            }
        }
        ownerByTrackId[track.id] = owner
    }
    return sortedWith(
        compareBy<MusicTrack> { ownerByTrackId[it.id] ?: "" }
            .thenBy { it.albumName }
            .thenBy { it.albumId }
            .thenBy { it.title }
    )
}
