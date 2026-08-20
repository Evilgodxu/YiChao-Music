package com.yichao.evilgodxu.update

import com.yichao.evilgodxu.log.CrashLogManager

import android.app.DownloadManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * 版本更新信息
 */
data class UpdateInfo(
    val latestVersion: String,
    val downloadUrl: String,
    val changelog: String,
    val isDownloading: Boolean = false,
    val downloadId: Long? = null
)

/** 下载状态 */
sealed class DownloadState {
    data object Idle : DownloadState()
    data class Downloading(val progress: Float) : DownloadState()
    data object Success : DownloadState()
    data class Failed(val errorMessage: String) : DownloadState()
}

/**
 * 应用更新管理器
 * 负责检查 GitHub Releases、版本比较和 APK 下载
 */
object UpdateManager {

    private const val PREFS_NAME = "update_prefs"
    private const val KEY_LAST_CHECK = "last_check_time"
    private const val KEY_PENDING_VERSION = "pending_version"
    private const val KEY_PENDING_URL = "pending_url"
    private const val KEY_PENDING_CHANGELOG = "pending_changelog"
    private const val KEY_IGNORED_VERSION = "ignored_version"
    private const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L // 24 小时冷却
    private const val TAG = "UpdateManager"

    // GitHub 仓库配置
    private const val GITHUB_OWNER = "Evilgodxu"
    private const val GITHUB_REPO = "YiChao-Music"
    const val GITHUB_REPOSITORY_URL = "https://github.com/$GITHUB_OWNER/$GITHUB_REPO"

    private val json = Json { ignoreUnknownKeys = true }
    private val prefsMap = ConcurrentHashMap<String, android.content.SharedPreferences>()

    private fun prefs(context: Context): android.content.SharedPreferences {
        return prefsMap.getOrPut(context.packageName) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    /**
     * GitHub Release 响应模型（仅解析需要的字段）
     */
    @Serializable
    private data class GitHubRelease(
        val tag_name: String = "",
        val body: String = "",
        val assets: List<GitHubAsset> = emptyList()
    )

    @Serializable
    private data class GitHubAsset(
        val name: String = "",
        val browser_download_url: String = ""
    )

    /**
     * 检查是否有新版本（非强制检查时遵守 24 小时冷却）
     */
    suspend fun checkForUpdate(
        context: Context,
        force: Boolean = false,
        onError: ((Exception) -> Unit)? = null
    ): UpdateInfo? {
        val prefs = prefs(context)
        val now = System.currentTimeMillis()

        // 非强制检查时遵守 24 小时冷却
        if (!force) {
            val lastCheck = prefs.getLong(KEY_LAST_CHECK, 0L)
            if (now - lastCheck < CHECK_INTERVAL_MS) {
                // 返回缓存的待更新信息
                val cachedVersion = prefs.getString(KEY_PENDING_VERSION, null)
                val cachedUrl = prefs.getString(KEY_PENDING_URL, null)
                if (cachedVersion != null && cachedUrl != null) {
                    return UpdateInfo(
                        latestVersion = cachedVersion,
                        downloadUrl = cachedUrl,
                        changelog = prefs.getString(KEY_PENDING_CHANGELOG, "") ?: ""
                    )
                }
                return null
            }
        }

        return try {
            val release = withContext(Dispatchers.IO) {
                val url = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"
                json.decodeFromString<GitHubRelease>(readJson(url))
            }

            val latest = normalizeVersion(release.tag_name)
            val current = getCurrentVersion(context)
            val ignored = prefs.getString(KEY_IGNORED_VERSION, null)?.let(::normalizeVersion)

            if (isNewerVersion(latest, current) && latest != ignored) {
                val apkAsset = release.assets.firstOrNull { it.name.endsWith(".apk") }
                val downloadUrl = apkAsset?.browser_download_url?.takeIf { it.isNotBlank() }
                    ?: throw IllegalStateException("GitHub Release 未提供可用 APK")

                prefs.edit()
                    .putLong(KEY_LAST_CHECK, now)
                    .putString(KEY_PENDING_VERSION, latest)
                    .putString(KEY_PENDING_URL, downloadUrl)
                    .putString(KEY_PENDING_CHANGELOG, release.body)
                    .apply()

                UpdateInfo(
                    latestVersion = latest,
                    downloadUrl = downloadUrl,
                    changelog = release.body
                )
            } else {
                prefs.edit().putLong(KEY_LAST_CHECK, now).apply()
                null
            }
        } catch (e: Exception) {
            CrashLogManager.logException("UpdateManager", "检查更新失败", e)
            onError?.invoke(e)
            null
        }
    }

    private fun readJson(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("User-Agent", "YiChaoMusic/$GITHUB_REPO")
        return try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("更新服务响应异常: HTTP $code")
            }
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun normalizeVersion(version: String): String {
        return version.trim().trimStart('v', 'V')
    }

    /**
     * 获取当前版本号
     */
    private fun getCurrentVersion(context: Context): String {
        return try {
            val pkgInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pkgInfo.versionName ?: "0.0.0"
        } catch (e: PackageManager.NameNotFoundException) {
            CrashLogManager.logException("UpdateManager", "获取当前版本号失败", e)
            "0.0.0"
        }
    }

    /**
     * 检查当前网络是否为 WiFi
     */
    fun isOnWifi(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    /**
     * 下载 APK 并引导安装（用于对话框点击「下载」）
     * 下载到应用私有目录，通过 onProgress 回调进度，完成后通过 FileProvider 打开安装界面
     * 长时间无进度变动（15 秒）判定为超时失败
     *
     * @return true 表示下载成功并启动了安装界面，false 表示下载失败
     */
    suspend fun downloadAndInstall(
        context: Context,
        updateInfo: UpdateInfo,
        onProgress: (Float) -> Unit = {}
    ): Boolean {
        val fileName = "YiChaoMusic_${updateInfo.latestVersion}.apk"
        val outFile = java.io.File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
            fileName
        )

        // 下载、轮询、文件操作全部在 IO 线程执行，避免 DownloadManager IPC 阻塞主线程
        return withContext(Dispatchers.IO) {
            // 删除已存在的旧文件
            if (outFile.exists()) outFile.delete()

            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val req = DownloadManager.Request(Uri.parse(requireHttps(updateInfo.downloadUrl)))
                .setTitle("忆潮音乐更新")
                .setDescription("正在下载 ${updateInfo.latestVersion}")
                .setDestinationUri(Uri.fromFile(outFile))
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)

            val downloadId = dm.enqueue(req)
            var lastProgressBytes = -1L
            var stallCount = 0
            val STALL_TIMEOUT = 30  // 30 次无进度 * 500ms = 15 秒

            // 轮询下载进度
            while (true) {
                val (status, done, total) = dm.query(DownloadManager.Query().setFilterById(downloadId)).use { cursor ->
                    if (!cursor.moveToFirst()) return@use null
                    Triple(
                        cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                        cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                        cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    )
                } ?: break

                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        onProgress(1f)
                        // 下载完成，通过 FileProvider 打开安装界面
                        val uri = androidx.core.content.FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            outFile
                        )
                        val installIntent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "application/vnd.android.package-archive")
                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(installIntent)
                        clearPendingUpdate(context)
                        return@withContext true
                    }
                    DownloadManager.STATUS_FAILED -> {
                        android.util.Log.w(TAG, "Download failed")
                        onProgress(-1f)
                        return@withContext false
                    }
                    else -> {
                        // 上报进度
                        if (total > 0) {
                            onProgress(done.toFloat() / total)
                        }

                        // 检测进度停滞超时
                        if (done == lastProgressBytes) {
                            stallCount++
                            if (stallCount >= STALL_TIMEOUT) {
                                android.util.Log.w(TAG, "Download stalled for 30s, aborting")
                                dm.remove(downloadId)
                                onProgress(-1f)
                                return@withContext false
                            }
                        } else {
                            lastProgressBytes = done
                            stallCount = 0
                        }
                        kotlinx.coroutines.delay(500)
                    }
                }
            }
            onProgress(-1f)
            false
        }
    }

    // 仅允许 HTTPS 下载地址，防止下载降级到明文传输
    private fun requireHttps(url: String): String {
        if (!url.startsWith("https://")) {
            throw IllegalArgumentException("不安全的下载地址: $url")
        }
        return url
    }

    /**
     * 清除缓存的更新信息
     */
    fun clearPendingUpdate(context: Context) {
        prefs(context).edit()
            .remove(KEY_PENDING_VERSION)
            .remove(KEY_PENDING_URL)
            .remove(KEY_PENDING_CHANGELOG)
            .apply()
    }

    /**
     * 忽略某个版本
     */
    fun ignoreVersion(context: Context, version: String) {
        prefs(context).edit()
            .putString(KEY_IGNORED_VERSION, version)
            .remove(KEY_PENDING_VERSION)
            .remove(KEY_PENDING_URL)
            .remove(KEY_PENDING_CHANGELOG)
            .apply()
    }

    /**
     * 版本号语义比较
     */
    private fun isNewerVersion(latest: String, current: String): Boolean {
        val l = latest.split(".").mapNotNull { it.toIntOrNull() }
        val c = current.split(".").mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(l.size, c.size)) {
            val lv = l.getOrElse(i) { 0 }
            val cv = c.getOrElse(i) { 0 }
            if (lv > cv) return true
            if (lv < cv) return false
        }
        return false
    }
}