package com.yichao.evilgodxu.musicpanel

import android.content.Intent
import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.common.ForwardingPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.yichao.evilgodxu.R
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
class MusicPlaybackService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        // 变速/变调交给 AudioTrack 原生处理，避免 Sonic 软件变速在低速时产生噪声
        val usbAudioSink = DefaultAudioSink.Builder(this)
            .setEnableAudioOutputPlaybackParameters(true)
            .build()
        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: android.content.Context,
                enableFloatOutput: Boolean,
                enableAudioOutputPlaybackParameters: Boolean,
            ): AudioSink = usbAudioSink
        }
        UsbAudioMonitor.audioSinkDeviceSetter = usbAudioSink::setPreferredDevice
        player = ExoPlayer.Builder(this, renderersFactory)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        player.addListener(object : Player.Listener {
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                val format = tracks.groups.firstOrNull { it.isSelected }?.getTrackFormat(0)
                val state = MusicPanelStateHolder.state
                // 无论 format 是否为空，每次轨道切换都更新信号路径状态
                val fileFormat = format?.let { f ->
                    val track = state.currentTrack
                    track?.path
                        ?.substringAfterLast('.', "")
                        ?.takeIf { it.isNotBlank() }
                        ?.uppercase()
                        ?.let { if (it == "MPEG") "MP3" else it }
                        ?: when (f.sampleMimeType) {
                            "audio/mpeg" -> "MP3"
                            "audio/flac" -> "FLAC"
                            "audio/wav", "audio/x-wav" -> "WAV"
                            "audio/ogg" -> "OGG"
                            "audio/mp4", "audio/aac" -> "AAC"
                            else -> f.sampleMimeType?.substringAfterLast('/')?.uppercase()
                        }
                }
                if (format != null) {
                    val sampleRate = format.sampleRate.takeIf { it > 0 } ?: 48000
                    val channels = format.channelCount.takeIf { it > 0 } ?: 2
                    val encoding = if (format.pcmEncoding > 0) format.pcmEncoding else android.media.AudioFormat.ENCODING_PCM_16BIT
                    UsbAudioMonitor.updatePlaybackFormat(sampleRate, channels, encoding)
                    // 独占模式下按新格式重新应用位完美混音属性（采样率/位深可能随曲目变化）
                    if (state.isUsbExclusiveMode) {
                        UsbAudioMonitor.setUsbExclusive(this@MusicPlaybackService, true)
                    }
                    state.audioSignalPathFormat = AudioSignalPathFormat(
                        format = fileFormat ?: "PCM",
                        sampleRate = sampleRate,
                        outputRate = sampleRate,
                        bitDepth = when (encoding) {
                            android.media.AudioFormat.ENCODING_PCM_8BIT -> 8
                            android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
                            android.media.AudioFormat.ENCODING_PCM_FLOAT -> 32
                            else -> 16
                        },
                        channels = channels,
                    )
                }
                // 每次轨道切换都刷新状态，确保信号路径始终有值
                updateSignalPathState(state)
            }

            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                if (mediaItem != null) {
                    player.playWhenReady = true
                }
            }
        })
        mediaSession = MediaSession.Builder(this, SkipProxyPlayer(player))
            .setCallback(sessionCallback)
            .build()
    }

    /** 拦截系统媒体面板和耳机/蓝牙媒体键的上一首/下一首操作 */
    private val sessionCallback = object : MediaSession.Callback {
        override fun onMediaButtonEvent(
            session: MediaSession,
            controllerInfo: MediaSession.ControllerInfo,
            intent: Intent,
        ): Boolean {
            val keyEvent = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
            if (keyEvent?.action == KeyEvent.ACTION_DOWN) {
                when (keyEvent.keyCode) {
                    KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                    KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD -> {
                        handlePreviousTrack()
                        return true
                    }
                    KeyEvent.KEYCODE_MEDIA_NEXT,
                    KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD -> {
                        handleNextTrack()
                        return true
                    }
                }
            }
            return super.onMediaButtonEvent(session, controllerInfo, intent)
        }
    }

    /**
     * 包装 ExoPlayer，把系统媒体面板/通知栏的上一首/下一首操作
     * 映射到应用自己的切歌逻辑，避免默认行为中「回退到当前曲目开头」。
     */
    private inner class SkipProxyPlayer(player: Player) : ForwardingPlayer(player) {
        override fun seekToPrevious() {
            handlePreviousTrack()
        }

        override fun seekToPreviousMediaItem() {
            handlePreviousTrack()
        }

        override fun seekToNext() {
            handleNextTrack()
        }

        override fun seekToNextMediaItem() {
            handleNextTrack()
        }
    }

    private fun handlePreviousTrack() {
        val state = MusicPanelStateHolder.state
        if (state.currentTrack != null && state.playlist.isNotEmpty()) {
            val prev = state.previousIndex()
            if (prev >= 0) {
                state.playbackScope.launch {
                    playTrackAt(this@MusicPlaybackService, state, prev)
                }
            }
        }
    }

    private fun handleNextTrack() {
        val state = MusicPanelStateHolder.state
        if (state.currentTrack != null && state.playlist.isNotEmpty()) {
            val next = state.nextIndex()
            if (next >= 0) {
                state.playbackScope.launch {
                    playTrackAt(this@MusicPlaybackService, state, next)
                }
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        super.onUpdateNotification(session, startInForegroundRequired)
        if (!player.isPlaying && player.playbackState == Player.STATE_IDLE) {
            stopSelf()
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.isPlaying) {
            stopSelf()
        }
    }

    fun stopPlayback() {
        player.stop()
        mediaSession?.release()
        mediaSession = null
        stopSelf()
    }

    override fun onDestroy() {
        mediaSession?.release()
        mediaSession = null
        UsbAudioMonitor.audioSinkDeviceSetter = null
        player.release()
        super.onDestroy()
    }

    private fun resolveOutputDeviceName(state: MusicPlaybackState): String {
        if (state.isUsbDeviceConnected && state.usbDeviceName.isNotBlank()) return state.usbDeviceName
        if (state.isBluetoothHeadsetConnected && state.bluetoothHeadsetName.isNotBlank()) {
            return state.bluetoothHeadsetName
        }
        val audioManager = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
        return audioManager.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { device ->
                device.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER ||
                    device.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
            }
            ?.productName
            ?.toString()
            ?.takeIf { it.isNotBlank() }
            ?: getString(R.string.signal_path_speaker)
    }

    /** 刷新播放链路面板的状态行 */
    private fun updateSignalPathState(state: MusicPlaybackState) {
        state.audioSignalPathStrategy = if (state.isUsbExclusiveMode) "Direct" else "Mixer"
        state.audioSignalPathOutputDevice = resolveOutputDeviceName(state)
        state.audioSignalPathRoute = if (state.isUsbDeviceConnected) "USB" else if (state.isBluetoothHeadsetConnected) "Bluetooth" else "System"
    }
}
