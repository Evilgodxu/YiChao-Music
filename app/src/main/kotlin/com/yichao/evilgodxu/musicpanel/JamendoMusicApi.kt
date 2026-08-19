package com.yichao.evilgodxu.musicpanel

import com.yichao.evilgodxu.log.CrashLogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

internal object JamendoMusicApi : OnlineMusicSource {
    private const val CLIENT_ID = "619ee256"

    // 在线音乐源统一接口实现，返回带可直接播放音频地址的搜索结果
    override suspend fun search(keyword: String): List<NeteaseSongSearchResult> = withContext(Dispatchers.IO) {
        try {
            val query = URLEncoder.encode(keyword, "UTF-8")
            val url = "https://api.jamendo.com/v3.0/tracks/?client_id=$CLIENT_ID" +
                    "&format=json&limit=20&search=$query&audioformat=mp32&imagesize=300"
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "GET"
                connection.connectTimeout = 10_000
                connection.readTimeout = 15_000
                val responseCode = connection.responseCode
                if (responseCode !in 200..299) throw IllegalStateException("HTTP $responseCode")
                val response = connection.inputStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                val results = JSONObject(response).optJSONArray("results") ?: return@withContext emptyList()
                List(results.length()) { index ->
                    val item = results.getJSONObject(index)
                    val cover = item.optString("image").takeIf { it.isNotBlank() }
                    NeteaseSongSearchResult(
                        id = item.optLong("id"),
                        title = item.optString("name"),
                        artist = item.optString("artist_name"),
                        coverUrl = cover,
                        coverThumbUrl = cover,
                        // Jamendo 返回的时长单位为秒，转换为毫秒与网易云一致
                        duration = item.optLong("duration", 0L) * 1000L,
                        source = MusicSearchSource.JAMENDO,
                        audioUrl = item.optString("audio").takeIf { it.isNotBlank() }
                    )
                }.filter { it.audioUrl != null }
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            CrashLogManager.logException("JamendoMusicApi", "搜索歌曲失败", e)
            emptyList()
        }
    }
}
