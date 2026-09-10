package com.yichao.evilgodxu.data.music.model

import android.net.Uri

// 本地封面候选：系统媒体库中的图片，id 为 MediaStore 媒体 id
data class RecentCover(val uri: Uri, val id: Long)
