package com.yichao.evilgodxu.musicpanel

// 在线音乐源统一搜索接口，新增平台时实现该接口并加入 sourceOf 映射
interface OnlineMusicSource {
    suspend fun search(keyword: String): List<NeteaseSongSearchResult>
}

// 按平台枚举映射到对应实现，供单平台搜索使用
internal fun sourceOf(type: MusicSearchSource): OnlineMusicSource = when (type) {
    MusicSearchSource.NETEASE -> NeteaseMusicApi
    MusicSearchSource.QQ -> QQMusicApi
    MusicSearchSource.KUGOU -> KugouMusicApi
    MusicSearchSource.KUWO -> KuwoMusicApi
    MusicSearchSource.MIGU -> MiguMusicApi
}
