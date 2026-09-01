package com.yichao.evilgodxu.screens.settings.dialog

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yichao.evilgodxu.R

// 代理音源导入方式选择对话框：本地/链接/文本
@Composable
fun ProxySourceImportDialog(
    onDismiss: () -> Unit,
    onLocalImport: () -> Unit,
    onLinkImport: () -> Unit,
    onTextImport: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(R.string.settings_proxy_source_dialog_title),
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ImportOption(R.string.settings_proxy_source_import_local, onLocalImport)
                ImportOption(R.string.settings_proxy_source_import_link, onLinkImport)
                ImportOption(R.string.settings_proxy_source_import_text, onTextImport)
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun ImportOption(textRes: Int, onClick: () -> Unit) {
    Text(
        text = stringResource(textRes),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurface,
    )
}
