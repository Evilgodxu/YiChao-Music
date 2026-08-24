package com.yichao.evilgodxu.musicpanel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
import com.yichao.evilgodxu.log.CrashLogManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal object MusicMetadataCache {
    // 封面铺满大屏（首页横竖屏大封面），按最长边 1024px 采样，兼顾清晰度与内存
    private const val COVER_MAX_EDGE = 1024

    // 使用应用私有目录，免外部存储权限（Android 10+ 分区存储下公共 Download 目录不可直接写）
    private fun downloadsDir(context: Context): File =
        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir

    internal fun coverRoot(context: Context): File = File(downloadsDir(context), "Cover")
    private fun lyricRoot(context: Context): File = File(downloadsDir(context), "Lyrics")
    private fun coverFile(context: Context, id: Long) = File(coverRoot(context), "$id.webp")
    private fun originalCoverFile(context: Context, id: Long) = File(coverRoot(context), "$id.image")

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

    /** 封面会铺满大屏显示，解码前按最长边采样，避免全尺寸位图的内存峰值 */
    fun decodeSampledBitmap(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sampleSize = 1
        while (bounds.outWidth / (sampleSize * 2) >= COVER_MAX_EDGE &&
            bounds.outHeight / (sampleSize * 2) >= COVER_MAX_EDGE
        ) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    fun saveCover(context: Context, id: Long, originalBytes: ByteArray): String? = try {
        val bitmap = decodeSampledBitmap(originalBytes) ?: return null
        try {
            saveCover(context, id, bitmap)
        } finally {
            bitmap.recycle()
        }
    } catch (e: Exception) {
        CrashLogManager.logException("MusicMetadataCache", "保存封面失败: 路径=${coverFile(context, id).absolutePath}", e)
        null
    }

    fun saveCover(context: Context, id: Long, bitmap: Bitmap): String? = try {
        val convertedFile = coverFile(context, id)
        // 目标目录未能创建（如缺少全文件权限）时直接返回，避免写入抛 ENOENT
        if (!ensureParentDir(convertedFile)) {
            CrashLogManager.logException("MusicMetadataCache", "创建封面目录失败: 路径=${convertedFile.parentFile?.absolutePath}")
            return null
        }
        ensureNoMedia(convertedFile.parentFile!!)
        // compress 返回值已反映编码结果，配合文件长度校验即可，无需再解码验证
        val success = convertedFile.outputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 85, output)
        }
        if (success && isValid(convertedFile.absolutePath)) {
            return convertedFile.absolutePath
        }
        // WEBP 编码失败，回退为 PNG 原样保存
        val fallbackFile = originalCoverFile(context, id)
        if (!ensureParentDir(fallbackFile)) {
            CrashLogManager.logException("MusicMetadataCache", "创建封面目录失败: 路径=${fallbackFile.parentFile?.absolutePath}")
            return null
        }
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, fallbackFile.outputStream())
        fallbackFile.absolutePath
    } catch (e: Exception) {
        CrashLogManager.logException("MusicMetadataCache", "保存封面失败: 路径=${coverFile(context, id).absolutePath}", e)
        null
    }

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

    fun loadCoverBytes(path: String): ByteArray? = try {
        if (!isValid(path)) null else File(path).readBytes()
    } catch (e: Exception) {
        CrashLogManager.logException("MusicMetadataCache", "读取封面文件失败: $path", e)
        null
    }

    fun deleteCoverFile(path: String) {
        if (path.isNotBlank()) {
            File(path).delete()
        }
    }

    fun cleanupOrphanedMetadata(context: Context, referencedPaths: Set<String>) {
        val referenced = referencedPaths.filter(String::isNotBlank).toSet()
        // 引用集为空（如歌单尚未加载）时跳过清理，避免误删全部缓存
        if (referenced.isEmpty()) return
        listOf(coverRoot(context), lyricRoot(context)).forEach { directory ->
            directory
                .takeIf { it.exists() }
                ?.listFiles()
                .orEmpty()
                .forEach { file ->
                if (file.isFile && file.absolutePath !in referenced) {
                    file.delete()
                }
            }
        }
    }
}
