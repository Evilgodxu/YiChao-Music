package com.yichao.evilgodxu.domain.music

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.log.CrashLogManager
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

// 假无损识别器：两级判定，逐曲串行，契合资源受限设备整库校验。
// ① 轻量预筛：扩展名 + FLAC 容器头 + 码率压缩比，压缩比正常的直接认定真无损（免解码）；
// ② 频谱截止分析：仅对可疑文件用 MediaCodec 稀疏窗口解码 3 段，FFT 求平均功率谱，
//    检出奈奎斯特以下的「砖墙式」截止 + 死区即判为有损转码来源（isflac 同源判据）。
// 结果带持久化缓存（键含文件大小与时长），重启后直接复用；仅对新增/变更文件增量解码，
// 进度由调用方逐曲驱动，协程取消即时释放解码器。
internal object FakeLosslessAnalyzer {

    // 假无损智能歌单过滤键：与本地化展示名解耦，保证序列化歌单 key 跨语言环境稳定
    const val FAKE_LOSSLESS_KEY = "fake-lossless"

    // 识别结果缓存：键含文件大小与时长，文件变化即失效；跨对话框/刷新复用避免重复解码
    private val resultCache = ConcurrentHashMap<String, Boolean>()

    // 持久化缓存文件（应用私有目录）：JSON key/value 平铺，全量库可重建，临时文件原子重命名落盘
    private const val CACHE_FILE = "fake_lossless_cache.json"
    // 批量增量校验期间每分析多少首新增文件落盘一次，收窄中断导致的缓存丢失窗口
    private const val CACHE_PERSIST_INTERVAL = 20
    // 单曲校验路径的落盘去抖窗口：合并密集写入，避免逐曲全量重写
    private const val CACHE_PERSIST_DEBOUNCE_MS = 800L

    // 进程内缓存状态：加载仅一次，落盘串行互斥，单曲写入去抖合并
    @Volatile
    private var cacheLoaded = false
    private val cacheLoadMutex = Mutex()
    private val persistMutex = Mutex()
    private val persistScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val persistScheduled = AtomicBoolean(false)

    // 压缩比直判阈值：真无损 CD 典型 0.50~0.65，达到该值视为真无损，跳过频谱分析
    private const val GENUINE_RATIO = 0.60f

    // 解码不可用时的回退阈值：回到社区公认的无损下限，保守保护真无损文件
    private const val FALLBACK_RATIO = 0.40f

    // 频谱分析参数：4096 点 FFT（44.1k 下约 10.8Hz/桶），每探测窗 4 秒，3 窗覆盖全曲
    private const val FFT_SIZE = 4096
    private const val PROBE_DURATION_US = 4_000_000L
    private val PROBE_POSITIONS = floatArrayOf(0.15f, 0.45f, 0.75f)
    private const val CODEC_TIMEOUT_US = 10_000L

    // MediaCodec 输出 PCM 编码值（KEY_PCM_ENCODING 取值，兼容各 API 层级）
    private const val PCM_16BIT = 2
    private const val PCM_FLOAT = 4
    private const val PCM_32BIT = 22

    // Hann 窗：预计算避免逐帧重复求余弦
    private val hannWindow = FloatArray(FFT_SIZE) { i ->
        0.5f - 0.5f * cos(2f * PI.toFloat() * i / (FFT_SIZE - 1))
    }

    // 是否为可校验的 FLAC 文件：扩展名口径，与曲库分析的 FLAC 格式类目一致
    fun isFlacCandidate(track: MusicTrack): Boolean =
        track.path.substringAfterLast('.', "").uppercase() == "FLAC"

    // 缓存键：路径 + 文件大小 + 时长齐备，文件内容变化即失效
    internal fun cacheKey(track: MusicTrack, sizeBytes: Long): String =
        "FLAC\u0000${track.path}\u0000$sizeBytes\u0000${track.duration}"

    // 判定入口：非 FLAC 或无法读取大小直接排除；缓存命中直接复用（含重启前持久化结果）
    suspend fun isSuspectedFakeLossless(context: Context, track: MusicTrack): Boolean {
        if (!isFlacCandidate(track)) return false
        ensureCacheLoaded(context)
        val sizeBytes = TrackAudioInfoReader.readFileSize(context, track) ?: return false
        val key = cacheKey(track, sizeBytes)
        resultCache[key]?.let { return it }
        val result = withContext(Dispatchers.IO) { analyze(context, track, sizeBytes) }
        // 无法判定的结果不缓存：本次按非假无损保守处理，下次可重新进入频谱分析
        if (result != null) {
            resultCache[key] = result
            schedulePersist(context)
        }
        return result ?: false
    }

    // 批量增量校验（曲库分析对话框入口）：按缓存键划分「已校验旧文件 / 待校验新文件」，
    // 仅对新增或内容变更的文件逐曲解码，缓存命中的旧文件直接复用持久化结果；
    // onProgress 以新增文件数为基数回传进度（全部命中时瞬时完成）；
    // 周期落盘收窄中断丢失窗口，结束清理已删除文件的残留条目并最终落盘
    suspend fun analyzeLibraryIncremental(
        context: Context,
        tracks: List<MusicTrack>,
        onProgress: suspend (checked: Int, total: Int) -> Unit,
    ): Int {
        ensureCacheLoaded(context)
        return withContext(Dispatchers.IO) io@{
            val pending = mutableListOf<Pair<MusicTrack, Long>>()
            val keepKeys = HashSet<String>()
            var count = 0
            tracks.forEach { track ->
                if (!isFlacCandidate(track)) return@forEach
                val sizeBytes = TrackAudioInfoReader.readFileSize(context, track) ?: return@forEach
                val key = cacheKey(track, sizeBytes)
                keepKeys.add(key)
                val cached = resultCache[key]
                if (cached != null) {
                    if (cached) count++
                } else {
                    pending.add(track to sizeBytes)
                }
            }
            if (pending.isEmpty()) {
                // 无新增文件：仅当存在已删除文件的残留条目时清理，避免无谓写盘
                if (resultCache.size > keepKeys.size) {
                    withContext(NonCancellable) {
                        resultCache.keys.removeAll { key -> key !in keepKeys }
                        flushCache(context)
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
                    // 无法判定的结果不缓存：下次打开对话框重新校验，避免瞬时失败固化
                    if (result != null) {
                        resultCache[key] = result
                        if (result) count++
                    }
                    if (checked % CACHE_PERSIST_INTERVAL == 0) flushCache(context)
                }
            } finally {
                // 对话框中途关闭取消协程时也落盘已完成结果，避免已分析结果随进程退出丢失
                withContext(NonCancellable) {
                    if (resultCache.size > keepKeys.size) {
                        resultCache.keys.removeAll { key -> key !in keepKeys }
                    }
                    flushCache(context)
                }
            }
            count
        }
    }

    // 首用时从私有目录加载持久化缓存，进程内仅加载一次
    private suspend fun ensureCacheLoaded(context: Context) {
        if (cacheLoaded) return
        cacheLoadMutex.withLock {
            if (cacheLoaded) return
            withContext(Dispatchers.IO) { loadCache(context) }
            cacheLoaded = true
        }
    }

    // 读取持久化缓存：文件缺失或损坏视为空缓存，逐条容错不影响整体
    private fun loadCache(context: Context) {
        val file = cacheFile(context)
        if (!file.isFile) return
        runCatching {
            val obj = JSONObject(file.readText())
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                runCatching { resultCache[key] = obj.getBoolean(key) }
            }
        }
    }

    // 立即落盘（批量校验结束/周期触发），与去抖写共用互斥锁防并发交织
    private suspend fun flushCache(context: Context) {
        persistMutex.withLock {
            withContext(Dispatchers.IO) { writeCache(context) }
        }
    }

    private fun cacheFile(context: Context): File = File(context.filesDir, CACHE_FILE)

    // 全量快照写盘：先写临时文件再原子重命名，避免进程中断残留半截 JSON
    private fun writeCache(context: Context) {
        runCatching {
            val file = cacheFile(context)
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "$CACHE_FILE.tmp")
            val content = JSONObject().apply {
                resultCache.forEach { (key, value) -> put(key, value) }
            }.toString()
            tmp.writeText(content)
            if (!tmp.renameTo(file)) {
                tmp.delete()
                file.writeText(content)
            }
        }
    }

    // 单曲校验结果异步落盘：去抖合并密集写入，任一时刻仅排一个写任务
    private fun schedulePersist(context: Context) {
        if (!persistScheduled.compareAndSet(false, true)) return
        persistScope.launch {
            delay(CACHE_PERSIST_DEBOUNCE_MS)
            persistScheduled.set(false)
            flushCache(context.applicationContext)
        }
    }

    // 单曲判定：两级判定均走全；返回 null 表示无法判定（容器头不可读/时长无效），
    // 调用方不得缓存，避免瞬态失败被固化后永久跳过更准确的频谱分析
    private suspend fun analyze(context: Context, track: MusicTrack, sizeBytes: Long): Boolean? {
        val format = TrackAudioInfoReader.readFlacContainerFormat(context, track) ?: return null
        if (format.sampleRate < 44100 || format.bitDepth < 16 || format.channels < 2) return false
        val durationSec = track.duration / 1000
        if (durationSec <= 0 || sizeBytes <= 0) return null
        val pcmKbps = format.sampleRate * format.channels * format.bitDepth / 1000f
        val ratio = (sizeBytes * 8 / durationSec / 1000) / pcmKbps
        if (ratio >= GENUINE_RATIO) return false
        // 可疑带：频谱判定；null 表示解码不可用，回退码率口径
        val spectral = detectSpectralCutoff(track, format.sampleRate, format.channels)
        return spectral ?: (ratio < FALLBACK_RATIO)
    }

    // 频谱截止检测：MediaExtractor 定位 + MediaCodec 解码稀疏窗口，Welch 平均功率谱。
    // 返回 null 表示解码流程不可用（OEM 提取器/解码器缺失），true/false 为频谱判定结果
    private suspend fun detectSpectralCutoff(track: MusicTrack, sampleRate: Int, channels: Int): Boolean? {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        return try {
            extractor.setDataSource(track.path)
            var trackIndex = -1
            for (i in 0 until extractor.trackCount) {
                if (extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) == "audio/flac") {
                    trackIndex = i
                    break
                }
            }
            if (trackIndex < 0) return null
            val mediaFormat = extractor.getTrackFormat(trackIndex)
            extractor.selectTrack(trackIndex)
            // 若提取器未给出采样率/声道，回退容器头解析值
            val sr = mediaFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
            val ch = mediaFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT, channels)
            if (sr <= 0 || ch <= 0) return null

            decoder = MediaCodec.createDecoderByType("audio/flac")
            decoder.configure(mediaFormat, null, null, 0)
            decoder.start()

            val powerSum = FloatArray(FFT_SIZE / 2 + 1)
            val scratchRe = FloatArray(FFT_SIZE)
            val scratchIm = FloatArray(FFT_SIZE)
            var blocks = 0
            val durationUs = track.duration * 1000
            for (pos in PROBE_POSITIONS) {
                decoder.flush()
                extractor.seekTo((durationUs * pos).toLong(), MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                blocks += decodeProbe(decoder, extractor, sr, ch, powerSum, scratchRe, scratchIm)
            }
            if (blocks <= 0) null else detectCliff(powerSum, blocks, sr)
        } catch (e: Exception) {
            CrashLogManager.logException("FakeLosslessAnalyzer", "频谱分析失败", e)
            null
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { extractor.release() }
        }
    }

    // 泵送一个探测窗的解码：PCM 转为单声道后 Welch 累加功率谱，返回 FFT 块数
    private suspend fun decodeProbe(
        decoder: MediaCodec,
        extractor: MediaExtractor,
        sampleRate: Int,
        channels: Int,
        powerSum: FloatArray,
        scratchRe: FloatArray,
        scratchIm: FloatArray,
    ): Int {
        val info = MediaCodec.BufferInfo()
        var pcmEncoding = PCM_16BIT
        var bytesPerSample = 2
        val targetFrames = sampleRate * PROBE_DURATION_US / 1_000_000
        var decodedFrames = 0
        var extractorEos = false
        var outputEos = false
        var blocks = 0
        while (!outputEos && decodedFrames < targetFrames) {
            // 校验进行中保持可取消：关闭对话框即中止，解码器由外层 finally 释放
            coroutineContext.ensureActive()
            // 喂入输入：连读提取器样本直至本窗目标时长
            if (!extractorEos) {
                val inIndex = decoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
                if (inIndex >= 0) {
                    val sampleSize = extractor.sampleSize
                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        extractorEos = true
                    } else {
                        val inputBuffer = decoder.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(inputBuffer, 0)
                        decoder.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            // 取出输出：累加本窗 PCM 的功率谱
            when (val outIndex = decoder.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    pcmEncoding = decoder.outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING, PCM_16BIT)
                    bytesPerSample = if (pcmEncoding == PCM_FLOAT || pcmEncoding == PCM_32BIT) 4 else 2
                }
                MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                else -> if (outIndex >= 0) {
                    val outputBuffer = decoder.getOutputBuffer(outIndex) ?: return blocks
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (isConfig || info.size <= 0) {
                        decoder.releaseOutputBuffer(outIndex, false)
                    } else {
                        blocks += consumePcm(
                            outputBuffer, info.offset, info.size,
                            channels, bytesPerSample, powerSum, scratchRe, scratchIm,
                        )
                        decodedFrames += info.size / (channels * bytesPerSample).coerceAtLeast(1)
                        decoder.releaseOutputBuffer(outIndex, false)
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputEos = true
                }
            }
        }
        return blocks
    }

    // PCM 字节流转为单声道浮点并做 Welch 帧累加，返回本缓冲贡献的 FFT 块数
    private fun consumePcm(
        buffer: ByteBuffer,
        offset: Int,
        size: Int,
        channels: Int,
        bytesPerSample: Int,
        powerSum: FloatArray,
        scratchRe: FloatArray,
        scratchIm: FloatArray,
    ): Int {
        val frames = size / (channels * bytesPerSample).coerceAtLeast(1)
        if (frames <= 0) return 0
        val mono = FloatArray(frames)
        val view = buffer.duplicate()
        view.order(ByteOrder.LITTLE_ENDIAN)
        var cursor = offset
        for (i in 0 until frames) {
            var acc = 0f
            for (c in 0 until channels) {
                acc += when (bytesPerSample) {
                    4 -> view.getFloat(cursor)
                    else -> view.getShort(cursor).toFloat() / 32768f
                }
                cursor += bytesPerSample
            }
            mono[i] = acc / channels
        }
        // 50% 重叠滑动窗：最大限度利用每个探测窗，稳定噪声底估计
        val step = FFT_SIZE / 2
        var blocks = 0
        var start = 0
        while (start + FFT_SIZE <= frames) {
            for (i in 0 until FFT_SIZE) {
                scratchRe[i] = mono[start + i] * hannWindow[i]
                scratchIm[i] = 0f
            }
            fftPower(scratchRe, scratchIm, powerSum)
            blocks++
            start += step
        }
        return blocks
    }

    // 迭代基 2 快速傅里叶变换并累加功率谱（仅 0..n/2 半谱）
    private fun fftPower(re: FloatArray, im: FloatArray, powerSum: FloatArray) {
        val n = re.size
        // 位反转重排
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                re[i] = re[j].also { re[j] = re[i] }
                im[i] = im[j].also { im[j] = im[i] }
            }
        }
        // 蝶形运算
        var len = 2
        while (len <= n) {
            val angle = 2f * PI.toFloat() / len
            val wStepRe = cos(angle)
            val wStepIm = -sin(angle)
            var i = 0
            while (i < n) {
                var wRe = 1f
                var wIm = 0f
                val half = len shr 1
                for (k in 0 until half) {
                    val uRe = re[i + k]
                    val uIm = im[i + k]
                    val tRe = re[i + k + half] * wRe - im[i + k + half] * wIm
                    val tIm = re[i + k + half] * wIm + im[i + k + half] * wRe
                    re[i + k] = uRe + tRe
                    im[i + k] = uIm + tIm
                    re[i + k + half] = uRe - tRe
                    im[i + k + half] = uIm - tIm
                    val nextRe = wRe * wStepRe - wIm * wStepIm
                    wIm = wRe * wStepIm + wIm * wStepRe
                    wRe = nextRe
                }
                i += len
            }
            len = len shl 1
        }
        val half = n / 2
        for (i in 0..half) {
            powerSum[i] += re[i] * re[i] + im[i] * im[i]
        }
    }

    // 砖墙截止判定：对平均功率谱求分贝，找显著下降沿 + 上方死区平坦且明显低于奈奎斯特。
    // 噪声底取高半区最小值：转码死区即落在该电平，真无损高区有真实内容故不会被误判
    private fun detectCliff(powerSum: FloatArray, blocks: Int, sampleRate: Int): Boolean {
        val n = FFT_SIZE / 2
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

        val binHz = sampleRate.toFloat() / FFT_SIZE
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
        return false
    }
}