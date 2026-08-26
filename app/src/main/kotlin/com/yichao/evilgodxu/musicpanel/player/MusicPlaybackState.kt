package com.yichao.evilgodxu.musicpanel

import com.yichao.evilgodxu.data.settings.settingsDataStore
import androidx.compose.runtime.getValue
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import androidx.compose.runtime.setValue
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.log.CrashLogManager
import org.json.JSONArray
import org.json.JSONObject
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import kotlin.jvm.JvmName

data class AudioSignalPathFormat(
    val format: String,
    val sampleRate: Int,
    val outputRate: Int,
    val bitDepth: Int,
    val channels: Int,
)

// 播放列表来源歌单：key 标识来源，name 为副标题显示名
data class PlaylistSource(
    val key: String,
    val name: String,
)

// 单次播放记录：曲目 ID + 播放时间戳（毫秒）
data class PlayEvent(
    val trackId: Long,
    val timestamp: Long,
)

// 音乐播放器状态持有者（悬浮窗级共享状态）
class MusicPlaybackState {

    // 常听收录窗口：仅统计 3 天内播放次数超过 3 次的歌曲
    private companion object {
        const val RECENT_WINDOW_DAYS = 3
        const val RECENT_MIN_PLAYS = 3
        // 播放期间周期性持久化间隔：保证冷启动/异常退出也能恢复当前曲目与进度
        const val STATE_PERSIST_INTERVAL_MS = 3000L
    }

    // 上次持久化播放状态的时刻，用于播放期间节流写入
    private var lastStatePersistAt = 0L

    private val savedUriKey = stringPreferencesKey("music_saved_uri")
    private val savedPositionKey = longPreferencesKey("music_saved_position")
    private val savedModeKey = intPreferencesKey("music_saved_mode")
    private val playlistCacheKey = "music_playlist_cache"
    private val playlistCachePreferences = "music_playlist_cache_preferences"
    // 当前歌单来源与默认库备份持久化键，重启后恢复选中状态
    private val playlistSourceKeyPref = "music_playlist_source_key"
    private val playlistSourceNamePref = "music_playlist_source_name"
    private val defaultPlaylistCacheKeyPref = "music_default_playlist_cache"
    private val searchHistoryKey = "music_search_history"
    private val searchHistoryPreferences = "music_search_history_preferences"
    private var persistenceJob: Job? = null
    private var playlistPersistJob: Job? = null
    private val persistenceMutex = Mutex()
    var appContext: Context? = null
    var mediaController: MediaController? by mutableStateOf(null)
    var player: Player? by mutableStateOf(null)
    private var suppressAutoNext = false
    val controllerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            syncPlaybackState()
        }

        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
            if (stopAfterCurrentTrack && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                // 定时关闭：当前曲目自然结束 → 停止播放
                stopAfterCurrentTrack = false
                timerAutoStopped = true
                release()
                playbackScope.launch {
                    appContext?.let { clearSavedPosition(it) }
                }
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
                // 切换曲目即持久化最新 URI，确保后台自动下一首也能被冷启动恢复
                persistState()
            }
            // 切换曲目后清理未在播放的在线歌曲，避免在线播放曲目在播放列表中常驻；
            // 元数据补全只在缓存完成时执行一次，切歌不再触发，避免重复写封面并触发系统级文件扫描
            cleanupIdleOnlineTracks()
            // 再次从控制器校正当前曲目，确保 UI 与真实音频一致（在线曲目切换时尤其关键）
            syncPlaybackState()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val controller = mediaController ?: return
            when (playbackState) {
                Player.STATE_READY -> {
                    isPrepared = true
                    ensurePositionTicker()
                    if (closeSearchResultsOnReady) {
                        closeSearchResultsOnReady = false
                        isSearchMode = false
                        showSearchResults = false
                        searchQuery = ""
                        searchResults = emptyList()
                        pendingSearchResults = emptyList()
                    }
                    // 音质试播就绪即播放成功：关闭音质对话框并清除待确认标记
                    if (pendingQualityPlayTrackId != null) {
                        pendingQualityPlayTrackId = null
                        qualityBusy = false
                        qualityPickTrack = null
                        qualityError = null
                    }
                    syncPlaybackState()
                }
                Player.STATE_ENDED -> {
                    isPlaying = false
                    currentPosition = duration
                    if (suppressAutoNext) {
                        suppressAutoNext = false
                        return
                    }
                    if (stopAfterCurrentTrack) {
                        stopAfterCurrentTrack = false
                        timerAutoStopped = true
                        release()
                        playbackScope.launch {
                            appContext?.let { clearSavedPosition(it) }
                        }
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
            val pendingId = pendingQualityPlayTrackId
            if (pendingId != null && qualityPickTrack != null) {
                pendingQualityPlayTrackId = null
                qualityBusy = false
                qualityError = appContext?.getString(R.string.music_panel_quality_failed)
                removeTrack(pendingId)
            }
            // errorMsg 为可空类型，appContext 为空时置 null（播放不会发生，正常显示无错误）
            errorMsg = appContext?.getString(R.string.music_panel_play_failed)
            isPlaying = false
            isPrepared = false
            stopPositionTicker()
            closeSearchResultsOnReady = false
            pendingSearchResults = emptyList()
            suppressAutoNext = true
            mediaController?.stop()
        }
    }
    var isPlaying by mutableStateOf(false)
    var isPrepared by mutableStateOf(false)
    val isPlayerActive: Boolean
        get() = mediaController?.let { ctrl ->
            ctrl.isPlaying || ctrl.playbackState == Player.STATE_BUFFERING
        } ?: false
    var duration by mutableLongStateOf(0L)
    var currentPosition by mutableLongStateOf(0L)
    private val _playlist = mutableStateOf<List<MusicTrack>>(emptyList())
    var playlist: List<MusicTrack>
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
    var currentIndex by mutableIntStateOf(-1)
    var currentTrack by mutableStateOf<MusicTrack?>(null)
    var playMode by mutableStateOf(PlayMode.RepeatAll)
    var errorMsg by mutableStateOf<String?>(null)
    var isScanning by mutableStateOf(false)
    var isLyricsVisible by mutableStateOf(false)

    // 在线搜索相关状态
    var isSearchMode by mutableStateOf(false)
    var searchQuery by mutableStateOf("")
    var searchResults by mutableStateOf<List<NeteaseSongSearchResult>>(emptyList())
    var searchHistory by mutableStateOf<List<String>>(emptyList())
    var isSearching by mutableStateOf(false)
    // 当前搜索协程句柄：新搜索发起时取消上一次，避免过期响应覆盖新查询结果
    var searchJob: Job? = null
    var showSearchResults by mutableStateOf(false)
    var pendingSearchResults by mutableStateOf<List<NeteaseSongSearchResult>>(emptyList())
    var closeSearchResultsOnReady by mutableStateOf(false)
    // 首页音质选择对话框：非空时显示，目标为待播在线歌曲
    var qualityPickTrack by mutableStateOf<NeteaseSongSearchResult?>(null)
    // 音质尝试中：解析地址与等待播放结果期间置 true，阻止重复点击/误关对话框
    var qualityBusy by mutableStateOf(false)
    // 最近一次音质尝试失败提示（失败时保留对话框展示，供用户换其它音质）
    var qualityError by mutableStateOf<String?>(null)
    // 音质试播曲目 ID：播放就绪(READY)后清空；播放失败时据此移除试播曲目并保留对话框
    var pendingQualityPlayTrackId by mutableStateOf<Long?>(null)
    var coverCandidates by mutableStateOf<List<NeteaseSongSearchResult>>(emptyList())
    var isCoverSearching by mutableStateOf(false)
    var localCoverCandidates by mutableStateOf<List<RecentCover>>(emptyList())
    // 封面写入版本号：每次成功写入新封面自增，驱动封面组件重新加载最新图
    var coverRevision by mutableIntStateOf(0)
    var lyricsCandidates by mutableStateOf<List<NeteaseSongSearchResult>>(emptyList())
    var isLyricsSearching by mutableStateOf(false)
    var isLyricsRefreshing by mutableStateOf(false)
    var lyricsRefreshError by mutableStateOf<String?>(null)

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

    suspend fun removeUnavailableExternalTracks(context: Context) {
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
        withContext(Dispatchers.IO) {
            context.settingsDataStore.edit { preferences ->
                preferences.remove(savedUriKey)
                preferences.remove(savedPositionKey)
            }
        }
    }

    // 仅清除持久化的播放位置（定时关闭时使用，保留歌曲 URI）
    private suspend fun clearSavedPosition(context: Context) {
        withContext(Dispatchers.IO) {
            context.settingsDataStore.edit { preferences ->
                preferences.remove(savedPositionKey)
            }
        }
    }

    // 缓存下载进行中的曲目 ID 集合：切歌清理时保留这些曲目，等待缓存完成后重定向至本地文件
    val cacheInProgressIds: MutableSet<Long> =
        java.util.Collections.synchronizedSet(mutableSetOf())

    // 自动清理未在播放的纯在线流曲目；已缓存为本地文件或缓存进行中的曲目保留，保证离线播放不中断
    fun cleanupIdleOnlineTracks() {
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

    fun removeTrack(trackId: Long) {
        if (playlist.none { it.id == trackId }) return
        playlist = playlist.filterNot { it.id == trackId }
        playNextQueue = playNextQueue.filterNot { it.id == trackId }
        if (queueResumeTrackId == trackId) queueResumeTrackId = null
        if (currentTrack?.id == trackId) {
            mediaController?.stop()
            currentTrack = null
            currentIndex = -1
            isPlaying = false
            isPrepared = false
            currentPosition = 0L
            duration = 0L
        } else {
            currentIndex = playlist.indexOfFirst { it.id == currentTrack?.id }
        }
        persistPlaylist()
    }

    // USB 独占模式相关状态
    var isUsbDeviceConnected by mutableStateOf(false)
    var isUsbExclusiveMode by mutableStateOf(false)
    var usbExclusiveEnabled by mutableStateOf(true)   // 用户偏好：是否启用 USB 独占（默认开启）
    var usbDeviceName by mutableStateOf("")
    var usbError by mutableStateOf<String?>(null)     // USB 错误信息（显示在面板底部）
    var audioSignalPathFormat by mutableStateOf<AudioSignalPathFormat?>(null)
    var audioSignalPathStrategy by mutableStateOf("Mixer")
    var audioSignalPathOutputDevice by mutableStateOf("-")
    var audioSignalPathRoute by mutableStateOf("-")
    var audioSignalPathDsdMode by mutableStateOf("PCM")

    // 蓝牙耳机相关状态
    var isBluetoothHeadsetConnected by mutableStateOf(false)
    var bluetoothHeadsetName by mutableStateOf("")
    // 单次播放会话内仅初始化一次蓝牙音量
    var bluetoothVolumeInitialized = false

    // 收藏的歌曲 ID 集合（面板级内存状态）
    var likedIds by mutableStateOf<Set<Long>>(emptySet())

    // 常听：3 天内播放次数超过 3 次的歌曲，按最近一次播放时间倒序
    private var recentPlayEvents by mutableStateOf<List<PlayEvent>>(emptyList())
    private val recentPlayedPreferences = "music_recent_played_preferences"
    private val recentPlayedKey = "music_recent_played_events"
    private val recentWindowMs: Long
        get() = RECENT_WINDOW_DAYS * 24L * 60 * 60 * 1000

    val recentPlayedIds: List<Long>
        get() {
            val cutoff = System.currentTimeMillis() - recentWindowMs
            val window = recentPlayEvents.filter { it.timestamp >= cutoff }
            return window.groupBy { it.trackId }
                .filterValues { it.size > RECENT_MIN_PLAYS }
                .entries
                .sortedByDescending { it.value.maxOf { e -> e.timestamp } }
                .map { it.key }
        }

    // 当前播放列表来源歌单（null = 默认全量播放列表）
    var playlistSource by mutableStateOf<PlaylistSource?>(null)
    // 默认全量播放列表备份：首次切到歌单时快照，供快捷切回默认
    var defaultPlaylistBackup by mutableStateOf<List<MusicTrack>?>(null)
    // 全量库：优先备份，否则为当前播放列表
    val libraryTracks: List<MusicTrack>
        get() = defaultPlaylistBackup ?: playlist

    // 记录一次播放：追加带时间戳的播放记录，并清理超出 3 天窗口的旧记录
    fun recordPlayed(trackId: Long) {
        val now = System.currentTimeMillis()
        recentPlayEvents = listOf(PlayEvent(trackId, now)) +
            recentPlayEvents.filter { it.timestamp >= now - recentWindowMs }
        persistRecentPlayed()
    }

    private fun persistRecentPlayed() {
        val context = appContext ?: return
        playbackScope.launch(Dispatchers.IO) {
            context.getSharedPreferences(recentPlayedPreferences, Context.MODE_PRIVATE)
                .edit()
                .putString(
                    recentPlayedKey,
                    recentPlayEvents.joinToString(",") { "${it.trackId}:${it.timestamp}" },
                ).apply()
        }
    }

    // 下一首播放插队队列：自然播完后依次播放队列曲目，再接续原播放位置
    var playNextQueue by mutableStateOf<List<MusicTrack>>(emptyList())
    // 建立队列时记录的当前曲目 ID，队列播完后据此接续原播放位置
    private var queueResumeTrackId: Long? by mutableStateOf(null)

    // 曲目是否已在下一首播放队列中
    fun isInPlayNext(trackId: Long): Boolean = playNextQueue.any { it.id == trackId }

    // 切换下一首播放：已在队列则取消插队，否则加入
    fun togglePlayNext(track: MusicTrack) {
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
    fun clearPlayNextQueue() {
        playNextQueue = emptyList()
        queueResumeTrackId = null
    }

    // 定时关闭相关状态（后台计时）
    var timerMinutes by mutableIntStateOf(10)
    var timerRemaining by mutableIntStateOf(0)
    var timerAutoStopped by mutableStateOf(false)
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

    val hasTrack: Boolean get() = currentTrack != null

    suspend fun restoreSavedState(context: Context) {
        appContext = context.applicationContext
        searchHistory = withContext(Dispatchers.IO) {
            context.getSharedPreferences(searchHistoryPreferences, Context.MODE_PRIVATE)
                .getString(searchHistoryKey, "")
                ?.split("\n")
                ?.filter(String::isNotBlank)
                .orEmpty()
        }
        recentPlayEvents = withContext(Dispatchers.IO) {
            context.getSharedPreferences(recentPlayedPreferences, Context.MODE_PRIVATE)
                .getString(recentPlayedKey, "")
                ?.split(",")
                ?.mapNotNull { token ->
                    val idx = token.lastIndexOf(':')
                    if (idx <= 0) return@mapNotNull null
                    val id = token.substring(0, idx).toLongOrNull() ?: return@mapNotNull null
                    val ts = token.substring(idx + 1).toLongOrNull() ?: return@mapNotNull null
                    PlayEvent(id, ts)
                }
                .orEmpty()
        }
        val preferences = withContext(Dispatchers.IO) {
            context.settingsDataStore.data.first()
        }
        val cachedPlaylist = withContext(Dispatchers.IO) {
            loadCachedPlaylist(context, playlistCacheKey)
        }
        // 恢复上次选中的歌单来源与默认库备份，扫描刷新后保持选中
        val savedSource = withContext(Dispatchers.IO) {
            val prefs = context.getSharedPreferences(playlistCachePreferences, Context.MODE_PRIVATE)
            prefs.getString(playlistSourceKeyPref, null)?.let { key ->
                PlaylistSource(key, prefs.getString(playlistSourceNamePref, "") ?: "")
            }
        }
        val cachedBackup = withContext(Dispatchers.IO) {
            loadCachedPlaylist(context, defaultPlaylistCacheKeyPref)
        }
        val savedUri = preferences[savedUriKey]
        val savedPosition = preferences[savedPositionKey] ?: 0L
        val savedMode = preferences[savedModeKey] ?: PlayMode.RepeatAll.ordinal
        withContext(Dispatchers.Main) {
            // 恢复上次选中的歌单来源与默认库备份；无来源时处于全量播放列表
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
            pendingSavedUri = savedUri
            pendingResumePosition = savedPosition
            if (currentTrack == null) {
                currentPosition = savedPosition
            }
            playMode = PlayMode.entries.getOrElse(savedMode) { PlayMode.RepeatAll }
        }
    }

    fun persistPlaylist() {
        val context = appContext ?: return
        // 合并连续写入：取消未开始的上一次任务，仅保留最后一次持久化
        playlistPersistJob?.cancel()
        playlistPersistJob = playbackScope.launch {
            withContext(Dispatchers.IO) {
                saveCachedPlaylist(context, playlistCacheKey, playlist)
                // 歌单来源与默认库备份随播放列表一同持久化，重启后恢复选中状态
                val prefs = context.getSharedPreferences(playlistCachePreferences, Context.MODE_PRIVATE)
                val source = playlistSource
                prefs.edit()
                    .putString(playlistSourceKeyPref, source?.key)
                    .putString(playlistSourceNamePref, source?.name)
                    .apply()
                val backup = defaultPlaylistBackup
                if (backup != null) {
                    saveCachedPlaylist(context, defaultPlaylistCacheKeyPref, backup)
                } else {
                    prefs.edit().remove(defaultPlaylistCacheKeyPref).apply()
                }
            }
        }
    }

    fun addSearchHistory(query: String) {
        val normalized = query.trim()
        if (normalized.isBlank()) return
        searchHistory = listOf(normalized) + searchHistory.filterNot { it == normalized }
        searchHistory = searchHistory.take(10)
        persistSearchHistory()
    }

    fun removeSearchHistory(query: String) {
        searchHistory = searchHistory.filterNot { it == query }
        persistSearchHistory()
    }

    fun clearSearchHistory() {
        searchHistory = emptyList()
        persistSearchHistory()
    }

    private fun persistSearchHistory() {
        val context = appContext ?: return
        playbackScope.launch(Dispatchers.IO) {
            context.getSharedPreferences(searchHistoryPreferences, Context.MODE_PRIVATE)
                .edit()
                .putString(searchHistoryKey, searchHistory.joinToString("\n"))
                .apply()
        }
    }

    private fun loadCachedPlaylist(context: Context, cacheKey: String): List<MusicTrack> {
        val json = context.getSharedPreferences(playlistCachePreferences, Context.MODE_PRIVATE)
            .getString(cacheKey, null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            List(array.length()) { index ->
                val item = array.getJSONObject(index)
                val savedLyricPath = item.optString("lyricCachePath", "")
                val lyricOffset = item.optLong("lyricOffsetMs", 0L)
                val lyricLines = if (MusicMetadataCache.isValid(savedLyricPath)) {
                    val rawLines = MusicMetadataCache.loadLyrics(savedLyricPath)
                    if (lyricOffset != 0L) shiftLyrics(rawLines, lyricOffset) else rawLines
                } else {
                    emptyList()
                }
                MusicTrack(
                    id = item.getLong("id"),
                    path = item.getString("path"),
                    audioUri = item.getString("audioUri"),
                    title = item.getString("title"),
                    artist = item.getString("artist"),
                    duration = item.getLong("duration"),
                    albumId = item.getLong("albumId"),
                    albumName = item.optString("albumName", ""),
                    neteaseId = item.optLong("neteaseId", 0L),
                    neteaseCoverUrl = item.optString("neteaseCoverUrl", ""),
                    coverCachePath = item.optString("coverCachePath", ""),
                    isFavorite = item.optBoolean("isFavorite", false),
                    isOnlinePlay = item.optBoolean("isOnlinePlay", false),
                    lyricCachePath = savedLyricPath.takeIf { lyricLines.isNotEmpty() }.orEmpty(),
                    lyricLines = lyricLines,
                    lyricOffsetMs = lyricOffset,
                    coverFailed = item.optBoolean("coverFailed", false),
                    lyricFailed = item.optBoolean("lyricFailed", false),
                )
            }
        } catch (e: Exception) {
            CrashLogManager.logException("MusicPlaybackState", "读取缓存的播放列表失败", e)
            emptyList()
        }
    }

    private fun saveCachedPlaylist(context: Context, cacheKey: String, tracks: List<MusicTrack>) {
        val array = JSONArray()
        tracks.forEach { track ->
            array.put(JSONObject().apply {
                put("id", track.id)
                put("path", track.path)
                put("audioUri", track.audioUri)
                put("title", track.title)
                put("artist", track.artist)
                put("duration", track.duration)
                put("albumId", track.albumId)
                put("albumName", track.albumName)
                put("neteaseId", track.neteaseId)
                put("neteaseCoverUrl", track.neteaseCoverUrl)
                put("coverCachePath", track.coverCachePath)
                put("lyricCachePath", track.lyricCachePath)
                put("isOnlinePlay", track.isOnlinePlay)
                put("isFavorite", track.isFavorite)
                put("lyricOffsetMs", track.lyricOffsetMs)
                put("coverFailed", track.coverFailed)
                put("lyricFailed", track.lyricFailed)
            })
        }
        context.getSharedPreferences(playlistCachePreferences, Context.MODE_PRIVATE)
            .edit()
            .putString(cacheKey, array.toString())
            .apply()
    }

    var pendingSavedUri: String? = null
    var pendingResumePosition: Long = 0L

    fun persistState() {
        val context = appContext ?: return
        val track = currentTrack ?: return
        val position = currentPosition
        val mode = playMode.ordinal
        persistenceJob?.cancel()
        persistenceJob = playbackScope.launch {
            persistenceMutex.withLock {
                withContext(Dispatchers.IO) {
                    context.settingsDataStore.edit { preferences ->
                        preferences[savedUriKey] = track.audioUri
                        preferences[savedPositionKey] = position
                        preferences[savedModeKey] = mode
                    }
                }
            }
        }
    }

    fun softRelease() {
        bluetoothVolumeInitialized = false
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
        player = null
        isPlaying = false
    }


    fun release() {
        bluetoothVolumeInitialized = false
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
        player = null
        currentPosition = 0L
        isPlaying = false
        isPrepared = false
        duration = 0L
        errorMsg = null
        stopTimer()
    }

    // 启动定时关闭（分钟），计时结束后停止播放并释放资源
    fun startTimer(minutes: Int) {
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
                release()
                playbackScope.launch {
                    appContext?.let { clearSavedPosition(it) }
                }
            }
        }
    }

    // 取消定时关闭
    fun stopTimer() {
        stopAfterCurrentTrack = false
        countdownJob?.cancel()
        countdownJob = null
        timerRemaining = 0
    }

    // 将原始曲目列表按收藏优先排序，并保留当前曲目索引
    fun setSortedPlaylist(tracks: List<MusicTrack>) {
        val currentId = currentTrack?.id
        val sorted = tracks
            .map { it.copy(isFavorite = likedIds.contains(it.id)) }
            .sortedWith(compareByDescending<MusicTrack> { it.isFavorite }.thenBy { it.title })
        playlist = sorted
        currentIndex = sorted.indexOfFirst { it.id == currentId }.coerceAtLeast(-1)
    }

    // 切换指定曲目的收藏状态并重排列表
    fun toggleFavorite(trackId: Long) {
        val newLiked = if (likedIds.contains(trackId)) likedIds - trackId else likedIds + trackId
        likedIds = newLiked
        // 仅重写目标曲目的收藏状态并重排，避免对无关元素重复 copy
        val currentId = currentTrack?.id
        val sorted = playlist
            .map { if (it.id == trackId) it.copy(isFavorite = trackId in newLiked) else it }
            .sortedWith(compareByDescending<MusicTrack> { it.isFavorite }.thenBy { it.title })
        playlist = sorted
        currentIndex = sorted.indexOfFirst { it.id == currentId }.coerceAtLeast(-1)
        persistPlaylist()
    }

    // 按新顺序重排当前播放队列，保持当前曲目与播放索引同步
    fun reorderPlaylist(ordered: List<MusicTrack>) {
        if (ordered.isEmpty()) return
        val currentId = currentTrack?.id
        val tracks = ordered.map { it.copy(isFavorite = likedIds.contains(it.id)) }
        playlist = tracks
        currentIndex = tracks.indexOfFirst { it.id == currentId }
        persistPlaylist()
    }

    // 更新当前播放位置（用于 UI 进度条）
    fun updateTrack(updated: MusicTrack) {
        playlist = playlist.map { if (it.id == updated.id) updated.copy(isFavorite = likedIds.contains(it.id)) else it }
        currentTrack = currentTrack?.let { if (it.id == updated.id) updated else it }
        persistPlaylist()
    }

    // 封面写入成功后自增，通知封面组件强制重载最新封面
    fun bumpCoverRevision() {
        coverRevision++
    }

    // 批量更新曲目元数据（封面等），一次触发重组 + 一次持久化；
    // 同时回写全量库备份，保证切歌单后其他歌单的歌曲引用到最新封面
    fun batchUpdateTracks(updates: List<MusicTrack>) {
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

    fun renameTrackMetadata(renamed: MusicTrack) {
        updateTrack(renamed)
    }

    // 微调歌词时间：stepMs 正值延后，负值提前（同时作用于逐字时间轴）
    fun adjustLyricsOffset(stepMs: Long) {
        val track = currentTrack ?: return
        if (track.lyricLines.isEmpty()) return
        val shifted = shiftLyrics(track.lyricLines, stepMs)
        updateTrack(track.copy(lyricLines = shifted, lyricOffsetMs = track.lyricOffsetMs + stepMs))
    }

    // 整体平移歌词时间轴
    private fun shiftLyrics(lines: List<LyricLine>, deltaMs: Long): List<LyricLine> =
        lines.map { line ->
            line.copy(
                timeMs = (line.timeMs + deltaMs).coerceAtLeast(0),
                words = line.words.map { word -> word.copy(startMs = (word.startMs + deltaMs).coerceAtLeast(0)) },
            )
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
        }
    }

    private fun syncPlaybackPosition(controller: MediaController, isActive: Boolean) {
        if (isActive) {
            val controllerDuration = controller.duration
            if (controllerDuration > 0L) duration = controllerDuration
            val controllerPosition = controller.currentPosition
            if (controllerPosition >= 0L && duration > 0L) {
                currentPosition = controllerPosition.coerceIn(0L, duration)
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

    // 构造下一首索引
    fun nextIndex(): Int = calculateIndex(direction = 1, repeatOne = false)

    // 构造上一首索引
    fun previousIndex(): Int = calculateIndex(direction = -1, repeatOne = false)

    // ===== UI 层状态写入口：悬浮窗 UI 统一通过这些方法写入状态，避免直接对 public var 赋值 =====
    // 方法与属性 setter 同名会冲突，故用 @JvmName 指定不同 JVM 名
    @JvmName("updatePlayMode")
    fun setPlayMode(mode: PlayMode) { playMode = mode }
    @JvmName("updateSearchMode")
    fun setSearchMode(enabled: Boolean) { isSearchMode = enabled }
    @JvmName("updateSearchResultsVisible")
    fun setSearchResultsVisible(visible: Boolean) { showSearchResults = visible }
    @JvmName("updateSearchQuery")
    fun setSearchQuery(query: String) { searchQuery = query }
    @JvmName("updateLyricsVisible")
    fun setLyricsVisible(visible: Boolean) { isLyricsVisible = visible }
    @JvmName("updateLocalCoverCandidates")
    fun setLocalCoverCandidates(candidates: List<RecentCover>) { localCoverCandidates = candidates }
    @JvmName("updateCoverCandidates")
    fun setCoverCandidates(candidates: List<NeteaseSongSearchResult>) { coverCandidates = candidates }
    @JvmName("updateLyricsCandidates")
    fun setLyricsCandidates(candidates: List<NeteaseSongSearchResult>) { lyricsCandidates = candidates }
    @JvmName("updateLyricsRefreshError")
    fun setLyricsRefreshError(error: String?) { lyricsRefreshError = error }
    @JvmName("updateErrorMsg")
    fun setErrorMsg(message: String?) { errorMsg = message }
    @JvmName("updateTimerMinutes")
    fun setTimerMinutes(minutes: Int) { timerMinutes = minutes }
    @JvmName("updateTimerAutoStopped")
    fun setTimerAutoStopped(stopped: Boolean) { timerAutoStopped = stopped }
    @JvmName("updateCurrentPosition")
    fun setCurrentPosition(position: Long) { currentPosition = position }
    @JvmName("updateUsbExclusiveEnabled")
    fun setUsbExclusiveEnabled(enabled: Boolean) { usbExclusiveEnabled = enabled }
    @JvmName("updateUsbExclusiveMode")
    fun setUsbExclusiveMode(enabled: Boolean) { isUsbExclusiveMode = enabled }
}
