package com.yichao.evilgodxu.screens.home.home_assembly.playlist_area

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.domain.music.FakeLosslessAnalyzer
import com.yichao.evilgodxu.domain.music.MusicPlaybackState
import com.yichao.evilgodxu.domain.music.PlaylistSource
import com.yichao.evilgodxu.domain.music.trackFormatCategory
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.ui.icons.AppIcons
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// 格式导航行高与可见行数：列表项超出可见行数时固定该高度滚动，避免对话框随格式数量拉伸
private val FormatListRowHeight = 30.dp
private const val FormatListVisibleRows = 3

// 曲库分析对话框：长按首页播放列表按钮弹出，圆环统计格式占比，下方按格式定位歌单
@Composable
internal fun LibraryAnalysisSheet(
    visible: Boolean,
    playbackState: MusicPlaybackState,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    val context = LocalContext.current
    // 全量库统计：切换歌单时曲库范围不变，仅依赖全量库数据
    val stats = remember(playbackState.libraryTracks) {
        analyzeLibraryFormats(context, playbackState.libraryTracks)
    }
    // 假无损校验状态：progress 为 (已校验数, 总数) 显示进度；count 为校验结果，null 表示进行中
    var checkingProgress by remember(playbackState.libraryTracks) { mutableStateOf<Pair<Int, Int>?>(null) }
    var fakeLosslessCount by remember(playbackState.libraryTracks) { mutableStateOf<Int?>(null) }
    LaunchedEffect(playbackState.libraryTracks) {
        // 假无损仅涉及 FLAC：复用格式分析的分类结果，只对 FLAC 子集逐曲校验，
        // 进度以 FLAC 数为基数；非 FLAC 曲目不进入校验流程
        val flacTracks = playbackState.libraryTracks.filter { FakeLosslessAnalyzer.isFlacCandidate(it) }
        var count = 0
        if (flacTracks.isNotEmpty()) {
            checkingProgress = 0 to flacTracks.size
            withContext(Dispatchers.IO) {
                flacTracks.forEachIndexed { index, track ->
                    checkingProgress = index to flacTracks.size
                    if (FakeLosslessAnalyzer.isSuspectedFakeLossless(context, track)) count++
                }
            }
            checkingProgress = null
        }
        fakeLosslessCount = count
    }
    val currentKey = playbackState.playlistSource?.key

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 580.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(24.dp))
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.library_analysis_title),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.library_analysis_formats, stats.size),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = AppIcons.Close,
                        contentDescription = stringResource(R.string.home_player_close_playlist),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            if (stats.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.library_analysis_empty),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                val total = stats.sumOf { it.count }
                // 假无损为识别算法的补充类目：仅在校验发现的疑似文件并入定位列表
                val fakeCount = fakeLosslessCount
                val hasFakeLossless = fakeCount != null && fakeCount > 0
                val navStats = buildList {
                    if (hasFakeLossless) {
                        add(
                            FormatStat(
                                key = FakeLosslessAnalyzer.FAKE_LOSSLESS_KEY,
                                name = stringResource(R.string.library_analysis_fake_lossless),
                                count = fakeCount ?: 0,
                                percent = ((fakeCount ?: 0) * 1000f / total).roundToInt() / 10f,
                            ),
                        )
                    }
                    addAll(stats)
                }
                // 圆环 + 图例：图例行点击切换到对应格式歌单
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier.size(140.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        SegmentedFormatRing(
                            stats = stats,
                            modifier = Modifier.fillMaxSize(),
                        )
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = stringResource(R.string.music_panel_track_count, total),
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = stringResource(R.string.library_analysis_formats, stats.size),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp,
                            )
                        }
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        stats.forEachIndexed { index, stat ->
                            FormatStatRow(
                                stat = stat,
                                color = FORMAT_COLOR_PALETTE[index % FORMAT_COLOR_PALETTE.size],
                                isCurrent = currentKey == formatSourceKey(stat.key),
                                onClick = {
                                    switchToFormat(context, playbackState, stat)
                                    onDismiss()
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.library_analysis_select_format),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                    )
                    // 校验中在标题右侧提示（含逐曲进度），显隐不改变列表区高度
                    val progress = checkingProgress
                    when {
                        progress != null -> Text(
                            text = stringResource(
                                R.string.library_analysis_check_progress,
                                progress.first,
                                progress.second,
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                        )
                        fakeLosslessCount == null -> Text(
                            text = stringResource(R.string.library_analysis_checking),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                // 格式列表不超过可见行数时按内容完整展示（不滚动），超出才固定高度滚动，
                // 避免仅有 3 条时仍出现滚动条
                if (navStats.size <= FormatListVisibleRows) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        navStats.forEachIndexed { index, stat ->
                            FormatNavRowItem(
                                index = index,
                                stat = stat,
                                hasFakeLossless = hasFakeLossless,
                                isCurrent = currentKey == formatSourceKey(stat.key),
                                onClick = {
                                    switchToFormat(context, playbackState, stat)
                                    onDismiss()
                                },
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .height(FormatListRowHeight * FormatListVisibleRows),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        itemsIndexed(navStats, key = { _, stat -> stat.key }) { index, stat ->
                            FormatNavRowItem(
                                index = index,
                                stat = stat,
                                hasFakeLossless = hasFakeLossless,
                                isCurrent = currentKey == formatSourceKey(stat.key),
                                onClick = {
                                    switchToFormat(context, playbackState, stat)
                                    onDismiss()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

// 切换播放列表为指定格式曲目（假无损按校验结果过滤，其余按格式分类过滤），
// 复用歌单切换（备份默认列表 + 播放首曲 + 补全元数据）。
// 在播放器全局作用域执行：假无损过滤需读文件（缓存命中即瞬时返回），且弹层关闭不取消切换
private fun switchToFormat(
    context: Context,
    playbackState: MusicPlaybackState,
    stat: FormatStat,
) {
    playbackState.playbackScope.launch {
        val tracks = playbackState.libraryTracks.filter { track ->
            if (stat.key == FakeLosslessAnalyzer.FAKE_LOSSLESS_KEY) {
                withContext(Dispatchers.IO) {
                    FakeLosslessAnalyzer.isSuspectedFakeLossless(context, track)
                }
            } else {
                trackFormatCategory(context, track) == stat.name
            }
        }
        switchToPlaylistQueue(
            context = context,
            state = playbackState,
            tracks = tracks,
            source = PlaylistSource(formatSourceKey(stat.key), stat.name),
        )
    }
}

// 格式歌单来源 key：刷新后据此重建歌单
private fun formatSourceKey(key: String): String = "smart:FORMAT:$key"

// 单个格式的占比统计；key 为稳定标识（格式名或假无损键），name 为展示名
private data class FormatStat(
    val key: String,
    val name: String,
    val count: Int,
    val percent: Float,
)

// 统计曲库格式占比：按文件扩展名归类，取数量前 5 种，其余合并为「其他」
private fun analyzeLibraryFormats(context: Context, tracks: List<MusicTrack>): List<FormatStat> {
    val counts = linkedMapOf<String, Int>()
    tracks.forEach { track ->
        val key = trackFormatCategory(context, track)
        counts[key] = (counts[key] ?: 0) + 1
    }
    val total = counts.values.sum()
    if (total == 0) return emptyList()
    val sorted = counts.entries.sortedByDescending { it.value }
    val result = sorted.take(5).associate { it.key to it.value }.toMutableMap()
    val restCount = sorted.drop(5).sumOf { it.value }
    val otherName = context.getString(R.string.library_analysis_other)
    if (restCount > 0 && otherName !in result) {
        result[otherName] = restCount
    }
    return result.map { (name, count) ->
        // 占比保留一位小数
        FormatStat(name, name, count, (count * 1000f / total).roundToInt() / 10f)
    }
}

// 分段圆环配色：前 5 档为可区分的色相，末档灰用于「其他」等兜底
private val FORMAT_COLOR_PALETTE = listOf(
    Color(0xFF4A6CF7),
    Color(0xFF12B5A5),
    Color(0xFFFFB020),
    Color(0xFFE8636B),
    Color(0xFFA371F7),
    Color(0xFF9AA4B2),
)

// 分段圆环：按占比绘制带间距与圆角端点的圆弧，小占比段自动收紧间距
@Composable
private fun SegmentedFormatRing(
    stats: List<FormatStat>,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val strokeWidth = size.minDimension * 0.13f
        val ringSize = Size(size.minDimension, size.minDimension)
        var startAngle = -90f
        stats.forEachIndexed { index, stat ->
            // 末段按剩余角度补齐，规避占比保留一位小数造成的累计偏差
            val isLast = index == stats.lastIndex
            val sweep = if (isLast) {
                360f - (startAngle + 90f)
            } else {
                stat.percent / 100f * 360f
            }
            // 段间距随占比收敛：小段避免被间距吞掉
            val gap = minOf(3f, sweep * 0.25f)
            val drawSweep = (sweep - gap).coerceAtLeast(0f)
            if (drawSweep > 0.3f) {
                drawArc(
                    color = FORMAT_COLOR_PALETTE[index % FORMAT_COLOR_PALETTE.size],
                    startAngle = startAngle + gap / 2f,
                    sweepAngle = drawSweep,
                    useCenter = false,
                    topLeft = Offset(0f, 0f),
                    size = ringSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                )
            }
            startAngle += sweep
        }
    }
}

// 图例行：色点 + 格式名 + 数量，点击切换到对应格式歌单
@Composable
private fun FormatStatRow(
    stat: FormatStat,
    color: Color,
    isCurrent: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                else Color.Transparent,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(color, RoundedCornerShape(2.dp)),
        )
        Text(
            text = stat.name,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 12.sp,
            lineHeight = 15.sp,
            fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(R.string.music_panel_track_count, stat.count),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            lineHeight = 15.sp,
        )
    }
}

// 格式导航项：统一配色规则，供普通 Column 与滚动 LazyColumn 两处复用
@Composable
private fun FormatNavRowItem(
    index: Int,
    stat: FormatStat,
    hasFakeLossless: Boolean,
    isCurrent: Boolean,
    onClick: () -> Unit,
) {
    FormatNavRow(
        stat = stat,
        color = if (stat.key == FakeLosslessAnalyzer.FAKE_LOSSLESS_KEY) {
            FORMAT_COLOR_PALETTE[3]
        } else {
            FORMAT_COLOR_PALETTE[
                (if (hasFakeLossless) index - 1 else index) % FORMAT_COLOR_PALETTE.size
            ]
        },
        isCurrent = isCurrent,
        onClick = onClick,
    )
}

// 格式导航行：色点 + 格式名 + 占比 + 进入箭头，占比在箭头左侧，点击切换到该格式歌单
@Composable
private fun FormatNavRow(
    stat: FormatStat,
    color: Color,
    isCurrent: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                else Color.Transparent,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(color, RoundedCornerShape(2.dp)),
        )
        Text(
            text = stat.name,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 13.sp,
            fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = formatPercent(stat.percent),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
        )
        Icon(
            imageVector = AppIcons.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

// 百分比显示：整数不带小数，否则保留一位小数
private fun formatPercent(percent: Float): String =
    if (percent == percent.toInt().toFloat()) {
        String.format(Locale.US, "%.0f%%", percent)
    } else {
        String.format(Locale.US, "%.1f%%", percent)
    }