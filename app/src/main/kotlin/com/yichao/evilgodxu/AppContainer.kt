package com.yichao.evilgodxu

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import com.yichao.evilgodxu.data.music.PlaylistRefresher
import com.yichao.evilgodxu.data.music.metadata.MetadataEnricher
import com.yichao.evilgodxu.data.music.panel.MusicPanelStateHolder
import com.yichao.evilgodxu.data.playlist.PlaylistStore
import com.yichao.evilgodxu.data.repository.SettingsRepository
import com.yichao.evilgodxu.localization.LocalizationManager
import com.yichao.evilgodxu.floatingwindow.LocalMusicPanelController
import com.yichao.evilgodxu.floatingwindow.MusicPanelController
import com.yichao.evilgodxu.update.LocalUpdateViewModel
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
    val updateViewModel: UpdateViewModel by lazy {
        UpdateViewModel(application, settingsRepository, localizationManager)
    }
    // 应用版本号：冷启动读取一次
    val appVersion: String = readAppVersion()

    private fun readAppVersion(): String =
        appContext.packageManager
            .getPackageInfo(appContext.packageName, PackageManager.PackageInfoFlags.of(0L))
            .versionName.orEmpty()
}

// 应用级依赖的组合局部：Composable 只消费具体依赖，禁止直接引用 AppContainer。
// 宿主（Activity 与悬浮窗 Compose 宿主）统一经 ProvideAppDependencies 提供
val LocalMusicPanelStateHolder = staticCompositionLocalOf<MusicPanelStateHolder> {
    error("MusicPanelStateHolder is not provided")
}

val LocalPlaylistStore = staticCompositionLocalOf<PlaylistStore> {
    error("PlaylistStore is not provided")
}

val LocalMetadataEnricher = staticCompositionLocalOf<MetadataEnricher> {
    error("MetadataEnricher is not provided")
}

val LocalPlaylistRefresher = staticCompositionLocalOf<PlaylistRefresher> {
    error("PlaylistRefresher is not provided")
}

val LocalApplication = staticCompositionLocalOf<Application> {
    error("Application is not provided")
}

val LocalSettingsRepository = staticCompositionLocalOf<SettingsRepository> {
    error("SettingsRepository is not provided")
}

val LocalLocalizationManager = staticCompositionLocalOf<LocalizationManager> {
    error("LocalizationManager is not provided")
}

// 统一注入应用级依赖：宿主在界面树根部调用一次，避免各处重复罗列组合局部
@Composable
fun ProvideAppDependencies(container: AppContainer, content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalMusicPanelController provides container.musicPanelController,
        LocalUpdateViewModel provides container.updateViewModel,
        LocalMusicPanelStateHolder provides container.stateHolder,
        LocalPlaylistStore provides container.playlistStore,
        LocalMetadataEnricher provides container.metadataEnricher,
        LocalPlaylistRefresher provides container.playlistRefresher,
        LocalApplication provides container.application,
        LocalSettingsRepository provides container.settingsRepository,
        LocalLocalizationManager provides container.localizationManager,
        content = content,
    )
}
