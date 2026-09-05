package com.yichao.evilgodxu.domain.music

import android.content.Context
import com.yichao.evilgodxu.data.music.model.MusicTrack
import kotlin.math.log10
import kotlinx.coroutines.Dispatchers
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

    // 识别结果缓存：键含文件大小与时长，文件变化即失效；供合并批量分析共享复用
    internal val cache = TrackVerdictCache("fake_lossless_cache.json")

    // 立体声相关性佐证：统计所需最少样本数（约 0.1 秒），不足视为无证据；
    // 砖墙命中且相关性不高于该值时，视为自然限带内容（真去相关）而非转码，降级放行
    private const val STEREO_MIN_SAMPLES = 4096L
    private const val STEREO_CORR_AGAINST_MAX = 0.55f

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

    // 低规格豁免判断（纯函数）：容器头可读且规格不足（<44.1kHz/<16bit/<2ch）时，
    // 带宽受限天然带高频截止，非假无损伪装目标，无需解码即可排除；
    // 容器头不可读时不豁免，交由频谱分析以解码格式参数兜底
    internal fun isLowSpecFakeLossless(format: TrackAudioInfoReader.ContainerFormat?): Boolean =
        format != null && (format.sampleRate < 44100 || format.bitDepth < 16 || format.channels < 2)

    // 低规格豁免入口：读容器头后按纯函数判定，供合并批量分析在解码前排除
    internal fun isLowSpecFakeLossless(context: Context, track: MusicTrack): Boolean =
        isLowSpecFakeLossless(TrackAudioInfoReader.readFlacContainerFormat(context, track))

    // 解码摘要判定：假无损判定核心（砖墙 / 升频锚点 + CD 级相关性反驳），
    // 供合并批量分析（analyzeLibraryCombined）复用已解码摘要，避免与单曲入口重复解码
    internal fun verdictFromSummary(summary: SpectralDecoder.DecodeSummary): Boolean =
        detectFakeSignals(summary)

    // 单曲判定：返回 null 表示无法判定（时长/大小无效或解码整体不可用），调用方缓存为 false。
    // 容器头不可读（如带 ID3v2 前置标签的 FLAC）不直接放弃：SpectralDecoder 仍可定位音频流，
    // 交由频谱分析以提取器格式参数兜底；仅低规格参数明确时才直接排除
    private suspend fun analyze(context: Context, track: MusicTrack, sizeBytes: Long): Boolean? {
        if (track.duration <= 0 || sizeBytes <= 0) return null
        val format = TrackAudioInfoReader.readFlacContainerFormat(context, track)
        if (isLowSpecFakeLossless(format)) return false
        // 频谱分析为强制判定环节：头部规格与码率压缩比不提供免检放行（伪造文件可借量化噪声/
        // 上采样令码率虚高，码率判据会失真）；容器头值作为提取器缺参兜底；null 表示解码不可用
        val summary = SpectralDecoder.decodeTrack(
            track,
            expectedMime = "audio/flac",
            fallbackSampleRate = format?.sampleRate ?: 0,
            fallbackChannels = format?.channels ?: 0,
        ) ?: return null
        return detectFakeSignals(summary)
    }

    // 假无损合成判定。两条主判据按文件规格分流，立体声相关性仅用于 CD 级防误判：
    // ① CD 级（44.1k/48k）：砖墙命中即未升频的直接转码。但老录音/窄母带等
    //    自然限带也可能撞出硬墙，若左右声道明确去相关（≤0.55）则放行，否则判真。
    // ② 高解析（≥88.2k）：不存在「自然限带」豁免，因为真实高解析录音不会在
    //    22.05k/24k（CD 奈奎斯特）处凭空出现 ≥35dB 硬切 + 上方死区。
    //    墙体未软化 → 砖墙命中，直接判真；
    //    墙体被重采样软化 → 砖墙未命中，转升频锚点判据抓「源奈奎斯特残留墙 + 上方死区」。
    // 因此升频假无损无论重采样好坏，都会被 ①/② 之一抓住——硬墙归砖墙，软墙归锚点。
    // 唯一漏报面：CD 源本身 20k 以上几乎没有内容时，升频后左带也无能量可作落差，
    // 两条判据都无从下手，频谱上与真高解析无法区分，属信息论极限（所有工具共有）
    private fun detectFakeSignals(s: SpectralDecoder.DecodeSummary): Boolean {
        val stats = computeSpectralStats(s.powerSum, s.blocks, s.sampleRate) ?: return false
        // ① 砖墙：先抓「未升频转码」与「硬墙升频」，再按规格做防误判分流
        if (detectCliff(stats)) {
            // 高解析文件的砖墙命中是升频残留证据，直接判真
            if (stats.sampleRate > 48000) return true
            // CD 级文件：去相关内容撞出硬墙更可能来自自然限带，放行；否则判真
            val hasStereoEvidence = s.channels == 2 && s.stereoCorrSamples >= STEREO_MIN_SAMPLES
            return !(hasStereoEvidence && s.stereoCorrelation <= STEREO_CORR_AGAINST_MAX)
        }
        // ② 升频锚点：砖墙未命中时抓「软墙升频」，高解析源才启用，不受相关性影响
        return detectUpsampledAnchorWall(stats)
    }

    // 频谱统计中间量：砖墙与升频锚点判据共享的相对峰值分贝谱、区间均值前缀和、噪声底与频率刻度
    private class SpectralStats(
        val db: FloatArray,
        val prefix: FloatArray,
        val floor: Float,
        val binHz: Float,
        val nyquist: Float,
        val n: Int,
        val sampleRate: Int,
    )

    // 平均功率谱转相对峰值分贝谱并提取共享统计；整体动态过小返回 null（无判定能力）
    private fun computeSpectralStats(
        powerSum: FloatArray,
        blocks: Int,
        sampleRate: Int,
    ): SpectralStats? {
        val n = SpectralDecoder.FFT_SIZE / 2
        val db = FloatArray(n + 1)
        // 噪声底取高半区最小值：转码死区即落在该电平，真无损高区有真实内容故恒高于此
        for (i in 0..n) {
            db[i] = 10f * log10((powerSum[i] / blocks + 1e-12f).toDouble()).toFloat()
        }
        var peakDb = db[0]
        for (i in 1..n) if (db[i] > peakDb) peakDb = db[i]
        for (i in 0..n) db[i] -= peakDb
        // 前缀和：窗口均值 O(1) 查询
        val prefix = FloatArray(n + 2)
        for (i in 0..n) prefix[i + 1] = prefix[i] + db[i]
        val binHz = sampleRate.toFloat() / SpectralDecoder.FFT_SIZE
        val nyquist = sampleRate / 2f
        val floorStart = (0.5f * nyquist / binHz).toInt().coerceIn(0, n)
        var floor = db[floorStart]
        for (i in floorStart + 1..n) if (db[i] < floor) floor = db[i]
        // 整体动态过小则无法判定
        if (-floor < 40f) return null
        return SpectralStats(db, prefix, floor, binHz, nyquist, n, sampleRate)
    }

    // 砖墙截止判定：找显著下降沿 + 上方死区平坦且明显低于奈奎斯特，
    // 表征有损转码的硬截止；未命中时由合成判定转入升频锚点判据
    private fun detectCliff(st: SpectralStats): Boolean {
        val n = st.n
        fun mean(from: Int, to: Int): Float =
            ((st.prefix[to + 1] - st.prefix[from]) / (to - from + 1).toFloat())

        val leftBins = (1500f / st.binHz).toInt().coerceAtLeast(8)
        val rightBins = (3000f / st.binHz).toInt().coerceAtLeast(16)
        val minCutoffBin = (3000f / st.binHz).toInt() + 1
        var b = n - 1
        while (b > minCutoffBin) {
            // 右侧死区：截止点到右端应为平坦噪声底
            val rightTo = (b + rightBins).coerceAtMost(n)
            val rightAvg = mean(b, rightTo)
            val leftFrom = (b - leftBins).coerceAtLeast(1)
            val leftAvg = mean(leftFrom, b - 1)
            // 硬切判据：下降沿 ≥35dB 且上方死区平坦（≤8dB 相对噪声底）
            if (leftAvg - st.floor >= 35f && rightAvg - st.floor <= 8f) {
                val cutoffHz = b * st.binHz
                if (cutoffHz <= 0.9f * st.nyquist) return true
            }
            b--
        }
        return false
    }

    // 升频假无损锚点判据：真高解析度在 22.05k/24k 上下有自然连续内容，
    // 升频文件则在源采样率奈奎斯特处残留墙体，其上方整体空虚平坦——
    // 即使廉价重采样将墙体软化为斜坡，锚点上方仍无真实八度内容可比。
    // 原生采样率文件（如 44.1k 本身）该位置无可用死区，自动豁免免误伤
    private fun detectUpsampledAnchorWall(st: SpectralStats): Boolean {
        // 前缀和区间均值：与砖墙判据同套路，O(1) 查询
        fun mean(from: Int, to: Int): Float =
            ((st.prefix[to + 1] - st.prefix[from]) / (to - from + 1).toFloat())
        // 候选锚点集：常见源采样率的奈奎斯特（CD 22.05k / 48k 源 24k），
        // 仅在文件采样率高于锚点时启用，避免对原生规格误判
        val anchors = when {
            st.sampleRate >= 88200 -> floatArrayOf(22050f, 24000f)
            st.sampleRate == 48000 -> floatArrayOf(22050f)
            else -> FloatArray(0)
        }
        for (anchorHz in anchors) {
            // 锚点需深入奈奎斯特以下且留足死区宽度（≥800Hz）供判定
            if (anchorHz + 800f > st.nyquist) continue
            val anchorBin = (anchorHz / st.binHz).toInt()
            val leftFrom = (anchorBin - (2200f / st.binHz).toInt()).coerceAtLeast(1)
            val leftTo = (anchorBin - (300f / st.binHz).toInt()).coerceAtLeast(1)
            if (leftTo <= leftFrom) continue
            val rightFrom = (anchorBin + (300f / st.binHz).toInt()).coerceAtMost(st.n)
            val rightTo = (anchorBin + (3400f / st.binHz).toInt()).coerceAtMost(st.n)
            if (rightTo <= rightFrom) continue
            val leftAvg = mean(leftFrom, leftTo)
            val rightAvg = mean(rightFrom, rightTo)
            var rightMax = st.db[rightFrom]
            var rightMin = rightMax
            for (i in rightFrom..rightTo) {
                if (st.db[i] > rightMax) rightMax = st.db[i]
                if (st.db[i] < rightMin) rightMin = st.db[i]
            }
            // 墙体：锚点两侧落差 ≥20dB，上方空虚平坦（均值贴近整体噪声底、
            // 极差 ≤14dB、且深于 -45dB 相对峰值），构成升频独有指纹。
            // 峰值约束：右侧任一个 bin 不得高于噪声底 20dB——真实录音即使均值低，
            // 也常在上方残留零星内容（时钟突刺、噪声整形毛刺），单 bin 即可暴露，
            // 借此拦下「高频自然静默」的真高解析度录音
            if (leftAvg - rightAvg >= 20f &&
                rightAvg - st.floor <= 14f &&
                rightMax - rightMin <= 14f &&
                rightMax - st.floor <= 20f &&
                rightAvg <= -45f
            ) {
                return true
            }
        }
        return false
    }
}