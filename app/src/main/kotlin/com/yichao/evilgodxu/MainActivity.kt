package com.yichao.evilgodxu

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.LocaleList
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.yichao.evilgodxu.data.music.proxy.ProxyParseResult
import com.yichao.evilgodxu.data.music.proxy.ProxySourceStore
import com.yichao.evilgodxu.data.settings.AppLanguage
import com.yichao.evilgodxu.data.settings.readBootLanguage
import com.yichao.evilgodxu.theme.SystemBarAppearance
import com.yichao.evilgodxu.windowsize.ProvideWindowSizeClass
import com.yichao.evilgodxu.floatingwindow.LocalMusicPanelController
import com.yichao.evilgodxu.floatingwindow.MusicPanelController
import com.yichao.evilgodxu.localization.LocalizationManager
import com.yichao.evilgodxu.localization.ProvideLocalizedContext
import com.yichao.evilgodxu.localization.toLocale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private lateinit var windowInsetsController: WindowInsetsController

    // 手动 DI：经 Application 容器取依赖，ViewModel 以工厂注入构造参数
    private val appContainer: AppContainer
        get() = (application as App).container
    private val localizationManager: LocalizationManager
        get() = appContainer.localizationManager
    private val musicPanelController: MusicPanelController
        get() = appContainer.musicPanelController
    private val activityViewModel: MainViewModel by viewModels {
        viewModelFactory {
            initializer {
                MainViewModel(
                    settingsRepository = appContainer.settingsRepository,
                    appVersion = appContainer.appVersion,
                )
            }
        }
    }

    // 冷启动按持久化语言创建配置上下文，进入界面即正确语言
    override fun attachBaseContext(newBase: Context) {
        val config = Configuration(newBase.resources.configuration).apply {
            setLocales(LocaleList(resolveBootLanguage(newBase).toLocale()))
        }
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    // 启动语言优先同步命中轻量镜像（单键读取），避免在 attachBaseContext 主线程阻塞读 DataStore；
    // 镜像缺失（首次安装 / 从旧版本升级）时本次回退为跟随系统——界面层语言流会即时纠正 Compose 侧文案，
    // 同时 Application 预热任务补写镜像，使下次冷启动同步命中
    private fun resolveBootLanguage(context: Context): AppLanguage =
        readBootLanguage(context) ?: AppLanguage.SYSTEM

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            // 系统栏图标外观由 Compose 按主题与页面控制，这里仅跟随系统作为初始兜底值
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        setupSystemBars()
        // 绑定当前 Activity，使对话框等独立窗口在切语言时同步更新资源
        localizationManager.bindActivity(this)

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
            CompositionLocalProvider(LocalAppContainer provides appContainer) {
                CompositionLocalProvider(LocalMainViewModel provides activityViewModel) {
                    ProvideLocalizedContext(localizationManager) {
                        CompositionLocalProvider(LocalMusicPanelController provides musicPanelController) {
                            ProvideWindowSizeClass {
                                AppContent()
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        // 解除语言管理器对 Activity 的绑定，避免单例持有已销毁实例
        localizationManager.unbindActivity(this)
        super.onDestroy()
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

    override fun onStop() {
        // 收起键盘并重置窗口输入状态，避免回前台时键盘偶发自动弹出
        window.insetsController?.hide(WindowInsets.Type.ime())
        super.onStop()
    }

    // 窗口重新获得焦点时系统可能重置系统栏外观与显隐（如对话框关闭后），复读 Compose 应用的状态
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            applySystemBarAppearance()
            updateSystemBarsVisibility()
        }
    }

    private fun applySystemBarAppearance() {
        windowInsetsController.setSystemBarsAppearance(
            if (SystemBarAppearance.isLightStatusBars) {
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
            } else {
                0
            },
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
        )
        windowInsetsController.setSystemBarsAppearance(
            if (SystemBarAppearance.isLightNavigationBars) {
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            } else {
                0
            },
            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleExternalIntent(intent)
    }

    // 处理外部通过打开/分享传入的内容：音频经迷你播放器后台播放；文本/JSON 作为代理音源导入
    private fun handleExternalIntent(intent: Intent) {
        val uri = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND ->
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        } ?: return
        if (isAudioUri(uri)) {
            musicPanelController.playExternalInBackground(uri)
            moveTaskToBack(true)
        } else {
            importProxySource(uri)
        }
    }

    private fun isAudioUri(uri: Uri): Boolean =
        contentResolver.getType(uri)?.startsWith("audio/") == true

    // 读取分享/打开的文本文件并按代理音源解析导入，结果以 Toast 提示；
    // 文件读取与同步写盘均为阻塞操作，移到 IO 线程避免阻塞主线程
    private fun importProxySource(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            val text = runCatching {
                contentResolver.openInputStream(uri)
                    ?.use { it.readBytes().toString(Charsets.UTF_8) }
            }.getOrNull()
            val message = when {
                text.isNullOrBlank() -> getString(R.string.settings_proxy_source_import_read_error)
                else -> when (val result = ProxySourceStore.import(this@MainActivity, text)) {
                    is ProxyParseResult.Success ->
                        getString(R.string.settings_proxy_source_import_success)
                    is ProxyParseResult.Failure -> getString(
                        R.string.settings_proxy_source_import_failed,
                        result.reason,
                    )
                }
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupSystemBars() {
        windowInsetsController = window.insetsController ?: return
        windowInsetsController.systemBarsBehavior =
            WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        updateSystemBarsVisibility()
    }

    // 横屏隐藏全部系统栏；首页竖屏沉浸式仅隐藏状态栏；其余情况显示
    private fun updateSystemBarsVisibility(orientation: Int = resources.configuration.orientation) {
        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            windowInsetsController.hide(WindowInsets.Type.systemBars())
        } else {
            windowInsetsController.show(WindowInsets.Type.systemBars())
            if (SystemBarAppearance.isHomePortraitImmersive) {
                windowInsetsController.hide(WindowInsets.Type.statusBars())
            }
        }
    }
}
