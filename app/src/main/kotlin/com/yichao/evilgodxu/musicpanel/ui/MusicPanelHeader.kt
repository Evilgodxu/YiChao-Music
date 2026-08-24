package com.yichao.evilgodxu.musicpanel

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import com.yichao.evilgodxu.ui.icons.AppIcons
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yichao.evilgodxu.R

@Composable
internal fun HeaderRow(
    playbackState: MusicPlaybackState,
    timerRemaining: Int,
    onTimerClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentTrackId = playbackState.currentTrack?.id
    val isLiked = currentTrackId?.let { id -> playbackState.likedIds.contains(id) } ?: false

    val hasUsbDevice = playbackState.isUsbDeviceConnected && playbackState.usbDeviceName.isNotBlank()
    val hasBluetoothDevice = playbackState.isBluetoothHeadsetConnected && playbackState.bluetoothHeadsetName.isNotBlank()

    Box(
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.align(Alignment.CenterStart)
        ) {
            HeaderIconButton(
                icon = AppIcons.Timer,
                contentDescription = stringResource(R.string.music_panel_timer_title),
                onClick = onTimerClick,
                modifier = Modifier.offset(y = 4.dp)
            )
            if (timerRemaining > 0) {
                Text(
                    text = "${timerRemaining}m",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 10.sp,
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .offset(y = 4.dp)
                        .clickable { playbackState.stopTimer() }
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
        }

        if (hasUsbDevice || hasBluetoothDevice) {
            Row(
                modifier = Modifier
                    .align(Alignment.Center)
                    .alpha(0.72f)
                    .widthIn(max = 264.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (hasBluetoothDevice) {
                    Icon(
                        imageVector = AppIcons.Bluetooth,
                        contentDescription = stringResource(R.string.music_panel_bluetooth_device),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp),
                    )
                    Text(
                        text = playbackState.bluetoothHeadsetName,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 108.dp),
                    )
                }
                if (hasUsbDevice) {
                    Icon(
                        imageVector = AppIcons.Usb,
                        contentDescription = stringResource(R.string.music_panel_usb_device),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp),
                    )
                    Text(
                        text = playbackState.usbDeviceName,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 108.dp),
                    )
                }
            }
        }

        HeaderIconButton(
            icon = if (isLiked) AppIcons.Favorite else AppIcons.FavoriteBorder,
            contentDescription = stringResource(R.string.music_panel_favorite),
            onClick = {
                currentTrackId?.let { playbackState.toggleFavorite(it) }
            },
            tint = if (isLiked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .offset(y = 4.dp)
        )
    }
}

@Composable
internal fun HeaderIconButton(
    icon: ImageVector,
    contentDescription: String? = null,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.size(32.dp)
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(21.dp),
            tint = if (enabled) tint else tint.copy(alpha = 0.3f)
        )
    }
}
