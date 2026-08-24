package com.yichao.evilgodxu

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.LocaleList
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.IntentCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yichao.evilgodxu.data.repository.SettingsRepository
import com.yichao.evilgodxu.musicpanel.LocalMusicPanelController
import com.yichao.evilgodxu.musicpanel.MusicPanelController
import com.yichao.evilgodxu.navigation.AppNavHost
import com.yichao.evilgodxu.theme.MyApplicationTheme
import com.yichao.evilgodxu.theme.SystemBarAppearance
import com.yichao.evilgodxu.ui.adaptive.ProvideWindowSizeClass
import com.yichao.evilgodxu.update.UpdateDialog
import com.yichao.evilgodxu.update.UpdateManager
import com.yichao.evilgodxu.update.UpdateViewModel
import com.yichao.evilgodxu.utils.localization.LocalizationManager
import com.yichao.evilgodxu.utils.localization.ProvideLocalizedContext
import com.yichao.evilgodxu.utils.localization.toLocale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.koin.android.ext.android.inject

class YiChaoActivity : ComponentActivity() {
    private lateinit var windowInsetsController: WindowInsetsControllerCompat
    private val localizationManager: LocalizationManager by inject()
    private val updateViewModel: UpdateViewModel by inject()
    private lateinit var musicPanelController: MusicPanelController

    // 冷启动按持久化语言创建配置上下文，进入界面即正确语言
    override fun attachBaseContext(newBase: Context) {
        val locale = runBlocking { SettingsRepository(newBase).appLanguage.first() }.toLocale()
        val config = Configuration(newBase.resources.configuration).apply {
            setLocales(LocaleList(locale))
        }
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            // 系统栏图标外观由 Compose 按主题与页面控制，这里仅跟随系统作为初始兜底值
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        setupSystemBars()
        // 绑定当前 Activity，使对话框等独立窗口在切语言时同步更新资源
        localizationManager.bindActivity(this)

        // 音乐面板悬浮窗控制器：应用后台播放时显示迷你播放器，回到前台时移除
        musicPanelController = MusicPanelController(applicationContext)
        handleExternalIntent(intent)
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                musicPanelController.onAppForegrounded()
            }

            override fun onStop(owner: LifecycleOwner) {
                musicPanelController.onAppBackgrounded()
            }
        })

        setContent {
            ProvideLocalizedContext(localizationManager) {
                CompositionLocalProvider(LocalMusicPanelController provides musicPanelController) {
                    ProvideWindowSizeClass {
                        YiChaoContent()
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        // 解除语言管理器对 Activity 的绑定，避免单例持有已销毁实例
        localizationManager.unbindActivity(this)
        super.onDestroy()
        // 销毁（含旋转重建）时释放悬浮窗，避免重建后残留无宿主窗口
        musicPanelController.release()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateSystemBarsVisibility(newConfig.orientation)
        // 系统 uiMode 变化时系统可能重置系统栏图标，复读 Compose 应用的外观
        applySystemBarAppearance()
    }

    override fun onResume() {
        super.onResume()
        applySystemBarAppearance()
    }

    // 窗口重新获得焦点时系统可能重置系统栏外观（如对话框关闭后），复读 Compose 应用的外观
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applySystemBarAppearance()
    }

    private fun applySystemBarAppearance() {
        windowInsetsController.isAppearanceLightStatusBars = SystemBarAppearance.isLightStatusBars
        windowInsetsController.isAppearanceLightNavigationBars = SystemBarAppearance.isLightNavigationBars
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleExternalIntent(intent)
    }

    // 处理外部通过打开/分享传入的音频：经迷你播放器后台播放，不展示全屏面板，并保持应用在后台
    private fun handleExternalIntent(intent: Intent) {
        val uri = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND ->
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        } ?: return
        musicPanelController.playExternalInBackground(uri)
        moveTaskToBack(true)
    }

    @Composable
    private fun YiChaoContent() {
        // 回前台时自动检查更新（每日仅检查一次）
        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME && UpdateManager.shouldCheckUpdate(applicationContext)) {
                    updateViewModel.checkForUpdate()
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
            }
        }

        // 更新对话框与手动检查反馈（全局弹出，覆盖所有页面）
        val updateInfo by updateViewModel.updateInfo.collectAsStateWithLifecycle()
        val showUpdateDialog by updateViewModel.showUpdateDialog.collectAsStateWithLifecycle()
        val downloadState by updateViewModel.downloadState.collectAsStateWithLifecycle()
        val checkFeedback by updateViewModel.checkFeedback.collectAsStateWithLifecycle()

        LaunchedEffect(checkFeedback) {
            when (checkFeedback) {
                UpdateViewModel.CheckFeedback.UP_TO_DATE ->
                    Toast.makeText(this@YiChaoActivity, R.string.update_toast_up_to_date, Toast.LENGTH_SHORT).show()
                UpdateViewModel.CheckFeedback.ERROR ->
                    Toast.makeText(this@YiChaoActivity, R.string.update_toast_error, Toast.LENGTH_SHORT).show()
                null -> {}
            }
            updateViewModel.clearCheckFeedback()
        }

        MyApplicationTheme {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
            ) {
                AppNavHost(onExit = { finish() })
            }
        }

        if (showUpdateDialog && updateInfo != null) {
            val info = updateInfo
            if (info != null) {
                UpdateDialog(
                    updateInfo = info,
                    downloadState = downloadState,
                    onDownload = { updateViewModel.downloadAndInstall() },
                    onOpenBrowser = {
                        val url = UpdateManager.GITHUB_REPOSITORY_URL
                        if (url.startsWith("http")) {
                            startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                        }
                        updateViewModel.dismissUpdateDialog()
                    },
                    onDismiss = { updateViewModel.dismissUpdateDialog() }
                )
            }
        }
    }

    private fun setupSystemBars() {
        windowInsetsController = WindowInsetsControllerCompat(window, window.decorView)
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        updateSystemBarsVisibility()
    }

    // 横屏隐藏系统栏，竖屏显示
    private fun updateSystemBarsVisibility(orientation: Int = resources.configuration.orientation) {
        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            windowInsetsController.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}