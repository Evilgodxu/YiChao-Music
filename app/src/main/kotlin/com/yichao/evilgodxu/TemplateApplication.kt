package com.yichao.evilgodxu

import android.app.Application
import com.yichao.evilgodxu.di.appModule
import com.yichao.evilgodxu.log.CrashLogManager
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin

class TemplateApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // 最先初始化崩溃日志，捕获启动阶段异常
        CrashLogManager.init(this)

        startKoin {
            androidLogger()
            androidContext(this@TemplateApplication)
            modules(appModule)
        }
    }
}
