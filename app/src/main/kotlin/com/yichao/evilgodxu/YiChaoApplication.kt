package com.yichao.evilgodxu

import android.app.Application

import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import com.yichao.evilgodxu.di.appModule
import com.yichao.evilgodxu.log.CrashLogManager
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin

class YiChaoApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // 最先初始化崩溃日志，捕获启动阶段异常
        CrashLogManager.init(this)
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
            androidLogger()
            androidContext(this@YiChaoApplication)
            modules(appModule)
        }
    }
}