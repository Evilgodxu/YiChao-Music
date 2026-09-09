package com.yichao.evilgodxu.domain.music

// 音乐面板全局状态持有者，保证面板关闭后播放状态不丢失，
// 并在未播放时释放 ExoPlayer 资源。由 Koin 以单例管理，全库共享同一实例。
class MusicPanelStateHolder {
    val state = MusicPlaybackState()

    fun releaseIfIdle() {
        if (!state.isPlayerActive) {
            state.softRelease()
        }
    }
}
