package com.yichao.evilgodxu.musicpanel.proxy

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray

// 代理音源持久化：SharedPreferences 存储原始 JSON 列表与启用状态，同名音源重复导入时覆盖
internal object ProxySourceStore {

    private const val PREFS_NAME = "proxy_sources"
    private const val KEY_SOURCES = "sources_json"
    private const val KEY_ENABLED = "enabled_names"

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
        // 新导入音源默认启用
        val enabled = enabledNames(context).toMutableSet()
        enabled.add(spec.name)
        saveEnabledNames(context, enabled)
        return parsed
    }

    fun remove(context: Context, name: String) {
        val list = rawList(context).toMutableList()
        list.removeAll { rawJson ->
            val existing = ProxySourceParser.parse(rawJson)
            existing is ProxyParseResult.Success && existing.spec.name == name
        }
        saveRawList(context, list)
        val enabled = enabledNames(context).toMutableSet()
        enabled.remove(name)
        saveEnabledNames(context, enabled)
    }

    // 切换音源启用状态，停用的音源即时停止参与解析
    fun setEnabled(context: Context, name: String, enabled: Boolean) {
        val names = enabledNames(context).toMutableSet()
        if (enabled) names.add(name) else names.remove(name)
        saveEnabledNames(context, names)
    }

    // 全部已导入音源，按导入顺序返回（附带启用状态）
    fun all(context: Context): List<ProxySourceSpec> {
        val enabled = enabledNames(context)
        return rawList(context).mapNotNull { rawJson ->
            val spec = (ProxySourceParser.parse(rawJson) as? ProxyParseResult.Success)?.spec ?: return@mapNotNull null
            spec.copy(enabled = enabled.contains(spec.name))
        }
    }

    // 指定平台的生效音源：仅启用音源中同一平台多音源时，最近导入者生效
    fun activeSpec(context: Context, platform: String): ProxySourceSpec? {
        return all(context)
            .filter { it.enabled }
            .lastOrNull { it.platforms.containsKey(platform) }
    }

    fun platformSpec(context: Context, platform: String): ProxyPlatformSpec? {
        return activeSpec(context, platform)?.platforms?.get(platform)
    }

    private fun saveRawList(context: Context, list: List<String>) {
        val array = JSONArray()
        list.forEach { array.put(it) }
        prefs(context).edit {
            putString(KEY_SOURCES, array.toString())
        }
    }

    private fun rawList(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_SOURCES, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            List(array.length()) { array.optString(it).trim() }.filter { it.isNotEmpty() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveEnabledNames(context: Context, names: Set<String>) {
        val array = JSONArray()
        names.forEach { array.put(it) }
        prefs(context).edit {
            putString(KEY_ENABLED, array.toString())
        }
    }

    private fun enabledNames(context: Context): Set<String> {
        val raw = prefs(context).getString(KEY_ENABLED, null) ?: return emptySet()
        return try {
            val array = JSONArray(raw)
            List(array.length()) { array.optString(it).trim() }.filter { it.isNotEmpty() }.toSet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}