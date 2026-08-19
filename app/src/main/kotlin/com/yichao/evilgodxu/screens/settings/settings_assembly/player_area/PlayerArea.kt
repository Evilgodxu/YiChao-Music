package com.yichao.evilgodxu.screens.settings.settings_assembly.player_area

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.screens.settings.settings_assembly.component.section.SettingsSection
import com.yichao.evilgodxu.theme.AppSwitch

// 播放分区：悬浮播放开关
@Composable
fun PlayerArea(
    miniPlayerEnabled: Boolean,
    onMiniPlayerEnabledChange: (Boolean) -> Unit,
) {
    SettingsSection(title = stringResource(R.string.settings_section_player)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_mini_player_title),
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = stringResource(R.string.settings_mini_player_desc),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
            Spacer(Modifier.width(12.dp))
            AppSwitch(
                checked = miniPlayerEnabled,
                onCheckedChange = onMiniPlayerEnabledChange,
            )
        }
    }
}
