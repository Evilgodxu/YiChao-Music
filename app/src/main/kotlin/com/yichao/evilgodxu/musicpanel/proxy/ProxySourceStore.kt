package com.yichao.evilgodxu.musicpanel.proxy

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray

// 代理音源持久化：SharedPreferences 存储原始 JSON 列表，同名音源重复导入时覆盖
internal object ProxySourceStore {

    private const val PREFS_NAME = "proxy_sources"
    private const val KEY_SOURCES = "sources_json"

    fun import(context: Context, raw: String): ProxyParseResult {
        val parsed = ProxySourceParser.parse(raw)
        if (parsed is ProxyParseResult.Failure) return parsed
        val spec = (parsed as ProxyParseResult.Success).spec
        val list = rawList(context).toMutableList()
        list.removeAll { rawJson ->
            val existing = ProxySourceParser.parse(rawJson)
            existing is ProxyParseResult.Success && existing.spec.name == spec.name
        }
        list.add(spec.rawJson)
        saveRawList(context, list)
        return parsed
    }

    fun remove(context: Context, name: String) {
        val list = rawList(context).toMutableList()
        list.removeAll { rawJson ->
            val existing = ProxySourceParser.parse(rawJson)
            existing is ProxyParseResult.Success && existing.spec.name == name
        }
        saveRawList(context, list)
    }

    // 全部已导入音源，按导入顺序返回
    fun all(context: Context): List<ProxySourceSpec> {
        return rawList(context).mapNotNull { rawJson ->
            (ProxySourceParser.parse(rawJson) as? ProxyParseResult.Success)?.spec
        }
    }

    // 指定平台的生效音源：同一平台多音源时，最近导入者生效
    fun activeSpec(context: Context, platform: String): ProxySourceSpec? {
        return all(context).lastOrNull { it.platforms.containsKey(platform) }
    }

    fun platformSpec(context: Context, platform: String): ProxyPlatformSpec? {
        return activeSpec(context, platform)?.platforms?.get(platform)
    }

    private fun saveRawList(context: Context, list: List<String>) {
        val array = JSONArray()
        list.forEach { array.put(it) }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putString(KEY_SOURCES, array.toString())
        }
    }

    private fun rawList(context: Context): List<String> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SOURCES, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            List(array.length()) { array.optString(it).trim() }.filter { it.isNotEmpty() }
        } catch (e: Exception) {
            emptyList()
        }
    }
}