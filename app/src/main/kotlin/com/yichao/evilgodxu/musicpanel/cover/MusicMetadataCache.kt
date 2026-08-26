package com.yichao.evilgodxu.musicpanel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.os.Environment
import com.yichao.evilgodxu.log.CrashLogManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.math.roundToInt

internal object MusicMetadataCache {
    // 封面保存上限与显示端对齐（2K）：覆盖折叠屏/平板横屏等最大显示场景，超过部分永不显示；
    // 位图内存峰值约 2048²×4 ≈ 16MB，解码后即压缩保存并回收，不常驻
    private const val COVER_MAX_EDGE = 2048

    // 使用应用私有目录，免外部存储权限（Android 10+ 分区存储下公共 Download 目录不可直接写）
    private fun downloadsDir(context: Context): File =
        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir

    internal fun coverRoot(context: Context): File = File(downloadsDir(context), "Cover")
    private fun lyricRoot(context: Context): File = File(downloadsDir(context), "Lyrics")
    // 封面按内容哈希命名：同图仅存一份，跨歌曲/专辑天然去重
    private fun coverFile(context: Context, key: String) = File(coverRoot(context), "$key.webp")
    private fun originalCoverFile(context: Context, key: String) = File(coverRoot(context), "$key.image")

    // 歌词文件按“标题 - 艺术家”命名，空字段自动忽略，保证可读且避免同名覆盖
    private fun lyricFile(context: Context, title: String, artist: String): File {
        val name = listOf(title, artist)
            .filter { it.isNotBlank() }
            .joinToString(" - ")
            .ifBlank { "unknown" }
        return File(lyricRoot(context), "${sanitizeFileName(name)}.lrc")
    }

    // 标记目录不被系统媒体扫描器收录，避免封面图出现在相册
    private fun ensureNoMedia(dir: File) = runCatching {
        File(dir, ".nomedia").apply { if (!exists()) createNewFile() }
    }

    // 父目录已存在则直接可用，否则尝试创建；创建失败返回 false
    private fun ensureParentDir(file: File): Boolean {
        val parent = file.parentFile ?: return false
        return parent.isDirectory || parent.mkdirs()
    }

    /** 按最长边等比高质量解码；maxEdge 可调以适配显示场景（显示端无需保存上限全尺寸位图） */
    fun decodeSampledBitmap(bytes: ByteArray, maxEdge: Int = COVER_MAX_EDGE): Bitmap? {
        // 用 ImageDecoder 替代 BitmapFactory.inSampleSize：
        // inSampleSize 为最近邻点采样，4K 等高分辨率封面降采样会产生混叠锯齿（解码时即固化）；
        // ImageDecoder 按目标尺寸高质量滤波缩放，直接解码到目标分辨率，无锯齿且内存可控
        return runCatching {
            val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                // 软件位图：保证后续 compress(WEBP/PNG) 与 recycle() 可用
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val longEdge = maxOf(info.size.width, info.size.height)
                if (longEdge > maxEdge) {
                    val scale = maxEdge.toFloat() / longEdge
                    decoder.setTargetSize(
                        (info.size.width * scale).roundToInt().coerceAtLeast(1),
                        (info.size.height * scale).roundToInt().coerceAtLeast(1),
                    )
                }
                // 统一 sRGB 输出，避免广色域封面在不同设备上渲染偏差
                decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            }
        }.getOrNull()
    }

    fun saveCover(context: Context, id: Long, originalBytes: ByteArray): String? = try {
        val bitmap = decodeSampledBitmap(originalBytes) ?: return null
        try {
            saveCover(context, id, bitmap)
        } finally {
            bitmap.recycle()
        }
    } catch (e: Exception) {
        CrashLogManager.logException("MusicMetadataCache", "保存封面失败: 来源=${originalBytes.size}B", e)
        null
    }

    fun saveCover(context: Context, id: Long, bitmap: Bitmap): String? = try {
        // 先编码到内存并取内容哈希作为文件名：同图共享同一文件，天然去重
        val webpBytes = ByteArrayOutputStream().use { out ->
            if (bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 92, out)) out.toByteArray() else null
        }
        if (webpBytes != null) {
            val convertedFile = coverFile(context, contentKey(webpBytes))
            if (isValid(convertedFile.absolutePath)) return convertedFile.absolutePath
            // 目标目录未能创建（如缺少全文件权限）时直接返回，避免写入抛 ENOENT
            if (!ensureParentDir(convertedFile)) {
                CrashLogManager.logException("MusicMetadataCache", "创建封面目录失败: 路径=${convertedFile.parentFile?.absolutePath}")
                return null
            }
            ensureNoMedia(convertedFile.parentFile!!)
            // compress 返回值已反映编码结果，配合文件长度校验即可，无需再解码验证
            // 高质量有损编码（92，视觉近似无损），大屏放大显示无明显压缩伪影
            convertedFile.writeBytes(webpBytes)
            if (isValid(convertedFile.absolutePath)) return convertedFile.absolutePath
        }
        // WEBP 编码/写入失败，回退为 PNG 原样保存
        val pngBytes = ByteArrayOutputStream().use { out ->
            if (bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) out.toByteArray() else return null
        }
        val fallbackFile = originalCoverFile(context, contentKey(pngBytes))
        if (!ensureParentDir(fallbackFile)) {
            CrashLogManager.logException("MusicMetadataCache", "创建封面目录失败: 路径=${fallbackFile.parentFile?.absolutePath}")
            return null
        }
        fallbackFile.writeBytes(pngBytes)
        fallbackFile.absolutePath
    } catch (e: Exception) {
        CrashLogManager.logException("MusicMetadataCache", "保存封面失败: 封面尺寸=${bitmap.width}x${bitmap.height}", e)
        null
    }

    // 封面内容 SHA-256 前 8 字节的十六进制作为缓存文件名，实现同图去重
    private fun contentKey(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).take(8).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun saveLyrics(context: Context, title: String, artist: String, lines: List<LyricLine>): String? = try {
        val file = lyricFile(context, title, artist)
        file.parentFile?.mkdirs()
        ensureNoMedia(file.parentFile!!)
        file.writeText(encodeLyrics(lines))
        file.absolutePath
    } catch (e: Exception) {
        CrashLogManager.logException("MusicMetadataCache", "保存歌词失败: 路径=${lyricFile(context, title, artist).absolutePath}", e)
        null
    }

    // 歌词按增强 LRC 文本序列化：标准 [mm:ss.xx] 行 + 行内 <mm:ss.xx> 逐字时间戳，翻译以 [tr][/tr] 追加
    internal fun encodeLyrics(lines: List<LyricLine>): String =
        lines.joinToString("\n") { line ->
            val timestamp = lrcTimestamp(line.timeMs)
            val translation = line.translation?.takeIf { it.isNotBlank() }?.let { "[tr]$it[/tr]" }.orEmpty()
            if (line.words.isEmpty()) "[$timestamp]${line.text}$translation"
            else "[$timestamp]" + line.words.joinToString("") { "<${lrcTimestamp(it.startMs)}>${it.text}" } + translation
        }

    // 按“标题 - 艺术家”查找已存在的歌词缓存文件：在线播放/手动刷新保存的 .lrc 可直接复用
    fun findLyrics(context: Context, title: String, artist: String): String? {
        val file = lyricFile(context, title, artist)
        return file.absolutePath.takeIf { isValid(it) }
    }

    fun loadLyrics(path: String): List<LyricLine> = try {
        parseLyricsText(File(path).readText())
    } catch (e: Exception) {
        CrashLogManager.logException("MusicMetadataCache", "加载歌词失败: 路径=$path", e)
        emptyList()
    }

    // 解析本地导入的 LRC 文本（兼容逐字增强标签），供外部导入歌词时复用
    fun parseLyricsText(text: String): List<LyricLine> =
        parseEnhancedLrc(text).ifEmpty { parseJsonLyrics(text) }

    private fun lrcTimestamp(ms: Long): String {
        val minutes = ms / 60_000
        val seconds = ms % 60_000 / 1000
        val hundredths = ms % 1000 / 10
        return "%02d:%02d.%02d".format(minutes, seconds, hundredths)
    }

    // 增强 LRC 解析：兼容纯文本行、行内 <mm:ss.xx> 逐字标签与 [tr][/tr] 翻译块
    private fun parseEnhancedLrc(lrc: String): List<LyricLine> {
        val linePattern = Regex("""\[(\d+):(\d+)(?:\.(\d+))?](.*)""")
        val wordPattern = Regex("""<(\d+):(\d+)(?:\.(\d+))?>([^<]*)""")
        val transPattern = Regex("""\[tr](.*?)\[/tr]""")
        return lrc.lineSequence().mapNotNull { rawLine ->
            val match = linePattern.find(rawLine) ?: return@mapNotNull null
            val timeMs = match.groupValues[1].toLong() * 60_000 +
                match.groupValues[2].toLong() * 1000 +
                match.groupValues[3].padEnd(3, '0').take(3).toLong()
            val content = match.groupValues[4]
            val translation = transPattern.find(content)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
            val cleanContent = transPattern.replace(content, "").trim()
            val words = wordPattern.findAll(cleanContent).map { word ->
                LyricWord(
                    startMs = word.groupValues[1].toLong() * 60_000 +
                        word.groupValues[2].toLong() * 1000 +
                        word.groupValues[3].padEnd(3, '0').take(3).toLong(),
                    durationMs = 0L,
                    text = word.groupValues[4]
                )
            }.filter { it.text.isNotEmpty() }.toList()
            val text = if (words.isNotEmpty()) words.joinToString("") { it.text } else cleanContent.trim()
            LyricLine(timeMs, text, words, translation).takeIf { it.text.isNotBlank() }
        }.sortedBy { it.timeMs }.toList()
    }

    // 旧版 JSON 缓存回退解析
    private fun parseJsonLyrics(text: String): List<LyricLine> {
        val array = JSONArray(text)
        return List(array.length()) { index ->
            val item = array.getJSONObject(index)
            val words = item.optJSONArray("words") ?: JSONArray()
            LyricLine(
                timeMs = item.getLong("timeMs"),
                text = item.getString("text"),
                words = List(words.length()) { wordIndex ->
                    val word = words.getJSONObject(wordIndex)
                    LyricWord(word.getLong("startMs"), word.getLong("durationMs"), word.getString("text"))
                }
            )
        }
    }

    fun isValid(path: String): Boolean = path.isNotBlank() && File(path).let { it.isFile && it.length() > 0 }

    // 是否为新版内容哈希命名的封面缓存（旧版按歌曲 id 命名，用于一次性迁移为哈希命名）
    fun isHashKeyFileName(path: String): Boolean = runCatching {
        File(path).nameWithoutExtension.matches(HASH_KEY_REGEX)
    }.getOrDefault(false)

    private val HASH_KEY_REGEX = Regex("[0-9a-f]{16}")

    fun loadCoverBytes(path: String): ByteArray? = try {
        if (!isValid(path)) null else File(path).readBytes()
    } catch (e: Exception) {
        CrashLogManager.logException("MusicMetadataCache", "读取封面文件失败: $path", e)
        null
    }

    // 按引用集合回收孤儿缓存：删除不再被任何已知歌曲引用的封面/歌词文件。
    // 参照集合由调用方按全量库+当前歌单构建，跨歌单共享的文件因存在引用而不会被误删
    fun cleanupOrphanedMetadata(context: Context, referencedPaths: Set<String>) {
        val referenced = referencedPaths.filter(String::isNotBlank).toSet()
        // 引用集为空（如歌曲库尚未加载）时跳过清理，避免误删全部缓存
        if (referenced.isEmpty()) return
        listOf(coverRoot(context), lyricRoot(context)).forEach { directory ->
            directory
                .takeIf { it.exists() }
                ?.listFiles()
                .orEmpty()
                .forEach { file ->
                    if (file.isFile && file.name != ".nomedia" && file.absolutePath !in referenced) {
                        file.delete()
                    }
                }
        }
    }
}
