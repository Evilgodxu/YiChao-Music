package com.yichao.evilgodxu.domain.music

import android.content.ClipboardManager
import android.content.ClipData
import android.content.Context
import android.widget.Toast
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.log.CrashLogManager

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
        Toast.makeText(context, context.getString(R.string.copy_success, text), Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        CrashLogManager.logException("MusicPanelUtils", "复制到剪贴板失败", e)
    }
}

// 无损格式集合：命中的格式已无需再升级
private val LOSSLESS_FORMATS = setOf(
    "FLAC", "WAV", "WAVE", "ALAC", "APE", "AIFF", "AIF", "PCM", "DSD", "DSF", "DFF",
)

// 按格式名判定是否已达到无损
internal fun isLosslessFormatName(name: String): Boolean {
    val normalized = name.uppercase().trim()
    return normalized in LOSSLESS_FORMATS || normalized.endsWith("LOSSLESS")
}
