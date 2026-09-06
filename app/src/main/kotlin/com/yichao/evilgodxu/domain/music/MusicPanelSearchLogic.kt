package com.yichao.evilgodxu.domain.music

import android.content.Context
import android.net.Uri
import com.yichao.evilgodxu.data.music.api.KugouMusicApi
import com.yichao.evilgodxu.data.music.api.KuwoMusicApi
import com.yichao.evilgodxu.data.music.api.MiguMusicApi
import com.yichao.evilgodxu.data.music.api.MusicQuality
import com.yichao.evilgodxu.data.music.api.NeteaseMusicApi
import com.yichao.evilgodxu.data.music.api.OnlineMusicSource
import com.yichao.evilgodxu.data.music.api.QQMusicApi
import com.yichao.evilgodxu.data.music.api.sourceOf
import com.yichao.evilgodxu.data.music.metadata.MetadataEnricher
import com.yichao.evilgodxu.data.music.metadata.MusicMetadataCache
import com.yichao.evilgodxu.data.music.metadata.MusicMetadataWriter
import com.yichao.evilgodxu.data.music.model.MusicSearchSource
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.data.music.model.NeteaseSongSearchResult
import com.yichao.evilgodxu.data.music.proxy.ProxySourceEngine
import com.yichao.evilgodxu.log.CrashLogManager
import com.yichao.evilgodxu.R
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// 搜索结果每页条数：首页 20 条，列表滑到底部后再加载下一页
private const val SEARCH_PAGE_SIZE = 20

// 代理音源一次拉取的条数上限：多数代理不支持分页，一次拿全量后本地按页切分
private const val PROXY_FETCH_COUNT = 60

// 单来源单次查询候选：每来源对每条查询各取前 10 条，单源失败不影响其它查询
private suspend fun searchSourceCandidates(source: OnlineMusicSource, query: String): List<NeteaseSongSearchResult> =
    runCatching { source.search(query, page = 1, pageSize = 10).take(10) }.getOrDefault(emptyList())

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

// 面板动作入口：UI 统一调用这些扩展方法，内部在面板级作用域编排后台任务，不直接持协程
internal fun MusicPanelUiState.searchLyricsCandidates(track: MusicTrack, source: MusicSearchSource) {
    val ui = this
    panelScope.launch {
        ui.isLyricsSearching = true
        ui.lyricsCandidates = emptyList()
        ui.lyricsRefreshError = null
        try {
            ui.lyricsCandidates = searchSingleSourceCandidates(sourceOf(source), track.title, track.artist)
                .filter { matchesTrackTitle(track.title, it) }
                .take(30)
        } catch (e: Exception) {
            CrashLogManager.logException("MusicPanelSearchLogic", "搜索歌词候选失败: 歌曲=${track.title}", e)
            ui.lyricsCandidates = emptyList()
        } finally {
            ui.isLyricsSearching = false
        }
    }
}

internal suspend fun applyLyricsCandidate(
    context: Context,
    ui: MusicPanelUiState,
    controller: PlaybackController,
    track: MusicTrack,
    candidate: NeteaseSongSearchResult,
): Boolean {
    ui.isLyricsRefreshing = true
    ui.lyricsRefreshError = null
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
                neteaseCoverUrl = candidate.coverUrl.orEmpty(),
                // 已获取到歌词，清除此前的匹配失败标记，避免重启后缓存恢复被挡住
                lyricFailed = false,
            )
        } ?: return false
        withContext(Dispatchers.Main) {
            controller.updateTrack(updated)
        }
        true
    } catch (e: Exception) {
        CrashLogManager.logException("MusicPanelSearchLogic", "应用歌词候选失败: 歌曲=${track.title} 路径=${track.path}", e)
        false
    } finally {
        withContext(Dispatchers.Main) {
            ui.isLyricsRefreshing = false
        }
    }
}

// 导入本地 LRC 歌词文件：解析后写入歌词缓存并应用到当前曲目
internal suspend fun applyLocalLyrics(
    context: Context,
    controller: PlaybackController,
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
            track.copy(lyricCachePath = path, lyricLines = lines, lyricFailed = false)
        } ?: return false
        withContext(Dispatchers.Main) {
            controller.updateTrack(updated)
        }
        true
    } catch (e: Exception) {
        CrashLogManager.logException("MusicPanelSearchLogic", "导入本地歌词失败: 歌曲=${track.title}", e)
        false
    }
}

// 编辑歌词行原文：整行按增强 LRC 文本（时间戳/逐字/翻译）重新解析后替换原行，写回缓存并刷新
internal suspend fun applyLyricsLineEdit(
    context: Context,
    controller: PlaybackController,
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
            track.copy(lyricCachePath = path, lyricLines = sorted, lyricFailed = false)
        } ?: return false
        withContext(Dispatchers.Main) {
            controller.updateTrack(updated)
        }
        true
    } catch (e: Exception) {
        CrashLogManager.logException("MusicPanelSearchLogic", "编辑歌词原文失败: 歌曲=${track.title}", e)
        false
    }
}

internal fun MusicPanelUiState.searchCoverCandidates(track: MusicTrack, source: MusicSearchSource) {
    val ui = this
    panelScope.launch {
        ui.isCoverSearching = true
        ui.coverCandidates = emptyList()
        try {
            ui.coverCandidates = searchSingleSourceCandidates(sourceOf(source), track.title, track.artist)
                .filter { matchesTrackTitle(track.title, it) && !it.coverUrl.isNullOrBlank() }
                .take(30)
        } catch (e: Exception) {
            CrashLogManager.logException("MusicPanelSearchLogic", "搜索封面候选失败: 歌曲=${track.title}", e)
            ui.coverCandidates = emptyList()
        } finally {
            ui.isCoverSearching = false
        }
    }
}

// 无损升级候选：代理音源优先，失败或未配置时回退内置搜索；
// 按标题/歌手搜索在线原曲，供用户确认选中后下载无损版本替换本地文件
internal fun MusicPanelUiState.searchLosslessUpgradeCandidates(context: Context, track: MusicTrack, source: MusicSearchSource) {
    val ui = this
    panelScope.launch {
        ui.isLosslessUpgradeSearching = true
        ui.losslessUpgradeCandidates = emptyList()
        ui.losslessUpgradeError = null
        try {
            val keyword = listOf(track.title, track.artist).filter { it.isNotBlank() }.joinToString(" ")
            val proxyResults = runCatching {
                ProxySourceEngine.search(context, source, keyword, page = 1, pageSize = 30)
            }.getOrNull()
            val candidates = if (proxyResults.isNullOrEmpty()) {
                searchSingleSourceCandidates(sourceOf(source), track.title, track.artist)
            } else proxyResults
            ui.losslessUpgradeCandidates = candidates
                .filter { matchesTrackTitle(track.title, it) }
                .take(30)
        } catch (e: Exception) {
            CrashLogManager.logException("MusicPanelSearchLogic", "搜索无损升级候选失败: 歌曲=${track.title}", e)
            ui.losslessUpgradeCandidates = emptyList()
        } finally {
            ui.isLosslessUpgradeSearching = false
        }
    }
}

internal suspend fun applyCoverCandidate(
    context: Context,
    ui: MusicPanelUiState,
    controller: PlaybackController,
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
            controller.updateTrack(updated)
            controller.bumpCoverRevision()
            ui.coverCandidates = emptyList()
        }
        true
    } catch (e: Exception) {
        CrashLogManager.logException("MusicPanelSearchLogic", "应用封面候选失败: 歌曲=${track.title} 路径=${track.path}", e)
        false
    }
}

// 在线搜索：面板级作用域执行，取消上一次未完成的搜索与分页加载
internal fun MusicPanelUiState.performSearch(context: Context) {
    val ui = this
    panelScope.launch {
        val query = ui.searchQuery.trim()
        if (query.isBlank()) return@launch
        // 取消上一次未完成的搜索与分页加载，避免过期响应覆盖新查询结果
        ui.searchJob?.cancel()
        ui.searchLoadJob?.cancel()
        ui.searchJob = currentCoroutineContext()[Job] ?: return@launch
        ui.isSearching = true
        ui.searchResults = emptyList()
        ui.searchPending = emptyList()
        ui.searchPendingFull = false
        core.updateErrorMsg(null)
        // 重置分页状态，从第一页开始
        ui.searchPage = 0
        ui.hasMoreSearchResults = true
        ui.isLoadingMore = false
        // 立即切到结果视图，使加载指示器在搜索期间可见
        ui.showSearchResults = true
        try {
            // 代理音源一次拉取全量（多数代理不支持分页），本地按页切分展示
            val proxyResults = ProxySourceEngine.search(
                context,
                ui.searchSource,
                query,
                page = 1,
                pageSize = PROXY_FETCH_COUNT,
            )
            if (proxyResults != null) {
                // 代理音源可能返回重复条目（同一首歌多种音质/hash 相同），按 source+id 去重防止列表 key 冲突
                val deduped = proxyResults.distinctBy { it.source to it.id }
                ui.searchResults = deduped.take(SEARCH_PAGE_SIZE)
                ui.searchPending = deduped.drop(SEARCH_PAGE_SIZE)
                ui.searchPendingFull = proxyResults.size >= PROXY_FETCH_COUNT
                ui.hasMoreSearchResults =
                    ui.searchPending.isNotEmpty() || ui.searchPendingFull
            } else {
                // 内置平台按页请求，首屏一页
                val results = runCatching { sourceOf(ui.searchSource).search(query, 1, SEARCH_PAGE_SIZE) }
                    .getOrDefault(emptyList())
                ui.searchResults = results.distinctBy { it.source to it.id }
                ui.hasMoreSearchResults = results.size >= SEARCH_PAGE_SIZE
            }
            ui.searchPage = 1
            if (ui.searchResults.isNotEmpty()) ui.addSearchHistory(query)
            ui.showSearchResults = true
            // 代理搜索结果的封面为逐条经 pic 动作换取，后台渐进补齐
            if (ui.searchResults.isNotEmpty()) fillProxySearchCovers(ui, core, context)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 搜索界面退出导致的协程取消，不是失败，向上传递取消
            throw e
        } catch (e: Exception) {
            CrashLogManager.logException("MusicPanelSearchLogic", "搜索歌曲失败", e)
            ui.searchResults = emptyList()
        } finally {
            // 仅当前搜索协程复位搜索状态，避免被取消的旧协程提前清掉新搜索的加载态
            if (ui.searchJob == currentCoroutineContext()[Job]) {
                ui.isSearching = false
            }
        }
    }
}

// 分页获取搜索结果：代理音源优先，失败或未配置时回退内置平台
private suspend fun fetchSearchPage(
    ui: MusicPanelUiState,
    context: Context,
    query: String,
    page: Int,
): List<NeteaseSongSearchResult> {
    val proxyResults = ProxySourceEngine.search(
        context,
        ui.searchSource,
        query,
        page = page,
        pageSize = SEARCH_PAGE_SIZE,
    )
    return if (proxyResults != null) {
        proxyResults
    } else {
        runCatching { sourceOf(ui.searchSource).search(query, page, SEARCH_PAGE_SIZE) }
            .getOrDefault(emptyList())
    }.distinctBy { it.source to it.id }
}

// 上拉加载下一页：优先消费代理全量缓冲，缓冲耗尽或内置平台再请求下一页
internal fun MusicPanelUiState.loadMoreSearchResults(context: Context) {
    val ui = this
    panelScope.launch {
        if (ui.isLoadingMore || ui.isSearching || !ui.hasMoreSearchResults) return@launch
        if (ui.searchResults.isEmpty()) return@launch
        // 代理音源全量缓冲：本地切分追加，无需重复请求（不支持分页的代理每次返回相同结果）
        if (ui.searchPending.isNotEmpty()) {
            // 缓冲内仍可能与已加载条目重复，追加前按 source+id 去重
            val existingKeys = ui.searchResults.map { it.source to it.id }.toMutableSet()
            val batch = ui.searchPending.take(SEARCH_PAGE_SIZE).filter { item ->
                existingKeys.add(item.source to item.id)
            }
            ui.searchResults = ui.searchResults + batch
            ui.searchPending = ui.searchPending.drop(SEARCH_PAGE_SIZE)
            ui.searchPage++
            ui.hasMoreSearchResults =
                ui.searchPending.isNotEmpty() || ui.searchPendingFull
            if (batch.isNotEmpty()) fillProxySearchCovers(ui, core, context)
            return@launch
        }
        val query = ui.searchQuery.trim()
        if (query.isBlank()) return@launch
        ui.searchLoadJob = currentCoroutineContext()[Job] ?: return@launch
        ui.isLoadingMore = true
        try {
            val nextPage = ui.searchPage + 1
            val pageResults = fetchSearchPage(ui, context, query, page = nextPage)
            // 新搜索已取代本次加载时丢弃过期分页
            if (ui.searchLoadJob != currentCoroutineContext()[Job]) return@launch
            val existingKeys = ui.searchResults.map { it.source to it.id }.toSet()
            val newItems = pageResults.filter { (it.source to it.id) !in existingKeys }
            if (newItems.isEmpty()) {
                // 本页无新增条目（全部与已加载重复）时视为已加载完全部结果，避免重复请求
                ui.hasMoreSearchResults = false
                return@launch
            }
            ui.searchResults = ui.searchResults + newItems
            ui.searchPage = nextPage
            ui.hasMoreSearchResults = pageResults.size >= SEARCH_PAGE_SIZE
            // 代理搜索结果的封面为逐条经 pic 动作换取，后台渐进补齐
            if (newItems.isNotEmpty()) fillProxySearchCovers(ui, core, context)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 新搜索发起导致的分页协程取消，不是失败，向上传递取消
            throw e
        } catch (e: Exception) {
            CrashLogManager.logException("MusicPanelSearchLogic", "加载更多搜索结果失败", e)
        } finally {
            ui.isLoadingMore = false
            ui.searchLoadJob = null
        }
    }
}

// 代理搜索结果的封面为逐条经 pic 动作换取：串行补齐前 N 条，控制聚合接口调用频率
private fun fillProxySearchCovers(ui: MusicPanelUiState, playbackState: MusicPlaybackState, context: Context) {
    val pending = ui.searchResults
        .filter { it.coverUrl.isNullOrBlank() && !it.coverId.isNullOrBlank() }
        .take(MAX_PROXY_COVER_FILL)
    if (pending.isEmpty()) return
    playbackState.playbackScope.launch {
        pending.forEach { result ->
            val url = ProxySourceEngine.coverUrl(context, result) ?: return@forEach
            withContext(Dispatchers.Main) {
                val index = ui.searchResults.indexOfFirst { it.id == result.id }
                if (index >= 0) {
                    val list = ui.searchResults.toMutableList()
                    list[index] = result.copy(coverUrl = url)
                    ui.searchResults = list
                }
            }
        }
    }
}

// 代理搜索结果封面逐条换取的条数上限
private const val MAX_PROXY_COVER_FILL = 8

// 在线结果加入播放列表并开始播放：追加曲目、后台加载歌词/封面并缓存到下载目录
private suspend fun MusicPanelUiState.downloadAndPlay(
    context: Context,
    result: NeteaseSongSearchResult,
    url: String,
) {
    val playbackState = core
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
                    // updateTrack 同步回写并持久化引用：仅改内存态会丢失持久化引用，
                    // 进程被杀后重启清理会把刚落盘的歌词缓存当作孤儿误删
                    val track = playbackState.playlist.firstOrNull { it.id == trackId } ?: return@withContext
                    playbackState.updateTrack(track.copy(lyricCachePath = lyricPath, lyricLines = lines))
                }
            }
        } catch (e: Exception) {
            CrashLogManager.logException("MusicPanelSearchLogic", "获取在线歌词失败", e)
        }
    }

    // 在线播放时同步下载封面原图落盘：缓存完成后可直接内嵌写入本地文件，面板与通知栏也即时获得本地封面。
    // 封面就绪前标记为下载中，缓存完成流程等待本协程结束再内嵌元数据，避免封面丢失
    val coverJob = playbackState.playbackScope.launch(Dispatchers.IO) {
        MetadataEnricher.markOnlineCoverInFlight(trackId)
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
                // updateTrack 同步回写并持久化引用：仅改内存态会丢失持久化引用，
                // 进程被杀后重启清理会把刚落盘的封面缓存当作孤儿误删
                val track = playbackState.playlist.firstOrNull { it.id == trackId } ?: return@withContext
                playbackState.updateTrack(track.copy(coverCachePath = coverPath))
            }
            // 封面就绪后刷新系统媒体面板的当前 MediaItem
            refreshCurrentMediaItem(playbackState)
        } catch (e: Exception) {
            CrashLogManager.logException("MusicPanelSearchLogic", "下载在线封面失败: 歌曲=${result.title}", e)
        } finally {
            MetadataEnricher.clearOnlineCoverInFlight(trackId)
        }
    }

    playbackState.playbackScope.launch(Dispatchers.IO) {
        cacheToDownloads(context, result, url, trackId, playbackState, coverJob)
    }
}

internal fun normalizeTitle(value: String): String {
    return value.lowercase()
        .replace(Regex("""[\s　（）()\[\]【】「」『』《》〈〉、，。！？"'""'']+"""), "")
        .trim()
}

internal suspend fun enrichOnlineMetadata(
    context: Context,
    controller: PlaybackController,
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
            // updateTrack 同步回写并持久化引用，避免进程被杀后重启清理误删刚下载的歌词缓存
            val track = controller.playlist.firstOrNull { it.id == track.id } ?: return@withContext
            controller.updateTrack(
                track.copy(
                    neteaseId = result.id,
                    neteaseCoverUrl = result.coverUrl.orEmpty(),
                    lyricCachePath = lyricPath,
                    lyricLines = lyric.lines
                )
            )
        }
    } catch (e: Exception) {
        CrashLogManager.logException("MusicPanelSearchLogic", "获取在线元数据失败", e)
    }
}

// 本地曲库命中同曲时直接播放；命中返回 true
internal suspend fun MusicPanelUiState.tryPlayLocalMatch(
    target: NeteaseSongSearchResult,
    context: Context,
): Boolean {
    val playbackState = core
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
        playbackState.playbackScope.launch {
            enrichOnlineMetadata(context, playbackState, localMatch, target)
        }
    }
    playbackState.updateErrorMsg(null)
    playbackState.currentIndex = idx
    playbackState.currentTrack = playbackState.playlist[idx]
    isSearchMode = false
    showSearchResults = false
    searchQuery = ""
    searchResults = emptyList()
    searchPending = emptyList()
    searchPendingFull = false
    playTrackAt(context, playbackState, idx)
    return true
}

// 播放搜索结果：优先本地命中，否则解析直链并加入播放列表播放
internal suspend fun MusicPanelUiState.playSearchResult(
    target: NeteaseSongSearchResult,
    context: Context,
) {
    val ui = this
    val playbackState = core
    if (tryPlayLocalMatch(target, context)) return

    ui.pendingSearchResults = emptyList()

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
        playbackState.updateErrorMsg(null)
        ui.closeSearchResultsOnReady = true
        downloadAndPlay(context, playTarget, url)
    } else {
        playbackState.updateErrorMsg(context.getString(R.string.music_panel_play_error))
        ui.pendingSearchResults = emptyList()
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
internal suspend fun MusicPanelUiState.playSearchResultWithQuality(
    target: NeteaseSongSearchResult,
    quality: MusicQuality,
    context: Context,
): Boolean {
    val url = resolvePlayUrlByQuality(context, target, quality) ?: return false
    pendingQualityPlayTrackId = target.id + 1000000L
    closeSearchResultsOnReady = true
    downloadAndPlay(context, target, url)
    return true
}
