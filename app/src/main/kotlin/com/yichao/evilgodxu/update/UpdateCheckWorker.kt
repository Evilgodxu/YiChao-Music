package com.yichao.evilgodxu.update

import com.yichao.evilgodxu.TemplateActivity
import com.yichao.evilgodxu.R

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * 应用更新检查 Worker
 * 由 WorkManager 周期性调度（最小间隔 15 分钟，内部遵守 24 小时冷却即每天检查）
 *
 * 检查到更新时：
 * - 应用在前台 → 不做处理，由 TemplateActivity 的 Lifecycle 回调弹出对话框
 * - 应用在后台 → 发送通知提醒用户
 */
class UpdateCheckWorker(
    ctx: Context,
    params: WorkerParameters
) : CoroutineWorker(ctx, params) {

    companion object {
        const val CHANNEL_ID = "app_updates"
        const val NOTIFICATION_ID = 1001
        const val WORK_NAME = "update_check"

        /** 应用是否在前台，由 TemplateActivity 的 Lifecycle 回调更新 */
        @Volatile
        var isAppInForeground: Boolean = false
    }

    override suspend fun doWork(): Result {
        // 检查更新
        val updateInfo = UpdateManager.checkForUpdate(applicationContext) ?: return Result.success()

        // 应用在前台时，不做通知（由 Activity 处理对话框）
        if (isAppInForeground) {
            return Result.success()
        }

        // 应用在后台时，发送通知
        showUpdateNotification(updateInfo)
        return Result.success()
    }

    private fun showUpdateNotification(updateInfo: UpdateInfo) {
        val context = applicationContext

        // 创建通知渠道
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.update_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.update_channel_desc)
        }
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)

        // 点击通知打开主界面
        val intent = Intent(context, TemplateActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("show_update", true)
        }
        val pi = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(context.getString(R.string.update_notification_title))
            .setContentText(
                context.getString(R.string.update_notification_text, updateInfo.latestVersion)
            )
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    context.getString(R.string.update_notification_text, updateInfo.latestVersion)
                )
            )
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } else {
            android.util.Log.w("UpdateCheckWorker", "POST_NOTIFICATIONS permission not granted")
        }
    }
}