package com.yichao.evilgodxu.musicpanel

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private suspend fun getController(context: Context, state: MusicPlaybackState): MediaController {
    state.mediaController?.let { return it }
    state.appContext = context.applicationContext
    val token = SessionToken(context, ComponentName(context, MusicPlaybackService::class.java))
    val controller = withContext(Dispatchers.Main) {
        MediaController.Builder(context, token).buildAsync().await()
    }
    withContext(Dispatchers.Main) {
        state.mediaController = controller
        state.player = controller
        controller.addListener(state.controllerListener)
        applyPlaybackMode(controller, state.playMode)
        applyPlaybackSpeed(controller, state.playbackSpeed)
    }
    return controller
}

fun applyPlaybackSpeed(controller: MediaController, speed: Float) {
    controller.setPlaybackSpeed(speed)
}

fun applyPlaybackMode(controller: MediaController, mode: PlayMode) {
    controller.repeatMode = when (mode) {
        PlayMode.RepeatOne -> androidx.media3.common.Player.REPEAT_MODE_ONE
        PlayMode.RepeatAll, PlayMode.Shuffle -> androidx.media3.common.Player.REPEAT_MODE_ALL
    }
    controller.shuffleModeEnabled = mode == PlayMode.Shuffle
}

suspend fun playTrackAt(
    context: Context,
    state: MusicPlaybackState,
    index: Int,
    autoPlay: Boolean = true,
    clearQueue: Boolean = true,
) {
    state.playTrackMutex.withLock {
        // 手动切歌默认清空插队队列；仅自然接续（队列消费/自动下一首）时由调用方显式关闭
        if (clearQueue) state.clearPlayNextQueue()
        val track = state.playlist.getOrNull(index) ?: return
        val controller = getController(context, state)
        val items = state.cachedMediaItems ?: withContext(Dispatchers.IO) {
            state.playlist.map { trackItem -> toMediaItem(context, trackItem) }.also {
                state.cachedMediaItems = it
            }
        }

        withContext(Dispatchers.Main) {
            applyPlaybackMode(controller, state.playMode)
            val resumePosition = if (state.pendingSavedUri == track.audioUri) {
                state.pendingResumePosition.coerceAtLeast(0L)
            } else {
                0L
            }
            // 队列一致性同时校验 mediaId 与 URI：在线曲目缓存完成后 URI 已指向本地文件，
            // 仅比较 mediaId 会误判一致，导致播放源无法重定向（这是在线/离线切换失效的根因）
            val sameQueue = controller.mediaItemCount == items.size &&
                    (0 until controller.mediaItemCount).all { i ->
                        val old = controller.getMediaItemAt(i)
                        old.mediaId == items[i].mediaId &&
                            old.localConfiguration?.uri?.toString() == items[i].localConfiguration?.uri?.toString()
                    }
            val sameTrack = controller.currentMediaItem?.mediaId == track.id.toString()
            // 封面更新后需要刷新系统媒体面板的 MediaItem
            val needRefreshItems = state.mediaItemsDirty

            state.currentIndex = index
            state.currentTrack = track
            state.errorMsg = null
            state.mediaItemsDirty = false
            if (!sameQueue || needRefreshItems) {
                controller.setMediaItems(items, index, resumePosition)
                controller.prepare()
            } else if (!sameTrack) {
                controller.seekToDefaultPosition(index)
            } else if (resumePosition > 0L && controller.currentPosition == 0L) {
                controller.seekTo(resumePosition)
            }
            if (autoPlay) {
                controller.play()
            } else {
                controller.pause()
            }
            state.pendingSavedUri = null
            state.pendingResumePosition = 0L
        }
    }
}

private fun toMediaItem(context: Context, track: MusicTrack): MediaItem {
    val metadata = androidx.media3.common.MediaMetadata.Builder()
        .setTitle(track.title)
        .setArtist(track.artist)
    // 使用 content:// URI 指向本地缓存封面，避免在 MediaItem 中嵌入 byte 数组
    // Media3 的 MediaSession 会自动为 content:// URI 授予控制器读取权限
    MusicCoverProvider.buildUri(context.packageName, track.coverCachePath)?.let { uri ->
        metadata.setArtworkUri(uri)
    }
    return MediaItem.Builder()
        .setMediaId(track.id.toString())
        .setUri(Uri.parse(track.audioUri))
        .setMediaMetadata(metadata.build())
        .build()
}

fun togglePlayPause(state: MusicPlaybackState) {
    state.playbackScope.launch {
        val controller = state.mediaController
        if (controller == null) {
            val context = state.appContext ?: return@launch
            val index = state.currentIndex
            if (index >= 0) {
                // 恢复当前曲目而非切歌，保留插队队列
                playTrackAt(context, state, index, clearQueue = false)
            }
            return@launch
        }
        if (controller.isPlaying) controller.pause() else controller.play()
    }
}

fun seekTo(state: MusicPlaybackState, positionMs: Long) {
    state.mediaController?.let { controller ->
        state.playbackScope.launch { controller.seekTo(positionMs) }
    }
}

/**
 * 封面等元数据后台补全后刷新系统媒体面板的当前 MediaItem。
 * 仅当 artworkUri 变化时才替换，替换不中断播放。
 */
fun refreshCurrentMediaItem(state: MusicPlaybackState) {
    val context = state.appContext ?: return
    val controller = state.mediaController ?: return
    val track = state.currentTrack ?: return
    val index = state.currentIndex
    if (index < 0) return
    state.playbackScope.launch {
        if (controller.mediaItemCount != state.playlist.size) return@launch
        val newItem = toMediaItem(context, track)
        if (controller.currentMediaItem?.mediaMetadata?.artworkUri != newItem.mediaMetadata.artworkUri) {
            controller.replaceMediaItem(index, newItem)
        }
    }
}
