package com.yichao.evilgodxu

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.runtime.staticCompositionLocalOf
import com.yichao.evilgodxu.data.music.PlaylistRefresher
import com.yichao.evilgodxu.data.music.metadata.MetadataEnricher
import com.yichao.evilgodxu.data.music.panel.MusicPanelStateHolder
import com.yichao.evilgodxu.data.playlist.PlaylistStore
import com.yichao.evilgodxu.data.repository.SettingsRepository
import com.yichao.evilgodxu.localization.LocalizationManager
import com.yichao.evilgodxu.ui.music.window.MusicPanelController
import com.yichao.evilgodxu.update.UpdateViewModel

// 手动 DI 容器：Application 启动时构造并持有全部应用级单例
class AppContainer(context: Context) {

    private val appContext = context.applicationContext
    // 供 AndroidViewModel 子类工厂构造注入使用
    val application: Application = appContext as Application

    val settingsRepository: SettingsRepository by lazy { SettingsRepository(appContext) }
    val localizationManager: LocalizationManager by lazy { LocalizationManager(appContext) }
    val playlistStore: PlaylistStore by lazy { PlaylistStore() }
    val metadataEnricher: MetadataEnricher by lazy { MetadataEnricher() }
    val playlistRefresher: PlaylistRefresher by lazy { PlaylistRefresher(playlistStore) }
    val stateHolder: MusicPanelStateHolder by lazy {
        MusicPanelStateHolder(metadataEnricher, playlistStore)
    }
    // 音乐面板/迷你播放器控制器单例：应用级悬浮窗生命周期，全库共享同一实例
    val musicPanelController: MusicPanelController by lazy {
        MusicPanelController(appContext, stateHolder, playlistRefresher, metadataEnricher)
    }
    // 更新检查以单例共享，主页自动检查与设置页手动检查读写同一状态
    val updateViewModel: UpdateViewModel by lazy { UpdateViewModel(application) }
    // 应用版本号：冷启动读取一次
    val appVersion: String = readAppVersion()

    private fun readAppVersion(): String =
        appContext.packageManager
            .getPackageInfo(appContext.packageName, PackageManager.PackageInfoFlags.of(0L))
            .versionName.orEmpty()
}

// 供界面树消费的组合局部，由宿主 Activity 提供
val LocalAppContainer = staticCompositionLocalOf<AppContainer> {
    error("AppContainer is not provided")
}
