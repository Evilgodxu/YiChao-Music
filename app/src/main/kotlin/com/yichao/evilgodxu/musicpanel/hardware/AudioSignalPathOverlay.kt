package com.yichao.evilgodxu.musicpanel

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import com.yichao.evilgodxu.ui.icons.AppIcons
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yichao.evilgodxu.R

@Composable
fun AudioSignalPathOverlay(
    visible: Boolean,
    playbackState: MusicPlaybackState,
    onDismiss: () -> Unit,
) {
    val title = stringResource(R.string.signal_path_title)
    AnimatedContent(
        targetState = visible,
        transitionSpec = {
            (slideInVertically { it } + fadeIn()).togetherWith(
                slideOutVertically { it } + fadeOut()
            )
        },
        label = title,
    ) { show ->
        if (show) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
                    .clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = title,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 13.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    )
                    HeaderIconButton(
                        icon = AppIcons.Close,
                        onClick = onDismiss,
                        modifier = Modifier.size(28.dp),
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                AudioSignalPathRows(playbackState)
            }
        } else {
            Box(modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun AudioSignalPathRows(playbackState: MusicPlaybackState) {
    val format = playbackState.audioSignalPathFormat
    val dash = stringResource(R.string.signal_path_dash)

    val rows = buildList {
        add(stringResource(R.string.signal_path_format) to (format?.format ?: dash).removePrefix("audio/"))
        add(stringResource(R.string.signal_path_sample_rate) to (format?.sampleRate?.let { formatAudioRate(it) } ?: dash))
        add(stringResource(R.string.signal_path_output_rate) to (format?.outputRate?.let { formatAudioRate(it) } ?: dash))
        add(stringResource(R.string.signal_path_bit_depth) to (format?.bitDepth?.let { stringResource(R.string.signal_path_bit_value, it) } ?: dash))
        add(stringResource(R.string.signal_path_channels) to (format?.channels?.let { formatChannels(it) } ?: dash))
        add(stringResource(R.string.signal_path_output_strategy) to playbackState.audioSignalPathStrategy.toSignalPathValue())
        add(stringResource(R.string.signal_path_output_device) to playbackState.audioSignalPathOutputDevice)
        add(stringResource(R.string.signal_path_route) to playbackState.audioSignalPathRoute.toRouteValue())
        val dsdMode = playbackState.audioSignalPathDsdMode
        if (dsdMode.isNotBlank() && dsdMode != dash && dsdMode != "Inactive" && dsdMode != "Not active") {
            add(stringResource(R.string.signal_path_dsd_mode) to dsdMode)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(rows.size) { index ->
            val (label, value) = rows[index]
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        if (index % 2 == 0) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                        else androidx.compose.ui.graphics.Color.Transparent,
                        RoundedCornerShape(8.dp),
                    )
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            ) {
                Text(
                    text = label,
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = value,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun String.toSignalPathValue(): String = when (this) {
    "Direct" -> stringResource(R.string.signal_path_direct)
    "Mixer" -> stringResource(R.string.signal_path_mixer)
    else -> this
}

@Composable
private fun String.toRouteValue(): String = when (this) {
    "System" -> stringResource(R.string.signal_path_system)
    "Bluetooth" -> stringResource(R.string.signal_path_bluetooth)
    else -> this
}

@Composable
private fun formatAudioRate(rate: Int): String = if (rate >= 1000) {
    stringResource(R.string.signal_path_khz, rate / 1000.0)
} else {
    stringResource(R.string.signal_path_hz, rate)
}

@Composable
private fun formatChannels(channels: Int): String = when (channels) {
    1 -> stringResource(R.string.signal_path_mono)
    2 -> stringResource(R.string.signal_path_stereo)
    else -> stringResource(R.string.signal_path_channels_value, channels)
}
