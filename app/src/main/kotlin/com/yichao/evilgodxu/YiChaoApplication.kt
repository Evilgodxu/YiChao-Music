package com.yichao.evilgodxu

import android.app.Application
import coil3.ImageLoader
import coil3.memory.MemoryCache
import coil3.SingletonImageLoader
import com.yichao.evilgodxu.data.settings.settingsDataStore
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

class YiChaoApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // 最先初始化崩溃日志，捕获启动阶段异常
        CrashLogManager.init(this)
        // 预热设置 DataStore：冷启动 attachBaseContext 同步读语言时可命中内存缓存，缩短主线程等待
        appScope.launch { runCatching { settingsDataStore.data.first() } }
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
            androidContext(this@YiChaoApplication)
            modules(appModule)
        }
    }
}
