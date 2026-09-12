package com.yichao.evilgodxu.data.music.playback

import com.yichao.evilgodxu.data.music.model.MusicTrack
import java.text.Collator
import java.util.Locale

// 播放列表排序字段：默认顺序 / 文件修改时间 / 标题 / 歌手 / 专辑 / 时长
enum class PlaylistSortField {
    DEFAULT,
    MODIFIED_TIME,
    TITLE,
    ARTIST,
    ALBUM,
    DURATION,
}

// 按字段排序播放列表；descending 为逆序。
// 标题/歌手/专辑以及默认顺序中的文本比较统一采用首字母自然序：
// 系统 Collator 对中文按拼音、英文按字母排序，首字符为数字（含中文数字）者统一靠前
internal fun sortTracks(
    tracks: List<MusicTrack>,
    field: PlaylistSortField,
    descending: Boolean,
): List<MusicTrack> {
    val ordered = when (field) {
        PlaylistSortField.DEFAULT -> tracks.sortedByDefaultOrder()
        PlaylistSortField.MODIFIED_TIME ->
            // 新旧：仅按文件修改时间排序（新在前），不附加任何其它排序机制
            tracks.sortedWith(compareByDescending<MusicTrack> { it.fileModifiedMs })
        PlaylistSortField.TITLE ->
            tracks.sortedWith(naturalStringComparator<MusicTrack> { it.title })
        PlaylistSortField.ARTIST ->
            tracks.sortedWith(
                naturalStringComparator<MusicTrack> { it.artist }
                    .then(naturalStringComparator<MusicTrack> { it.title })
            )
        PlaylistSortField.ALBUM ->
            tracks.sortedWith(
                naturalStringComparator<MusicTrack> { it.albumName }
                    .thenBy { it.albumId }
                    .then(naturalStringComparator<MusicTrack> { it.title })
            )
        PlaylistSortField.DURATION ->
            tracks.sortedWith(
                compareBy<MusicTrack> { it.duration }
                    .then(naturalStringComparator<MusicTrack> { it.title })
            )
    }
    return if (descending) ordered.reversed() else ordered
}

// 首字母自然序字符串比较器：数字开头排前且按数值升序，其余按中文拼音/英文字母排序。
// Collator 非线程安全，按比较器实例创建，单次排序内独占使用
private fun <T> naturalStringComparator(selector: (T) -> String): Comparator<T> {
    val collator = Collator.getInstance(Locale.CHINA)
    return Comparator { a, b ->
        val aText = selector(a)
        val bText = selector(b)
        val aNumber = leadingNumberValue(aText)
        val bNumber = leadingNumberValue(bText)
        val byNumber = when {
            aNumber != null && bNumber != null -> aNumber.compareTo(bNumber)
            aNumber != null -> -1
            bNumber != null -> 1
            else -> 0
        }
        if (byNumber != 0) byNumber else collator.compare(aText, bText)
    }
}

// 取首个字符的数值：阿拉伯数字取连续数字串，中文数字取单字数值；非数字开头返回 null
private fun leadingNumberValue(text: String): Long? {
    val trimmed = text.trimStart()
    if (trimmed.isEmpty()) return null
    val first = trimmed[0]
    if (first.isDigit()) return trimmed.takeWhile { it.isDigit() }.toLongOrNull()
    return CHINESE_NUMERALS[first]
}

// 中文数字字符数值表（含大写与异体），用于数字开头判定
private val CHINESE_NUMERALS: Map<Char, Long> = mapOf(
    '〇' to 0L, '零' to 0L,
    '一' to 1L, '二' to 2L, '两' to 2L, '三' to 3L, '四' to 4L,
    '五' to 5L, '六' to 6L, '七' to 7L, '八' to 8L, '九' to 9L,
    '十' to 10L, '百' to 100L, '千' to 1000L, '万' to 10000L, '亿' to 100000000L,
)

// 按默认规则排序：标题（首字母自然序，数字优先）→ 歌手 → 专辑；
// 标题为主键，歌手/专辑作为同级标题下的归并键，使同歌手、同专辑曲目尽量相邻
private fun List<MusicTrack>.sortedByDefaultOrder(): List<MusicTrack> =
    sortedWith(
        naturalStringComparator<MusicTrack> { it.title }
            .then(naturalStringComparator<MusicTrack> { it.artist })
            .then(naturalStringComparator<MusicTrack> { it.albumName })
            .thenBy { it.albumId }
    )
