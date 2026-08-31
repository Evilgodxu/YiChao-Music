package com.yichao.evilgodxu.musicpanel

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.yichao.evilgodxu.log.CrashLogManager
import java.io.File
import java.io.FileInputStream

// 本地音频格式信息读取：解码头未给出或冷启动未播放时，直接读文件元数据补齐
internal object TrackAudioInfoReader {

    // 读取真实比特率（kbps）：优先媒体元数据，其次按文件大小/时长估算平均比特率
    fun readBitrateKbps(context: Context, track: MusicTrack): Int? {
        val retriever = MediaMetadataRetriever()
        try {
            setDataSource(retriever, context, track)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                ?.toLongOrNull()
                ?.takeIf { it > 0 }
                ?.let { return (it / 1000).toInt().coerceAtLeast(1) }
        } catch (e: Exception) {
            CrashLogManager.logException("TrackAudioInfoReader", "读取曲目比特率失败", e)
        } finally {
            runCatching { retriever.release() }
        }
        return estimateAverageBitrateKbps(context, track)
    }

    // 冷启动未播放时预填的格式信息：采样率取媒体元数据，位深 FLAC 解析 STREAMINFO，其余按 16，声道按立体声
    fun readIdleFormat(context: Context, track: MusicTrack): AudioSignalPathFormat? {
        if (!track.isLocalAudioSource) return null
        val formatName = track.path
            .substringAfterLast('.', "")
            .uppercase()
            .takeIf { it.isNotBlank() }
            ?: readMimeFormat(context, track)
            ?: return null
        val sampleRate = readSampleRate(context, track) ?: 0
        val bitrateKbps = readBitrateKbps(context, track) ?: 0
        if (sampleRate <= 0 && bitrateKbps <= 0) return null
        return AudioSignalPathFormat(
            format = formatName,
            sampleRate = sampleRate,
            outputRate = sampleRate,
            bitDepth = if (formatName == "FLAC") readFlacBitDepth(context, track) ?: 16 else 16,
            channels = 2,
            bitrate = bitrateKbps,
        )
    }

    // mime 映射为展示用格式名；已知格式统一命名，其余取 mime 尾段
    fun mimeToFormatName(mime: String?): String? = when (mime) {
        "audio/mpeg" -> "MP3"
        "audio/flac" -> "FLAC"
        "audio/wav", "audio/x-wav" -> "WAV"
        "audio/ogg" -> "OGG"
        "audio/mp4", "audio/aac" -> "AAC"
        null -> null
        else -> mime.substringAfterLast('/').uppercase().takeIf { it.isNotBlank() }
    }

    private fun readSampleRate(context: Context, track: MusicTrack): Int? {
        val retriever = MediaMetadataRetriever()
        return try {
            setDataSource(retriever, context, track)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)
                ?.toIntOrNull()
                ?.takeIf { it > 0 }
        } catch (e: Exception) {
            CrashLogManager.logException("TrackAudioInfoReader", "读取采样率失败", e)
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun readMimeFormat(context: Context, track: MusicTrack): String? {
        val retriever = MediaMetadataRetriever()
        return try {
            setDataSource(retriever, context, track)
            mimeToFormatName(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE))
        } catch (e: Exception) {
            CrashLogManager.logException("TrackAudioInfoReader", "读取音频类型失败", e)
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun setDataSource(retriever: MediaMetadataRetriever, context: Context, track: MusicTrack) {
        if (track.path.isNotBlank()) {
            retriever.setDataSource(track.path)
        } else {
            retriever.setDataSource(context, Uri.parse(track.audioUri))
        }
    }

    // 估算平均比特率：文件大小 × 8 ÷ 时长（秒）÷ 1000
    private fun estimateAverageBitrateKbps(context: Context, track: MusicTrack): Int? {
        val sizeBytes: Long? = if (track.path.isNotBlank()) {
            File(track.path).takeIf { it.isFile }?.length()
        } else if (track.audioUri.startsWith("content:") || track.audioUri.startsWith("file:")) {
            runCatching {
                Uri.parse(track.audioUri).let {
                    context.contentResolver.openFileDescriptor(it, "r")?.use { fd -> fd.statSize }
                }
            }.getOrNull()
        } else {
            null
        }
        val durationSec = track.duration / 1000
        if (sizeBytes == null || sizeBytes <= 0 || durationSec <= 0) return null
        return (sizeBytes * 8 / durationSec / 1000).toInt().takeIf { it > 0 }
    }

    // 解析 FLAC STREAMINFO（fLaC + 块头 + 34 字节流信息）中的位深
    private fun readFlacBitDepth(context: Context, track: MusicTrack): Int? = runCatching {
        val bytes = ByteArray(42)
        val input = if (track.path.isNotBlank()) {
            FileInputStream(track.path)
        } else {
            context.contentResolver.openInputStream(Uri.parse(track.audioUri))
        } ?: return@runCatching null
        val read = input.use { it.read(bytes) }
        if (read < 42 || !bytes.copyOfRange(0, 4).contentEquals(byteArrayOf(0x66, 0x4C, 0x61, 0x43))) {
            return@runCatching null
        }
        // 流信息 8 字节位域：采样率 20 位 + 声道 3 位 + 位深 5 位 + 总采样 36 位
        (((bytes[12].toInt() and 1) shl 4) or ((bytes[13].toInt() and 0xF0) ushr 4)) + 1
    }.getOrNull()
}