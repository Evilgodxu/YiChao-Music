package com.yichao.evilgodxu.log

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.system.exitProcess

/**
 * 捕获并记录未捕获异常与 catch 到的异常，链式调用系统默认处理器。
 * 日志按天写入应用专属外部目录，自动清理超期文件。
 */
object CrashLogManager : Thread.UncaughtExceptionHandler {

    private const val TAG = "CrashLogManager"

    /** 日志目录名（应用专属外部目录下） */
    private const val LOG_DIR_NAME = "logs"

    /** 日志文件名前缀 */
    private const val LOG_FILE_PREFIX = "crash_"

    /** 日志保留天数 */
    private const val KEEP_DAYS = 3L

    private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    private var logDir: File? = null
    private var appVersion = "unknown"
    private var previousHandler: Thread.UncaughtExceptionHandler? = null
    private var lastCleanDate: LocalDate? = null

    // 单线程异步写日志，避免阻塞调用线程
    private val logExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "CrashLogWriter").apply { isDaemon = true }
    }

    /** 初始化日志系统，应在 Application.onCreate 最前面调用 */
    fun init(context: Context) {
        logDir = File(context.getExternalFilesDir(null), LOG_DIR_NAME).apply { mkdirs() }
        appVersion = runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            "${info.versionName} (${info.longVersionCode})"
        }.getOrDefault("unknown")

        // 链式接管默认处理器，保留系统默认崩溃流程
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(this)

        cleanOldLogs()
    }

    /** 记录一般异常，标题为"类名: 描述" */
    fun logException(className: String, description: String, throwable: Throwable? = null) {
        if (logDir == null) {
            // 未初始化（如独立进程）时降级到系统日志
            Log.e(TAG, "$className: $description", throwable)
            return
        }
        // 异步写入，不阻塞调用线程
        logExecutor.execute {
            writeLog(title = "$className: $description", throwable = throwable)
        }
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        // 崩溃日志必须同步落盘，确保进程终止前写入完成
        writeLog(title = "未捕获异常（线程 ${thread.name}）", thread = thread, throwable = throwable, withDeviceInfo = true)
        // 交给原处理器，缺失时结束进程
        previousHandler?.uncaughtException(thread, throwable) ?: exitProcess(2)
    }

    @Synchronized
    private fun writeLog(title: String, thread: Thread? = null, throwable: Throwable?, withDeviceInfo: Boolean = false) {
        val dir = logDir ?: return
        // 旧日志清理每天一次，避免每次写入都遍历目录
        val today = LocalDate.now()
        if (lastCleanDate != today) {
            lastCleanDate = today
            cleanOldLogs()
        }
        val logFile = File(dir, "$LOG_FILE_PREFIX${today.format(dateFormat)}.log")
        try {
            FileWriter(logFile, true).use { writer ->
                writer.appendLine("================ $title ================")
                writer.appendLine("时间: ${LocalDateTime.now().format(timeFormat)}")
                if (withDeviceInfo) {
                    writer.appendLine("线程: ${thread?.name}")
                    writer.appendLine("进程: ${android.os.Process.myPid()}")
                    writer.appendLine("设备: ${Build.MANUFACTURER} ${Build.MODEL}")
                    writer.appendLine("系统: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                    writer.appendLine("版本: $appVersion")
                }
                if (throwable != null) {
                    writer.appendLine("异常: ${throwable.javaClass.name}: ${throwable.message}")
                    writer.appendLine("堆栈:")
                    StringWriter().use { sw ->
                        throwable.printStackTrace(PrintWriter(sw))
                        writer.append(sw.toString())
                    }
                }
                writer.appendLine()
            }
        } catch (e: Exception) {
            // 写日志本身失败时降级到系统日志，避免递归崩溃
            Log.e(TAG, "写入日志失败", e)
        }
    }

    /** 清理超过保留天数的旧日志文件 */
    private fun cleanOldLogs() {
        val dir = logDir ?: return
        val deadline = System.currentTimeMillis() - KEEP_DAYS * 24 * 60 * 60 * 1000L
        dir.listFiles { f -> f.isFile && f.name.startsWith(LOG_FILE_PREFIX) }
            ?.filter { it.lastModified() < deadline }
            ?.forEach { it.delete() }
    }
}
