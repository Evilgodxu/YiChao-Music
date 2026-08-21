package com.yichao.evilgodxu.musicpanel

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yichao.evilgodxu.log.CrashLogManager
import kotlinx.coroutines.delay

internal fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}

internal fun copyToClipboard(context: Context, text: String) {
    try {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("label", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "已复制: $text", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        CrashLogManager.logException("MusicPanelUtils", "复制到剪贴板失败", e)
    }
}

/** 音乐面板通用错误横幅：通栏红底样式，显示约 2 秒后自动消失 */
@Composable
internal fun MusicErrorBanner(
    message: String,
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit,
) {
    LaunchedEffect(message) {
        delay(2000)
        onDismiss()
    }
    Text(
        text = message,
        color = MaterialTheme.colorScheme.error,
        fontSize = 11.sp,
        modifier = modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.error.copy(alpha = 0.08f),
                RoundedCornerShape(6.dp)
            )
            .padding(horizontal = 10.dp, vertical = 6.dp)
    )
}