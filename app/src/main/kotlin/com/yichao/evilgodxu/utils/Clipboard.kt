package com.yichao.evilgodxu.utils

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import com.yichao.evilgodxu.log.CrashLogManager

// 写入系统剪贴板，并弹出复制成功提示
internal fun copyToClipboard(context: Context, text: String) {
    try {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("label", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "已复制: $text", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        CrashLogManager.logException("Clipboard", "复制到剪贴板失败", e)
    }
}
