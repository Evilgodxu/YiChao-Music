package com.yichao.evilgodxu.screens.home.home_assembly.permission_area

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.data.permission.PermissionType
import com.yichao.evilgodxu.data.permission.mediaAudioPermission
import com.yichao.evilgodxu.screens.home.HomeUiState
import com.yichao.evilgodxu.screens.settings.settings_assembly.component.section.SettingsSection

// 权限状态分区：展示悬浮窗、全部文件、音乐访问、蓝牙四项权限状态并可点击申请，全部授权后自动隐藏
@Composable
fun PermissionArea(
    uiState: HomeUiState,
    onRefresh: () -> Unit,
    onStartPermissionMonitor: (PermissionType, Activity) -> Unit = { _, _ -> },
    onStopPermissionMonitor: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // LocalContext 为本地化包装 context，宿主 Activity 需从注册表所有者获取
    val activity = LocalActivityResultRegistryOwner.current as? Activity

    // 运行时权限（音乐访问、蓝牙）申请结果回调后统一刷新状态
    val runtimePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        onRefresh()
    }

    // 从系统设置页返回时刷新权限状态并停止监控
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                onRefresh()
                onStopPermissionMonitor()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            onStopPermissionMonitor()
        }
    }

    AnimatedVisibility(
        visible = !uiState.allPermissionsGranted,
        enter = expandVertically(),
        exit = shrinkVertically(),
    ) {
        SettingsSection(title = stringResource(R.string.home_permission_title)) {
            Column(
                modifier = modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PermissionCardRow(
                    icon = {
                        Icon(
                            painterResource(R.drawable.ic_overlay),
                            null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                    title = stringResource(R.string.permission_overlay_title),
                    description = stringResource(R.string.permission_overlay_desc),
                    granted = uiState.overlayGranted,
                    onRequest = {
                        // 跳转系统设置前启动权限监控，授权后自动返回本应用
                        activity?.let { onStartPermissionMonitor(PermissionType.OVERLAY, it) }
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}"),
                        )
                        if (activity != null) {
                            activity.startActivity(intent)
                        } else {
                            // 无宿主 Activity 时需加 NEW_TASK
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(intent)
                        }
                    },
                )
                PermissionCardRow(
                    icon = {
                        Icon(
                            painterResource(R.drawable.ic_all_files),
                            null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                    title = stringResource(R.string.permission_all_files_title),
                    description = stringResource(R.string.permission_all_files_desc),
                    granted = uiState.allFilesGranted,
                    onRequest = {
                        // 跳转系统设置前启动权限监控，授权后自动返回本应用
                        activity?.let { onStartPermissionMonitor(PermissionType.MANAGE_EXTERNAL_STORAGE, it) }
                        val intent = Intent(
                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:${context.packageName}"),
                        )
                        if (activity != null) {
                            activity.startActivity(intent)
                        } else {
                            // 无宿主 Activity 时需加 NEW_TASK
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(intent)
                        }
                    },
                )
                PermissionCardRow(
                    icon = {
                        Icon(
                            painterResource(R.drawable.ic_music),
                            null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                    title = stringResource(R.string.permission_music_title),
                    description = stringResource(R.string.permission_music_desc),
                    granted = uiState.mediaAudioGranted,
                    onRequest = {
                        runtimePermissionLauncher.launch(arrayOf(mediaAudioPermission()))
                    },
                )
                PermissionCardRow(
                    icon = {
                        Icon(
                            painterResource(R.drawable.ic_bluetooth),
                            null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                    title = stringResource(R.string.permission_bluetooth_title),
                    description = stringResource(R.string.permission_bluetooth_desc),
                    granted = uiState.bluetoothGranted,
                    onRequest = {
                        runtimePermissionLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT))
                    },
                )
            }
        }
    }
}
