package com.yichao.evilgodxu.musicpanel

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.log.CrashLogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

// 封面/歌词刷新候选的来源（网易云+QQ+酷狗）
private val metadataCandidateSources: List<OnlineMusicSource> = listOf(
    NeteaseMusicApi,
    QQMusicApi,
    KugouMusicApi,
)

// 多源并行搜索候选：每个来源对每条查询各取前 5 条（取封面可用者），单源失败不影响其它来源
private suspend fun searchMetadataCandidates(query: String): List<NeteaseSongSearchResult> = coroutineScope {
    metadataCandidateSources.mapNotNull { source ->
        runCatching {
            source.search(query)
                .filter { !it.coverUrl.isNullOrBlank() }
                .take(5)
        }.getOrNull()
    }.flatten()
}

internal suspend fun searchLyricsCandidates(
    playbackState: MusicPlaybackState,
    track: MusicTrack,
) {
    playbackState.isLyricsSearching = true
    playbackState.lyricsCandidates = emptyList()
    playbackState.lyricsRefreshError = null
    try {
        val combined = listOf(track.title, track.artist)
            .filter { it.isNotBlank() }
            .joinToString(" ")
        val occupied = if (combined.isBlank()) emptyList() else searchMetadataCandidates(combined)
            .filter { !it.coverUrl.isNullOrBlank() }
        val occupiedIds = occupied.map { it.id }.toSet()
        val titleOnly = if (track.title.isBlank()) emptyList() else searchMetadataCandidates(track.title)
            .filter { it.id !in occupiedIds && !it.coverUrl.isNullOrBlank() }
        playbackState.lyricsCandidates = NeteaseMusicApi.rankSearchResults(
            occupied + titleOnly,
            combined.ifBlank { track.title }
        ).take(30)
    } catch (e: Exception) {
        CrashLogManager.logException("MusicPanelSearchLogic", "搜索歌词候选失败: 歌曲=${track.title}", e)
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
            val lines = when (candidate.source) {
                MusicSearchSource.QQ -> QQMusicApi.lyricLines(candidate).orEmpty()
                MusicSearchSource.KUGOU -> KugouMusicApi.lyricLines(candidate).orEmpty()
                else -> NeteaseMusicApi.lyric(candidate.id).lines
            }
            if (lines.isEmpty()) return@withContext null
            val path = MusicMetadataCache.saveLyrics(context, track.title, track.artist, lines).orEmpty()
            if (path.isBlank()) return@withContext null
            track.copy(
                lyricCachePath = path,
                lyricLines = lines,
                neteaseId = candidate.id,
                neteaseCoverUrl = candidate.coverUrl.orEmpty()
            )
        } ?: return false
        withContext(Dispatchers.Main) {
            playbackState.updateTrack(updated)
        }
        true
    } catch (e: Exception) {
        CrashLogManager.logException("MusicPanelSearchLogic", "应用歌词候选失败: 歌曲=${track.title} 路径=${track.path}", e)
        false
    } finally {
        withContext(Dispatchers.Main) {
            playbackState.isLyricsRefreshing = false
        }
    }
}

// 导入本地 LRC 歌词文件：解析后写入歌词缓存并应用到当前曲目
internal suspend fun applyLocalLyrics(
    context: Context,
    playbackState: MusicPlaybackState,
    track: MusicTrack,
    uri: Uri,
): Boolean {
    return try {
        val updated = withContext(Dispatchers.IO) {
            val text = context.contentResolver.openInputStream(uri)
                ?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: return@withContext null
            val lines = MusicMetadataCache.parseLyricsText(text)
            if (lines.isEmpty()) return@withContext null
            val path = MusicMetadataCache.saveLyrics(context, track.title, track.artist, lines).orEmpty()
            if (path.isBlank()) return@withContext null
            track.copy(lyricCachePath = path, lyricLines = lines)
        } ?: return false
        withContext(Dispatchers.Main) {
            playbackState.updateTrack(updated)
        }
        true
    } catch (e: Exception) {
        CrashLogManager.logException("MusicPanelSearchLogic", "导入本地歌词失败: 歌曲=${track.title}", e)
        false
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
            searchMetadataCandidates(titleArtist)
                .filter { !it.coverUrl.isNullOrBlank() }
        }
        val titleCandidates = if (track.title.isBlank()) emptyList() else {
            searchMetadataCandidates(track.title)
                .filter { !it.coverUrl.isNullOrBlank() }
        }
        playbackState.coverCandidates = NeteaseMusicApi.rankSearchResults(
            titleArtistCandidates + titleCandidates,
            titleArtist.ifBlank { track.title }
        ).take(30)
    } catch (e: Exception) {
        CrashLogManager.logException("MusicPanelSearchLogic", "搜索封面候选失败: 歌曲=${track.title}", e)
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
            // 旧文件若已无引用，由 cleanupOrphanedMetadata 统一回收，避免误删被共享的封面
            track.copy(
                neteaseId = candidate.id,
                neteaseCoverUrl = if (writeSuccess) "" else candidate.coverUrl.orEmpty(),
                coverCachePath = path
            )
        } ?: return false
        withContext(Dispatchers.Main) {
            playbackState.updateTrack(updated)
            playbackState.bumpCoverRevision()
            playbackState.coverCandidates = emptyList()
        }
        true
    } catch (e: Exception) {
        CrashLogManager.logException("MusicPanelSearchLogic", "应用封面候选失败: 歌曲=${track.title} 路径=${track.path}", e)
        false
    }
}

internal suspend fun performSearch(
    playbackState: MusicPlaybackState,
    context: Context,
) {
    val query = playbackState.searchQuery.trim()
    if (query.isBlank()) return
    // 取消上一次未完成的搜索，避免过期响应覆盖新查询结果
    playbackState.searchJob?.cancel()
    playbackState.searchJob = currentCoroutineContext()[Job] ?: return
    playbackState.isSearching = true
    playbackState.searchResults = emptyList()
    playbackState.errorMsg = null
    // 立即切到结果视图，使加载指示器在搜索期间可见
    playbackState.showSearchResults = true
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
        // 仅当前搜索协程复位搜索状态，避免被取消的旧协程提前清掉新搜索的加载态
        if (playbackState.searchJob == currentCoroutineContext()[Job]) {
            playbackState.isSearching = false
        }
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
        // 仅网易云结果写入 neteaseId，供播放失败重试时匹配原曲
        neteaseId = if (result.source == MusicSearchSource.NETEASE) result.id else 0L,
        neteaseCoverUrl = result.coverUrl.orEmpty(),
        isOnlinePlay = true,
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

    // 在线结果后台加载歌词：各平台各有歌词接口
    playbackState.playbackScope.launch(Dispatchers.IO) {
        try {
            val lines = when (result.source) {
                MusicSearchSource.NETEASE -> NeteaseMusicApi.lyric(result.id).lines
                MusicSearchSource.QQ -> QQMusicApi.lyricLines(result).orEmpty()
                MusicSearchSource.KUGOU -> KugouMusicApi.lyricLines(result).orEmpty()
            }
            if (lines.isNotEmpty()) {
                val lyricPath = MusicMetadataCache.saveLyrics(context, result.title, result.artist, lines).orEmpty()
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

    // 在线播放时同步下载封面原图落盘：缓存完成后可直接内嵌写入本地文件，面板与通知栏也即时获得本地封面
    playbackState.playbackScope.launch(Dispatchers.IO) {
        try {
            val coverUrl = result.coverUrl?.takeIf { it.isNotBlank() } ?: return@launch
            val bytes = NeteaseMusicApi.loadCoverBytes(coverUrl) ?: return@launch
            val coverPath = MusicMetadataCache.saveCover(context, result.id, bytes).orEmpty()
            if (coverPath.isBlank()) return@launch
            withContext(Dispatchers.Main) {
                val idx = playbackState.playlist.indexOfFirst { it.id == trackId }
                if (idx < 0) return@withContext
                val updated = playbackState.playlist[idx].copy(coverCachePath = coverPath)
                val list = playbackState.playlist.toMutableList()
                list[idx] = updated
                playbackState.playlist = list
                if (playbackState.currentTrack?.id == trackId) {
                    playbackState.currentTrack = updated
                }
            }
            // 封面就绪后刷新系统媒体面板的当前 MediaItem
            refreshCurrentMediaItem(playbackState)
        } catch (e: Exception) {
            CrashLogManager.logException("MusicPanelSearchLogic", "下载在线封面失败: 歌曲=${result.title}", e)
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
    // 缓存进行中的曲目切歌后仍保留在播放列表，等待下载完成重定向至本地文件
    playbackState.cacheInProgressIds.add(trackId)
    try {
        // 按实际 URL 后缀推断格式，避免高音质文件误存为 mp3
        val extension = url.substringBefore('?')
            .substringAfterLast('.', "")
            .lowercase()
            .takeIf { it in AUDIO_EXTENSIONS } ?: "mp3"
        val fileName = "${sanitizeFileName(result.title)} - ${sanitizeFileName(result.artist)}.$extension"

        val existingUri = findExistingDownload(context, fileName)
        if (existingUri != null) {
            withContext(Dispatchers.Main) {
                updateTrackAudioUri(playbackState, trackId, existingUri)
                // 复用已有缓存：把当前播放源重定向至本地文件，实现在线/离线无缝过渡
                redirectCachedCurrentItem(context, playbackState)
            }
            // 提取封面/歌词展示缓存并清理冗余封面文件
            MetadataEnricher.enrichAndCleanup(context, playbackState)
            return
        }

        // 流式下载到应用缓存临时文件，再写入 MediaStore，避免整曲驻留内存
        val tempFile = File.createTempFile("download", ".$extension", context.cacheDir)
        var audioUri: String? = null
        try {
            val request = Request.Builder().url(url).build()
            MusicHttpClient.client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return
                resp.body.byteStream().use { input ->
                    tempFile.outputStream().use { output -> input.copyTo(output, STREAM_BUFFER_SIZE) }
                }
            }

            // 试听片段(≤30秒)不缓存，保持在线播放
            if (isTrialAudioFile(tempFile)) return

            val contentValues = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, audioMimeType(extension))
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/YiChao/Audio")
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            if (uri != null) {
                context.contentResolver.openOutputStream(uri)?.use { os ->
                    tempFile.inputStream().use { input -> input.copyTo(os, STREAM_BUFFER_SIZE) }
                }
                audioUri = uri.toString()
            }
        } finally {
            tempFile.delete()
        }
        if (audioUri == null) return

        withContext(Dispatchers.Main) {
            updateTrackAudioUri(playbackState, trackId, audioUri)
            // 缓存完成：把当前播放源重定向至本地缓存文件，实现在线/离线无缝过渡
            redirectCachedCurrentItem(context, playbackState)
        }
        // 用在线播放时已下载的封面原图内嵌写入缓存文件，无额外网络匹配
        embedCachedCover(context, playbackState, trackId)
        // 下载完成：提取封面/歌词展示缓存并清理冗余封面文件
        MetadataEnricher.enrichAndCleanup(context, playbackState)
    } catch (e: Exception) {
        CrashLogManager.logException("MusicPanelSearchLogic", "缓存下载文件失败", e)
    } finally {
        playbackState.cacheInProgressIds.remove(trackId)
    }
}

internal fun sanitizeFileName(name: String): String {
    return name.replace(Regex("[/\\\\:*?\"<>|]"), "_")
        .take(80)
        .trim()
}

// 支持的音频扩展名，用于按实际 URL 推断缓存格式
private val AUDIO_EXTENSIONS = setOf("mp3", "flac", "ogg", "m4a", "wav", "aac", "opus")

// 流式复制音频数据的读缓冲大小
private const val STREAM_BUFFER_SIZE = 64 * 1024

// 探测音频时长是否 ≤30 秒的试听片段
private fun isTrialAudioFile(file: File): Boolean {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(file.absolutePath)
        val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        durationMs in 1..30_000
    } catch (e: Exception) {
        false
    } finally {
        retriever.release()
    }
}

private fun audioMimeType(extension: String): String = when (extension) {
    "flac" -> "audio/flac"
    "ogg" -> "audio/ogg"
    "m4a" -> "audio/mp4"
    "wav" -> "audio/x-wav"
    "aac" -> "audio/aac"
    "opus" -> "audio/opus"
    else -> "audio/mpeg"
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
            MusicMetadataCache.saveLyrics(context, track.title, track.artist, lyric.lines).orEmpty()
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
        val args = arrayOf(fileName, Environment.DIRECTORY_DOWNLOADS + "/YiChao/Audio/")
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

// 将在线播放时已下载的封面原图内嵌写入已缓存文件；封面尚未就绪时跳过，交由兜底补全处理
private suspend fun embedCachedCover(
    context: Context,
    playbackState: MusicPlaybackState,
    trackId: Long,
) {
    val track = playbackState.playlist.firstOrNull { it.id == trackId } ?: return
    val bytes = MusicMetadataCache.loadCoverBytes(track.coverCachePath) ?: return
    try {
        MusicMetadataWriter.writeCoverToSource(context, track, bytes)
    } catch (e: Exception) {
        CrashLogManager.logException("MusicPanelSearchLogic", "内嵌缓存封面失败: 歌曲=${track.title}", e)
    }
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

    // QQ/酷狗按平台标识解析播放地址；网易云结果需向接口请求播放 URL
    val playTarget: NeteaseSongSearchResult
    val url: String?
    when (target.source) {
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
