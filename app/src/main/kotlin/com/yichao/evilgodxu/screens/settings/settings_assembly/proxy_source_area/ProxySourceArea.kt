package com.yichao.evilgodxu.screens.settings.settings_assembly.proxy_source_area

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.musicpanel.proxy.ProxySourceSpec
import com.yichao.evilgodxu.screens.settings.dialog.ProxySourceImportDialog
import com.yichao.evilgodxu.screens.settings.dialog.ProxySourceInputDialog
import com.yichao.evilgodxu.screens.settings.settings_assembly.component.clickableItem.SettingsClickableItem
import com.yichao.evilgodxu.screens.settings.settings_assembly.component.section.SettingsSection
import com.yichao.evilgodxu.ui.icons.AppIcons

// 代理音源分区：导入、管理第三方音源并展示导入结果
@Composable
fun ProxySourceArea(
    sources: List<ProxySourceSpec>,
    importMessage: String?,
    importFailed: Boolean,
    onImport: (String) -> Unit,
    onRemove: (String) -> Unit,
    onMessageDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var showImportDialog by remember { mutableStateOf(false) }
    var inputMode by remember { mutableStateOf<ImportMode?>(null) }

    // 本地导入：读取所选文本文件的完整内容后交由导入流程解析
    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching {
            context.contentResolver.openInputStream(uri)
                ?.use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull()
        if (!text.isNullOrBlank()) onImport(text)
    }

    SettingsSection(title = stringResource(R.string.settings_section_proxy_source)) {
        SettingsClickableItem(
            icon = AppIcons.Folder,
            title = stringResource(R.string.settings_proxy_source_import_title),
            subtitle = stringResource(R.string.settings_proxy_source_import_desc),
            onClick = { showImportDialog = true },
        )
        ImportedSourceList(sources, onRemove)
        ImportMessage(
            message = importMessage,
            failed = importFailed,
            onClick = onMessageDismiss,
        )
    }

    if (showImportDialog) {
        ProxySourceImportDialog(
            onDismiss = { showImportDialog = false },
            onLocalImport = {
                showImportDialog = false
                filePickerLauncher.launch(arrayOf("text/*", "application/json", "application/javascript"))
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

// 已导入音源列表：名称、版本与覆盖平台，支持逐个移除
@Composable
private fun ImportedSourceList(sources: List<ProxySourceSpec>, onRemove: (String) -> Unit) {
    if (sources.isEmpty()) {
        Text(
            text = stringResource(R.string.settings_proxy_source_empty),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
        return
    }
    sources.forEach { source ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = listOf(source.name, source.version.takeIf { it.isNotBlank() }?.let { "v$it" })
                        .filterNotNull()
                        .joinToString(" · "),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(
                        R.string.settings_proxy_source_platforms,
                        source.platforms.keys.joinToString(" / ")
                    ),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
            IconButton(onClick = { onRemove(source.name) }) {
                Icon(
                    imageVector = AppIcons.Delete,
                    contentDescription = stringResource(R.string.settings_proxy_source_remove_desc),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        }
    }
}

// 最近一次导入结果提示：成功/失败着色不同，点击后关闭
@Composable
private fun ImportMessage(
    message: String?,
    failed: Boolean,
    onClick: () -> Unit,
) {
    if (message == null) return
    Text(
        text = message,
        fontSize = 13.sp,
        color = if (failed) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

private enum class ImportMode { LINK, TEXT }