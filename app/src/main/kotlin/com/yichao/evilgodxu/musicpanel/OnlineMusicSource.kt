package com.yichao.evilgodxu.musicpanel

// 在线音乐源统一搜索接口，新增平台时实现该接口并加入 onlineMusicSources 注册表
interface OnlineMusicSource {
    suspend fun search(keyword: String): List<NeteaseSongSearchResult>
}

// 全部在线音乐源，搜索时按列表顺序聚合展示结果
internal val onlineMusicSources: List<OnlineMusicSource> = listOf(
    NeteaseMusicApi,
    JamendoMusicApi,
    QQMusicApi,
    KugouMusicApi,
)
