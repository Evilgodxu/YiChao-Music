package com.yichao.evilgodxu.di

import android.content.Context
import android.content.pm.PackageManager
import com.yichao.evilgodxu.MainViewModel
import com.yichao.evilgodxu.data.music.PlaylistRefresher
import com.yichao.evilgodxu.data.music.metadata.MetadataEnricher
import com.yichao.evilgodxu.data.playlist.PlaylistStore
import com.yichao.evilgodxu.data.repository.SettingsRepository
import com.yichao.evilgodxu.domain.music.panel.MusicPanelStateHolder
import com.yichao.evilgodxu.screens.home.HomeViewModel
import com.yichao.evilgodxu.screens.settings.SettingsViewModel
import com.yichao.evilgodxu.screens.typography.TypographyViewModel
import com.yichao.evilgodxu.ui.music.window.MusicPanelController
import com.yichao.evilgodxu.update.UpdateViewModel
import com.yichao.evilgodxu.localization.LocalizationManager
import org.koin.android.ext.koin.androidApplication
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

// Koin 模块：注册数据层与 ViewModel
val appModule = module {
    single { SettingsRepository(get()) }
    single { LocalizationManager(get()) }
    // 自定义歌单存储单例：持有内存态歌单列表，供刷新器、播放状态与歌单 UI 共享
    single { PlaylistStore() }
    // 音乐面板播放状态单例：供播放服务、悬浮窗、页面与控制器共享同一实例
    single { MusicPanelStateHolder(get(), get()) }
    // 音乐面板/迷你播放器控制器单例：应用级悬浮窗生命周期，全库注入共享，避免手动 new
    single { MusicPanelController(androidContext()) }
    // 播放列表刷新器与封面/歌词补全器单例：均内置互斥/并发控制状态，单例保证串行语义
    single { PlaylistRefresher(get()) }
    single { MetadataEnricher() }
    // 应用版本号：供 Activity/设置页展示
    single { appVersionName(androidContext()) }
    viewModelOf(::MainViewModel)
    // 更新检查以单例共享，主页自动检查与设置页手动检查读写同一状态
    single { UpdateViewModel(androidApplication()) }
    viewModelOf(::HomeViewModel)
    viewModelOf(::SettingsViewModel)
    viewModelOf(::TypographyViewModel)
}

// 应用版本号
private fun appVersionName(context: Context): String =
    context.packageManager
        .getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0L))
        .versionName.orEmpty()
