package com.yichao.evilgodxu.musicpanel

import com.yichao.evilgodxu.R

// 在线音乐源统一搜索接口，新增平台时实现该接口并加入 sourceOf 映射
interface OnlineMusicSource {
    suspend fun search(keyword: String, page: Int, pageSize: Int): List<NeteaseSongSearchResult>
}

// 按平台枚举映射到对应实现，供单平台搜索使用
internal fun sourceOf(type: MusicSearchSource): OnlineMusicSource = when (type) {
    MusicSearchSource.NETEASE -> NeteaseMusicApi
    MusicSearchSource.QQ -> QQMusicApi
    MusicSearchSource.KUGOU -> KugouMusicApi
    MusicSearchSource.KUWO -> KuwoMusicApi
    MusicSearchSource.MIGU -> MiguMusicApi
}

// 平台显示名：候选无封面时占位及来源标签使用
internal fun MusicSearchSource.sourceNameRes(): Int = when (this) {
    MusicSearchSource.NETEASE -> R.string.music_panel_search_source
    MusicSearchSource.QQ -> R.string.music_panel_search_source_qq
    MusicSearchSource.KUGOU -> R.string.music_panel_search_source_kugou
    MusicSearchSource.KUWO -> R.string.music_panel_search_source_kuwo
    MusicSearchSource.MIGU -> R.string.music_panel_search_source_migu
}
