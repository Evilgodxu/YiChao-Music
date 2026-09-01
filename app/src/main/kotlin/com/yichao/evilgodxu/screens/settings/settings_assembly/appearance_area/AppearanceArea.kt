package com.yichao.evilgodxu.screens.settings.settings_assembly.appearance_area

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import com.yichao.evilgodxu.data.settings.ThemeMode
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.screens.settings.settings_assembly.component.clickableItem.SettingsClickableItem
import com.yichao.evilgodxu.screens.settings.settings_assembly.component.section.SettingsSection
import com.yichao.evilgodxu.ui.icons.AppIcons

// 外观分区
@Composable
fun AppearanceArea(themeMode: ThemeMode, onThemeClick: (Offset) -> Unit) {
    SettingsSection(title = stringResource(R.string.settings_section_appearance)) {
        SettingsClickableItem(
            icon = AppIcons.Palette,
            title = stringResource(R.string.settings_theme_title),
            subtitle = when (themeMode) {
                ThemeMode.SYSTEM -> stringResource(R.string.theme_system)
                ThemeMode.DARK -> stringResource(R.string.theme_dark)
                ThemeMode.LIGHT -> stringResource(R.string.theme_light)
            },
            onClick = {},
            onClickWithPosition = { position ->
                onThemeClick(position)
            },
        )
    }
}
