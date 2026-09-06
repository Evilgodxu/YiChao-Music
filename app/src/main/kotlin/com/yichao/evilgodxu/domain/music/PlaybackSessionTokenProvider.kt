package com.yichao.evilgodxu.domain.music

import android.content.Context
import androidx.media3.session.SessionToken

// 播放会话令牌提供者：构建 MediaController 所需，默认实现由应用装配层注入，
// 避免 domain 层反向依赖 service 包造成循环
fun interface PlaybackSessionTokenProvider {
    fun create(context: Context): SessionToken
}

// 会话令牌提供者装配点：由 YiChaoApplication 初始化时注入默认实现，测试可替换为 fake
object PlaybackSessionTokenProviders {
    @Volatile
    var provider: PlaybackSessionTokenProvider? = null
}
