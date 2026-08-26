package com.yichao.evilgodxu.musicpanel

import android.content.Context
import android.net.Uri
import com.yichao.evilgodxu.log.CrashLogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// 封面/歌词后台补全器：扫描/刷新/媒体变更后调用，串行执行避免并发覆盖
object MetadataEnricher {
    // 封面/歌词后台提取的并发上限：限制同时进行的位图解码与网络请求数量，
    // 避免大歌单首次启动时内存与 CPU 尖峰导致面板卡顿
    private val metadataDispatcher = Dispatchers.IO.limitedParallelism(4)
    // 封面/歌词后台提取的互斥锁：show / 刷新扫描 / 媒体变更 / 授权后扫描都会并发触发 enrich，
    // 不加锁会导致在线封面在本地封面尚未提交时抢先匹配，把有内嵌封面的歌永久变成在线封面
    private val enrichMutex = Mutex()

    suspend fun enrichAndCleanup(context: Context, playbackState: MusicPlaybackState) =
        enrichMutex.withLock {
            enrichPlaylistMetadata(context, playbackState)
            val referenced = withContext(Dispatchers.Main) {
                playbackState.playlist.flatMap { listOf(it.coverCachePath, it.lyricCachePath) }.toSet()
            }
            withContext(Dispatchers.IO) {
                MusicMetadataCache.cleanupOrphanedMetadata(context, referenced)
            }
            // 封面补全后刷新系统媒体面板的当前 MediaItem，避免封面就绪后仍显示空封面
            refreshCurrentMediaItem(playbackState)
        }

    private suspend fun enrichPlaylistMetadata(context: Context, playbackState: MusicPlaybackState) {
        // 先加载本地封面
        enrichLocalCovers(context, playbackState)
        val tracks = withContext(Dispatchers.Main) { playbackState.playlist.toList() }

        // 并行加载在线封面和歌词（两者互不依赖），合并后一次性更新
        val (coverUpdates, lyricUpdates) = coroutineScope {
            async { enrichOnlineCovers(context, tracks) } to
                async { enrichLyrics(context, tracks) }
        }.let { (c, l) -> c.await() to l.await() }

        val allUpdates = mergeCoverAndLyricUpdates(coverUpdates, lyricUpdates)
        if (allUpdates.isEmpty()) return
        withContext(Dispatchers.Main) {
            playbackState.batchUpdateTracks(allUpdates)
        }
    }

    /** 后台加载本地歌曲封面（从 MediaStore 提取），不阻塞主流程 */
    private suspend fun enrichLocalCovers(context: Context, playbackState: MusicPlaybackState) {
        val tracks = withContext(Dispatchers.Main) { playbackState.playlist.toList() }
        // 本地音频即使带自动匹配的在线封面也允许重试本地提取，修复在线封面文件缺失/损坏导致的永久在线匹配；
        // 纯在线歌曲（无本地文件）不参与本地提取，沿用在线封面
        val needCover = tracks.filter { track ->
            val path = track.coverCachePath
            // 封面缓存按内容哈希命名且文件有效才视为已具备封面并跳过提取；
            // 旧版按歌曲 id 命名的缓存不匹配，重新提取时自动迁移为哈希命名（同图去重）
            val coverOwned = MusicMetadataCache.isValid(path) &&
                MusicMetadataCache.isHashKeyFileName(path)
            // 有可提取内嵌封面的本地音频源才尝试提取：本地文件路径或 MediaStore 本地文件（在线缓存歌）均纳入
            !coverOwned && (track.path.isNotBlank() || isLocalFileUri(track.audioUri))
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
                            val coverPath = MusicMetadataCache.saveCover(context, track.id, cover).orEmpty()
                            // 旧文件若已无引用，由 cleanupOrphanedMetadata 统一回收，避免误删被共享的封面
                            track.copy(coverCachePath = coverPath)
                        } finally {
                            cover.recycle()
                        }
                    } catch (e: Exception) {
                        CrashLogManager.logException(
                            "MetadataEnricher",
                            "提取本地封面失败: 歌曲=${track.title} - ${track.artist} 路径=${track.path}",
                            e
                        )
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

    // 是否为可读取本地音频文件（本地文件路径或 MediaStore 本地文件），区分纯在线流媒体资源
    private fun isLocalFileUri(uri: String): Boolean = runCatching {
        val scheme = Uri.parse(uri).scheme
        scheme == "content" || scheme == "file"
    }.getOrDefault(false)

    /** 后台加载在线封面，返回封面更新列表 */
    private suspend fun enrichOnlineCovers(context: Context, tracks: List<MusicTrack>): List<MusicTrack> {
        val needCover = tracks.filter { track ->
            // 旧版按歌曲 id 命名的缓存也纳入重匹配，落盘时自动迁移为内容哈希命名
            !MusicMetadataCache.isValid(track.coverCachePath) ||
                !MusicMetadataCache.isHashKeyFileName(track.coverCachePath)
        }
        if (needCover.isEmpty()) return emptyList()
        return coroutineScope {
            needCover.map { track ->
                async(metadataDispatcher) {
                    try {
                        var matchedId = track.neteaseId
                        var matchedUrl = track.neteaseCoverUrl
                        // 优先复用已保存的封面 URL 直连下载，避免缓存完成场景下重复网络匹配；
                        // URL 为空或下载失败时才按标题匹配兜底（下载失败保留已有信息，下次重试）
                        val coverBytes = matchedUrl.takeIf { it.isNotBlank() }
                            ?.let { NeteaseMusicApi.loadCoverBytes(it) }
                            ?: run {
                                val match = NeteaseMusicApi.match(track.title, track.artist, track.duration)
                                    ?: return@async null
                                matchedId = match.id
                                matchedUrl = match.coverUrl.orEmpty()
                                NeteaseMusicApi.loadCoverBytes(match.coverUrl.orEmpty())
                            } ?: return@async null
                        // 优先把匹配到的封面写入音频文件元数据，同时保留独立封面缓存：
                        // 音频文件内嵌封面无法被 MediaItem 引用，系统媒体面板只能通过
                        // coverCachePath 对应的 content:// URI 读取封面
                        if (MusicMetadataWriter.writeCoverToSource(context, track, coverBytes)) {
                            val coverPath = MusicMetadataCache.saveCover(context, matchedId, coverBytes).orEmpty()
                            track.copy(
                                neteaseId = matchedId,
                                neteaseCoverUrl = matchedUrl,
                                coverCachePath = coverPath
                            )
                        } else {
                            val coverPath = MusicMetadataCache.saveCover(context, matchedId, coverBytes).orEmpty()
                            if (coverPath.isBlank()) return@async null
                            track.copy(
                                neteaseId = matchedId,
                                neteaseCoverUrl = matchedUrl,
                                coverCachePath = coverPath
                            )
                        }
                    } catch (e: Exception) {
                        CrashLogManager.logException(
                            "MetadataEnricher",
                            "获取在线封面失败: 歌曲=${track.title} - ${track.artist} 路径=${track.path}",
                            e
                        )
                        null
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }

    /** 后台加载在线歌词，返回歌词更新列表 */
    private suspend fun enrichLyrics(context: Context, tracks: List<MusicTrack>): List<MusicTrack> {
        val needLyrics = tracks.filter { track ->
            when {
                // 已有缓存文件直接沿用
                MusicMetadataCache.isValid(track.lyricCachePath) -> false
                // 无缓存：仅在缺歌词时拉取
                else -> track.lyricLines.isEmpty()
            }
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
                        // 自动补全仅缓存歌词文件，不写音频元数据（元数据只由在线播放流程写入）
                        val lyricPath = MusicMetadataCache.saveLyrics(context, track.title, track.artist, lyric.lines).orEmpty()
                        track.copy(lyricCachePath = lyricPath, lyricLines = lyric.lines)
                    } catch (e: Exception) {
                        CrashLogManager.logException(
                            "MetadataEnricher",
                            "获取在线歌词失败: 歌曲=${track.title} - ${track.artist} 路径=${track.path}",
                            e
                        )
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
}
