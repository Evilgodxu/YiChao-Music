package com.yichao.evilgodxu.data.permission

import android.content.Context
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

// 应用级悬浮窗授权监控：生命周期独立于 Activity/ViewModel。
// 用户在系统悬浮窗设置页授权时，即使 Activity 已被销毁（如开启"不保留活动"或配置变更），
// 只要进程存活，仍能检测到授权并回调 onGranted，用于把应用自动带回前台。
object OverlayGrantMonitor {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    // 只保留最新一次回调，避免 ViewModel 重建后旧回调持有已清理实例
    @Volatile
    private var onGranted: ((Context) -> Unit)? = null

    @Synchronized
    fun start(context: Context, onGranted: (Context) -> Unit) {
        this.onGranted = onGranted
        if (job?.isActive == true) return
        val appContext = context.applicationContext
        job = scope.launch {
            flow {
                while (true) {
                    val granted = Settings.canDrawOverlays(appContext)
                    emit(granted)
                    if (granted) break
                    delay(500)
                }
            }
                .flowOn(Dispatchers.IO)
                .collect { granted ->
                    if (granted) this@OverlayGrantMonitor.onGranted?.invoke(appContext)
                }
        }
    }

    // 用户已从设置页返回或主动关闭开关：结束监控，避免无意义轮询
    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        onGranted = null
    }
}
