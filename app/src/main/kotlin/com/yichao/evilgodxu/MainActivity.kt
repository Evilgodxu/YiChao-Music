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
import com.yichao.evilgodxu.floatingwindow.MusicPanelController
import com.yichao.evilgodxu.localization.LocalizationManager
import com.yichao.evilgodxu.localization.ProvideLocalizedContext
import com.yichao.evilgodxu.localization.toLocale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    // 系统栏显隐目标：横屏全部隐藏 / 首页竖屏仅隐藏状态栏 / 其余全部显示
    private enum class SystemBarTarget { HIDE_ALL, HIDE_STATUS, VISIBLE }

    // 同一帧内的多次系统栏请求合并为一次下发
    private var systemBarsScheduled = false

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
            ProvideAppDependencies(appContainer) {
                CompositionLocalProvider(LocalMainViewModel provides activityViewModel) {
                    ProvideLocalizedContext(localizationManager) {
                        ProvideWindowSizeClass {
                            AppContent()
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        // 解除语言管理器与系统栏回调对 Activity 的持有，避免单例持有已销毁实例
        SystemBarAppearance.onChanged = null
        localizationManager.unbindActivity(this)
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 系统 uiMode 变化时系统可能重置系统栏图标与显隐，按当前朝向复读界面声明的状态
        scheduleSystemBars()
    }

    override fun onResume() {
        super.onResume()
        scheduleSystemBars()
    }

    override fun onStop() {
        // 收起键盘并重置窗口输入状态，避免回前台时键盘偶发自动弹出
        window.insetsController?.hide(WindowInsets.Type.ime())
        super.onStop()
    }

    // 窗口重新获得焦点时系统可能重置系统栏（如对话框、弹窗、下拉通知栏关闭后），复读界面声明的状态
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) scheduleSystemBars()
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
        // 界面侧只声明请求，窗口操作统一收敛到 applySystemBars
        SystemBarAppearance.onChanged = { scheduleSystemBars() }
        installSystemBarsEnforcement()
        scheduleSystemBars()
    }

    // 兜底防重置：期望隐藏时被外部途径置为可见（系统手势临时唤出、ROM 重置、窗口切换残留），
    // 在下一次 insets 分发即压回隐藏。键盘弹起期间不干预，避免与输入法布局竞争
    private fun installSystemBarsEnforcement() {
        window.decorView.setOnApplyWindowInsetsListener { view, insets ->
            val barsVisible = insets.isVisible(WindowInsets.Type.statusBars()) ||
                    insets.isVisible(WindowInsets.Type.navigationBars())
            if (barsVisible &&
                !insets.isVisible(WindowInsets.Type.ime()) &&
                view.hasWindowFocus() &&
                systemBarTarget() != SystemBarTarget.VISIBLE
            ) {
                scheduleSystemBars()
            }
            view.onApplyWindowInsets(insets)
        }
    }

    // 合并同一帧内的多次请求，并延后到当前窗口过渡/布局结束后执行：对话框、弹出窗口等独立窗口
    // 切换期间系统会强制显示系统栏，焦点回归时立即 hide 可能被窗口切换过程覆盖，延后执行可稳定落回预期显隐
    private fun scheduleSystemBars() {
        if (systemBarsScheduled) return
        systemBarsScheduled = true
        window.decorView.post {
            systemBarsScheduled = false
            applySystemBars()
        }
    }

    // 朝向判据与界面层 rememberWindowLandscape 同源（窗口实测宽高）：配置读取可能滞后于实际窗口，
    // 若此处仍按竖屏下发，会把系统栏 show 出来并常驻遮挡顶部按钮
    private fun isWindowLandscape(): Boolean {
        val decor = window.decorView
        if (decor.width > 0 && decor.height > 0) return decor.width > decor.height
        val bounds = windowManager.currentWindowMetrics.bounds
        return bounds.width() > bounds.height()
    }

    private fun systemBarTarget(): SystemBarTarget = when {
        isWindowLandscape() -> SystemBarTarget.HIDE_ALL
        SystemBarAppearance.isHomePortraitImmersive -> SystemBarTarget.HIDE_STATUS
        else -> SystemBarTarget.VISIBLE
    }

    // 系统栏唯一下发点：图标外观与显隐一并处理，并按当前实际状态判定是否需要下发，避免无谓调用
    private fun applySystemBars() {
        val controller = window.insetsController ?: return
        controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.setSystemBarsAppearance(
            if (SystemBarAppearance.isLightStatusBars) {
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
            } else {
                0
            },
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
        )
        controller.setSystemBarsAppearance(
            if (SystemBarAppearance.isLightNavigationBars) {
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            } else {
                0
            },
            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
        )

        val insets = window.decorView.rootWindowInsets
        val statusVisible = insets?.isVisible(WindowInsets.Type.statusBars()) ?: true
        val navigationVisible = insets?.isVisible(WindowInsets.Type.navigationBars()) ?: true
        when (systemBarTarget()) {
            SystemBarTarget.HIDE_ALL ->
                if (statusVisible || navigationVisible) {
                    controller.hide(WindowInsets.Type.systemBars())
                }
            // 仅隐藏状态栏：只把导航栏摆正，不做「先 show 全部再 hide 状态栏」，避免中间可见帧
            SystemBarTarget.HIDE_STATUS -> {
                if (!navigationVisible) controller.show(WindowInsets.Type.navigationBars())
                if (statusVisible) controller.hide(WindowInsets.Type.statusBars())
            }
            SystemBarTarget.VISIBLE ->
                if (!statusVisible || !navigationVisible) {
                    controller.show(WindowInsets.Type.systemBars())
                }
        }
    }
}
