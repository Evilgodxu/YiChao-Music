package com.yichao.evilgodxu

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.yichao.evilgodxu.data.repository.SettingsRepository
import com.yichao.evilgodxu.musicpanel.LocalMusicPanelController
import com.yichao.evilgodxu.musicpanel.MusicPanelController
import com.yichao.evilgodxu.navigation.AppNavHost
import com.yichao.evilgodxu.theme.MyApplicationTheme
import com.yichao.evilgodxu.ui.adaptive.ProvideWindowSizeClass
import com.yichao.evilgodxu.utils.localization.LocalizationManager
import com.yichao.evilgodxu.utils.localization.ProvideLocalizedContext
import com.yichao.evilgodxu.utils.localization.toLocale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.koin.android.ext.android.inject

class TemplateActivity : ComponentActivity() {
    private lateinit var windowInsetsController: WindowInsetsControllerCompat
    private val localizationManager: LocalizationManager by inject()
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
        enableEdgeToEdge()
        setupSystemBars()
        // 绑定当前 Activity，使对话框等独立窗口在切语言时同步更新资源
        localizationManager.bindActivity(this)

        // 音乐面板悬浮窗控制器：应用后台播放时显示迷你播放器，回到前台时移除
        musicPanelController = MusicPanelController(applicationContext)
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
                        TemplateContent()
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 销毁（含旋转重建）时释放悬浮窗，避免重建后残留无宿主窗口
        musicPanelController.release()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateSystemBarsVisibility(newConfig.orientation)
    }

    @Composable
    private fun TemplateContent() {
        MyApplicationTheme {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
            ) {
                AppNavHost(onExit = { finish() })
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
