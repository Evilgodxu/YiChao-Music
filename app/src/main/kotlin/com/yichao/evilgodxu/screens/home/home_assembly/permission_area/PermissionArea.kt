package com.yichao.evilgodxu.screens.home.home_assembly.permission_area

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.data.permission.PermissionType
import com.yichao.evilgodxu.data.permission.mediaAudioPermission
import com.yichao.evilgodxu.screens.home.HomeUiState

// 权限状态对话框：未全部授权时显示且不可关闭，列表 + 右侧按钮申请，全部授权后自动隐藏
@Composable
fun PermissionDialog(
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

    if (!uiState.allPermissionsGranted) {
        Dialog(
            onDismissRequest = {},
            properties = DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
            ),
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 4.dp,
                modifier = modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column {
                        Text(
                            text = stringResource(R.string.home_permission_title),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(R.string.home_permission_hint),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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
                            activity?.let {
                                onStartPermissionMonitor(PermissionType.MANAGE_EXTERNAL_STORAGE, it)
                            }
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
}
