package com.yichao.evilgodxu.musicpanel

import android.content.Context
import android.net.Uri
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.log.CrashLogManager
import com.yichao.evilgodxu.musicpanel.proxy.ProxySourceEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// 单来源单次查询候选：每来源对每条查询各取前 10 条，单源失败不影响其它查询
private suspend fun searchSourceCandidates(source: OnlineMusicSource, query: String): List<NeteaseSongSearchResult> =
    runCatching { source.search(query).take(10) }.getOrDefault(emptyList())

// 单来源候选：先以“歌名+歌手”查询、再以纯歌名查询，各取前 10 后合并去重（10+10）
private suspend fun searchSingleSourceCandidates(
    source: OnlineMusicSource,
    title: String,
    artist: String,
): List<NeteaseSongSearchResult> {
    val combined = listOf(title, artist).filter { it.isNotBlank() }.joinToString(" ")
    val occupied = if (combined.isBlank()) emptyList() else searchSourceCandidates(source, combined)
    val occupiedIds = occupied.map { it.id }.toSet()
    val titleOnly = if (title.isBlank()) emptyList() else
        searchSourceCandidates(source, title).filter { it.id !in occupiedIds }
    return occupied + titleOnly
}

// 过滤候选：以是否匹配当前歌曲标题为唯一判定依据，normalize 后相等或互相包含
private fun matchesTrackTitle(title: String, result: NeteaseSongSearchResult): Boolean {
    val nTarget = normalizeTitle(title)
    if (nTarget.isBlank()) return true
    val nResult = normalizeTitle(result.title)
    return nResult.isNotEmpty() && (nResult == nTarget || nResult.contains(nTarget) || nTarget.contains(nResult))
}

internal suspend fun searchLyricsCandidates(
    playbackState: MusicPlaybackState,
    track: MusicTrack,
    source: MusicSearchSource,
) {
    playbackState.isLyricsSearching = true
    playbackState.lyricsCandidates = emptyList()
    playbackState.lyricsRefreshError = null
    try {
        playbackState.lyricsCandidates = searchSingleSourceCandidates(sourceOf(source), track.title, track.artist)
            .filter { matchesTrackTitle(track.title, it) }
            .take(30)
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
                MusicSearchSource.KUWO -> KuwoMusicApi.lyricLines(candidate).orEmpty()
                MusicSearchSource.MIGU -> MiguMusicApi.lyricLines(candidate).orEmpty()
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

// 编辑歌词行原文：整行按增强 LRC 文本(时间戳/逐字/翻译)重新解析后替换原行，写回缓存并刷新
internal suspend fun applyLyricsLineEdit(
    context: Context,
    playbackState: MusicPlaybackState,
    track: MusicTrack,
    index: Int,
    rawText: String,
): Boolean {
    return try {
        val updated = withContext(Dispatchers.IO) {
            val edited = MusicMetadataCache.parseLyricsText(rawText)
            if (edited.isEmpty()) return@withContext null
            val lines = track.lyricLines.toMutableList()
            if (index !in lines.indices) return@withContext null
            // 编辑结果可能拆分为多行，整体替换原位置并维持时间序
            lines.removeAt(index)
            lines.addAll(index, edited)
            val sorted = lines.sortedBy { it.timeMs }
            val path = MusicMetadataCache.saveLyrics(context, track.title, track.artist, sorted).orEmpty()
            if (path.isBlank()) return@withContext null
            track.copy(lyricCachePath = path, lyricLines = sorted)
        } ?: return false
        withContext(Dispatchers.Main) {
            playbackState.updateTrack(updated)
        }
        true
    } catch (e: Exception) {
        CrashLogManager.logException("MusicPanelSearchLogic", "编辑歌词原文失败: 歌曲=${track.title}", e)
        false
    }
}

internal suspend fun searchCoverCandidates(
    playbackState: MusicPlaybackState,
    track: MusicTrack,
    source: MusicSearchSource,
) {
    playbackState.isCoverSearching = true
    playbackState.coverCandidates = emptyList()
    try {
        playbackState.coverCandidates = searchSingleSourceCandidates(sourceOf(source), track.title, track.artist)
            .filter { matchesTrackTitle(track.title, it) && !it.coverUrl.isNullOrBlank() }
            .take(30)
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
        // 代理音源优先搜索，失败或未配置时回退内置平台
        val proxyResults = ProxySourceEngine.search(context, playbackState.searchSource, query)
        val results = if (proxyResults != null) {
            proxyResults
        } else {
            runCatching { sourceOf(playbackState.searchSource).search(query) }
                .getOrDefault(emptyList())
        }.distinctBy { it.id }
        playbackState.searchResults = results
        if (results.isNotEmpty()) playbackState.addSearchHistory(query)
        playbackState.showSearchResults = true
        // 代理搜索结果的封面为逐条经 pic 动作换取，后台渐进补齐
        if (proxyResults != null) fillProxySearchCovers(playbackState, context)
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

// 代理搜索结果的封面为逐条经 pic 动作换取：串行补齐前 N 条，控制聚合接口调用频率
private fun fillProxySearchCovers(playbackState: MusicPlaybackState, context: Context) {
    val pending = playbackState.searchResults
        .filter { it.coverUrl.isNullOrBlank() && !it.coverId.isNullOrBlank() }
        .take(MAX_PROXY_COVER_FILL)
    if (pending.isEmpty()) return
    playbackState.playbackScope.launch {
        pending.forEach { result ->
            val url = ProxySourceEngine.coverUrl(context, result) ?: return@forEach
            withContext(Dispatchers.Main) {
                val index = playbackState.searchResults.indexOfFirst { it.id == result.id }
                if (index >= 0) {
                    val list = playbackState.searchResults.toMutableList()
                    list[index] = result.copy(coverUrl = url)
                    playbackState.searchResults = list
                }
            }
        }
    }
}

// 代理搜索结果封面逐条换取的条数上限
private const val MAX_PROXY_COVER_FILL = 8

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
            val lines = ProxySourceEngine.lyricLines(context, result.source, result)
                ?: when (result.source) {
                    MusicSearchSource.NETEASE -> NeteaseMusicApi.lyric(result.id).lines
                    MusicSearchSource.QQ -> QQMusicApi.lyricLines(result).orEmpty()
                    MusicSearchSource.KUGOU -> KugouMusicApi.lyricLines(result).orEmpty()
                    MusicSearchSource.KUWO -> KuwoMusicApi.lyricLines(result).orEmpty()
                    MusicSearchSource.MIGU -> MiguMusicApi.lyricLines(result).orEmpty()
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
            // 代理音源优先按 coverId 换取封面，失败时回退搜索结果的封面直链
            val bytes = ProxySourceEngine.coverBytes(context, result)
                ?: run {
                    val coverUrl = result.coverUrl?.takeIf { it.isNotBlank() } ?: return@run null
                    NeteaseMusicApi.loadCoverBytes(coverUrl)
                }
                ?: return@launch
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

// 本地曲库命中同曲时直接播放；命中返回 true
internal suspend fun tryPlayLocalMatch(
    target: NeteaseSongSearchResult,
    playbackState: MusicPlaybackState,
    context: Context,
    scope: kotlinx.coroutines.CoroutineScope,
): Boolean {
    val normalizedTitle = normalizeTitle(target.title)
    val normalizedArtist = normalizeTitle(target.artist)
    val localMatch = playbackState.playlist.firstOrNull { t ->
        t.path.isNotBlank() &&
        normalizeTitle(t.title) == normalizedTitle &&
        (normalizedArtist.isBlank() || normalizeTitle(t.artist) == normalizedArtist)
    } ?: return false
    val idx = playbackState.playlist.indexOfFirst { it.id == localMatch.id }
    if (idx < 0) return false
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
    return true
}

internal suspend fun playSearchResult(
    target: NeteaseSongSearchResult,
    playbackState: MusicPlaybackState,
    context: Context,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    if (tryPlayLocalMatch(target, playbackState, context, scope)) return

    playbackState.pendingSearchResults = emptyList()

    // 代理音源优先解析播放地址，失败时回退内置解析；缓存下载沿用同一直链
    val playTarget: NeteaseSongSearchResult
    val url: String?
    when (target.source) {
        MusicSearchSource.QQ -> {
            playTarget = target
            url = ProxySourceEngine.resolveUrl(context, target, MusicQuality.HIGH)
                ?: withContext(Dispatchers.IO) { QQMusicApi.songUrl(target.sourceId.orEmpty()) }
        }
        MusicSearchSource.KUGOU -> {
            playTarget = target
            url = ProxySourceEngine.resolveUrl(context, target, MusicQuality.HIGH)
                ?: withContext(Dispatchers.IO) { KugouMusicApi.songUrl(target.sourceId.orEmpty()) }
        }
        MusicSearchSource.KUWO -> {
            playTarget = target
            url = ProxySourceEngine.resolveUrl(context, target, MusicQuality.HIGH)
                ?: withContext(Dispatchers.IO) { KuwoMusicApi.songUrl(target.sourceId.orEmpty()) }
        }
        MusicSearchSource.MIGU -> {
            playTarget = target
            url = ProxySourceEngine.resolveUrl(context, target, MusicQuality.HIGH)
                ?: withContext(Dispatchers.IO) { MiguMusicApi.songUrl(target.sourceId.orEmpty()) }
        }
        MusicSearchSource.NETEASE -> {
            val fullResult = if (target.coverUrl.isNullOrBlank() || target.duration <= 0L) {
                withContext(Dispatchers.IO) {
                    NeteaseMusicApi.songDetail(target.id) ?: target
                }
            } else target
            playTarget = fullResult
            url = ProxySourceEngine.resolveUrl(context, fullResult, MusicQuality.HIGH)
                ?: withContext(Dispatchers.IO) {
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

// 按指定音质解析在线播放地址；代理音源优先，返回 null 表示该音质不可用
internal suspend fun resolvePlayUrlByQuality(
    context: Context,
    target: NeteaseSongSearchResult,
    quality: MusicQuality,
): String? = withContext(Dispatchers.IO) {
    ProxySourceEngine.resolveUrl(context, target, quality) ?: when (target.source) {
        MusicSearchSource.NETEASE -> NeteaseMusicApi.songUrl(target.id, quality)
        MusicSearchSource.QQ -> QQMusicApi.songUrl(target.sourceId.orEmpty(), quality)
        MusicSearchSource.KUGOU -> KugouMusicApi.songUrl(target.sourceId.orEmpty())
        MusicSearchSource.KUWO -> KuwoMusicApi.songUrl(target.sourceId.orEmpty(), quality)
        MusicSearchSource.MIGU -> MiguMusicApi.songUrl(target.sourceId.orEmpty(), quality)
    }
}

// 按用户选定音质播放在线搜索结果；URL 解析成功即加入播放列表开始播放，
// 并标记待确认曲目交由播放器回调判定成败（失败时移除曲目、保留音质对话框）
internal suspend fun playSearchResultWithQuality(
    target: NeteaseSongSearchResult,
    quality: MusicQuality,
    playbackState: MusicPlaybackState,
    context: Context,
): Boolean {
    val url = resolvePlayUrlByQuality(context, target, quality) ?: return false
    playbackState.pendingQualityPlayTrackId = target.id + 1000000L
    playbackState.closeSearchResultsOnReady = true
    downloadAndPlay(context, playbackState, target, url)
    return true
}
