package com.yichao.evilgodxu.musicpanel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.yichao.evilgodxu.log.CrashLogManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal object MusicMetadataCache {
    private const val COVER_MAX_EDGE = 512
    // 歌词缓存格式版本：旧版本（无逐字时序 words）命中该文件的歌词不视为有效，需重取刷新
    private const val LYRIC_CACHE_VERSION = 2

    private fun root(context: Context) = File(context.filesDir, "music_metadata")
    private fun coverFile(context: Context, id: Long) = File(File(root(context), "covers_v2"), "$id.webp")
    private fun originalCoverFile(context: Context, id: Long) = File(File(root(context), "covers_original"), "$id.image")
    private fun lyricFile(context: Context, id: Long) = File(File(root(context), "lyrics"), "$id.json")

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
        CrashLogManager.logException("MusicMetadataCache", "保存封面失败", e)
        null
    }

    fun saveCover(context: Context, id: Long, bitmap: Bitmap): String? = try {
        val convertedFile = coverFile(context, id)
        convertedFile.parentFile?.mkdirs()
        // compress 返回值已反映编码结果，配合文件长度校验即可，无需再解码验证
        val success = convertedFile.outputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 85, output)
        }
        if (success && isValid(convertedFile.absolutePath)) {
            return convertedFile.absolutePath
        }
        // WEBP 编码失败，回退为 PNG 原样保存
        originalCoverFile(context, id).apply {
            parentFile?.mkdirs()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream())
        }.absolutePath
    } catch (e: Exception) {
        CrashLogManager.logException("MusicMetadataCache", "保存封面失败", e)
        null
    }

    fun saveLyrics(context: Context, id: Long, lines: List<LyricLine>): String? = try {
        val file = lyricFile(context, id)
        file.parentFile?.mkdirs()
        val array = JSONArray()
        lines.forEach { line ->
            array.put(JSONObject().apply {
                put("v", LYRIC_CACHE_VERSION)
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
        CrashLogManager.logException("MusicMetadataCache", "保存歌词失败", e)
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
        CrashLogManager.logException("MusicMetadataCache", "加载歌词失败", e)
        emptyList()
    }

    fun isValid(path: String): Boolean = path.isNotBlank() && File(path).let { it.isFile && it.length() > 0 }

    // 歌词缓存是否为最新格式：文件存在且带当前版本号才视为有效，旧格式需重取
    fun isCurrentLyricFormat(path: String): Boolean = try {
        isValid(path) && JSONObject(File(path).readText()).optInt("v", 0) == LYRIC_CACHE_VERSION
    } catch (e: Exception) {
        false
    }

    fun isCurrentCoverPath(path: String): Boolean = isValid(path) && File(path).parentFile?.name == "covers_v2"

    fun loadCoverBytes(path: String): ByteArray? = try {
        if (!isValid(path)) null else File(path).readBytes()
    } catch (e: Exception) {
        CrashLogManager.logException("MusicMetadataCache", "读取封面文件失败", e)
        null
    }

    fun deleteCoverFile(path: String) {
        if (path.isNotBlank()) {
            File(path).delete()
        }
    }

    fun deleteLyricFile(path: String) {
        if (path.isNotBlank()) {
            File(path).delete()
        }
    }

    fun cleanupOrphanedMetadata(context: Context, referencedPaths: Set<String>) {
        val referenced = referencedPaths.filter(String::isNotBlank).toSet()
        listOf("covers_v2", "covers_original", "lyrics").forEach { directoryName ->
            File(root(context), directoryName)
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
