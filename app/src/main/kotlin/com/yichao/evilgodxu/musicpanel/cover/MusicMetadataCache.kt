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
    private const val COVER_MAX_EDGE = 512

    // 使用应用私有目录，免外部存储权限（Android 10+ 分区存储下公共 Download 目录不可直接写）
    private fun downloadsDir(context: Context): File =
        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir

    internal fun coverRoot(context: Context): File = File(downloadsDir(context), "YiChao/Cover")
    private fun lyricRoot(context: Context): File = File(downloadsDir(context), "YiChao/Lyrics")
    private fun coverFile(context: Context, id: Long) = File(coverRoot(context), "$id.webp")
    private fun originalCoverFile(context: Context, id: Long) = File(coverRoot(context), "$id.image")
    private fun lyricFile(context: Context, id: Long) = File(lyricRoot(context), "$id.json")

    // 标记目录不被系统媒体扫描器收录，避免封面图出现在相册
    private fun ensureNoMedia(dir: File) = runCatching {
        File(dir, ".nomedia").apply { if (!exists()) createNewFile() }
    }

    // 父目录已存在则直接可用，否则尝试创建；创建失败返回 false
    private fun ensureParentDir(file: File): Boolean {
        val parent = file.parentFile ?: return false
        return parent.isDirectory || parent.mkdirs()
    }

    /** 封面在面板中只显示 64dp 小图，解码前按最长边 512px 采样，避免全尺寸位图的内存峰值 */
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

    fun saveLyrics(context: Context, id: Long, lines: List<LyricLine>): String? = try {
        val file = lyricFile(context, id)
        file.parentFile?.mkdirs()
        ensureNoMedia(file.parentFile!!)
        val array = JSONArray()
        lines.forEach { line ->
            array.put(JSONObject().apply {
                put("timeMs", line.timeMs)
                put("text", line.text)
                put("words", JSONArray().also { words ->
                    line.words.forEach { word ->
                        words.put(JSONObject().apply {
                            put("startMs", word.startMs)
                            put("durationMs", word.durationMs)
                            put("text", word.text)
                        })
                    }
                })
            })
        }
        file.writeText(array.toString())
        file.absolutePath
    } catch (e: Exception) {
        CrashLogManager.logException("MusicMetadataCache", "保存歌词失败: 路径=${lyricFile(context, id).absolutePath}", e)
        null
    }

    fun loadLyrics(path: String): List<LyricLine> = try {
        val array = JSONArray(File(path).readText())
        List(array.length()) { index ->
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
    } catch (e: Exception) {
        CrashLogManager.logException("MusicMetadataCache", "加载歌词失败: 路径=$path", e)
        emptyList()
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
