package com.yichao.evilgodxu.musicpanel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yichao.evilgodxu.R

// 播放调速对话框：布局与定时关闭对话框一致
@Composable
internal fun SpeedDialog(
    visible: Boolean,
    speed: Float,
    onSpeedChange: (Float) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    if (visible) {
        MetadataDialogCard(onDismiss = onCancel) {
            SpeedPanelContent(
                speed = speed,
                onSpeedChange = onSpeedChange,
                onConfirm = onConfirm,
                onCancel = onCancel,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

// 调速面板主体：标题 + 加减 0.1 + 中间数值(点击重置 1.0) + 确认/取消
@Composable
private fun SpeedPanelContent(
    speed: Float,
    onSpeedChange: (Float) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.music_panel_speed_title),
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            TimerAdjustButton(text = "−", onClick = { onSpeedChange(speed - 0.1f) })
            // 中间数值：点击重置回默认速度，透明背景仅保留点击区
            Surface(
                color = Color.Transparent,
                onClick = { onSpeedChange(MusicPlaybackState.PLAYBACK_SPEED_DEFAULT) },
            ) {
                Text(
                    text = stringResource(R.string.music_panel_speed_value, speed),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
                )
            }
            TimerAdjustButton(text = "+", onClick = { onSpeedChange(speed + 0.1f) })
        }
        Spacer(modifier = Modifier.height(16.dp))
        Row(
            modifier = Modifier.widthIn(max = 200.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                onClick = onCancel,
            ) {
                Text(
                    text = stringResource(R.string.music_panel_speed_cancel),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(vertical = 10.dp),
                )
            }
            Surface(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.primary,
                onClick = onConfirm,
            ) {
                Text(
                    text = stringResource(R.string.music_panel_speed_confirm),
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(vertical = 10.dp),
                )
            }
        }
    }
}