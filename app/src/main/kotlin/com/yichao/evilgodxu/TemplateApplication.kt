package com.yichao.evilgodxu

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.yichao.evilgodxu.di.appModule
import com.yichao.evilgodxu.log.CrashLogManager
import com.yichao.evilgodxu.update.UpdateCheckWorker
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import java.util.concurrent.TimeUnit

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

        // 创建更新通知渠道并调度周期性更新检查（最小间隔 15 分钟，内部有 24 小时冷却）
        createUpdateNotificationChannel()
        scheduleUpdateCheck()
    }

    private fun createUpdateNotificationChannel() {
        val channel = NotificationChannel(
            UpdateCheckWorker.CHANNEL_ID,
            getString(R.string.update_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = getString(R.string.update_channel_desc)
        }
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    private fun scheduleUpdateCheck() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(
            15, TimeUnit.MINUTES  // WorkManager 最小周期为 15 分钟
        )
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            UpdateCheckWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }
}
