package com.yichao.evilgodxu

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.StrictMode
import coil3.ImageLoader
import coil3.memory.MemoryCache
import coil3.SingletonImageLoader
import com.yichao.evilgodxu.data.settings.bootstrapAppLanguage
import com.yichao.evilgodxu.data.settings.settingsDataStore
import com.yichao.evilgodxu.data.settings.writeBootLanguage
import com.yichao.evilgodxu.di.appModule
import com.yichao.evilgodxu.log.CrashLogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin

class App : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // 调试构建启用线程策略：让主线程磁盘/网络访问在开发期直接暴露
        enableStrictModeInDebugBuild()
        // 最先初始化崩溃日志，捕获启动阶段异常
        CrashLogManager.init(this)
        // 预热设置 DataStore，并把启动语言写入轻量镜像：
        // 下次冷启动 attachBaseContext 即可同步命中镜像，无需等待 DataStore 首次读取
        appScope.launch {
            runCatching { settingsDataStore.data.first() }
            runCatching { writeBootLanguage(applicationContext, applicationContext.bootstrapAppLanguage()) }
        }
        // 收窄图片内存缓存到进程堆的 10%，把堆留给 ExoPlayer 高解析度音频缓冲，
        // 缓解封面解码与播放并发时的 OOM
        SingletonImageLoader.setSafe { context ->
            ImageLoader.Builder(context)
                .memoryCache {
                    MemoryCache.Builder()
                        .maxSizePercent(context, 0.10)
                        .build()
                }
                .build()
        }

        startKoin {
            // 仅保留错误级日志，避免 release 输出依赖解析噪声
            androidLogger(level = org.koin.core.logger.Level.ERROR)
            androidContext(this@App)
            modules(appModule)
        }
    }

    // 仅调试构建启用：检测主线程磁盘读写与网络访问并输出日志。
    // 用日志而非崩溃作为惩罚，避免生命周期内必要的 I/O 直接把调试包打断
    private fun enableStrictModeInDebugBuild() {
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0) return
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .penaltyLog()
                .build(),
        )
    }
}
