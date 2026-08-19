package com.yichao.evilgodxu.data.permission

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

// 需要申请的核心权限类型
enum class PermissionType {
    OVERLAY,                 // 悬浮窗（系统特殊权限）
    MANAGE_EXTERNAL_STORAGE, // 全部文件（系统特殊权限）
    MEDIA_AUDIO,             // 音乐访问（运行时权限）
    BLUETOOTH,               // 蓝牙设备（运行时权限）
}

// 音乐访问的运行时权限名：API 33 及以上为媒体权限，以下为旧的外部存储权限
fun mediaAudioPermission(): String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    Manifest.permission.READ_MEDIA_AUDIO
} else {
    Manifest.permission.READ_EXTERNAL_STORAGE
}

// 权限状态监控器
class PermissionMonitor(private val context: Context) {

    fun isOverlayGranted(): Boolean = Settings.canDrawOverlays(context)

    fun isAllFilesGranted(): Boolean = Environment.isExternalStorageManager()

    fun isMediaAudioGranted(): Boolean =
        ContextCompat.checkSelfPermission(context, mediaAudioPermission()) == PackageManager.PERMISSION_GRANTED

    fun isBluetoothGranted(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    fun isGranted(permissionType: PermissionType): Boolean = when (permissionType) {
        PermissionType.OVERLAY -> isOverlayGranted()
        PermissionType.MANAGE_EXTERNAL_STORAGE -> isAllFilesGranted()
        PermissionType.MEDIA_AUDIO -> isMediaAudioGranted()
        PermissionType.BLUETOOTH -> isBluetoothGranted()
    }

    // 持续监控指定权限，直到授权后返回 true
    fun monitorPermission(permissionType: PermissionType, intervalMs: Long = 500): Flow<Boolean> = flow {
        while (true) {
            val granted = isGranted(permissionType)
            emit(granted)
            if (granted) break
            delay(intervalMs)
        }
    }.flowOn(Dispatchers.IO)
}
