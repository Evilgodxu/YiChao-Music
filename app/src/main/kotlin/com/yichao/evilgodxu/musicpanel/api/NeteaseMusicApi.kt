package com.yichao.evilgodxu.musicpanel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.yichao.evilgodxu.log.CrashLogManager
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

internal data class NeteaseSongMatch(
    val id: Long,
    val title: String,
    val artist: String,
    val coverUrl: String?
)

// 在线音乐搜索来源
enum class MusicSearchSource { NETEASE, JAMENDO, QQ, KUGOU }

data class NeteaseSongSearchResult(
    val id: Long,
    val title: String,
    val artist: String,
    val coverUrl: String?,
    /** CDN 缩略图 URL（封面 + ?param=128y128），列表行使用以加快加载 */
    val coverThumbUrl: String? = null,
    val duration: Long = 0L,
    val source: MusicSearchSource = MusicSearchSource.NETEASE,
    /** Jamendo 结果自带可直接播放的音频地址，网易云结果为空 */
    val audioUrl: String? = null,
    /** 平台内歌曲标识（QQ 的 songmid、酷狗的 hash），取播放地址/歌词时使用 */
    val sourceId: String? = null,
)

internal data class NeteaseLyricData(val lines: List<LyricLine>)

data class LyricWord(val startMs: Long, val durationMs: Long, val text: String)

data class LyricLine(
    val timeMs: Long,
    val text: String,
    val words: List<LyricWord> = emptyList()
)

internal object NeteaseMusicApi : OnlineMusicSource {
    suspend fun loadCoverBytes(url: String): ByteArray? = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext null
        try {
            URL(url).openStream().use { it.readBytes() }
        } catch (e: Exception) {
            CrashLogManager.logException("NeteaseMusicApi", "下载封面失败: $url", e)
            null
        }
    }

    suspend fun match(title: String, artist: String, durationMs: Long): NeteaseSongMatch? = withContext(Dispatchers.IO) {
        val keyword = if (artist.isBlank() || artist == "未知艺术家" || artist == "<unknown>") title else "$title $artist"
        val songs = searchMatch(keyword)
        val best = songs.minByOrNull { score(it, title, artist, durationMs) } ?: return@withContext null
        if (!best.coverUrl.isNullOrBlank()) best else detail(best)
    }

    suspend fun lyric(songId: Long): NeteaseLyricData = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("id", songId)
            put("lv", 1)
            put("kv", 1)
            put("tv", 1)
            put("rv", 1)
            put("yv", 1)
        }
        val root = request("song/lyric/v1", body)
        parseLrc(
            root.optJSONObject("lrc")?.optString("lyric").orEmpty(),
            root.optJSONObject("yrc")?.optString("lyric").orEmpty()
        )
    }

    private fun detail(song: NeteaseSongMatch): NeteaseSongMatch {
        val root = request("v3/song/detail", JSONObject().put("c", "[{\"id\":${song.id}}]"))
        val item = root.optJSONArray("songs")?.optJSONObject(0) ?: return song
        val album = item.optJSONObject("al") ?: item.optJSONObject("album")
        return song.copy(coverUrl = album?.optString("picUrl")?.takeIf { it.isNotBlank() })
    }

    // 在线音乐源统一接口实现，返回完整的搜索结果显示
    override suspend fun search(keyword: String): List<NeteaseSongSearchResult> = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("s", keyword)
            put("type", 1)
            put("limit", 20)
            put("offset", 0)
        }
        val root = request("search/get", body)
        val songs = root.optJSONObject("result")?.optJSONArray("songs") ?: JSONArray()
        val results = List(songs.length()) { index ->
            val song = songs.getJSONObject(index)
            val artists = song.optJSONArray("artists") ?: song.optJSONArray("ar") ?: JSONArray()
            val artist = List(artists.length()) { artists.getJSONObject(it).optString("name") }
                .filter { it.isNotBlank() }
                .joinToString(" / ")
            val album = song.optJSONObject("album") ?: song.optJSONObject("al")
            val cover = album?.optString("picUrl")?.takeIf { it.isNotBlank() }
            val safeCover = cover?.let { ensureHttps(it) }
            NeteaseSongSearchResult(
                id = song.optLong("id"),
                title = song.optString("name"),
                artist = artist,
                coverUrl = safeCover,
                coverThumbUrl = safeCover?.let { thumbUrl(it) },
                duration = song.optLong("duration", 0L)
            )
        }
        // 按与查询关键词的相关性重排，优先展示与原曲(歌名+歌手)更匹配的结果，再补全缺失封面
        fillMissingCovers(rankSearchResults(results, keyword))
    }

    // 网易模糊搜索常把同名翻唱/伴奏等排在原唱之前，这里按相关性降权重排
    internal fun rankSearchResults(
        results: List<NeteaseSongSearchResult>,
        query: String,
    ): List<NeteaseSongSearchResult> {
        val nq = normalize(query)
        return results.sortedBy { result ->
            val nt = normalize(result.title)
            var score = if (nq.isNotBlank() && nt.isNotEmpty() && (nq == nt || nq.contains(nt) || nt.contains(nq))) -100 else 0
            if (VERSION_MARKERS.any { result.title.lowercase().contains(it) }) score += 60
            // 查询中剔除歌名部分后仍剩歌手关键词，而结果歌手未匹配该关键词时降权
            val normArtist = normalize(result.artist)
            val residue = nq.replace(nt, "")
            if (residue.isNotEmpty() && normArtist.isNotEmpty() && !nq.contains(normArtist)) score += 80
            score
        }
    }

    // 原曲以外的版本/翻唱等标记，命中即降权
    private val VERSION_MARKERS = listOf(
        "深情版", "女声版", "男声版", "童声", "翻唱", "现场", "弹唱", "伴奏",
        "演唱会", "钢琴", "吉他", "纯音乐", "dj版", "remix", "cover", "live版", "重唱"
    )

    /** 批量补全搜索结果中缺失封面 URL 的条目，与 QPlayer 的 fillMissingCovers() 对应 */
    private fun fillMissingCovers(results: List<NeteaseSongSearchResult>): List<NeteaseSongSearchResult> {
        val missingIds = results.filter { it.coverUrl.isNullOrBlank() }.map { it.id }
        if (missingIds.isEmpty()) return results
        try {
            val c = missingIds.joinToString(",") { "{\"id\":$it}" }
            val root = request("v3/song/detail", JSONObject().put("c", "[$c]"))
            val songs = root.optJSONArray("songs") ?: return results
            val coverMap = mutableMapOf<Long, String>()
            for (i in 0 until songs.length()) {
                val item = songs.getJSONObject(i)
                val id = item.optLong("id")
                val album = item.optJSONObject("al") ?: item.optJSONObject("album") ?: continue
                val picUrl = album.optString("picUrl").takeIf { it.isNotBlank() } ?: continue
                coverMap[id] = ensureHttps(picUrl)
            }
            return results.map { result ->
                if (result.coverUrl.isNullOrBlank()) {
                    val cover = coverMap[result.id]
                    if (cover != null) {
                        result.copy(coverUrl = cover, coverThumbUrl = thumbUrl(cover))
                    } else result
                } else result
            }
        } catch (e: Exception) {
            CrashLogManager.logException("NeteaseMusicApi", "补全歌曲封面失败", e)
            return results
        }
    }

    // 获取歌曲播放 URL，返回 null 表示完全不可播；试听片段也如实返回
    suspend fun getSongUrlInfo(songId: Long, level: String = "standard"): SongUrlInfo? = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().apply {
                put("ids", "[$songId]")
                put("level", level)
                put("encodeType", "mp3")
            }
            val root = request("song/enhance/player/url/v1", body)
            val data = root.optJSONArray("data")?.optJSONObject(0) ?: return@withContext null
            val url = data.optString("url", "")
            if (url.isBlank()) return@withContext null
            val hasFreeTrial = data.has("freeTrialInfo") && !data.isNull("freeTrialInfo")
            SongUrlInfo(url = ensureHttps(url), trial = hasFreeTrial)
        } catch (e: Exception) {
            CrashLogManager.logException("NeteaseMusicApi", "获取歌曲播放地址失败", e)
            null
        }
    }

    /** 歌曲播放 URL 信息 */
    data class SongUrlInfo(val url: String, val trial: Boolean)

    // 多音质回退获取播放 URL：优先非试听完整 URL，全为试听时降级返回试听片段
    suspend fun getSongUrlWithFallback(songId: Long): String? {
        for (level in arrayOf("standard", "higher", "exhigh")) {
            val info = getSongUrlInfo(songId, level) ?: continue
            if (!info.trial && info.url.isNotBlank()) return info.url
        }
        // 全为试听片段时降级返回第一个试听 URL
        for (level in arrayOf("standard", "higher", "exhigh")) {
            val info = getSongUrlInfo(songId, level) ?: continue
            if (info.url.isNotBlank()) return info.url
        }
        return null
    }

    /** 补全歌曲元数据（标题/艺术家/封面等） */
    suspend fun songDetail(songId: Long): NeteaseSongSearchResult? = withContext(Dispatchers.IO) {
        try {
            val root = request("v3/song/detail", JSONObject().put("c", "[{\"id\":$songId}]"))
            val item = root.optJSONArray("songs")?.optJSONObject(0) ?: return@withContext null
            val artists = item.optJSONArray("ar") ?: item.optJSONArray("artists") ?: JSONArray()
            val artist = List(artists.length()) { artists.getJSONObject(it).optString("name") }
                .filter { it.isNotBlank() }
                .joinToString(" / ")
            val album = item.optJSONObject("al") ?: item.optJSONObject("album")
            val cover = album?.optString("picUrl")?.takeIf { it.isNotBlank() }
            NeteaseSongSearchResult(
                id = item.optLong("id"),
                title = item.optString("name"),
                artist = artist,
                coverUrl = cover,
                coverThumbUrl = cover?.let { thumbUrl(it) },
                duration = item.optLong("dt", 0L)
            )
        } catch (e: Exception) {
            CrashLogManager.logException("NeteaseMusicApi", "获取歌曲详情失败", e)
            null
        }
    }

    private fun searchMatch(keyword: String): List<NeteaseSongMatch> {
        val body = JSONObject().apply {
            put("s", keyword)
            put("type", 1)
            put("limit", 10)
            put("offset", 0)
        }
        val root = request("search/get", body)
        val songs = root.optJSONObject("result")?.optJSONArray("songs") ?: JSONArray()
        return List(songs.length()) { index ->
            val song = songs.getJSONObject(index)
            val artists = song.optJSONArray("artists") ?: song.optJSONArray("ar") ?: JSONArray()
            val artist = List(artists.length()) { artists.getJSONObject(it).optString("name") }
                .filter { it.isNotBlank() }
                .joinToString(" / ")
            val album = song.optJSONObject("album") ?: song.optJSONObject("al")
            NeteaseSongMatch(
                id = song.optLong("id"),
                title = song.optString("name"),
                artist = artist,
                coverUrl = album?.optString("picUrl")?.takeIf { it.isNotBlank() }
            )
        }
    }

    private fun score(song: NeteaseSongMatch, title: String, artist: String, _durationMs: Long): Int {
        val normalizedTitle = normalize(song.title)
        val normalizedArtist = normalize(song.artist)
        var score = if (normalizedTitle == normalize(title)) 0 else 100
        if (artist.isNotBlank() && artist != "未知艺术家" && artist != "<unknown>" && normalizedArtist != normalize(artist)) score += 30
        return score
    }

    private fun normalize(value: String): String = value.lowercase()
        .replace("（", "(")
        .replace("）", ")")
        .replace(Regex("\\([^)]*\\)|\\[[^]]*]"), "")
        .replace(Regex("\\s+"), "")

    private fun request(path: String, body: JSONObject): JSONObject {
        val encrypted = NeteaseCrypto.weapi(body.toString())
        val connection = URL("https://music.163.com/weapi/$path").openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            connection.setRequestProperty("Referer", "https://music.163.com")
            connection.setRequestProperty("X-Real-IP", randomChinaIp())
            connection.setRequestProperty("X-Forwarded-For", randomChinaIp())
            val form = "params=${java.net.URLEncoder.encode(encrypted.getValue("params"), "UTF-8")}&encSecKey=${java.net.URLEncoder.encode(encrypted.getValue("encSecKey"), "UTF-8")}"
            connection.outputStream.use { it.write(form.toByteArray()) }
            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (responseCode !in 200..299) throw IllegalStateException("HTTP $responseCode: $response")
            JSONObject(response)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseLrc(lrc: String, yrc: String): NeteaseLyricData {
        val wordLines = parseYrc(yrc)
        if (wordLines.isNotEmpty()) return NeteaseLyricData(wordLines)
        return NeteaseLyricData(parseLrcText(lrc))
    }

    private fun parseYrc(raw: String): List<LyricLine> {
        val linePattern = Regex("\\[(\\d+),(\\d+)](.*)")
        val wordPattern = Regex("\\((\\d+),(\\d+),\\d+\\)([^()]*)")
        return raw.lineSequence().mapNotNull { rawLine ->
            val line = linePattern.find(rawLine) ?: return@mapNotNull null
            val start = line.groupValues[1].toLong()
            val words = wordPattern.findAll(line.groupValues[3]).map {
                LyricWord(
                    startMs = start + it.groupValues[1].toLong(),
                    durationMs = it.groupValues[2].toLong(),
                    text = it.groupValues[3]
                )
            }.filter { it.text.isNotEmpty() }.toList()
            LyricLine(start, words.joinToString("") { it.text }.trim(), words)
                .takeIf { it.text.isNotBlank() }
        }.toList()
    }

    // 随机中国大陆 IP（降低风控概率）
    private val chinaIpPrefixes = intArrayOf(36, 39, 42, 58, 59, 60, 101, 106, 110, 111, 112, 113, 114, 115, 116, 117, 118, 119, 120, 121, 122, 123, 124, 125, 175, 180, 182, 183, 202, 203, 210, 211, 218, 219, 220, 221, 222, 223)
    private fun randomChinaIp(): String {
        val a = chinaIpPrefixes[kotlin.random.Random.nextInt(chinaIpPrefixes.size)]
        return "$a.${kotlin.random.Random.nextInt(256)}.${kotlin.random.Random.nextInt(256)}.${1 + kotlin.random.Random.nextInt(254)}"
    }

    // CDN 缩略图 URL：追加 ?param=128y128 请求小图
    internal fun thumbUrl(coverUrl: String): String {
        return coverUrl + if (coverUrl.contains("?")) "&param=128y128" else "?param=128y128"
    }

    // 确保 URL 使用 HTTPS（网易云 CDN 可能返回 HTTP 链接）
    private fun ensureHttps(url: String): String {
        return if (url.startsWith("http://")) url.replace("http://", "https://") else url
    }
}

// 标准 LRC 解析：网易云（无逐字标签时）、QQ、酷狗均复用
internal fun parseLrcText(lrc: String): List<LyricLine> {
    return lrc.lineSequence().mapNotNull { line ->
        val match = Regex("\\[(\\d+):(\\d+)(?:\\.(\\d+))?](.*)").find(line) ?: return@mapNotNull null
        LyricLine(
            timeMs = match.groupValues[1].toLong() * 60_000 +
                    match.groupValues[2].toLong() * 1_000 +
                    match.groupValues[3].padEnd(3, '0').take(3).toLong(),
            text = match.groupValues[4].trim()
        ).takeIf { it.text.isNotBlank() }
    }.sortedBy { it.timeMs }.toList()
}

// 把字符串平台标识（QQ 的 songmid、酷狗的 hash）转成稳定的数字 id，
// 搜索结果列表和播放列表统一用 Long 类型 id 做去重与关联
internal fun stableIdFromString(value: String): Long {
    var hash = -0x340d631b7bdddcdbL
    for (c in value) {
        hash = hash xor c.code.toLong()
        hash *= 0x100000001b3L
    }
    return hash
}
