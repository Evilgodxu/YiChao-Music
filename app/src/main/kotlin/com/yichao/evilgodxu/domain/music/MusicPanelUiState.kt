package com.yichao.evilgodxu.domain.music

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.yichao.evilgodxu.data.music.SearchHistoryStore
import com.yichao.evilgodxu.data.music.model.MusicSearchSource
import com.yichao.evilgodxu.data.music.model.NeteaseSongSearchResult
import com.yichao.evilgodxu.data.music.model.RecentCover
import kotlin.jvm.JvmName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

// 音乐面板/首页在线搜索等页面级瞬态 UI 状态：
// 与播放核心分离，面板关闭后状态仍保留，供在线搜索/音质/封面/歌词/无损升级弹层复用
class MusicPanelUiState {
    // 搜索历史持久化用上下文，由播放状态恢复流程注入
    var appContext: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // 在线搜索相关状态
    var isSearchMode by mutableStateOf(false)
    var searchQuery by mutableStateOf("")
    // 当前选中的在线搜索平台，单平台搜索时使用
    var searchSource by mutableStateOf(MusicSearchSource.NETEASE)
    var searchResults by mutableStateOf<List<NeteaseSongSearchResult>>(emptyList())
    var searchHistory by mutableStateOf<List<String>>(emptyList())
    var isSearching by mutableStateOf(false)
    // 当前搜索协程句柄：新搜索发起时取消上一次，避免过期响应覆盖新查询结果
    var searchJob: Job? = null
    // 搜索结果分页：已加载页数、是否正在加载更多、是否还有更多
    var searchPage by mutableIntStateOf(0)
    var isLoadingMore by mutableStateOf(false)
    var hasMoreSearchResults by mutableStateOf(true)
    // 代理音源一次拉取的全量结果缓冲：本地按页切分展示，避免不支持分页的代理重复请求
    var searchPending by mutableStateOf<List<NeteaseSongSearchResult>>(emptyList())
    // 代理音源首次请求是否拉满（可能支持分页，缓冲耗尽后继续请求下一页）
    var searchPendingFull by mutableStateOf(false)
    // 加载更多分页的协程句柄：新搜索发起时取消，避免过期分页混入新结果
    var searchLoadJob: Job? = null
    var showSearchResults by mutableStateOf(false)
    var pendingSearchResults by mutableStateOf<List<NeteaseSongSearchResult>>(emptyList())
    var closeSearchResultsOnReady by mutableStateOf(false)
    // 首页音质选择对话框：非空时显示，目标为待播在线歌曲
    var qualityPickTrack by mutableStateOf<NeteaseSongSearchResult?>(null)
    // 音质尝试中：解析地址与等待播放结果期间置 true，阻止重复点击/误关对话框
    var qualityBusy by mutableStateOf(false)
    // 最近一次音质尝试失败提示（失败时保留对话框展示，供用户换其它音质）
    var qualityError by mutableStateOf<String?>(null)
    // 音质试播曲目 ID：播放就绪(READY)后清空；播放失败时据此移除试播曲目并保留对话框
    var pendingQualityPlayTrackId by mutableStateOf<Long?>(null)
    // 无损升级进行中：阻止重复触发与误关对话框
    var losslessUpgradeBusy by mutableStateOf(false)
    // 最近一次无损升级失败提示：升级失败时保留对话框展示，供用户重试
    var losslessUpgradeError by mutableStateOf<String?>(null)
    // 无损升级候选：按来源搜索的在线原曲，用户确认选中后下载无损替换本地文件
    var losslessUpgradeCandidates by mutableStateOf<List<NeteaseSongSearchResult>>(emptyList())
    var isLosslessUpgradeSearching by mutableStateOf(false)
    // 无损升级候选搜索来源
    var losslessUpgradeSource by mutableStateOf(MusicSearchSource.NETEASE)
    var coverCandidates by mutableStateOf<List<NeteaseSongSearchResult>>(emptyList())
    var isCoverSearching by mutableStateOf(false)
    var localCoverCandidates by mutableStateOf<List<RecentCover>>(emptyList())
    // 封面写入版本号：每次成功写入新封面自增，驱动封面组件重新加载最新图
    var coverRevision by mutableIntStateOf(0)
    var lyricsCandidates by mutableStateOf<List<NeteaseSongSearchResult>>(emptyList())
    var isLyricsSearching by mutableStateOf(false)
    var isLyricsRefreshing by mutableStateOf(false)
    var lyricsRefreshError by mutableStateOf<String?>(null)
    // 歌词/封面刷新当前来源：按来源独立搜索，切换来源时轮换并重新搜索
    var lyricsRefreshSource by mutableStateOf(MusicSearchSource.NETEASE)
    var coverRefreshSource by mutableStateOf(MusicSearchSource.NETEASE)

    fun addSearchHistory(query: String) {
        val normalized = query.trim()
        if (normalized.isBlank()) return
        searchHistory = listOf(normalized) + searchHistory.filterNot { it == normalized }
        searchHistory = searchHistory.take(10)
        persistSearchHistory()
    }

    fun removeSearchHistory(query: String) {
        searchHistory = searchHistory.filterNot { it == query }
        persistSearchHistory()
    }

    fun clearSearchHistory() {
        searchHistory = emptyList()
        persistSearchHistory()
    }

    private fun persistSearchHistory() {
        val context = appContext ?: return
        scope.launch(Dispatchers.IO) {
            SearchHistoryStore.save(context, searchHistory)
        }
    }

    // ===== UI 层状态写入口：悬浮窗/首页 UI 统一通过这些方法写入状态，避免直接对 public var 赋值 =====
    // 方法与属性 setter 同名会冲突，故用 @JvmName 指定不同 JVM 名
    @JvmName("updateSearchMode")
    fun setSearchMode(enabled: Boolean) { isSearchMode = enabled }
    @JvmName("updateSearchResultsVisible")
    fun setSearchResultsVisible(visible: Boolean) { showSearchResults = visible }
    @JvmName("updateSearchQuery")
    fun setSearchQuery(query: String) { searchQuery = query }
    @JvmName("updateSearchSource")
    fun setSearchSource(source: MusicSearchSource) { searchSource = source }
    @JvmName("updateLocalCoverCandidates")
    fun setLocalCoverCandidates(candidates: List<RecentCover>) { localCoverCandidates = candidates }
    @JvmName("updateCoverCandidates")
    fun setCoverCandidates(candidates: List<NeteaseSongSearchResult>) { coverCandidates = candidates }
    @JvmName("updateLyricsCandidates")
    fun setLyricsCandidates(candidates: List<NeteaseSongSearchResult>) { lyricsCandidates = candidates }
    @JvmName("updateLyricsRefreshError")
    fun setLyricsRefreshError(error: String?) { lyricsRefreshError = error }
    @JvmName("updateLyricsRefreshSource")
    fun setLyricsRefreshSource(source: MusicSearchSource) { lyricsRefreshSource = source }
    @JvmName("updateCoverRefreshSource")
    fun setCoverRefreshSource(source: MusicSearchSource) { coverRefreshSource = source }
}
