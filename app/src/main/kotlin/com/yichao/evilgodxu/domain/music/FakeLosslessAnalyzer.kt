package com.yichao.evilgodxu.domain.music

import android.content.Context
import com.yichao.evilgodxu.data.music.model.MusicTrack
import kotlin.math.log10
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

// 假无损识别器：两级判定，逐曲串行，契合资源受限设备整库校验。
// ① 轻量预筛：扩展名 + FLAC 容器头，仅排除非 FLAC 与超低规格（<44.1kHz/<16bit/<2ch）文件；
//    不设码率压缩比/头部规格免检路径——伪造文件可借量化噪声/上采样令码率虚高，
//    头部参数亦不可信，任何候选文件都不得绕过频谱判定；
// ② 频谱判定：全部候选 FLAC 用共享 SpectralDecoder 稀疏窗口解码 3 段（每窗 4 秒），FFT 求平均
//    功率谱，两条互补判据任一命中即判为可疑：
//    a) 砖墙主判据：奈奎斯特以下「砖墙式」截止 + 平坦死区，表征有损转码来源；
//    b) 升频锚点判据：截止恰为源采样率奈奎斯特（22.05k/24k）且其上方整体空虚平坦，
//       覆盖升频假无损——廉价重采样在锚点上方留下的斜坡过渡带会令 a) 漏判，
//       而真高解析度在 22.05k 上下存在自然连续内容，不会被 b) 误伤。
// 结果持久化缓存与批量增量校验复用 TrackVerdictCache；
// 进度由调用方逐曲驱动，协程取消即时释放解码器。
internal object FakeLosslessAnalyzer {

    // 假无损智能歌单过滤键：与本地化展示名解耦，保证序列化歌单 key 跨语言环境稳定
    const val FAKE_LOSSLESS_KEY = "fake-lossless"

    // 识别结果缓存：键含文件大小与时长，文件变化即失效；跨对话框/刷新复用避免重复解码
    private val cache = TrackVerdictCache("fake_lossless_cache.json")

    // 批量增量校验期间每分析多少首新增文件落盘一次，收窄中断导致的缓存丢失窗口
    private const val CACHE_PERSIST_INTERVAL = 20

    // 是否为可校验的 FLAC 文件：扩展名口径，与曲库分析的 FLAC 格式类目一致
    fun isFlacCandidate(track: MusicTrack): Boolean =
        track.path.substringAfterLast('.', "").uppercase() == "FLAC"

    // 缓存键：路径 + 文件大小 + 时长齐备，文件内容变化即失效
    internal fun cacheKey(track: MusicTrack, sizeBytes: Long): String =
        "FLAC\u0000${track.path}\u0000$sizeBytes\u0000${track.duration}"

    // 判定入口：非 FLAC 或无法读取大小直接排除；缓存命中直接复用（含重启前持久化结果）
    suspend fun isSuspectedFakeLossless(context: Context, track: MusicTrack): Boolean {
        if (!isFlacCandidate(track)) return false
        cache.awaitLoaded(context)
        val sizeBytes = TrackAudioInfoReader.readFileSize(context, track) ?: return false
        val key = cacheKey(track, sizeBytes)
        cache.get(key)?.let { return it }
        val result = withContext(Dispatchers.IO) { analyze(context, track, sizeBytes) }
        // 无法判定的结果也缓存为 false：避免歌单过滤时对未判定文件重复做昂贵的频谱分析，
        // 导致假无损歌单切换看似无响应；识别策略升级后由「刷新」清空缓存强制重新校验
        cache.map[key] = result ?: false
        cache.schedulePersist(context)
        return result ?: false
    }

    // 清除全部校验缓存（内存 + 落盘）：识别策略升级或用户主动刷新时用于强制全量重新分析，
    // 避免旧版本判定结果（如放宽标准时的「真无损」）被持久化缓存复用而漏掉假无损
    suspend fun resetCache(context: Context) = cache.reset(context)

    // 批量增量校验（曲库分析对话框入口）：按缓存键划分「已校验旧文件 / 待校验新文件」，
    // 仅对新增或内容变更的文件逐曲解码，缓存命中的旧文件直接复用持久化结果；
    // onProgress 以新增文件数为基数回传进度（全部命中时瞬时完成）；
    // 周期落盘收窄中断丢失窗口，结束清理已删除文件的残留条目并最终落盘
    suspend fun analyzeLibraryIncremental(
        context: Context,
        tracks: List<MusicTrack>,
        onProgress: suspend (checked: Int, total: Int) -> Unit,
    ): Int {
        cache.awaitLoaded(context)
        return withContext(Dispatchers.IO) io@{
            val pending = mutableListOf<Pair<MusicTrack, Long>>()
            val keepKeys = HashSet<String>()
            var count = 0
            tracks.forEach { track ->
                if (!isFlacCandidate(track)) return@forEach
                val sizeBytes = TrackAudioInfoReader.readFileSize(context, track) ?: return@forEach
                val key = cacheKey(track, sizeBytes)
                keepKeys.add(key)
                val cached = cache.get(key)
                if (cached != null) {
                    if (cached) count++
                } else {
                    pending.add(track to sizeBytes)
                }
            }
            if (pending.isEmpty()) {
                // 无新增文件：仅当存在已删除文件的残留条目时清理，避免无谓写盘
                if (cache.map.size > keepKeys.size) {
                    withContext(NonCancellable) {
                        cache.map.keys.removeAll { key -> key !in keepKeys }
                        cache.flush(context)
                    }
                }
                return@io count
            }
            onProgress(0, pending.size)
            var checked = 0
            try {
                pending.forEach { (track, sizeBytes) ->
                    checked++
                    onProgress(checked, pending.size)
                    val key = cacheKey(track, sizeBytes)
                    val result = analyze(context, track, sizeBytes)
                    // 无法判定的结果也缓存为 false：避免同批文件每次重开对话框都重新分析；
                    // 识别策略升级后由「刷新」清空缓存强制全量重新校验
                    cache.map[key] = result ?: false
                    if (result == true) count++
                    if (checked % CACHE_PERSIST_INTERVAL == 0) cache.flush(context)
                }
            } finally {
                // 对话框中途关闭取消协程时也落盘已完成结果，避免已分析结果随进程退出丢失
                withContext(NonCancellable) {
                    if (cache.map.size > keepKeys.size) {
                        cache.map.keys.removeAll { key -> key !in keepKeys }
                    }
                    cache.flush(context)
                }
            }
            count
        }
    }

    // 单曲判定：返回 null 表示无法判定（时长/大小无效或解码整体不可用），调用方缓存为 false。
    // 容器头不可读（如带 ID3v2 前置标签的 FLAC）不直接放弃：SpectralDecoder 仍可定位音频流，
    // 交由频谱分析以提取器格式参数兜底；仅低规格参数明确时才直接排除
    private suspend fun analyze(context: Context, track: MusicTrack, sizeBytes: Long): Boolean? {
        if (track.duration <= 0 || sizeBytes <= 0) return null
        val format = TrackAudioInfoReader.readFlacContainerFormat(context, track)
        // 超低规格（<44.1kHz/<16bit/<2ch）非假无损伪装目标，且带宽受限天然带高频截止，直接排除
        if (format != null && (format.sampleRate < 44100 || format.bitDepth < 16 || format.channels < 2)) {
            return false
        }
        // 频谱分析为强制判定环节：头部规格与码率压缩比不提供免检放行（伪造文件可借量化噪声/
        // 上采样令码率虚高，码率判据会失真）；null 表示解码不可用
        val summary = SpectralDecoder.decodeTrack(
            track,
            expectedMime = "audio/flac",
            fallbackSampleRate = format?.sampleRate ?: 0,
            fallbackChannels = format?.channels ?: 0,
        ) ?: return null
        return detectCliff(summary.powerSum, summary.blocks, summary.sampleRate)
    }

    // 砖墙截止判定：对平均功率谱求分贝，找显著下降沿 + 上方死区平坦且明显低于奈奎斯特。
    // 噪声底取高半区最小值：转码死区即落在该电平，真无损高区有真实内容故不会被误判。
    // 主判据未命中时转入升频锚点判据，补抓被重采样过渡带软化的截止形态
    private fun detectCliff(powerSum: FloatArray, blocks: Int, sampleRate: Int): Boolean {
        val n = SpectralDecoder.FFT_SIZE / 2
        val db = FloatArray(n + 1)
        var peakDb = Float.NEGATIVE_INFINITY
        for (i in 0..n) {
            db[i] = 10f * log10((powerSum[i] / blocks + 1e-12f).toDouble()).toFloat()
            if (db[i] > peakDb) peakDb = db[i]
        }
        for (i in 0..n) db[i] -= peakDb
        // 前缀和：窗口均值 O(1) 查询
        val prefix = FloatArray(n + 2)
        for (i in 0..n) prefix[i + 1] = prefix[i] + db[i]
        fun mean(from: Int, to: Int): Float =
            ((prefix[to + 1] - prefix[from]) / (to - from + 1).toFloat())

        val binHz = sampleRate.toFloat() / SpectralDecoder.FFT_SIZE
        val nyquist = sampleRate / 2f
        val floorStart = (0.5f * nyquist / binHz).toInt().coerceIn(0, n)
        var floor = db[floorStart]
        for (i in floorStart + 1..n) if (db[i] < floor) floor = db[i]
        // 整体动态过小则无法判定
        if (peakDb - floor < 40f) return false

        val leftBins = (1500f / binHz).toInt().coerceAtLeast(8)
        val rightBins = (3000f / binHz).toInt().coerceAtLeast(16)
        val minCutoffBin = (3000f / binHz).toInt() + 1
        var b = n - 1
        while (b > minCutoffBin) {
            // 右侧死区：截止点到右端应为平坦噪声底
            val rightTo = (b + rightBins).coerceAtMost(n)
            val rightAvg = mean(b, rightTo)
            val leftFrom = (b - leftBins).coerceAtLeast(1)
            val leftAvg = mean(leftFrom, b - 1)
            // 硬切判据：下降沿 ≥35dB 且上方死区平坦（≤8dB 相对噪声底）
            if (leftAvg - floor >= 35f && rightAvg - floor <= 8f) {
                val cutoffHz = b * binHz
                if (cutoffHz <= 0.9f * nyquist) return true
            }
            b--
        }
        // 砖墙形态不成立时，检查升频锚点（源采样率奈奎斯特处的墙体 + 上方空虚）
        return detectUpsampledAnchorWall(binHz, nyquist, n, floor, db, prefix, sampleRate)
    }

    // 升频假无损锚点判据：真高解析度在 22.05k/24k 上下有自然连续内容，
    // 升频文件则在源采样率奈奎斯特处残留墙体，其上方整体空虚平坦——
    // 即使廉价重采样将墙体软化为斜坡，锚点上方仍无真实八度内容可比。
    // 原生采样率文件（如 44.1k 本身）该位置无可用死区，自动豁免免误伤
    private fun detectUpsampledAnchorWall(
        binHz: Float,
        nyquist: Float,
        n: Int,
        floor: Float,
        db: FloatArray,
        prefix: FloatArray,
        sampleRate: Int,
    ): Boolean {
        // 前缀和区间均值：与 detectCliff 同套路，O(1) 查询
        fun mean(from: Int, to: Int): Float =
            ((prefix[to + 1] - prefix[from]) / (to - from + 1).toFloat())
        // 候选锚点集：常见源采样率的奈奎斯特（CD 22.05k / 48k 源 24k），
        // 仅在文件采样率高于锚点时启用，避免对原生规格误判
        val anchors = when {
            sampleRate >= 88200 -> floatArrayOf(22050f, 24000f)
            sampleRate == 48000 -> floatArrayOf(22050f)
            else -> FloatArray(0)
        }
        for (anchorHz in anchors) {
            // 锚点需深入奈奎斯特以下且留足死区宽度（≥800Hz）供判定
            if (anchorHz + 800f > nyquist) continue
            val anchorBin = (anchorHz / binHz).toInt()
            val leftFrom = (anchorBin - (2200f / binHz).toInt()).coerceAtLeast(1)
            val leftTo = (anchorBin - (300f / binHz).toInt()).coerceAtLeast(1)
            if (leftTo <= leftFrom) continue
            val rightFrom = (anchorBin + (300f / binHz).toInt()).coerceAtMost(n)
            val rightTo = (anchorBin + (3400f / binHz).toInt()).coerceAtMost(n)
            if (rightTo <= rightFrom) continue
            val leftAvg = mean(leftFrom, leftTo)
            val rightAvg = mean(rightFrom, rightTo)
            var rightMax = db[rightFrom]
            var rightMin = rightMax
            for (i in rightFrom..rightTo) {
                if (db[i] > rightMax) rightMax = db[i]
                if (db[i] < rightMin) rightMin = db[i]
            }
            // 墙体：锚点两侧落差 ≥20dB，上方空虚平坦（均值贴近整体噪声底、
            // 极差 ≤14dB、且深于 -45dB 相对峰值），构成升频独有指纹
            if (leftAvg - rightAvg >= 20f &&
                rightAvg - floor <= 14f &&
                rightMax - rightMin <= 14f &&
                rightAvg <= -45f
            ) {
                return true
            }
        }
        return false
    }
}