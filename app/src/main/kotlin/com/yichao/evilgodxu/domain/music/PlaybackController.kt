package com.yichao.evilgodxu.domain.music

import android.content.Context
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.data.music.model.PlayMode

// 播放控制器窄接口：悬浮窗/页面 UI 与跨页动作只依赖此契约，
// 不直接操作播放状态类内部可变字段，行为统一经方法进入
interface PlaybackController {
    // 播放核心只读状态
    val currentTrack: MusicTrack?
    val isPlaying: Boolean
    val isPrepared: Boolean
    val isPlayerActive: Boolean
    val duration: Long
    val currentPosition: Long
    val playlist: List<MusicTrack>
    val currentIndex: Int
    val playMode: PlayMode
    val playbackSpeed: Float
    val errorMsg: String?
    val isScanning: Boolean
    val isLyricsVisible: Boolean
    val likedIds: Set<Long>
    val playlistSource: PlaylistSource?
    val defaultPlaylistBackup: List<MusicTrack>?
    val libraryTracks: List<MusicTrack>
    val recentPlayedIds: List<Long>
    val playNextQueue: List<MusicTrack>
    val hasTrack: Boolean
    val audioSignalPathFormat: AudioSignalPathFormat?
    val audioSignalPathTrackId: Long?
    val timerMinutes: Int
    val timerRemaining: Int
    val timerAutoStopped: Boolean
    val sleepTimerExpired: Boolean

    // 播放控制动作
    // playTrackAt 经播放核心应用级作用域派发（fire-and-forget），不阻塞调用方
    fun playTrackAt(index: Int, autoPlay: Boolean = true, clearQueue: Boolean = true)
    fun togglePlayPause()
    fun seekTo(positionMs: Long)
    fun nextIndex(): Int
    fun previousIndex(): Int
    fun updatePlayMode(mode: PlayMode)
    fun updatePlaybackSpeed(speed: Float)
    fun updateLyricsVisible(visible: Boolean)
    fun updateErrorMsg(message: String?)
    fun updateCurrentPosition(position: Long)
    fun updateTimerMinutes(minutes: Int)
    fun updateTimerAutoStopped(stopped: Boolean)
    fun updateSleepTimerExpired(expired: Boolean)
    fun startTimer(minutes: Int)
    fun stopTimer()
    fun isInPlayNext(trackId: Long): Boolean
    fun togglePlayNext(track: MusicTrack)
    fun clearPlayNextQueue()
    fun toggleFavorite(trackId: Long)
    fun removeTrack(trackId: Long, advanceToNext: Boolean)
    fun reorderPlaylist(ordered: List<MusicTrack>)
    fun updateTrack(updated: MusicTrack)
    fun batchUpdateTracks(updates: List<MusicTrack>)
    fun setSortedPlaylist(tracks: List<MusicTrack>)
    fun requestMetadata(track: MusicTrack?)
    fun renameTrackMetadata(renamed: MusicTrack)
    fun adjustLyricsOffset(stepMs: Long)
    fun bumpCoverRevision()
    fun persistState()
    fun persistPlaylist()
    fun release()
    fun softRelease()
    suspend fun restoreSavedState(context: Context)
    suspend fun deleteSongPermanently(context: Context, track: MusicTrack)
    fun cleanupIdleOnlineTracks()
    fun refreshIdleTrackFormatInfo(context: Context)
    fun refreshTrackFormatInfoFromLocal(context: Context)
    fun reconcileTrackFormatInfo(context: Context)
    suspend fun removeUnavailableExternalTracks(context: Context)
    fun addSearchHistory(query: String)
    fun removeSearchHistory(query: String)
    fun clearSearchHistory()
}
