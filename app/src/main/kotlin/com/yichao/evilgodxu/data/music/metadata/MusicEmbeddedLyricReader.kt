package com.yichao.evilgodxu.data.music.metadata

import android.content.Context
import android.net.Uri
import com.yichao.evilgodxu.data.music.model.LyricLine
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.log.CrashLogManager
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// 本地音频内嵌歌词读取：解析 MP3(ID3v2 USLT)、FLAC/OGG(Vorbis 注释 LYRICS)、M4A(©lyr) 中的歌词文本，
// 统一按增强 LRC 解析为时间轴歌词；非本地音频源或无内嵌歌词时返回空列表
internal object MusicEmbeddedLyricReader {

    // 非 MP3 容器读取的头部字节上限（FLAC/OGG 注释与 M4A moov 均位于文件头部附近）
    private const val HEADER_CAP = 512 * 1024
    // MP3 ID3v2 标签大小上限（含封面等大帧，歌词 USLT 帧通常位于标签前部）
    private const val MAX_MP3_TAG = 4 * 1024 * 1024

    suspend fun read(context: Context, track: MusicTrack): List<LyricLine> = withContext(Dispatchers.IO) {
        val input = openInput(context, track) ?: return@withContext emptyList()
        try {
            input.use { stream ->
                // 先读 10 字节判断是否带 ID3 头：MP3 标签可能含大封面帧，按声明的标签尺寸精确读取
                val prefix = readPrefix(stream, 10)
                val text = if (prefix.size >= 10 && prefix.startsWith("ID3")) {
                    val tagSize = syncsafe(prefix, 6)
                    if (tagSize > 0) {
                        val tag = prefix + readPrefix(stream, (tagSize + 10 - prefix.size).coerceAtMost(MAX_MP3_TAG))
                        extractMp3Lyrics(tag)
                    } else {
                        null
                    }
                } else {
                    val bytes = prefix + readPrefix(stream, HEADER_CAP - prefix.size)
                    extractLyrics(bytes)
                }
                MusicMetadataCache.parseLyricsText(text.orEmpty())
            }
        } catch (e: Exception) {
            CrashLogManager.logException(
                "MusicEmbeddedLyricReader",
                "读取内嵌歌词失败: 歌曲=${track.title} 路径=${track.path}",
                e
            )
            emptyList()
        }
    }

    private fun openInput(context: Context, track: MusicTrack): InputStream? =
        if (track.path.isNotBlank()) {
            runCatching { FileInputStream(track.path) }.getOrNull()
        } else if (track.audioUri.startsWith("content:") || track.audioUri.startsWith("file:")) {
            runCatching { context.contentResolver.openInputStream(Uri.parse(track.audioUri)) }.getOrNull()
        } else {
            null
        }

    private fun readPrefix(stream: InputStream, maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var remaining = maxBytes
        while (remaining > 0) {
            val read = stream.read(buffer, 0, minOf(buffer.size, remaining))
            if (read < 0) break
            out.write(buffer, 0, read)
            remaining -= read
        }
        return out.toByteArray()
    }

    // 按容器格式提取内嵌歌词文本，未找到返回 null
    private fun extractLyrics(bytes: ByteArray): String? = when {
        isMp4(bytes) -> extractMp4Lyrics(bytes)
        isFlac(bytes) -> extractFlacLyrics(bytes)
        isOgg(bytes) -> extractOggLyrics(bytes)
        else -> null
    }

    private fun isFlac(bytes: ByteArray) = bytes.startsWith("fLaC")
    private fun isOgg(bytes: ByteArray) = bytes.startsWith("OggS")
    private fun isMp4(bytes: ByteArray) = bytes.startsWith("ftyp", 4)

    // FLAC：遍历元数据块，取 VORBIS_COMMENT(4) 块中的 LYRICS 字段
    private fun extractFlacLyrics(bytes: ByteArray): String? {
        var p = 4
        while (p + 4 <= bytes.size) {
            val header = bytes[p].toInt() and 0xff
            val type = header and 0x7f
            val length = (bytes[p + 1].toInt() and 0xff shl 16) or
                (bytes[p + 2].toInt() and 0xff shl 8) or (bytes[p + 3].toInt() and 0xff)
            p += 4
            if (p + length > bytes.size) return null
            if (type == 4) return parseVorbisLyrics(bytes, p, length)
            p += length
            if (header and 0x80 != 0) break
        }
        return null
    }

    // OGG(Opus/Vorbis)：定位注释包标记后解析其后的 Vorbis 注释结构
    private fun extractOggLyrics(bytes: ByteArray): String? {
        val opus = bytes.indexOfAscii("OpusTags")
        val vorbis = bytes.indexOfAscii("vorbis_comment")
        val (start, headerLength) = when {
            opus >= 0 -> opus to "OpusTags".length
            vorbis >= 0 -> vorbis to "vorbis_comment".length
            else -> return null
        }
        return parseVorbisLyrics(bytes, start + headerLength, bytes.size - start - headerLength)
    }

    // Vorbis 注释：LE 长度的 vendor + 字段数 + "KEY=value" 字段；优先 LYRICS，其次 SYNCED/UNSYNCEDLYRICS
    private fun parseVorbisLyrics(data: ByteArray, offset: Int, length: Int): String? {
        var p = offset
        val end = (offset + length).coerceAtMost(data.size)
        if (p + 4 > end) return null
        val vendorLength = intLE(data, p)
        if (vendorLength < 0 || p + 4 + vendorLength > end) return null
        p += 4 + vendorLength
        if (p + 4 > end) return null
        val count = intLE(data, p)
        p += 4
        var fallback: String? = null
        repeat(count.coerceAtLeast(0)) {
            if (p + 4 > end) return@repeat
            val valueLength = intLE(data, p)
            p += 4
            if (valueLength < 0 || p + valueLength > end) return@repeat
            val entry = String(data, p, valueLength, StandardCharsets.UTF_8)
            p += valueLength
            val key = entry.substringBefore('=').uppercase()
            val value = entry.substringAfter('=', "").takeIf { it.isNotBlank() } ?: return@repeat
            when (key) {
                "LYRICS" -> return value
                "SYNCEDLYRICS", "UNSYNCEDLYRICS" -> if (fallback == null) fallback = value
            }
        }
        return fallback
    }

    // MP3：遍历 ID3v2 帧取首个 USLT（非同步歌词）帧的歌词文本
    private fun extractMp3Lyrics(tag: ByteArray): String? {
        if (tag.size < 10 || !tag.startsWith("ID3")) return null
        val version = tag[3].toInt() and 0xff
        if (version !in 3..4) return null
        val flags = tag[5].toInt() and 0xff
        val tagEnd = minOf(tag.size, 10 + syncsafe(tag, 6))
        var p = 10
        // 扩展头：v2.4 为 syncsafe 尺寸，v2.3 为大端 int32
        if (flags and 0x40 != 0 && p + 4 <= tagEnd) {
            val extSize = if (version >= 4) syncsafe(tag, p) else int32BE(tag, p)
            p += 4 + extSize
        }
        while (p + 10 <= tagEnd) {
            val id = String(tag, p, 4, StandardCharsets.ISO_8859_1)
            if (id.all { it == '\u0000' }) break
            val size = if (version >= 4) syncsafe(tag, p + 4) else int32BE(tag, p + 4)
            if (size < 0 || p + 10 + size > tagEnd) break
            if (id == "USLT") {
                decodeUslt(tag, p + 10, size)?.let { return it }
            }
            p += 10 + size
        }
        return null
    }

    // USLT 帧数据：编码字节 + 语言(3) + 空结尾内容描述符 + 歌词文本
    private fun decodeUslt(data: ByteArray, offset: Int, length: Int): String? {
        if (length < 4 || offset + length > data.size) return null
        val encoding = data[offset].toInt() and 0xff
        val end = offset + length
        // UTF-16 的字节序由帧数据起始处（描述符开头）的 BOM 决定，空字符为 00 00 与字节序无关
        val littleEndian = if (encoding == 1 && offset + 6 <= end) {
            when {
                data[offset + 4] == 0xff.toByte() && data[offset + 5] == 0xfe.toByte() -> true
                data[offset + 4] == 0xfe.toByte() && data[offset + 5] == 0xff.toByte() -> false
                else -> true
            }
        } else null
        val terminator = if (encoding == 1 || encoding == 2) 2 else 1
        var p = offset + 4
        while (p + terminator <= end) {
            if (data[p] == 0.toByte() && (terminator == 1 || data[p + 1] == 0.toByte())) break
            p++
        }
        p += terminator
        if (p > end) return null
        return decodeText(data.copyOfRange(p, end), encoding, littleEndian)
    }

    // ID3v2 文本编码：0=ISO-8859-1、1=UTF-16(带 BOM)、2=UTF-16BE、3=UTF-8
    private fun decodeText(bytes: ByteArray, encoding: Int, littleEndian: Boolean?): String = when (encoding) {
        0 -> String(bytes, StandardCharsets.ISO_8859_1)
        1 -> if (littleEndian == true) String(bytes, StandardCharsets.UTF_16LE) else String(bytes, StandardCharsets.UTF_16BE)
        2 -> String(bytes, StandardCharsets.UTF_16BE)
        else -> String(bytes, StandardCharsets.UTF_8)
    }

    // M4A：递归遍历 moov/udta/meta/ilst，取 ©lyr(或 lyr) 与 ----:LYRICS 自定义原子
    private fun extractMp4Lyrics(bytes: ByteArray): String? =
        extractMp4LyricsAt(bytes, 0, bytes.size, isMeta = false)

    private fun extractMp4LyricsAt(bytes: ByteArray, start: Int, end: Int, isMeta: Boolean): String? {
        // meta 原子在子原子前有 4 字节版本/标志
        var p = start + if (isMeta) 4 else 0
        while (p + 8 <= end) {
            val size = int32BE(bytes, p)
            if (size < 8 || p + size > end) return null
            val type = String(bytes, p + 4, 4, StandardCharsets.ISO_8859_1)
            when (type) {
                "©lyr", "lyr" -> decodeDataAtom(bytes, p + 8, p + size)?.let { return it }
                "----" -> decodeFreeformLyrics(bytes, p + 8, p + size)?.let { return it }
                "moov", "udta", "meta", "ilst" ->
                    extractMp4LyricsAt(bytes, p + 8, p + size, isMeta = type == "meta")?.let { return it }
            }
            p += size
        }
        return null
    }

    // 定位 data 子原子：跳过 type(4) + locale(4) 后为歌词文本
    private fun decodeDataAtom(bytes: ByteArray, start: Int, end: Int): String? {
        var p = start
        while (p + 8 <= end) {
            val size = int32BE(bytes, p)
            if (size < 8 || p + size > end) return null
            if (String(bytes, p + 4, 4, StandardCharsets.ISO_8859_1) == "data") {
                return String(bytes, p + 16, p + size - 16, StandardCharsets.UTF_8)
                    .takeIf { it.isNotBlank() }
            }
            p += size
        }
        return null
    }

    // ---- 自定义原子：mean(4 字节) + UTF-8 空结尾名称 + data 子原子
    private fun decodeFreeformLyrics(bytes: ByteArray, start: Int, end: Int): String? {
        var p = start + 4
        val nameStart = p
        while (p < end && bytes[p] != 0.toByte()) p++
        if (p >= end) return null
        val name = String(bytes, nameStart, p - nameStart, StandardCharsets.UTF_8)
        p++
        if (!name.equals("lyrics", ignoreCase = true)) return null
        return decodeDataAtom(bytes, p, end)
    }

    private fun syncsafe(bytes: ByteArray, p: Int): Int =
        (bytes[p].toInt() and 0x7f shl 21) or (bytes[p + 1].toInt() and 0x7f shl 14) or
            (bytes[p + 2].toInt() and 0x7f shl 7) or (bytes[p + 3].toInt() and 0x7f)

    private fun int32BE(bytes: ByteArray, p: Int): Int =
        (bytes[p].toInt() and 0xff shl 24) or (bytes[p + 1].toInt() and 0xff shl 16) or
            (bytes[p + 2].toInt() and 0xff shl 8) or (bytes[p + 3].toInt() and 0xff)

    private fun intLE(bytes: ByteArray, p: Int): Int =
        (bytes[p].toInt() and 0xff) or (bytes[p + 1].toInt() and 0xff shl 8) or
            (bytes[p + 2].toInt() and 0xff shl 16) or (bytes[p + 3].toInt() and 0xff shl 24)

    private fun ByteArray.startsWith(value: String): Boolean =
        size >= value.length && String(this, 0, value.length, StandardCharsets.US_ASCII) == value

    private fun ByteArray.startsWith(value: String, offset: Int): Boolean =
        size >= offset + value.length && String(this, offset, value.length, StandardCharsets.US_ASCII) == value

    private fun ByteArray.indexOfAscii(value: String): Int =
        (0..(size - value.length).coerceAtLeast(0)).firstOrNull { startsWith(value, it) } ?: -1
}
