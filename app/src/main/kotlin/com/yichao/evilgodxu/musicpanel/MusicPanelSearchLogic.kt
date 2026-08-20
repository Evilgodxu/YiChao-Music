package com.yichao.evilgodxu.musicpanel

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.log.CrashLogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

internal suspend fun searchLyricsCandidates(
    playbackState: MusicPlaybackState,
    track: MusicTrack,
) {
    playbackState.isLyricsSearching = true
    playbackState.lyricsCandidates = emptyList()
    playbackState.lyricsRefreshError = null
    try {
        val occupied = NeteaseMusicApi.search("${track.title} ${track.artist}")
            .filter { !it.coverUrl.isNullOrBlank() }
            .take(5)
        val occupiedIds = occupied.map { it.id }.toSet()
        val titleOnly = NeteaseMusicApi.search(track.title)
            .filter { it.id !in occupiedIds && !it.coverUrl.isNullOrBlank() }
            .take(5)
        playbackState.lyricsCandidates = (occupied + titleOnly).distinctBy { it.id }.take(10)
    } catch (e: Exception) {
        CrashLogManager.logException("MusicPanelSearchLogic", "搜索歌词候选失败", e)
        playbackState.lyricsCandidates = emptyList()
    } finally {
        playbackState.isLyricsSearching = false
    }
}

internal suspend fun applyLyricsCandidate(
    context: Context,
    playbackState: MusicPlaybackState,
    track: MusicTrack,
    candidate: NeteaseSongSearchResult,
): Boolean {
    playbackState.isLyricsRefreshing = true
    playbackState.lyricsRefreshError = null
    return try {
        val updated = withContext(Dispatchers.IO) {
            val lyric = NeteaseMusicApi.lyric(candidate.id)
            if (lyric.lines.isEmpty()) return@withContext null
            val path = MusicMetadataCache.saveLyrics(context, candidate.id, lyric.lines).orEmpty()
            if (path.isBlank()) return@withContext null
            track.copy(
                lyricCachePath = path,
                lyricLines = lyric.lines,
                neteaseId = candidate.id,
                neteaseCoverUrl = candidate.coverUrl.orEmpty()
            )
        } ?: return false
        withContext(Dispatchers.Main) {
            playbackState.updateTrack(updated)
        }
        true
    } catch (e: Exception) {
        CrashLogManager.logException("MusicPanelSearchLogic", "应用歌词候选失败", e)
        false
    } finally {
        withContext(Dispatchers.Main) {
            playbackState.isLyricsRefreshing = false
        }
    }
}

internal suspend fun searchCoverCandidates(
    playbackState: MusicPlaybackState,
    track: MusicTrack,
) {
    playbackState.isCoverSearching = true
    playbackState.coverCandidates = emptyList()
    try {
        val titleArtist = listOf(track.title, track.artist)
            .filter { it.isNotBlank() }
            .joinToString(" ")
        val titleArtistCandidates = if (titleArtist.isBlank()) emptyList() else {
            NeteaseMusicApi.search(titleArtist)
                .filter { !it.coverUrl.isNullOrBlank() }
                .take(5)
        }
        val titleCandidates = if (track.title.isBlank()) emptyList() else {
            NeteaseMusicApi.search(track.title)
                .filter { !it.coverUrl.isNullOrBlank() }
                .take(5)
        }
        playbackState.coverCandidates = (titleArtistCandidates + titleCandidates)
            .distinctBy { it.id }
            .take(10)
    } catch (e: Exception) {
        CrashLogManager.logException("MusicPanelSearchLogic", "搜索封面候选失败", e)
        playbackState.coverCandidates = emptyList()
    } finally {
        playbackState.isCoverSearching = false
    }
}

internal suspend fun applyCoverCandidate(
    context: Context,
    playbackState: MusicPlaybackState,
    track: MusicTrack,
    candidate: NeteaseSongSearchResult,
): Boolean {
    return try {
        val updated = withContext(Dispatchers.IO) {
            val bytes = NeteaseMusicApi.loadCoverBytes(candidate.coverUrl.orEmpty()) ?: return@withContext null
            // 手动刷新封面：按音频容器格式原生写入元数据
            val writeSuccess = MusicMetadataWriter.writeCover(context, track, bytes)
            val path = MusicMetadataCache.saveCover(context, candidate.id, bytes).orEmpty()
            if (path.isBlank()) return@withContext null
            val oldPath = track.coverCachePath
            if (writeSuccess) {
                // 封面已内嵌进音频文件：删除旧封面缓存，保留本轮缓存文件供面板即时显示
                MusicMetadataCache.deleteCoverFile(oldPath)
            } else if (oldPath.isNotBlank() && oldPath != path) {
                MusicMetadataCache.deleteCoverFile(oldPath)
            }
            track.copy(
                neteaseId = candidate.id,
                neteaseCoverUrl = if (writeSuccess) "" else candidate.coverUrl.orEmpty(),
                coverCachePath = path
            )
        } ?: return false
        withContext(Dispatchers.Main) {
            playbackState.updateTrack(updated)
            playbackState.coverCandidates = emptyList()
        }
        true
    } catch (e: Exception) {
        CrashLogManager.logException("MusicPanelSearchLogic", "应用封面候选失败", e)
        false
    }
}

internal suspend fun performSearch(
    playbackState: MusicPlaybackState,
    context: Context,
) {
    val query = playbackState.searchQuery.trim()
    if (query.isBlank()) return
    playbackState.isSearching = true
    playbackState.searchResults = emptyList()
    playbackState.errorMsg = null
    try {
        // 并行查询所有已注册的音乐源并聚合展示（单个来源失败不影响其他来源）
        // 各来源仅对自身结果按 id 去重，不跨平台去重，避免误过滤另一平台的歌曲
        val results = coroutineScope {
            onlineMusicSources.map { source ->
                async {
                    runCatching { source.search(query) }.getOrDefault(emptyList())
                        .distinctBy { it.id }
                }
            }.awaitAll().flatten()
        }
        playbackState.searchResults = results
        if (results.isNotEmpty()) playbackState.addSearchHistory(query)
        playbackState.showSearchResults = true
    } catch (e: kotlinx.coroutines.CancellationException) {
        // 搜索界面退出导致的协程取消，不是失败，向上传递取消
        throw e
    } catch (e: Exception) {
        CrashLogManager.logException("MusicPanelSearchLogic", "搜索歌曲失败", e)
        playbackState.searchResults = emptyList()
    } finally {
        playbackState.isSearching = false
    }
}

internal suspend fun downloadAndPlay(
    context: Context,
    playbackState: MusicPlaybackState,
    result: NeteaseSongSearchResult,
    url: String,
) {
    val trackId = result.id + 1000000L
    val track = MusicTrack(
        id = trackId,
        path = "",
        audioUri = url,
        title = result.title,
        artist = result.artist,
        duration = result.duration,
        albumId = 0L,
        // Jamendo 结果不写入 neteaseId，避免播放失败重试时误向网易云请求
        neteaseId = if (result.source == MusicSearchSource.NETEASE) result.id else 0L,
        neteaseCoverUrl = result.coverUrl.orEmpty()
    )

    withContext(Dispatchers.Main) {
        val existingIndex = playbackState.playlist.indexOfFirst { it.id == trackId }
        val targetIndex = if (existingIndex >= 0) {
            existingIndex
        } else {
            playbackState.playlist = playbackState.playlist + track
            playbackState.playlist.size - 1
        }
        playbackState.currentIndex = targetIndex
        playbackState.currentTrack = playbackState.playlist[targetIndex]
        playbackState.persistPlaylist()
        playTrackAt(context, playbackState, targetIndex)
    }

    // 在线结果后台加载歌词：网易云/QQ/酷狗各有歌词接口，Jamendo 无歌词接口，跳过
    if (result.source != MusicSearchSource.JAMENDO) {
        playbackState.playbackScope.launch(Dispatchers.IO) {
            try {
                val lines = when (result.source) {
                    MusicSearchSource.NETEASE -> NeteaseMusicApi.lyric(result.id).lines
                    MusicSearchSource.QQ -> QQMusicApi.lyricLines(result).orEmpty()
                    MusicSearchSource.KUGOU -> KugouMusicApi.lyricLines(result).orEmpty()
                    MusicSearchSource.JAMENDO -> emptyList()
                }
                if (lines.isNotEmpty()) {
                    val lyricPath = MusicMetadataCache.saveLyrics(context, result.id, lines).orEmpty()
                    withContext(Dispatchers.Main) {
                        val idx = playbackState.playlist.indexOfFirst { it.id == trackId }
                        if (idx >= 0) {
                            val updated = playbackState.playlist[idx].copy(
                                lyricCachePath = lyricPath,
                                lyricLines = lines
                            )
                            val list = playbackState.playlist.toMutableList()
                            list[idx] = updated
                            playbackState.playlist = list
                            if (playbackState.currentTrack?.id == trackId) {
                                playbackState.currentTrack = updated
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                CrashLogManager.logException("MusicPanelSearchLogic", "获取在线歌词失败", e)
            }
        }
    }

    playbackState.playbackScope.launch(Dispatchers.IO) {
        cacheToDownloads(context, result, url, trackId, playbackState)
    }
}

internal suspend fun cacheToDownloads(
    context: Context,
    result: NeteaseSongSearchResult,
    url: String,
    trackId: Long,
    playbackState: MusicPlaybackState,
) {
    try {
        val fileName = "${sanitizeFileName(result.title)} - ${sanitizeFileName(result.artist)}.mp3"

        val existingUri = findExistingDownload(context, fileName)
        if (existingUri != null) {
            withContext(Dispatchers.Main) {
                updateTrackAudioUri(playbackState, trackId, existingUri)
            }
            return
        }

        val connection = URL(url).openConnection()
        connection.connectTimeout = 15000
        connection.readTimeout = 60000
        val bytes = (connection as java.net.HttpURLConnection).inputStream.use { it.readBytes() }

        val audioUri: String

        val contentValues = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "audio/mpeg")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/YiChao Music")
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
        if (uri != null) {
            context.contentResolver.openOutputStream(uri)?.use { os -> os.write(bytes) }
            audioUri = uri.toString()
        } else {
            return
        }

        withContext(Dispatchers.Main) {
            updateTrackAudioUri(playbackState, trackId, audioUri)
        }
    } catch (e: Exception) {
        CrashLogManager.logException("MusicPanelSearchLogic", "缓存下载文件失败", e)
    }
}

internal fun sanitizeFileName(name: String): String {
    return name.replace(Regex("[/\\\\:*?\"<>|]"), "_")
        .take(80)
        .trim()
}

internal fun normalizeTitle(value: String): String {
    return value.lowercase()
        .replace(Regex("""[\s　（）()\[\]【】「」『』《》〈〉、，。！？"'""'']+"""), "")
        .trim()
}

internal suspend fun enrichOnlineMetadata(
    context: Context,
    playbackState: MusicPlaybackState,
    track: MusicTrack,
    result: NeteaseSongSearchResult,
) {
    if (track.neteaseId != 0L && track.lyricLines.isNotEmpty()) return
    try {
        val lyric = NeteaseMusicApi.lyric(result.id)
        val lyricPath = if (lyric.lines.isNotEmpty()) {
            MusicMetadataCache.saveLyrics(context, result.id, lyric.lines).orEmpty()
        } else ""
        withContext(Dispatchers.Main) {
            val idx = playbackState.playlist.indexOfFirst { it.id == track.id }
            if (idx < 0) return@withContext
            val updated = playbackState.playlist[idx].copy(
                neteaseId = result.id,
                neteaseCoverUrl = result.coverUrl.orEmpty(),
                lyricCachePath = lyricPath,
                lyricLines = lyric.lines
            )
            val list = playbackState.playlist.toMutableList()
            list[idx] = updated
            playbackState.playlist = list
            if (playbackState.currentTrack?.id == track.id) {
                playbackState.currentTrack = updated
            }
        }
    } catch (e: Exception) {
        CrashLogManager.logException("MusicPanelSearchLogic", "获取在线元数据失败", e)
    }
}

internal suspend fun findExistingDownload(
    context: Context,
    fileName: String,
): String? = withContext(Dispatchers.IO) {
    try {
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Downloads._ID)
        val selection = "${MediaStore.Downloads.DISPLAY_NAME} = ? AND " +
                "${MediaStore.Downloads.RELATIVE_PATH} = ?"
        val args = arrayOf(fileName, Environment.DIRECTORY_DOWNLOADS + "/Memory Meets Tomorrow/")
        context.contentResolver.query(collection, projection, selection, args, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(0)
                return@withContext Uri.withAppendedPath(collection, id.toString()).toString()
            }
        }
    } catch (e: Exception) {
        CrashLogManager.logException("MusicPanelSearchLogic", "查询已下载文件失败", e)
    }
    null
}

internal fun updateTrackAudioUri(
    playbackState: MusicPlaybackState,
    trackId: Long,
    audioUri: String,
) {
    val idx = playbackState.playlist.indexOfFirst { it.id == trackId }
    if (idx < 0) return
    val updated = playbackState.playlist[idx].copy(audioUri = audioUri)
    val list = playbackState.playlist.toMutableList()
    list[idx] = updated
    playbackState.playlist = list
    if (playbackState.currentTrack?.id == trackId) {
        playbackState.currentTrack = updated
    }
    playbackState.persistPlaylist()
}

internal suspend fun playSearchResult(
    target: NeteaseSongSearchResult,
    playbackState: MusicPlaybackState,
    context: Context,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    val normalizedTitle = normalizeTitle(target.title)
    val normalizedArtist = normalizeTitle(target.artist)
    val localMatch = playbackState.playlist.firstOrNull { t ->
        t.path.isNotBlank() &&
        normalizeTitle(t.title) == normalizedTitle &&
        (normalizedArtist.isBlank() || normalizeTitle(t.artist) == normalizedArtist)
    }
    if (localMatch != null) {
        val idx = playbackState.playlist.indexOfFirst { it.id == localMatch.id }
        if (idx >= 0) {
            if (target.source == MusicSearchSource.NETEASE) {
                scope.launch {
                    enrichOnlineMetadata(context, playbackState, localMatch, target)
                }
            }
            playbackState.errorMsg = null
            playbackState.currentIndex = idx
            playbackState.currentTrack = playbackState.playlist[idx]
            playbackState.isSearchMode = false
            playbackState.showSearchResults = false
            playbackState.searchQuery = ""
            playbackState.searchResults = emptyList()
            playTrackAt(context, playbackState, idx)
            return
        }
    }

    playbackState.pendingSearchResults = emptyList()

    // Jamendo 搜索结果自带音频地址，直接播放；QQ/酷狗按平台标识解析播放地址；网易云结果需向接口请求播放 URL
    val playTarget: NeteaseSongSearchResult
    val url: String?
    when (target.source) {
        MusicSearchSource.JAMENDO -> {
            playTarget = target
            url = target.audioUrl
        }
        MusicSearchSource.QQ -> {
            playTarget = target
            url = withContext(Dispatchers.IO) { QQMusicApi.songUrl(target.sourceId.orEmpty()) }
        }
        MusicSearchSource.KUGOU -> {
            playTarget = target
            url = withContext(Dispatchers.IO) { KugouMusicApi.songUrl(target.sourceId.orEmpty()) }
        }
        MusicSearchSource.NETEASE -> {
            val fullResult = if (target.coverUrl.isNullOrBlank() || target.duration <= 0L) {
                withContext(Dispatchers.IO) {
                    NeteaseMusicApi.songDetail(target.id) ?: target
                }
            } else target
            playTarget = fullResult
            url = withContext(Dispatchers.IO) {
                NeteaseMusicApi.getSongUrlWithFallback(fullResult.id)
            }
        }
    }

    if (url != null) {
        playbackState.errorMsg = null
        playbackState.closeSearchResultsOnReady = true
        downloadAndPlay(context, playbackState, playTarget, url)
    } else {
        playbackState.errorMsg = context.getString(R.string.music_panel_play_error)
        playbackState.pendingSearchResults = emptyList()
    }
}
