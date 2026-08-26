package com.yichao.evilgodxu.musicpanel.proxy

import com.yichao.evilgodxu.musicpanel.MusicQuality
import org.json.JSONObject

// 代理音源解析结果：成功返回规范模型，失败返回面向用户的提示
internal sealed interface ProxyParseResult {
    data class Success(val spec: ProxySourceSpec) : ProxyParseResult
    data class Failure(val reason: String) : ProxyParseResult
}

// 代理音源解析与校验：严格校验必填字段，宽松处理可选动作
internal object ProxySourceParser {

    // 应用内支持的平台键，与 MusicSearchSource 名称小写对齐
    val SUPPORTED_PLATFORMS = setOf("netease", "qq", "kugou", "kuwo", "migu")

    private val QUALITY_KEYS = mapOf(
        "standard" to MusicQuality.STANDARD,
        "high" to MusicQuality.HIGH,
        "lossless" to MusicQuality.LOSSLESS,
    )

    fun parse(raw: String): ProxyParseResult {
        val root = try {
            JSONObject(raw)
        } catch (e: Exception) {
            return ProxyParseResult.Failure("JSON 解析失败")
        }
        val name = root.optString("name").trim()
        if (name.isEmpty()) return ProxyParseResult.Failure("缺少必填字段 name")

        val platformsObj = root.optJSONObject("platforms")
            ?: return ProxyParseResult.Failure("缺少必填字段 platforms")
        val platforms = mutableMapOf<String, ProxyPlatformSpec>()
        platformsObj.keys().forEach { key ->
            if (key !in SUPPORTED_PLATFORMS) return@forEach
            val platformObj = platformsObj.optJSONObject(key) ?: return@forEach
            parsePlatform(platformObj)?.let { platforms[key] = it }
        }
        if (platforms.isEmpty()) {
            return ProxyParseResult.Failure("未定义受支持平台（netease/qq/kugou/kuwo/migu）")
        }

        return ProxyParseResult.Success(
            ProxySourceSpec(
                name = name,
                version = root.optString("version", "").trim(),
                author = root.optString("author", "").trim(),
                homepage = root.optString("homepage", "").trim(),
                description = root.optString("description", "").trim(),
                platforms = platforms,
                rawJson = raw,
            )
        )
    }

    private fun parsePlatform(platform: JSONObject): ProxyPlatformSpec? {
        return ProxyPlatformSpec(
            search = if (platform.has("search")) parseAction(platform.optJSONObject("search")) else null,
            url = if (platform.has("url")) parseAction(platform.optJSONObject("url")) else null,
            lyric = if (platform.has("lyric")) parseAction(platform.optJSONObject("lyric")) else null,
            pic = if (platform.has("pic")) parseAction(platform.optJSONObject("pic")) else null,
        ).takeIf { it.hasAnyAction }
    }

    private fun parseAction(actionObj: JSONObject?): ProxyActionSpec? {
        if (actionObj == null) return null
        val url = actionObj.optString("url").trim()
        if (url.isEmpty()) return null
        return ProxyActionSpec(
            method = actionObj.optString("method", "GET").trim().ifEmpty { "GET" },
            url = url,
            params = parseStringMap(actionObj.optJSONObject("params")),
            query = parseStringMap(actionObj.optJSONObject("query")),
            headers = parseStringMap(actionObj.optJSONObject("headers")),
            qualities = parseQualities(actionObj.optJSONObject("qualities")),
            result = parseResult(actionObj.optJSONObject("result")),
        )
    }

    private fun parseStringMap(obj: JSONObject?): Map<String, String> {
        if (obj == null) return emptyMap()
        val map = mutableMapOf<String, String>()
        obj.keys().forEach { key ->
            val value = obj.opt(key)
            if (value != null && value != JSONObject.NULL) {
                map[key] = value.toString()
            }
        }
        return map
    }

    private fun parseQualities(obj: JSONObject?): Map<MusicQuality, String> {
        if (obj == null) return emptyMap()
        val map = mutableMapOf<MusicQuality, String>()
        QUALITY_KEYS.forEach { (key, quality) ->
            if (obj.has(key)) {
                val value = obj.opt(key)
                if (value != null && value != JSONObject.NULL) {
                    map[quality] = value.toString()
                }
            }
        }
        return map
    }

    private fun parseResult(obj: JSONObject?): ProxyResultSpec {
        if (obj == null) return ProxyResultSpec()
        return ProxyResultSpec(
            list = obj.optString("list", "").takeIf { it.isNotBlank() },
            id = obj.optString("id", "").takeIf { it.isNotBlank() },
            title = obj.optString("title", "").takeIf { it.isNotBlank() },
            artist = obj.optString("artist", "").takeIf { it.isNotBlank() },
            coverUrl = obj.optString("coverUrl", "").takeIf { it.isNotBlank() },
            coverId = obj.optString("coverId", "").takeIf { it.isNotBlank() },
            sourceId = obj.optString("sourceId", "").takeIf { it.isNotBlank() },
            duration = obj.optString("duration", "").takeIf { it.isNotBlank() },
            url = obj.optString("url", "").takeIf { it.isNotBlank() },
            lyric = obj.optString("lyric", "").takeIf { it.isNotBlank() },
            tlyric = obj.optString("tlyric", "").takeIf { it.isNotBlank() },
        )
    }
}