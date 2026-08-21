package com.yichao.evilgodxu.musicpanel

import com.yichao.evilgodxu.log.CrashLogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import kotlin.random.Random

/**
 * QQ 音乐在线源：官方 musicu.fcg 接口搜索 + GetVkey 获取播放地址 + 歌词接口。
 * 歌曲标识为 songmid 字符串，转成稳定数字 id 存入搜索结果。
 */
internal object QQMusicApi : OnlineMusicSource {

    private const val ENDPOINT = "https://u.y.qq.com/cgi-bin/musicu.fcg"
    private const val MUSIC_DOMAIN = "https://isure.stream.qqmusic.qq.com/"
    // 官方接口的 comm 参数需要 QIMEI36，取不到设备标识时用该固定兜底值
    private const val QIMEI36 = "6c9d3cd110abca9b16311cee10001e717614"
    private const val VERSION_CODE = 13020508
    private const val UID = "3931641530"
    private const val GUID_CHARS = "abcdef1234567890"

    // 音质代号 + 扩展名，按从高到低依次尝试
    private val SORTED_QUALITIES = arrayOf(
        "AI00" to ".flac", "Q000" to ".flac", "Q001" to ".flac", "F000" to ".flac",
        "O801" to ".ogg", "O800" to ".ogg", "O600" to ".ogg", "O400" to ".ogg",
        "M800" to ".mp3", "M500" to ".mp3", "C600" to ".m4a", "C400" to ".m4a", "C200" to ".m4a"
    )

    override suspend fun search(keyword: String): List<NeteaseSongSearchResult> = withContext(Dispatchers.IO) {
        try {
            var results = doSearch(keyword)
            // QQ 搜索对陌生 IP 偶发返回空列表（风控软封），重试一次再判空
            if (results.isEmpty()) {
                delay(300)
                results = doSearch(keyword)
            }
            results
        } catch (e: Exception) {
            CrashLogManager.logException("QQMusicApi", "搜索歌曲失败", e)
            emptyList()
        }
    }

    private fun doSearch(keyword: String): List<NeteaseSongSearchResult> {
        val body = JSONObject()
        body.put("comm", commonParams())
        val search = JSONObject()
        search.put("module", "music.search.SearchCgiService")
        search.put("method", "DoSearchForQQMusicMobile")
        val param = JSONObject()
        param.put("searchid", randomSearchId())
        param.put("query", keyword)
        param.put("search_type", 0)
        param.put("num_per_page", 20)
        param.put("page_num", 1)
        param.put("highlight", 1)
        param.put("grp", 1)
        search.put("param", param)
        body.put("music.search.SearchCgiService.DoSearchForQQMusicMobile", search)

        val root = post(body)
        val itemSong = root.optJSONObject("music.search.SearchCgiService.DoSearchForQQMusicMobile")
            ?.optJSONObject("data")?.optJSONObject("body")?.optJSONArray("item_song") ?: JSONArray()
        return List(itemSong.length()) { index ->
            val item = itemSong.getJSONObject(index)
            val mid = item.optString("mid").ifBlank { item.optString("songmid") }
            val singer = item.optJSONArray("singer") ?: JSONArray()
            val artist = List(singer.length()) { singer.getJSONObject(it).optString("name") }
                .filter { it.isNotBlank() }
                .joinToString(" / ")
            val albumMid = item.optJSONObject("album")?.optString("mid")
                .orEmpty().ifBlank { item.optString("albummid") }
            val cover = if (albumMid.isNotBlank()) {
                "https://y.gtimg.cn/music/photo_new/T002R800x800M000$albumMid.jpg"
            } else null
            NeteaseSongSearchResult(
                id = stableIdFromString(mid),
                title = item.optString("title").ifBlank { item.optString("name") },
                artist = artist,
                coverUrl = cover,
                // 封面 CDN 按尺寸段生成小图，使用可用的 150x150
                coverThumbUrl = if (albumMid.isNotBlank()) {
                    "https://y.gtimg.cn/music/photo_new/T002R150x150M000$albumMid.jpg"
                } else null,
                duration = item.optLong("interval", 0L) * 1000L,
                source = MusicSearchSource.QQ,
                sourceId = mid
            )
        }
    }

    /** 获取播放地址，从高音质到低音质逐个尝试，返回第一个可用链接 */
    suspend fun songUrl(mid: String): String? = withContext(Dispatchers.IO) {
        if (mid.isBlank()) return@withContext null
        try {
            for ((code, ext) in SORTED_QUALITIES) {
                val body = JSONObject()
                body.put("comm", commonParams(ct = 19))
                val vkey = JSONObject()
                vkey.put("module", "music.vkey.GetVkey")
                vkey.put("method", "UrlGetVkey")
                val param = JSONObject()
                param.put("filename", JSONArray().put("$code$mid$mid$ext"))
                param.put("guid", randomGuid())
                param.put("songmid", JSONArray().put(mid))
                param.put("songtype", JSONArray().put(0))
                vkey.put("param", param)
                body.put("music.vkey.GetVkey.UrlGetVkey", vkey)

                val root = post(body)
                val purl = root.optJSONObject("music.vkey.GetVkey.UrlGetVkey")
                    ?.optJSONObject("data")?.optJSONArray("midurlinfo")?.optJSONObject(0)
                    ?.optString("purl")?.ifBlank { null }
                    ?: continue
                return@withContext MUSIC_DOMAIN + purl
            }
            null
        } catch (e: Exception) {
            CrashLogManager.logException("QQMusicApi", "获取播放地址失败", e)
            null
        }
    }

    /** 获取歌词，接口返回 base64 编码的 LRC 文本 */
    suspend fun lyricLines(result: NeteaseSongSearchResult): List<LyricLine>? = withContext(Dispatchers.IO) {
        val mid = result.sourceId ?: return@withContext null
        try {
            val url = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg" +
                    "?songmid=$mid&g_tk=5381&loginUin=0&hostUin=0&format=json" +
                    "&inCharset=utf8&outCharset=utf-8&platform=yqq"
            val connection = URL(url).openConnection() as HttpURLConnection
            val response = try {
                connection.requestMethod = "GET"
                connection.connectTimeout = 10_000
                connection.readTimeout = 15_000
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                connection.setRequestProperty("Referer", "https://y.qq.com/portal/player.html")
                val responseCode = connection.responseCode
                val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (responseCode !in 200..299) throw IllegalStateException("HTTP $responseCode")
                text
            } finally {
                connection.disconnect()
            }
            val b64 = JSONObject(response).optString("lyric").ifBlank { return@withContext null }
            val lrc = String(Base64.getDecoder().decode(b64), Charsets.UTF_8)
            parseLrcText(lrc).takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            CrashLogManager.logException("QQMusicApi", "获取歌词失败", e)
            null
        }
    }

    private fun commonParams(ct: Int = 11): JSONObject = JSONObject().apply {
        put("cv", VERSION_CODE)
        put("v", VERSION_CODE)
        put("QIMEI36", QIMEI36)
        put("ct", ct)
        put("tmeAppID", "qqmusic")
        put("format", "json")
        put("inCharset", "utf-8")
        put("outCharset", "utf-8")
        put("uid", UID)
    }

    private fun post(body: JSONObject): JSONObject {
        val connection = URL(ENDPOINT).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            // 缺 Referer/Origin 时搜索接口会返回空列表
            connection.setRequestProperty("Referer", "https://y.qq.com/")
            connection.setRequestProperty("Origin", "https://y.qq.com/")
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (responseCode !in 200..299) throw IllegalStateException("HTTP $responseCode: $response")
            JSONObject(response)
        } finally {
            connection.disconnect()
        }
    }

    // searchid 按时间戳与随机数拼出大整数
    private fun randomSearchId(): String {
        val t = (1 + Random.nextInt(20)) * 18014398509481984L
        val n = Random.nextInt(0, 4194305) * 4294967296L
        val r = System.currentTimeMillis() % (24 * 60 * 60 * 1000)
        return (t + n + r).toString()
    }

    private fun randomGuid(): String = buildString(32) {
        repeat(32) { append(GUID_CHARS[Random.nextInt(GUID_CHARS.length)]) }
    }
}
