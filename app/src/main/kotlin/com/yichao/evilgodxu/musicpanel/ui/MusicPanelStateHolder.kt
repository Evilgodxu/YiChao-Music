package com.yichao.evilgodxu.musicpanel

// 音乐面板全局状态持有者，保证面板关闭后播放状态不丢失，
// 并在未播放时释放 ExoPlayer 资源。
object MusicPanelStateHolder {
    val state = MusicPlaybackState()

    fun releaseIfIdle() {
        if (!state.isPlayerActive) {
            state.softRelease()
        }
    }
}
