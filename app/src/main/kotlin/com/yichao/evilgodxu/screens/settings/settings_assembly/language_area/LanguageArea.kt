package com.yichao.evilgodxu.screens.settings.settings_assembly.language_area

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.data.settings.AppLanguage
import com.yichao.evilgodxu.ui.icons.AppIcons
import com.yichao.evilgodxu.screens.settings.settings_assembly.component.clickableItem.SettingsClickableItem
import com.yichao.evilgodxu.screens.settings.settings_assembly.component.section.SettingsSection

// 语言分区
@Composable
fun LanguageArea(language: AppLanguage, onLanguageSelected: (AppLanguage) -> Unit, onShowDialog: () -> Unit) {
    SettingsSection(title = stringResource(R.string.settings_section_language)) {
        SettingsClickableItem(
            icon = AppIcons.Language,
            title = stringResource(R.string.settings_language_title),
            subtitle = when (language) {
                AppLanguage.SYSTEM -> stringResource(R.string.language_system)
                AppLanguage.CHINESE -> stringResource(R.string.language_chinese)
                AppLanguage.ENGLISH -> stringResource(R.string.language_english)
            },
            onClick = onShowDialog,
        )
    }
}
