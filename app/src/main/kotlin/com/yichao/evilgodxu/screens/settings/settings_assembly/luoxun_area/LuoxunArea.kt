package com.yichao.evilgodxu.screens.settings.settings_assembly.luoxun_area

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.screens.settings.settings_assembly.component.clickableItem.SettingsClickableItem
import com.yichao.evilgodxu.screens.settings.settings_assembly.component.section.SettingsSection
import com.yichao.evilgodxu.ui.icons.AppIcons

// 洛雪音源分区：导入第三方音源
@Composable
fun LuoxunArea(onImportClick: () -> Unit) {
    SettingsSection(title = stringResource(R.string.settings_section_luoxun)) {
        SettingsClickableItem(
            icon = AppIcons.Folder,
            title = stringResource(R.string.settings_luoxun_import_title),
            subtitle = stringResource(R.string.settings_luoxun_import_desc),
            onClick = onImportClick,
        )
    }
}
