package com.yichao.evilgodxu.musicpanel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yichao.evilgodxu.R

// 播放调速对话框：调节实时生效，点击外部或返回键关闭
@Composable
internal fun SpeedDialog(
    visible: Boolean,
    speed: Float,
    onSpeedChange: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    if (visible) {
        MetadataDialogCard(onDismiss = onDismiss) {
            SpeedPanelContent(
                speed = speed,
                onSpeedChange = onSpeedChange,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

// 调速面板主体：标题 + 加减 0.1 + 中间数值(点击重置 1.0)
@Composable
private fun SpeedPanelContent(
    speed: Float,
    onSpeedChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 记录标题高度，底部留白与之等高，保证上下视觉对称
    var titleHeightPx by remember { mutableIntStateOf(0) }
    val titleBottomSpacer = with(LocalDensity.current) { titleHeightPx.toDp() }
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.music_panel_speed_title),
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.onSizeChanged { titleHeightPx = it.height },
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
        Spacer(modifier = Modifier.height(titleBottomSpacer))
    }
}