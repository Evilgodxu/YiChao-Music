package com.yichao.evilgodxu.data.music

import android.content.Context
import com.yichao.evilgodxu.data.music.metadata.MusicMetadataCache
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.domain.music.AiMusicAnalyzer
import com.yichao.evilgodxu.domain.music.FakeLosslessAnalyzer
import com.yichao.evilgodxu.domain.music.MusicPlaybackState
import com.yichao.evilgodxu.domain.music.normalizeTitle
import com.yichao.evilgodxu.domain.music.PlaylistSource
import com.yichao.evilgodxu.domain.music.trackFormatCategory
import com.yichao.evilgodxu.screens.home.data.PlaylistStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// 播放列表刷新器：首页与音乐面板共用扫描入口，串行执行避免并发重复扫描
object PlaylistRefresher {
    private val scanMutex = Mutex()

    // 扫描本地音乐并与外部曲目合并；restoreCurrent 控制扫描后是否恢复当前播放曲目
    suspend fun refresh(
        context: Context,
        state: MusicPlaybackState,
        restoreCurrent: Boolean,
        afterMerge: suspend () -> Unit = {},
    ) {
        scanMutex.withLock {
            val started = withContext(Dispatchers.Main) {
                if (state.isScanning) {
                    false
                } else {
                    state.isScanning = true
                    true
                }
            }
            if (!started) return@withLock
            try {
                val tracks = MusicScanner.scan(context)
                // 合并去重在 IO 线程执行：同一文件可能经 SAF 选择器 / 缓存下载 / MediaStore 多条 URI
                // 形态进入列表，仅按 URI 去重会残留同文件多条目且每次刷新重新产生，故按真实文件路径合并
                val mergedBase = withContext(Dispatchers.IO) {
                    // 在线播放曲目（含已缓存）仅保留当前播放项，其余未播放的一律丢弃，
                    // 其余外部曲目（path 为空）照常合并，避免刷新后在线歌曲常驻
                    val activeId = state.currentTrack?.id
                    val externalTracks = state.playlist.filter {
                        it.path.isBlank() && (!it.isOnlinePlay || it.id == activeId)
                    }
                    (tracks + externalTracks).distinctBy { trackIdentityKey(context, it) }
                }
                withContext(Dispatchers.Main) {
                    // 缓存复用索引以全量库为准而非当前列表：停留在歌单时当前列表只是全量库子集，
                    // 仅按它建索引会丢掉库内其他歌曲的歌词/封面缓存引用，导致切歌单后缓存污染
                    val cachedLibrary = state.libraryTracks
                    val previous = cachedLibrary.associateBy { normalizedAudioUri(it.audioUri) }
                    // 缓存下载后 audioUri 由 downloads 集合切换为 audio/media 集合，归一化后仍不一致；
                    // 按"标题 - 艺术家"兜底匹配旧列表，复用在线播放期间已保存的歌词/封面缓存
                    val previousByTitleArtist = cachedLibrary
                        .filter { it.lyricCachePath.isNotBlank() || it.coverCachePath.isNotBlank() }
                        .associateBy { titleArtistKey(it) }
                    val mergedTracks = mergedBase
                        .map { track ->
                            val cached = previous[normalizedAudioUri(track.audioUri)]
                                ?: previousByTitleArtist[titleArtistKey(track)]
                                ?: return@map track
                            track.copy(
                                neteaseId = cached.neteaseId,
                                neteaseCoverUrl = cached.neteaseCoverUrl,
                                coverCachePath = cached.coverCachePath,
                                lyricCachePath = cached.lyricCachePath,
                                lyricLines = cached.lyricLines,
                                coverFailed = cached.coverFailed,
                                lyricFailed = cached.lyricFailed,
                            )
                        }
                    // 扫描前快照当前歌单选择，扫描后按新库重建并保持选中而非回到默认
                    val selectedSource = state.playlistSource
                    val currentId = state.currentTrack?.id
                    state.setSortedPlaylist(mergedTracks)
                    val sortedLibrary = state.playlist
                    if (selectedSource != null) {
                        val rebuilt = rebuildFromSource(context, state, sortedLibrary, selectedSource)
                        if (rebuilt.isNotEmpty()) {
                            state.playlist = rebuilt
                            state.playlistSource = selectedSource
                            val newIndex = rebuilt.indexOfFirst { it.id == currentId }
                            state.currentIndex = newIndex
                            if (newIndex >= 0) state.currentTrack = rebuilt[newIndex]
                            state.defaultPlaylistBackup = sortedLibrary
                        } else {
                            state.playlistSource = null
                            state.defaultPlaylistBackup = null
                        }
                    } else {
                        state.defaultPlaylistBackup = null
                    }
                    state.persistPlaylist()
                    if (restoreCurrent) restoreCurrentTrack(state)
                }
                afterMerge()
            } finally {
                withContext(Dispatchers.Main + NonCancellable) {
                    state.isScanning = false
                }
            }
        }
    }

    // 归一化"标题 + 艺术家"作为歌词/封面缓存复用的匹配键
    private fun titleArtistKey(track: MusicTrack): String =
        normalizeTitle(track.title) + "\u0000" + normalizeTitle(track.artist)

    private fun restoreCurrentTrack(state: MusicPlaybackState) {
        if (state.playlist.isEmpty() || state.currentTrack != null) return
        val savedUri = state.pendingSavedUri
        val index = savedUri?.let { uri -> state.playlist.indexOfFirst { it.audioUri == uri } }
            ?.takeIf { it >= 0 }
            ?: 0
        state.currentIndex = index
        state.currentTrack = state.playlist[index]
    }

    // 按来源 key 从全量库重建歌单，供扫描刷新后保持选中
    private suspend fun rebuildFromSource(
        context: Context,
        state: MusicPlaybackState,
        library: List<MusicTrack>,
        source: PlaylistSource,
    ): List<MusicTrack> {
        PlaylistStore.ensureLoaded(context)
        return when {
            source.key == "smart:RECENT" ->
                state.recentPlayedIds.mapNotNull { id -> library.find { it.id == id } }
            source.key == "smart:FAVORITE" ->
                library.filter { it.id in state.likedIds }
            source.key.startsWith("smart:FORMAT:") -> {
                val format = source.key.removePrefix("smart:FORMAT:")
                // 假无损 / AI 音乐为识别类目：逐曲校验（缓存命中即瞬时返回），放 IO 线程避免阻塞刷新协程
                if (format == FakeLosslessAnalyzer.FAKE_LOSSLESS_KEY ||
                    format == AiMusicAnalyzer.AI_MUSIC_KEY
                ) {
                    withContext(Dispatchers.IO) {
                        buildList {
                            library.forEach { track ->
                                val hit = if (format == FakeLosslessAnalyzer.FAKE_LOSSLESS_KEY) {
                                    FakeLosslessAnalyzer.isSuspectedFakeLossless(context, track)
                                } else {
                                    AiMusicAnalyzer.isSuspectedAiMusic(context, track)
                                }
                                if (hit) add(track)
                            }
                        }
                    }
                } else {
                    library.filter { trackFormatCategory(context, it) == format }
                }
            }
            source.key.startsWith("custom:") -> {
                val playlistId = source.key.removePrefix("custom:").toLongOrNull()
                    ?: return emptyList()
                val playlist = PlaylistStore.playlists.find { it.id == playlistId }
                    ?: return emptyList()
                playlist.trackIds.mapNotNull { trackId -> library.find { it.id == trackId } }
            }
            source.key.startsWith("album:") -> {
                val albumId = source.key.removePrefix("album:").toLongOrNull()
                library.filter { albumId != null && it.albumId == albumId }
            }
            source.key.startsWith("artist:") ->
                library.filter { it.artist == source.key.removePrefix("artist:") }
            else -> emptyList()
        }
    }
}
