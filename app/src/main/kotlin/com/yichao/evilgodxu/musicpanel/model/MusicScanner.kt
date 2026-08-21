package com.yichao.evilgodxu.musicpanel

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.util.Size
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.log.CrashLogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// 本地音乐扫描器（基于 MediaStore）
object MusicScanner {

    // 封面来源，用于决定缓存文件归属：内嵌封面属于歌曲，专辑封面/缩略图属于专辑
    internal enum class AlbumArtSource { EMBEDDED, ALBUM, THUMBNAIL }

    internal data class AlbumArtResult(
        val bitmap: Bitmap,
        val source: AlbumArtSource,
    )

    suspend fun fromUri(context: Context, uri: Uri): MusicTrack? = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment ?: context.getString(R.string.music_scanner_external_music)
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?.takeIf { it.isNotBlank() } ?: context.getString(R.string.music_scanner_unknown_artist)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val id = -kotlin.math.abs(uri.toString().hashCode().toLong())
            val trackId = if (id == 0L) -1L else id
            // 提取内嵌封面写入本地缓存供面板显示，位图用完即回收
            var coverCachePath = ""
            retriever.embeddedPicture?.let { picture ->
                MusicMetadataCache.decodeSampledBitmap(picture)?.let { art ->
                    try {
                        coverCachePath = MusicMetadataCache.saveCover(context, trackId, art).orEmpty()
                    } finally {
                        art.recycle()
                    }
                }
            }
            MusicTrack(
                id = trackId,
                path = "",
                audioUri = uri.toString(),
                title = title,
                artist = artist,
                duration = duration,
                albumId = 0L,
                coverCachePath = coverCachePath
            )
        } catch (e: Exception) {
            CrashLogManager.logException("MusicScanner", "读取外部音频元数据失败", e)
            null
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                CrashLogManager.logException("MusicScanner", "释放元数据读取器失败", e)
            }
        }
    }

    // 扫描设备本地音乐文件，过滤时长 >= 30 秒的音频
    // 仅从 MediaStore 游标读取基础元数据，封面延迟加载，不阻塞扫描
    suspend fun scan(context: Context): List<MusicTrack> = withContext(Dispatchers.IO) {
        val tracks = mutableListOf<MusicTrack>()
        val contentResolver = context.contentResolver
        try {
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.IS_MUSIC,
            )
            val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND " +
                    "${MediaStore.Audio.Media.DURATION} >= 30000"
            contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                null,
                "${MediaStore.Audio.Media.TITLE} ASC"
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(MediaStore.Audio.Media._ID)
                val titleIdx = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE)
                val artistIdx = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST)
                val dataIdx = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                val durationIdx = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION)
                val albumIdIdx = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID)
                if (idIdx < 0 || titleIdx < 0) return@withContext tracks
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIdx)
                    val path = if (dataIdx >= 0) cursor.getString(dataIdx).orEmpty() else ""
                    val audioUri = ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        id
                    )
                    val title = cursor.getString(titleIdx)?.takeIf { it.isNotBlank() }
                        ?: path.substringAfterLast('/').substringBeforeLast('.').ifBlank { context.getString(R.string.music_scanner_unknown_song) }
                    val artist = if (artistIdx >= 0) {
                        cursor.getString(artistIdx)?.takeIf { it.isNotBlank() } ?: context.getString(R.string.music_scanner_unknown_artist)
                    } else context.getString(R.string.music_scanner_unknown_artist)
                    val duration = if (durationIdx >= 0) cursor.getLong(durationIdx) else 0L
                    val albumId = if (albumIdIdx >= 0) cursor.getLong(albumIdIdx) else 0L
                    tracks.add(
                        MusicTrack(
                            id = id,
                            path = path,
                            audioUri = audioUri.toString(),
                            title = title,
                            artist = artist,
                            duration = duration,
                            albumId = albumId
                        )
                    )
                }
            }
        } catch (e: Exception) {
            CrashLogManager.logException("MusicScanner", "扫描本地音乐失败", e)
        }
        tracks
    }

    internal fun loadAlbumArt(
        context: Context,
        contentResolver: ContentResolver,
        audioUri: Uri,
        albumId: Long,
        fallbackPath: String
    ): AlbumArtResult? {
        // 优先官方缩略图 API：从 MediaStore 缩略图缓存读取小图，最轻量且带系统缓存
        try {
            return AlbumArtResult(
                contentResolver.loadThumbnail(audioUri, Size(256, 256), null),
                AlbumArtSource.THUMBNAIL
            )
        } catch (e: Exception) {
            CrashLogManager.logException("MusicScanner", "加载缩略图封面失败", e)
        }
        extractEmbeddedArt(context, audioUri)?.let { return AlbumArtResult(it, AlbumArtSource.EMBEDDED) }
        if (albumId > 0) {
            try {
                val uri = Uri.parse("content://media/external/audio/albumart/$albumId")
                contentResolver.openInputStream(uri)?.use { input ->
                    MusicMetadataCache.decodeSampledBitmap(input.readBytes())
                        ?.let { return AlbumArtResult(it, AlbumArtSource.ALBUM) }
                }
            } catch (e: Exception) {
                CrashLogManager.logException("MusicScanner", "读取专辑封面失败", e)
            }
        }
        fallbackPath.takeIf { it.isNotBlank() }?.let { path ->
            extractEmbeddedArt(path)?.let { return AlbumArtResult(it, AlbumArtSource.EMBEDDED) }
        }
        return null
    }

    private fun extractEmbeddedArt(context: Context, audioUri: Uri): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, audioUri)
            retriever.embeddedPicture?.let { MusicMetadataCache.decodeSampledBitmap(it) }
        } catch (e: Exception) {
            CrashLogManager.logException("MusicScanner", "提取内嵌封面失败", e)
            null
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                CrashLogManager.logException("MusicScanner", "释放元数据读取器失败", e)
            }
        }
    }

    private fun extractEmbeddedArt(path: String): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            retriever.embeddedPicture?.let { MusicMetadataCache.decodeSampledBitmap(it) }
        } catch (e: Exception) {
            CrashLogManager.logException("MusicScanner", "提取内嵌封面失败", e)
            null
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                CrashLogManager.logException("MusicScanner", "释放元数据读取器失败", e)
            }
        }
    }
}

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
                withContext(Dispatchers.Main) {
                    val externalTracks = state.playlist.filter { it.path.isBlank() }
                    val previous = state.playlist.associateBy { normalizedUri(it.audioUri) }
                    val mergedTracks = (tracks + externalTracks)
                        .distinctBy { normalizedUri(it.audioUri) }
                        .map { track ->
                            val cached = previous[normalizedUri(track.audioUri)] ?: return@map track
                            track.copy(
                                neteaseId = cached.neteaseId,
                                neteaseCoverUrl = cached.neteaseCoverUrl,
                                coverCachePath = cached.coverCachePath,
                                lyricCachePath = cached.lyricCachePath,
                                lyricLines = cached.lyricLines
                            )
                        }
                    state.setSortedPlaylist(mergedTracks)
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

    private fun normalizedUri(audioUri: String): String =
        Uri.parse(audioUri)
            .normalizeScheme()
            .buildUpon()
            .clearQuery()
            .fragment(null)
            .build()
            .toString()

    private fun restoreCurrentTrack(state: MusicPlaybackState) {
        if (state.playlist.isEmpty() || state.currentTrack != null) return
        val savedUri = state.pendingSavedUri
        val index = savedUri?.let { uri -> state.playlist.indexOfFirst { it.audioUri == uri } }
            ?.takeIf { it >= 0 }
            ?: 0
        state.currentIndex = index
        state.currentTrack = state.playlist[index]
    }
}
