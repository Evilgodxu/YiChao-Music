package com.yichao.evilgodxu.screens.home.home_assembly.player_area

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.musicpanel.MusicPlaybackState
import com.yichao.evilgodxu.musicpanel.upgradeCurrentTrackToLossless
import kotlinx.coroutines.launch

// 无损升级确认对话框：确认后后台下载无损并替换当前曲目；升级中不可关闭，失败时保留供重试
@Composable
internal fun LosslessUpgradeDialog(
    visible: Boolean,
    playbackState: MusicPlaybackState,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    if (!visible) return
    // 对话框仅在格式低于无损时弹出，此处格式必然就绪，直接取当前曲目格式名
    val formatName = playbackState.audioSignalPathFormat
        .takeIf { playbackState.audioSignalPathTrackId == playbackState.currentTrack?.id }
        ?.format?.removePrefix("audio/")
    AlertDialog(
        onDismissRequest = {
            if (!playbackState.losslessUpgradeBusy) {
                playbackState.losslessUpgradeError = null
                onDismiss()
            }
        },
        title = {
            Text(
                text = stringResource(R.string.home_upgrade_lossless_title),
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.home_upgrade_lossless_message, formatName.orEmpty()),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                if (playbackState.losslessUpgradeBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                playbackState.losslessUpgradeError?.let { error ->
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !playbackState.losslessUpgradeBusy,
                onClick = {
                    scope.launch {
                        playbackState.losslessUpgradeBusy = true
                        playbackState.losslessUpgradeError = null
                        val success = upgradeCurrentTrackToLossless(context, playbackState)
                        playbackState.losslessUpgradeBusy = false
                        if (success) {
                            playbackState.losslessUpgradeError = null
                            onDismiss()
                        } else {
                            playbackState.losslessUpgradeError =
                                context.getString(R.string.home_upgrade_lossless_failed)
                        }
                    }
                },
            ) {
                Text(stringResource(R.string.home_upgrade_lossless_confirm))
            }
        },
        dismissButton = {
            TextButton(
                enabled = !playbackState.losslessUpgradeBusy,
                onClick = {
                    playbackState.losslessUpgradeError = null
                    onDismiss()
                },
            ) {
                Text(stringResource(R.string.home_upgrade_cancel))
            }
        },
    )
}
