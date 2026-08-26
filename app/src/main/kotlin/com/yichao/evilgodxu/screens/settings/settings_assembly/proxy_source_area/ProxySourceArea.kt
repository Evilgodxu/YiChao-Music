package com.yichao.evilgodxu.screens.settings.settings_assembly.proxy_source_area

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.screens.settings.dialog.ProxySourceImportDialog
import com.yichao.evilgodxu.screens.settings.dialog.ProxySourceInputDialog
import com.yichao.evilgodxu.screens.settings.settings_assembly.component.clickableItem.SettingsClickableItem
import com.yichao.evilgodxu.screens.settings.settings_assembly.component.section.SettingsSection
import com.yichao.evilgodxu.ui.icons.AppIcons

// 代理音源分区：导入第三方音源
@Composable
fun ProxySourceArea(onImport: (String) -> Unit) {
    var showImportDialog by remember { mutableStateOf(false) }
    var inputMode by remember { mutableStateOf<ImportMode?>(null) }

    // 本地导入文件选择器，选择结果暂不处理
    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { }

    SettingsSection(title = stringResource(R.string.settings_section_proxy_source)) {
        SettingsClickableItem(
            icon = AppIcons.Folder,
            title = stringResource(R.string.settings_proxy_source_import_title),
            subtitle = stringResource(R.string.settings_proxy_source_import_desc),
            onClick = { showImportDialog = true },
        )
    }

    if (showImportDialog) {
        ProxySourceImportDialog(
            onDismiss = { showImportDialog = false },
            onLocalImport = {
                showImportDialog = false
                filePickerLauncher.launch(arrayOf("text/*", "application/javascript"))
            },
            onLinkImport = {
                showImportDialog = false
                inputMode = ImportMode.LINK
            },
            onTextImport = {
                showImportDialog = false
                inputMode = ImportMode.TEXT
            },
        )
    }

    inputMode?.let { mode ->
        ProxySourceInputDialog(
            title = stringResource(
                if (mode == ImportMode.LINK) R.string.settings_proxy_source_link_title
                else R.string.settings_proxy_source_text_title
            ),
            placeholder = stringResource(
                if (mode == ImportMode.LINK) R.string.settings_proxy_source_link_hint
                else R.string.settings_proxy_source_text_hint
            ),
            singleLine = mode == ImportMode.LINK,
            onDismiss = { inputMode = null },
            onConfirm = { content ->
                inputMode = null
                onImport(content)
            },
        )
    }
}

private enum class ImportMode { LINK, TEXT }
