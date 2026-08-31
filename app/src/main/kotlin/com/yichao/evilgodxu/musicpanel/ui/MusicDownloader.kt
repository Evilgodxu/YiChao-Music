package com.yichao.evilgodxu.musicpanel

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.yichao.evilgodxu.log.CrashLogManager
import com.yichao.evilgodxu.musicpanel.MusicHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

// 在线歌曲缓存下载：流式下载到公共下载目录的媒体集合条目，完成后重定向播放源
internal suspend fun cacheToDownloads(
    context: Context,
    result: NeteaseSongSearchResult,
    url: String,
    trackId: Long,
    playbackState: MusicPlaybackState,
) {
    // 缓存进行中的曲目切歌后仍保留在播放列表，等待下载完成将索引指向本地文件
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
                // 复用已有缓存：仅把播放列表索引指向本地文件，当前播放仍保持在线流
                updateTrackAudioUri(playbackState, trackId, existingUri)
            }
            // 把在线播放时的标题/艺术家与封面原图写入缓存文件，供刷新后正确显示
            embedCachedMetadata(context, playbackState, trackId)
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

            // 下载集合用 Downloads + RELATIVE_PATH 写入公共下载目录，无写权限时 insert 直接失败，维持在线播放
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val contentValues = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, audioMimeType(extension))
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/YiChao/Audio")
            }
            val uri = context.contentResolver.insert(collection, contentValues)
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
        }
        // 缓存完成时播放源仍是在线流，文件未被播放占用，可安全整文件重写；
        // 将标题/艺术家与封面原图一次写入本地文件，刷新后不再丢失元数据
        embedCachedMetadata(context, playbackState, trackId)
        // 下载完成：提取封面/歌词展示缓存并清理冗余封面文件
        MetadataEnricher.enrichAndCleanup(context, playbackState)
    } catch (e: Exception) {
        CrashLogManager.logException("MusicDownloader", "缓存下载文件失败", e)
    } finally {
        playbackState.cacheInProgressIds.remove(trackId)
    }
}

internal fun sanitizeFileName(name: String): String {
    return name.replace(Regex("[/\\\\:*?\"<>|]"), "_")
        .take(80)
        .trim()
}

// 歌单同步批量下载：把在线曲目下载到公共下载目录并写入标题/艺术家/封面，
// 返回库内文件名（含扩展名）供刷新后按路径匹配入库；已存在或试听片段返回 null
internal suspend fun downloadTrackToLibrary(
    context: Context,
    result: NeteaseSongSearchResult,
    url: String,
    coverBytes: ByteArray? = null,
): String? = withContext(Dispatchers.IO) {
    try {
        val extension = url.substringBefore('?')
            .substringAfterLast('.', "")
            .lowercase()
            .takeIf { it in AUDIO_EXTENSIONS } ?: "mp3"
        val fileName = "${sanitizeFileName(result.title)} - ${sanitizeFileName(result.artist)}.$extension"
        if (findExistingDownload(context, fileName) != null) return@withContext fileName
        val tempFile = File.createTempFile("download", ".$extension", context.cacheDir)
        try {
            val request = Request.Builder().url(url).build()
            MusicHttpClient.client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                resp.body.byteStream().use { input ->
                    tempFile.outputStream().use { output -> input.copyTo(output, STREAM_BUFFER_SIZE) }
                }
            }
            // 试听片段(≤30秒)不缓存入库，与播放缓存规则一致
            if (isTrialAudioFile(tempFile)) return@withContext null
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val contentValues = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, audioMimeType(extension))
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/YiChao/Audio")
            }
            val uri = context.contentResolver.insert(collection, contentValues)
            if (uri == null) return@withContext null
            context.contentResolver.openOutputStream(uri)?.use { os ->
                tempFile.inputStream().use { input -> input.copyTo(os, STREAM_BUFFER_SIZE) }
            }
            // 只写本次同步下载的文件：写入标题/艺术家/封面并触发媒体库扫描
            val path = queryMediaPath(context, uri)
            if (path != null) {
                runCatching {
                    MusicMetadataWriter.writeMetadataToSource(
                        context,
                        MusicTrack(
                            id = 0L,
                            path = path,
                            audioUri = uri.toString(),
                            title = result.title,
                            artist = result.artist,
                            duration = 0L,
                            albumId = 0L,
                        ),
                        result.title,
                        result.artist,
                        coverBytes,
                    )
                }
                MediaScannerConnection.scanFile(context, arrayOf(path), null, null)
            }
            fileName
        } finally {
            tempFile.delete()
        }
    } catch (e: Exception) {
        CrashLogManager.logException("MusicDownloader", "歌单同步下载失败: 歌曲=${result.title}", e)
        null
    }
}

// 查询已写入媒体库条目的真实文件路径
private fun queryMediaPath(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0).takeIf { it.isNotBlank() } else null
    }
}.getOrNull()

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

// 查询公共下载目录下是否已存在同名缓存文件，命中时复用其 Uri
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
        CrashLogManager.logException("MusicDownloader", "查询已下载文件失败", e)
    }
    null
}

// 缓存完成后把曲目的播放地址指向本地文件
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

// 缓存完成后把在线播放时的标题/艺术家与封面原图写入本地文件；封面尚未就绪时跳过，交由兜底补全处理
private suspend fun embedCachedMetadata(
    context: Context,
    playbackState: MusicPlaybackState,
    trackId: Long,
) {
    val track = playbackState.playlist.firstOrNull { it.id == trackId } ?: return
    val bytes = MusicMetadataCache.loadCoverBytes(track.coverCachePath) ?: return
    try {
        MusicMetadataWriter.writeMetadataToSource(context, track, track.title, track.artist, bytes)
    } catch (e: Exception) {
        CrashLogManager.logException("MusicDownloader", "内嵌缓存元数据失败: 歌曲=${track.title}", e)
    }
}