package com.yichao.evilgodxu.domain.music

// 音乐面板全局状态持有者，保证面板关闭后播放状态不丢失，
// 并在未播放时释放 ExoPlayer 资源。
object MusicPanelStateHolder {
    // 页面级瞬态 UI 状态（搜索/音质/封面/歌词/无损升级），与播放核心分离
    val ui = MusicPanelUiState()
    val state = MusicPlaybackState(ui)

    fun releaseIfIdle() {
        if (!state.isPlayerActive) {
            state.softRelease()
        }
    }
}
