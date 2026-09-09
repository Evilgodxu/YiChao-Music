package com.yichao.evilgodxu.di

import android.content.Context
import android.content.pm.PackageManager
import com.yichao.evilgodxu.data.repository.SettingsRepository
import com.yichao.evilgodxu.screens.home.HomeViewModel
import com.yichao.evilgodxu.screens.settings.SettingsViewModel
import com.yichao.evilgodxu.screens.typography.TypographyViewModel
import com.yichao.evilgodxu.update.UpdateViewModel
import com.yichao.evilgodxu.utils.localization.LocalizationManager
import org.koin.android.ext.koin.androidApplication
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

// Koin 模块：注册数据层与 ViewModel
val appModule = module {
    single { SettingsRepository(get()) }
    single { LocalizationManager(get(), get()) }
    // 应用版本号：供 Activity/设置页展示
    single { appVersionName(androidContext()) }
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
