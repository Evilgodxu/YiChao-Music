package com.yichao.evilgodxu.domain.music

import android.content.Context
import com.yichao.evilgodxu.data.music.model.MusicTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

// 合并批量分析结果：假无损与 AI 各识别的命中数
internal data class LibraryAnalysisResult(
    val fakeLosslessCount: Int,
    val aiMusicCount: Int,
)

// 合并单次遍历：假无损与 AI 识别共享一次整库扫描，同一待分析文件仅解码一次，
// 同时产出两类判定并分别写入各自持久化缓存，消除原先两分析器各自全库遍历导致的
// FLAC 重复解码。进度以「本批需解码文件总数」为基数连续递增，不再出现两段式重跑。
internal suspend fun analyzeLibraryCombined(
    context: Context,
    tracks: List<MusicTrack>,
    onProgress: suspend (checked: Int, total: Int) -> Unit,
): LibraryAnalysisResult {
    val fakeCache = FakeLosslessAnalyzer.cache
    val aiCache = AiMusicAnalyzer.cache
    fakeCache.awaitLoaded(context)
    aiCache.awaitLoaded(context)
    return withContext(Dispatchers.IO) io@{
        // 待解码文件：至少一个分析器缓存未命中；各分析器独立记账缓存命中数
        data class Pending(val track: MusicTrack, val sizeBytes: Long, val fakeWanted: Boolean, val aiWanted: Boolean)
        val pending = ArrayList<Pending>()
        val keepFake = HashSet<String>()
        val keepAi = HashSet<String>()
        var fakeCount = 0
        var aiCount = 0
        tracks.forEach { track ->
            val isFlac = FakeLosslessAnalyzer.isFlacCandidate(track)
            val isAiCandidate = AiMusicAnalyzer.isDecodableCandidate(track)
            if (!isFlac && !isAiCandidate) return@forEach
            val sizeBytes = TrackAudioInfoReader.readFileSize(context, track) ?: return@forEach
            var fakeWanted = false
            if (isFlac) {
                val key = FakeLosslessAnalyzer.cacheKey(track, sizeBytes)
                keepFake.add(key)
                val cached = fakeCache.get(key)
                if (cached != null) {
                    if (cached) fakeCount++
                } else {
                    // 低规格豁免：读容器头后可免解码判定非假无损（不持久化，下次扫描重检，
                    // 代价仅为读一次文件头，换取无需引入结果落盘的脏标记）
                    if (!FakeLosslessAnalyzer.isLowSpecFakeLossless(context, track)) {
                        fakeWanted = true
                    }
                }
            }
            var aiWanted = false
            if (isAiCandidate) {
                val key = AiMusicAnalyzer.cacheKey(track, sizeBytes)
                keepAi.add(key)
                val cached = aiCache.get(key)
                if (cached != null) {
                    if (cached) aiCount++
                } else {
                    aiWanted = true
                }
            }
            if (fakeWanted || aiWanted) pending.add(Pending(track, sizeBytes, fakeWanted, aiWanted))
        }
        if (pending.isEmpty()) {
            // 无待解码文件：仅当存在已删除文件的残留条目时清理，避免无谓写盘
            if (fakeCache.map.size > keepFake.size || aiCache.map.size > keepAi.size) {
                withContext(NonCancellable) {
                    if (fakeCache.map.size > keepFake.size) {
                        fakeCache.map.keys.removeAll { key -> key !in keepFake }
                    }
                    if (aiCache.map.size > keepAi.size) {
                        aiCache.map.keys.removeAll { key -> key !in keepAi }
                    }
                    fakeCache.flush(context)
                    aiCache.flush(context)
                }
            }
            return@io LibraryAnalysisResult(fakeCount, aiCount)
        }
        onProgress(0, pending.size)
        var checked = 0
        try {
            pending.forEach { p ->
                checked++
                onProgress(checked, pending.size)
                // 单次解码：一律取首个音频轨（FLAC 文件的唯一音频流即 FLAC 轨，等效于
                // 单曲入口的指定 mime 解码；采样率/声道由提取器提供，无需容器头兜底）
                val summary = SpectralDecoder.decodeTrack(p.track, expectedMime = null)
                if (p.fakeWanted) {
                    val key = FakeLosslessAnalyzer.cacheKey(p.track, p.sizeBytes)
                    // 无法判定的结果也缓存为 false：避免同批文件每次重开对话框都重新分析；
                    // 识别策略升级后由「刷新」清空缓存强制全量重新校验
                    val verdict = summary?.let { FakeLosslessAnalyzer.verdictFromSummary(it) } ?: false
                    fakeCache.map[key] = verdict
                    if (verdict) fakeCount++
                }
                if (p.aiWanted) {
                    val key = AiMusicAnalyzer.cacheKey(p.track, p.sizeBytes)
                    val verdict = summary?.let { AiMusicAnalyzer.verdictFromSummary(it) } ?: false
                    aiCache.map[key] = verdict
                    if (verdict) aiCount++
                }
                // 每分析 20 首落盘一次，收窄中断导致的缓存丢失窗口
                if (checked % CACHE_PERSIST_INTERVAL == 0) {
                    fakeCache.flush(context)
                    aiCache.flush(context)
                }
            }
        } finally {
            // 对话框中途关闭取消协程时也落盘已完成结果，避免已分析结果随进程退出丢失
            withContext(NonCancellable) {
                if (fakeCache.map.size > keepFake.size) {
                    fakeCache.map.keys.removeAll { key -> key !in keepFake }
                }
                if (aiCache.map.size > keepAi.size) {
                    aiCache.map.keys.removeAll { key -> key !in keepAi }
                }
                fakeCache.flush(context)
                aiCache.flush(context)
            }
        }
        LibraryAnalysisResult(fakeCount, aiCount)
    }
}

// 批量分析期间每分析多少首新增文件落盘一次
private const val CACHE_PERSIST_INTERVAL = 20