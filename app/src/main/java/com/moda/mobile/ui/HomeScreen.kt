package com.moda.mobile.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.moda.mobile.R
import com.moda.mobile.model.HomeFeed
import com.moda.mobile.model.HomeTab
import com.moda.mobile.model.LibraryEntry
import com.moda.mobile.model.VideoItem
import com.moda.mobile.ui.components.ContinueWatchingCard
import com.moda.mobile.ui.components.EmptyState
import com.moda.mobile.ui.components.ErrorState
import com.moda.mobile.ui.components.LoadingState
import com.moda.mobile.ui.components.SectionTitle
import com.moda.mobile.ui.components.VideoCard
import com.moda.mobile.ui.theme.ModaColors
import com.moda.mobile.ui.theme.ModaType
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * 手机竖屏 3 列。数字只在这里出现一次，网格列数与「继续观看」的尾段排布都读它。
 *
 * 3 列是用户按"一页能看几部"定的（2 列一屏只有 4 部）。360dp 竖屏下每格约 101dp、
 * 卡片内文宽约 81dp：标题 15sp 两行放得下约 10 个汉字（超出省略），
 * 副标题 12sp 单行放得下约 6 个汉字——只会省略，不会换行成一个字一行。
 */
private const val GRID_COLUMNS = 3

/**
 * 首页「继续观看」最多显示多少条。
 *
 * 5 条是需求上限；这一行是「顺手接着看」，不是历史页的复刻——
 * 完整记录去历史页看。历史流本身已按 updatedAt 倒序，取前 5 条即「最近看的 5 条」。
 */
private const val CONTINUE_WATCHING_LIMIT = 5

/**
 * 首屏预取高清封面的卡片数。
 *
 * 只给推荐 tab 最前面这几张：它们是最可能被先看到的卡，
 * 同时把首屏的 eps 请求数压到个位数（见 RemoteImage 里的取舍说明）。
 */
private const val HI_RES_PREFETCH_COUNT = 6

/**
 * 首页：品牌条 + 分类条 + 内容网格。
 *
 * 与 TV 端同源，但**壳**完全不同：TV 是「固定页头 + 焦点驱动滚动」，
 * 手机是「页头（品牌/时间/搜索）+ 可横滑分类条 + 一屏网格」，刷新只走页头按钮，不做下拉手势。
 * 数据只有一条通路：feeds 里当前 tab 的 items；没有第二份兜底，没数据就是加载/空态。
 *
 * 时间也只有一条通路 [pageFetchedAt]——整页最近一次成功刷新的时刻，
 * 与 30 分钟门禁判的是同一个值。按 tab 各算各的 fetchedAt 只会有两个结果对不上。
 *
 * 签名里保留了 AppRoot 既有的全部回调（契约 §5：不得删改已有参数）。页头画四个按钮：
 * [onRefresh] 在左，[onOpenSearch]、[onOpenHistory]、[onOpenCompleted] 依次在右——
 * 这三页都不在底栏（契约 §6），首页工具栏是它们的直达入口；收藏/设置由底栏承载，页头不重复。
 *
 * 页序由调用方给（[tabOrder]）：用户在「设置 → 分类排序」里的顺序，就是首页 tab 行与分页的顺序。
 * 本文件中所有"第几页"都指 [tabOrder] 的下标，**不再**用 [HomeTab.ordinal]。
 */
@Composable
fun HomeScreen(
    loading: Boolean,
    /** 调用方已过 appErrorText 的成品文案：错误码→文案的唯一出口在 components/ErrorText.kt */
    error: String?,
    onRetry: () -> Unit,
    onOpenDetail: (String, String) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenPlayer: (String, String, String, Int) -> Unit,
    onOpenHistory: () -> Unit = {},
    onOpenFavorites: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    tab: HomeTab = HomeTab.Recommend,
    feeds: Map<HomeTab, HomeFeed> = emptyMap(),
    /** 整页最近一次成功刷新的时刻；0L 表示从未刷成功过 */
    pageFetchedAt: Long = 0L,
    onSelectTab: (HomeTab) -> Unit = {},
    /**
     * 整页刷新：**不判缓存时间、无条件整页重拉**，与 [onRetry] 的差别只在错误清理时机。
     * 默认值复用 onRetry 只是未接线时的降级，AppRoot 接的是 vm::refreshHome。
     */
    onRefresh: () -> Unit = onRetry,
    /** 整页刷新中：驱动工具栏那颗按钮换成转圈，一轮 3~4 秒，没有它用户判断不出这一下生效没有 */
    refreshing: Boolean = false,
    /** 最近一次整页刷新的结果，非 null 且 tick 变化时弹一条 2.5 秒的提示 */
    refreshNotice: HomeRefreshNotice? = null,
    history: List<LibraryEntry> = emptyList(),
    /** 点「继续观看」直接进播放器（走 onResumeHistory，不经过详情页） */
    onResumeHistory: (LibraryEntry) -> Unit = { entry ->
        onOpenPlayer(entry.cid, entry.vid, entry.title, entry.episodeIndex)
    },
    onOpenCompleted: () -> Unit = {},
    /**
     * 首页分类的展示顺序，调用方传 vm.homeTabOrder。
     *
     * 默认值 [HomeTab.entries] 只是"没接线时的降级"——它就是用户没改过排序时的默认顺序。
     * 参数放在末尾且带默认值，是为了不动上面 19 个既有参数的名字、顺序与类型。
     */
    tabOrder: List<HomeTab> = HomeTab.entries,
    /**
     * 底栏显隐的上报口：当前页向下滚 → false（隐藏），向上滚或回到顶部 → true（显示）。
     * 真正的显隐动画在 AppRoot（它持有底栏），这里只负责报方向。
     */
    onNavBarVisibleChange: (Boolean) -> Unit = {},
) {
    // 页序 = 用户在「设置 → 分类排序」里定下的顺序（[tabOrder]，推荐固定第一），
    // 不再是枚举的声明顺序。tab 标题在组合期就解析成 String：空态/加载态那两句要用同一份文案。
    // 这份标题表与 [tabOrder] 同下标——titles[i] 就是 tabOrder[i] 的标题。
    val tabTitles = tabOrder.map { stringResource(it.titleRes) }

    // tab → 页号。所有"第几页"都从这里取，别再出现 tab.ordinal：
    // 自定义顺序下 ordinal 是枚举下标而不是页号，混用就是"点了电影却停在原分类"。
    val indexOf = remember(tabOrder) { tabOrder.withIndex().associate { it.value to it.index } }

    // 一页一个 tab，页数跟着 [tabOrder] 走。
    // initialPage 取当前 tab 在**当前顺序**里的位置：冷启动/重进首页时不该先闪一下第 0 页再跳过去。
    // tabOrder 里找不到当前 tab（理论上不会发生）时退化为第 0 页，而不是越界崩掉。
    val pagerState = rememberPagerState(initialPage = indexOf[tab] ?: 0) { tabOrder.size }

    // 每个 tab 各自的网格滚动位置。pager 默认只组合当前页，页面滑走就被回收，
    // 页面内 remember 出来的 state 会跟着一起没。挂在 HomeScreen 这一层（19 个空对象，
    // 代价可忽略），滑过去再滑回来还是原来那一屏，而不是回到顶部。
    // 键是 tab 本身而不是页号：页序变了，同一个分类的滚动位置不该跟着换人。
    val gridStates = remember(tabOrder) { tabOrder.associateWith { LazyGridState() } }

    // 程序化滚动计数：>0 表示 pager 正被我们自己驱动。
    // 用计数而不是布尔：连点两个 tab 时新旧两个 LaunchedEffect 会短暂重叠，
    // 布尔会在旧协程的 finally 里被提前复位，于是新动画途经的中间页被当成用户的滑动上报，
    // 又变成一次回弹。计数要等两个 finally 都跑完才归零。
    var programmatic by remember { mutableIntStateOf(0) }

    // 内容 → 顶部：currentPage 一变就上报，不等停稳。
    // 为什么不等停稳：邻页的数据要等 onSelectTab 之后才有人去拉，压到松手才报，
    // 整段拖动里那一页就始终没有数据可画。
    //
    // latestTab 必须是 rememberUpdatedState 取的最新值，且 collector **不能以 tab 为 key**：
    // 重启一次就会先发射一次「旧页 vs 新 tab」，把用户刚点的选择改回旧页——
    // 「点了电影却停在原分类」就是那个初始发射干的。
    val latestTab = rememberUpdatedState(tab)
    // 顺序也取最新值：本 effect 的 key 里**不能**有 tabOrder（重启会先发射一次「旧页 vs 新 tab」，
    // 把用户刚点的选择改回去），所以用 rememberUpdatedState 把最新顺序读进来。
    val latestOrder = rememberUpdatedState(tabOrder)
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            // 程序化滚动途经的中间页不是用户的选择，不能上报，否则点 tab 的动画被自己反向打断
            if (programmatic > 0) return@collect
            val pageTab = latestOrder.value.getOrNull(page) ?: return@collect
            if (pageTab != latestTab.value) onSelectTab(pageTab)
        }
    }

    // 顶部 → 内容：点哪个 tab 就滚到哪一页，点一次一定生效。
    // 「已经是目标页」直接返回：上面那一路同步过来的 tab 会走到这里，再滚一次就是一次点击两次选中。
    // 也刻意不看 isScrollInProgress：fling 途中点 tab 必须能接管，否则那一下点击就白点了。
    // key 里带上 tabOrder：顺序若在首页可见期间被换掉（真正走的是设置页，首页会被拆掉重装，
    // 这里只是兜底），也要把 pager 拉回「当前 tab 在新顺序里的那一页」，而不是停在旧页号上显示别的分类。
    LaunchedEffect(tab, tabOrder) {
        val target = indexOf[tab] ?: 0
        if (pagerState.currentPage == target) return@LaunchedEffect
        programmatic++
        try {
            pagerState.animateScrollToPage(target)
        } finally {
            // 滚完或被取消都必须复位。动画停在的就是 tab 那一页，
            // 所以收尾不再调 onSelectTab——一次点击只该有一次选中回调。
            programmatic--
        }
    }

    // 底栏显隐：跟着**当前页**的网格走（pager 每页各有一份自己的 grid state）。
    //
    // 两层去重：distinctUntilChanged 掐掉「滚动量没变」的重复帧；布尔量再自己记一个上次上报值，
    // 只在方向翻转时回调——否则一次滑动会每帧调一次，等于每帧写一次外部状态。
    // 换页用 collectLatest 把上一页的监视整个换掉，回调永远只描述当前这一页。
    val latestOnNavBarVisible = rememberUpdatedState(onNavBarVisibleChange)
    LaunchedEffect(pagerState, gridStates) {
        // null = 本屏还没上报过：进组合后一定先报一次。
        // 从二级页返回时首页是重新组合的（网格位置也是新的），不先报一次，底栏就会停在
        // 离开时那次"隐藏"上，而没有任何东西会把它调回来（页面上滑也不一定发生）。
        var reported: Boolean? = null
        snapshotFlow { pagerState.currentPage }
            .distinctUntilChanged()
            .collectLatest { page ->
                val pageTab = tabOrder.getOrNull(page) ?: return@collectLatest
                val gridState = gridStates[pageTab] ?: return@collectLatest
                var previous = gridScrollKey(gridState)
                snapshotFlow { gridScrollKey(gridState) }
                    .distinctUntilChanged()
                    .collect { key ->
                        // 合成量变小 ≡ 往回滚；变大 ≡ 往深处滚；0 ≡ 正好在顶部
                        val visible = key == 0L || key < previous
                        previous = key
                        if (visible != reported) {
                            reported = visible
                            latestOnNavBarVisible.value(visible)
                        }
                    }
            }
    }

    // 页头右半的「现在几点」：对齐到整分再更新，不需要每秒重组。
    // 60_000 - (now % 60_000) 就是「离下一个整分还有多少毫秒」，于是每一跳都落在 :00 秒。
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L - (System.currentTimeMillis() % 60_000L))
            nowMs = System.currentTimeMillis()
        }
    }

    // 刷新提示的生命周期归 UI：ViewModel 只负责"发出一条结果"。
    // key 取 tick 而不是整条 notice：连续两次刷新失败文案完全一样，
    // 用对象内容当 key 协程不会重启，第二句话就再也不会出现。
    // 时长仍是 2.5 秒（TV 端同一语义）：Snackbar 用 Indefinite 起，再自己掐时间关掉，
    // 这样既走了 Snackbar 的无障碍队列，又保住原来那 2.5 秒。
    val snackbar = remember { SnackbarHostState() }
    val noticeText = refreshNotice?.let { stringResource(it.messageRes) }
    val noticeSuccess = refreshNotice?.success ?: true
    LaunchedEffect(refreshNotice?.tick) {
        val text = noticeText ?: return@LaunchedEffect
        val job = launch { snackbar.showSnackbar(message = text, duration = SnackbarDuration.Indefinite) }
        delay(2_500L)
        snackbar.currentSnackbarData?.dismiss()
        job.cancel()
    }

    Box(Modifier.fillMaxSize().background(ModaColors.Bg)) {
        Column(Modifier.fillMaxSize()) {
            HomeHeader(
                fetchedAt = pageFetchedAt,
                now = nowMs,
                refreshing = refreshing,
                onRefresh = onRefresh,
                onOpenSearch = onOpenSearch,
                onOpenHistory = onOpenHistory,
                onOpenCompleted = onOpenCompleted,
            )
            HomeTabBar(
                selected = tab,
                order = tabOrder,
                titles = tabTitles,
                onSelect = onSelectTab,
            )

            // 这里刻意没有下拉刷新：手机端「滚到顶再往下拽」与「滚到顶」是同一条手势，
            // 误触一次就是整页 19 个频道重拉，代价远大于收益。
            // 刷新入口只留工具栏左上那颗按钮，与 TV 端一致。
            //
            // 内容区是 pager：横向手势归它，页内网格只管纵向，两者方向正交，不抢手势。
            // 刻意**不**给 beyondViewportPageCount 赋值——默认只组合当前页（横向滑动时的邻页才组合），
            // 19 页每页都有自己的网格和图片请求，多预取一页就是白烧一遍。
            // key 用 tab 本身：同一页滚回来时能复用它的 composition 与保存态。
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                key = { index -> tabOrder[index] },
            ) { index ->
                val pageTab = tabOrder[index]
                // null = 这一页还从没拉到过东西（19 个 tab 是按需拉的），
                // 与「拉回来了但是空的」是两件事：前者是 loading，后者才是空态
                val pageFeed = feeds[pageTab]
                val pageItems = pageFeed?.items.orEmpty()
                val pageIsRecommend = pageTab == HomeTab.Recommend
                // 高清封面只预取推荐页最前面几张，其它页一张都不取
                val pagePrefetchCids = if (pageIsRecommend) {
                    pageItems.take(HI_RES_PREFETCH_COUNT).map { it.cid }.toSet()
                } else {
                    emptySet()
                }
                when {
                    // 「错误只在没有任何数据时才露脸」：有内容时请求失败必须静默——
                    // 用户看到的仍然是能用的那一屏。判的都是**这一页**的 feed
                    pageItems.isEmpty() && error != null -> ErrorState(message = error, onRetry = onRetry)
                    // 拖到一半露出来的邻页，feeds 里还没有它：这一页必须是 loading。
                    // 不能落到下面的空态——「暂无内容」是结论，而这一页只是还没开始拉。
                    // （外面的 loading 只是**当前 tab** 的，别的页借不到，所以这里判 null。）
                    pageItems.isEmpty() && pageFeed == null -> LoadingState(
                        stringResource(R.string.home_loading_tab, tabTitles[index]),
                    )
                    pageItems.isEmpty() && loading -> LoadingState(
                        stringResource(R.string.home_loading_tab, tabTitles[index]),
                    )
                    pageItems.isEmpty() -> EmptyState(
                        message = stringResource(R.string.home_empty_tab, tabTitles[index]),
                        onRetry = onRefresh,
                    )
                    else -> FeedGrid(
                        tab = pageTab,
                        state = gridStates.getValue(pageTab),
                        items = pageItems,
                        // 历史流已是 updatedAt 倒序，take 前 N 条即「最近 N 条」；
                        // 需求：没记录时整段不出现，不是给一个空占位。
                        // 只有推荐页拿得到这段，另外 18 页恒为空，那一段也就不出现。
                        history = if (pageIsRecommend) history.take(CONTINUE_WATCHING_LIMIT) else emptyList(),
                        loading = loading,
                        prefetchCids = pagePrefetchCids,
                        onOpenDetail = onOpenDetail,
                        onResumeHistory = onResumeHistory,
                    )
                }
            }
        }

        // 提示浮在底部：脱离布局流，不推挤内容。一次刷新的结论只值一行字，
        // 为这一行字把整片内容顶上去再弹回来，比不提示还难受。
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 16.dp, vertical = 12.dp),
        ) { data ->
            Snackbar(
                snackbarData = data,
                containerColor = ModaColors.SurfaceHigh,
                // 成功用常规字色、失败用 Danger：颜色只是第二重区分，文案本身也说清了结果
                contentColor = if (noticeSuccess) ModaColors.Text else ModaColors.Danger,
                shape = RoundedCornerShape(12.dp),
            )
        }
    }
}

/**
 * 页头一行：刷新（左） + 品牌与整页时间（中） + 搜索、历史、已看完（右）。
 *
 * 布局由契约 §6 定：底栏只有 首页/收藏/设置，**搜索页、历史页、已看完页都是压栈页、不在底栏**，
 * 所以这三个入口只在这一行出现，必须一眼看得见、点得到（IconButton 自带 48dp 触控区）。
 * 右侧三颗彼此等距 4dp：间距大于 8dp 会让它们看起来像两组，小于 4dp 又分不清是三颗。
 * 收藏/设置不再在页头重复——底栏已经有，一屏两个入口只会让人以为它们去的地方不同。
 *
 * 刷新图标留在左上，并且是整页刷新的唯一入口（手机端不加下拉手势，与 TV 端一致）：
 * 空态/错误态本来就没有可滚动内容，那时能刷新的也只有这颗按钮。
 *
 * 时间读作两截：「上次刷新 今天 08:32」是数据有多新，「现在 14:05」是此刻几点。
 * 只给一个裸时刻，读者分不清它是刷新的时刻还是此刻的钟点。
 */
@Composable
private fun HomeHeader(
    fetchedAt: Long,
    now: Long,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenCompleted: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 工具栏不再放刷新按钮：刷新的语义统一收在「双击底栏首页」与「冷启动 30 分钟门禁」两处。
        // 一棵常驻的刷新图标在手机上只会被误点（一次误触就是 19 个频道整页重拉），而它占的位置
        // 会挤压中间的「品牌 + 两截时间」。刷新进行中的可见反馈改成时间戳右侧那颗小转圈。
        // 中间这块被左右两串图标夹在中间，宽度只剩一截：**必须**单行省略。
        // 让它换行会把整行撑成两行高，下面的分类条与内容区跟着整体下移，
        // 「品牌 + 两截时间」也就再也对不齐——所以标题与副标题都钉死 maxLines = 1。
        Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
            Text(
                text = stringResource(R.string.app_name),
                style = ModaType.Title,
                color = ModaColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = lastRefreshLabel(fetchedAt, now) + " · " + stringResource(R.string.home_now, clockLabel(now)),
                    style = ModaType.Caption,
                    color = ModaColors.TextDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (refreshing) {
                    // 一轮整页刷新 3~4 秒，内容与时间戳都可能一动不动：这颗小转圈是唯一的「按对了」反馈
                    Spacer(Modifier.width(6.dp))
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        color = ModaColors.AccentSoft,
                        strokeWidth = 1.5.dp,
                    )
                }
            }
        }
        // 右侧三颗一组：等距 4dp。IconButton 自带 48dp 触控区（≥44dp 的触摸下限），
        // 所以这里不再另外包 padding——包了只会把触控区撑大、把中间那块挤没。
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconButton(onClick = onOpenSearch) {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = stringResource(R.string.home_action_search),
                    tint = ModaColors.Text,
                )
            }
            IconButton(onClick = onOpenHistory) {
                Icon(
                    imageVector = Icons.Outlined.History,
                    contentDescription = stringResource(R.string.home_action_history),
                    tint = ModaColors.Text,
                )
            }
            // 已看完的另一个入口在历史页；这里补的是首页直达，省掉「先进历史再点一次」
            IconButton(onClick = onOpenCompleted) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = stringResource(R.string.home_action_completed),
                    tint = ModaColors.Text,
                )
            }
        }
    }
}

/**
 * 分类条：一屏放不下，所以是可横滑的 LazyRow。
 *
 * 顺序与内容页共用同一份 [order]：这条 chip 排第 i 个，就是 pager 的第 i 页。
 * 两处各读一次枚举顺序，总有一处会漏改（自定义顺序下就是"点了 A 停在 B"）。
 *
 * 为什么不用 ScrollableTabRow：那一套自带 indicator/edgePadding/spec 三层默认值，
 * 首项会被 edgePadding 顶开，而它与页头的左缘必须严格对齐。
 * 这里用「形状 + 底色」表达选中（与设置页的分组卡同一套），点击一律 clickable。
 */
@Composable
private fun HomeTabBar(
    selected: HomeTab,
    /** 用户顺序：[titles] 与它同下标 */
    order: List<HomeTab>,
    titles: List<String>,
    onSelect: (HomeTab) -> Unit,
) {
    val listState = rememberLazyListState()
    // 内容页滑到后面的分类时，那颗 chip 多半在一屏之外：只改高亮不滚，用户看不到
    // 「现在是哪个分类」。已经在可视区里就不滚——每次重组都来一下会把用户的横滑手势顶回去。
    // 下标取 order 里的位置（不是 selected.ordinal）：自定义顺序下两者不是一回事。
    LaunchedEffect(selected, order) {
        val index = order.indexOf(selected).takeIf { it >= 0 } ?: return@LaunchedEffect
        val visible = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
        val fullyVisible = visible != null &&
            visible.offset >= listState.layoutInfo.viewportStartOffset &&
            visible.offset + visible.size <= listState.layoutInfo.viewportEndOffset
        if (!fullyVisible) listState.animateScrollToItem(index)
    }
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        state = listState,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(count = order.size, key = { index -> order[index].name }) { index ->
            val entry = order[index]
            TabChip(
                title = titles[index],
                isSelected = entry == selected,
                onClick = { onSelect(entry) },
            )
        }
    }
}

@Composable
private fun TabChip(title: String, isSelected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(18.dp))
            // 选中底色用强调蓝的浅染：压在 #0B0B0F 页底上，纯 SurfaceHigh 只有约 1.35:1，
            // 6 寸屏上也几乎看不出「当前是哪个分类」
            .background(if (isSelected) ModaColors.Accent.copy(alpha = 0.32f) else Color.Transparent)
            .clickable(interactionSource = null, indication = LocalIndication.current, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            text = title,
            style = ModaType.Body,
            // 靠亮度与字重分级，不靠字号：同一栏里字号忽大忽小会让整条看起来在抖
            color = if (isSelected) ModaColors.Text else ModaColors.TextDim,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

/**
 * 内容网格：推荐 tab 尾部接一段「继续观看」，分类 tab 只有海报。
 *
 * 两者放进**同一个** LazyVerticalGrid 而不是两个滚动容器：
 * 网格套网格会出现「内层滚完外层再滚」，触摸端同样极难操作。
 * 分区靠满宽 span 的标题行隔开。
 */
@Composable
private fun FeedGrid(
    tab: HomeTab,
    /** 每个 tab 各一份：页与页之间绝不共用，否则滑过去再回来位置全乱 */
    state: LazyGridState,
    items: List<VideoItem>,
    history: List<LibraryEntry>,
    loading: Boolean,
    prefetchCids: Set<String>,
    onOpenDetail: (String, String) -> Unit,
    onResumeHistory: (LibraryEntry) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(GRID_COLUMNS),
        state = state,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        itemsIndexed(items = items, key = { _, item -> "${tab.name}-${item.cid}" }) { _, item ->
            VideoCard(
                item = item,
                onClick = { onOpenDetail(item.cid, item.title) },
                preferHiRes = prefetchCids.contains(item.cid),
            )
        }

        if (history.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "continue-title") {
                Row(
                    Modifier.fillMaxWidth().padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SectionTitle(stringResource(R.string.home_continue_watching))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.home_continue_count, history.size),
                        style = ModaType.Caption,
                        color = ModaColors.TextDim,
                    )
                }
            }
            itemsIndexed(
                items = history,
                // 同一部剧可能同时有多集记录，只用 cid 会撞 key，网格会把重复项直接吞掉
                key = { i, entry -> "history-${entry.cid}-${entry.episodeIndex}-$i" },
            ) { _, entry ->
                ContinueWatchingCard(
                    entry = entry,
                    // 直接进播放页，不经过详情
                    onClick = { onResumeHistory(entry) },
                )
            }
        }

        // 本分支本来就要求 items 非空，所以这里永远不是「首加载骨架」，
        // 只表示「这一屏正在重拉」：有旧数据就继续显示，底部一行小字提示即可
        if (loading) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "loading-more") {
                Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.home_refreshing), style = ModaType.Caption, color = ModaColors.TextDim)
                }
            }
        }
    }
}

/**
 * 「上次刷新」文案。
 *
 * fetchedAt == 0 表示「整页还没成功刷新过」，必须显式判零：
 * 直接格式化会把 0 渲染成 1970-01-01 08:00，看起来像真抓到了什么。
 * 今天/昨天用相对日而不是日期：这个位置一天里绝大多数时候都是「今天」。
 */
@Composable
private fun lastRefreshLabel(fetchedAt: Long, now: Long): String {
    if (fetchedAt <= 0L) return stringResource(R.string.home_last_refresh_never)
    val day = when {
        isSameDay(fetchedAt, now) -> stringResource(R.string.home_day_today)
        isSameDay(fetchedAt, now - DAY_MS) -> stringResource(R.string.home_day_yesterday)
        // 更早的才退回日期，且不带年份
        else -> monthDayLabel(fetchedAt)
    }
    return stringResource(R.string.home_last_refresh, "$day ${clockLabel(fetchedAt)}")
}

/** 「月日」格式串交给系统按当前语言给：中文得「3月5日」、英文得「Mar 5」 */
private fun monthDayLabel(at: Long): String {
    val locale = Locale.getDefault()
    val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, "MMMd")
    return SimpleDateFormat(pattern, locale).format(Date(at))
}

/** 时分。两处（上次刷新 / 现在）共用一份格式，避免各写一遍、日后改一处漏一处 */
private fun clockLabel(at: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(at))

private const val DAY_MS = 24 * 60 * 60 * 1000L

/**
 * 网格滚动的合成量：firstVisibleItemIndex 与 firstVisibleItemScrollOffset 拼成一个 Long。
 *
 * 两者分开看会出现「换行时 index+1、offset 归零」的假回退——offset 从 300 掉到 0
 * 会被当成"用户上滑了"，底栏于是自己弹回来。高 32 位放 index、低 32 位放 offset，
 * 合成之后就重新单调了（offset 是像素，不会超过 2^31）。0 表示正停在顶部。
 */
private fun gridScrollKey(state: LazyGridState): Long =
    (state.firstVisibleItemIndex.toLong() shl 32) or
        (state.firstVisibleItemScrollOffset.toLong() and 0xFFFF_FFFFL)

/** 同一「自然日」判断：按本地时区的年月日比，不能用 24 小时差量糊过去 */
private fun isSameDay(a: Long, b: Long): Boolean {
    val ca = Calendar.getInstance().apply { timeInMillis = a }
    val cb = Calendar.getInstance().apply { timeInMillis = b }
    return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) &&
        ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
}
