package com.yichao.evilgodxu.musicpanel

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import com.yichao.evilgodxu.log.CrashLogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

// 蓝牙耳机监听器，检测蓝牙耳机连接并自动降低媒体音量
class BluetoothHeadsetMonitor(
    private val context: Context,
    private val onHeadsetConnected: (deviceName: String?, isNewConnection: Boolean) -> Unit,
    private val onHeadsetDisconnected: () -> Unit,
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var registered = false
    private var isHeadsetConnected = false
    // 蓝牙 Profile 代理，用于读取官方推荐的活跃设备（BluetoothA2dp.activeDevice）
    @Volatile
    private var a2dpProxy: BluetoothA2dp? = null
    @Volatile
    private var headsetProxy: BluetoothHeadset? = null

    private val profileServiceListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            when (profile) {
                BluetoothProfile.A2DP -> a2dpProxy = proxy as BluetoothA2dp
                BluetoothProfile.HEADSET -> headsetProxy = proxy as BluetoothHeadset
            }
            // Profile 就绪后刷新设备名称，避免首次显示时仅能回退到 productName
            checkExisting()
        }

        override fun onServiceDisconnected(profile: Int) {
            when (profile) {
                BluetoothProfile.A2DP -> a2dpProxy = null
                BluetoothProfile.HEADSET -> headsetProxy = null
            }
            // Profile 断开时音频设备回调可能尚未触发，主动复核当前路由状态。
            // 延迟复核，避免音频设备列表更新滞后导致状态判断错误
            scope.launch {
                delay(200)
                checkExisting()
            }
        }
    }

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) {
            val btDevice = addedDevices.firstOrNull { isBluetoothA2dp(it) }
            if (btDevice != null) {
                handleConnected(btDevice)
            }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) {
            if (removedDevices.any { isBluetoothA2dp(it) }) {
                val remaining = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                if (remaining.none { isBluetoothA2dp(it) }) {
                    handleDisconnected()
                } else {
                    // 音频设备列表可能存在更新延迟，延迟复核避免漏判断开
                    scope.launch {
                        delay(300)
                        val remainingAfterDelay = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                        if (remainingAfterDelay.none { isBluetoothA2dp(it) }) {
                            handleDisconnected()
                        }
                    }
                }
            }
        }
    }

    fun register() {
        if (registered) return
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, Handler(Looper.getMainLooper()))
        registered = true
        connectProfiles()
        // 异步兜底检查：注册回调后可能遗漏已在连接中的设备
        checkExisting()
    }

    /**
     * 同步检查并更新当前已连接的蓝牙设备状态。
     * 应在 UI 渲染前调用，确保首次显示时状态正确。
     */
    fun checkExistingSync() {
        val btDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { isBluetoothA2dp(it) }
        btDevices.firstOrNull()?.let { device ->
            handleConnected(device)
        } ?: run {
            handleDisconnected()
        }
    }

    fun unregister() {
        if (registered) {
            audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
            registered = false
            closeProfiles()
        }
    }

    /**
     * 连接 A2DP / HEADSET Profile 代理（官方推荐方式），
     * 用于通过 BluetoothA2dp / BluetoothHeadset 的已连接设备列表读取远程设备名称。
     */
    private fun connectProfiles() {
        if (!hasBluetoothPermission()) return
        try {
            val adapter = context.getSystemService(BluetoothManager::class.java).adapter ?: return
            adapter.getProfileProxy(context, profileServiceListener, BluetoothProfile.A2DP)
            adapter.getProfileProxy(context, profileServiceListener, BluetoothProfile.HEADSET)
        } catch (e: SecurityException) {
            CrashLogManager.logException("BluetoothHeadsetMonitor", "连接蓝牙 Profile 失败（蓝牙权限不足）", e)
        }
    }

    private fun closeProfiles() {
        if (a2dpProxy == null && headsetProxy == null) return
        try {
            val adapter = context.getSystemService(BluetoothManager::class.java).adapter ?: return
            a2dpProxy?.let { adapter.closeProfileProxy(BluetoothProfile.A2DP, it) }
            headsetProxy?.let { adapter.closeProfileProxy(BluetoothProfile.HEADSET, it) }
        } catch (e: SecurityException) {
            CrashLogManager.logException("BluetoothHeadsetMonitor", "释放蓝牙 Profile 失败（蓝牙权限不足）", e)
        } finally {
            a2dpProxy = null
            headsetProxy = null
        }
    }

    private fun hasBluetoothPermission(): Boolean {
        return context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun checkExisting() {
        scope.launch {
            val btDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .filter { isBluetoothA2dp(it) }
            btDevices.firstOrNull()?.let { device ->
                handleConnected(device)
            } ?: run {
                handleDisconnected()
            }
        }
    }

    private fun handleConnected(device: AudioDeviceInfo) {
        val deviceName = resolveBluetoothDeviceName(device)
        if (isHeadsetConnected) {
            // 名称解析可能因 Profile 尚未就绪而暂时失败，不能覆盖已获取的真实名称。
            deviceName?.let { onHeadsetConnected(it, false) }
            return
        }
        isHeadsetConnected = true
        onHeadsetConnected(deviceName, true)
    }

    /**
     * 通过官方蓝牙 API 获取远程蓝牙设备的真实名称。
     *
     * AudioDeviceInfo.productName 在部分设备上返回的是本机蓝牙名称而非远程设备名称，
     * 因此优先通过 BluetoothManager / BluetoothProfile 获取远程设备名称。
     */
    private fun resolveBluetoothDeviceName(audioDevice: AudioDeviceInfo): String? {
        // 在调用蓝牙 API 前先检查 BLUETOOTH_CONNECT 运行时权限，
        // 避免触发系统权限弹窗（Android 12+ 为危险权限）
        if (!hasBluetoothPermission()) {
            return null
        }
        try {
            resolveBluetoothDevice(audioDevice.address)?.let { device ->
                // alias 为用户为设备设置的名称，未设置时返回 null，优先展示
                device.alias?.takeIf { it.isNotBlank() }?.let { return it }
                // getName() 返回远程设备在其广播或配对过程中声明的名称
                device.name?.takeIf { it.isNotBlank() }?.let { return it }
            }
        } catch (e: SecurityException) {
            CrashLogManager.logException("BluetoothHeadsetMonitor", "获取蓝牙设备名称失败（蓝牙权限不足）", e)
            // BLUETOOTH_CONNECT 权限不足，暂不显示远程设备名
        } catch (e: Exception) {
            CrashLogManager.logException("BluetoothHeadsetMonitor", "获取蓝牙设备名称失败", e)
            // 其他异常，暂不显示远程设备名
        }
        // productName 在部分设备上是本机蓝牙名称，不能作为远程设备名称回退。
        return null
    }

    /**
     * 解析远程蓝牙设备。
     *
     * 优先使用同步的 BluetoothManager.getConnectedDevices()，不依赖异步获取的
     * Profile 代理（面板首次显示时代理可能尚未就绪）。AudioDeviceInfo.address
     * 在部分厂商 ROM 上为空或格式不一致，因此地址匹配失败时取已连接设备中的
     * 第一个作为兜底，避免回退到本机名称。
     */
    // 权限已在方法入口（hasBluetoothPermission）及调用方 resolveBluetoothDeviceName 中检查，
    // lint 无法识别经辅助函数的守卫，故此处标注 SuppressLint
    @SuppressLint("MissingPermission")
    private fun resolveBluetoothDevice(address: String): BluetoothDevice? {
        // 下方所有蓝牙 API 均受 BLUETOOTH_CONNECT 保护，先检查权限避免 SecurityException
        if (!hasBluetoothPermission()) return null
        val manager = context.getSystemService(BluetoothManager::class.java)
        // 官方 API：同步获取当前已连接的蓝牙设备
        // 部分 ROM（如小米）不支持通过 BluetoothManager 按 Profile 查询，需逐个容错
        val connected = mutableListOf<BluetoothDevice>()
        try {
            connected.addAll(manager.getConnectedDevices(BluetoothProfile.A2DP))
        } catch (e: IllegalArgumentException) {
            // 该 ROM 不支持 A2DP 查询，忽略
        }
        try {
            connected.addAll(manager.getConnectedDevices(BluetoothProfile.HEADSET))
        } catch (e: IllegalArgumentException) {
            // 该 ROM 不支持 HEADSET 查询，忽略
        }
        val normalizedAddress = address.uppercase()
        connected.firstOrNull { it.address == normalizedAddress }?.let { return it }
        a2dpProxy?.connectedDevices?.firstOrNull { it.address == normalizedAddress }?.let { return it }
        headsetProxy?.connectedDevices?.firstOrNull { it.address == normalizedAddress }?.let { return it }
        // 地址无效或不匹配时，取已连接设备中的第一个（通常为当前音频路由设备）
        connected.firstOrNull()?.let { return it }
        a2dpProxy?.connectedDevices?.firstOrNull()?.let { return it }
        headsetProxy?.connectedDevices?.firstOrNull()?.let { return it }
        return try {
            manager.adapter?.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            CrashLogManager.logException("BluetoothHeadsetMonitor", "获取蓝牙设备失败（设备地址无效）", e)
            null
        }
    }

    private fun handleDisconnected() {
        val wasConnected = isHeadsetConnected
        isHeadsetConnected = false
        // 即使监听器状态未及时记录为已连接，也要通知面板清理可能残留的设备信息。
        if (wasConnected || registered) {
            onHeadsetDisconnected()
        }
    }

    companion object {
        private val btA2dpTypes by lazy {
            // AudioDeviceInfo.TYPE_BLUETOOTH_A2DP = 8, TYPE_BLUETOOTH_SCO = 7
            setOf(
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            )
        }

        fun isBluetoothA2dp(device: AudioDeviceInfo): Boolean = device.type in btA2dpTypes

        /** 降低媒体音量到指定百分比（0f ~ 1f） */
        fun reduceMediaVolume(context: Context, percentage: Float) {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val targetVolume = (maxVolume * percentage.coerceIn(0f, 1f)).toInt()
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVolume, 0)
        }
    }
}
