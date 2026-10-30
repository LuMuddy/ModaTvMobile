package com.moda.mobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.moda.mobile.R
import com.moda.mobile.data.NetKind
import com.moda.mobile.model.AppError
import com.moda.mobile.model.Danmaku
import com.moda.mobile.model.Episode
import com.moda.mobile.model.SeriesDetail
import com.moda.mobile.model.Tier
import com.moda.mobile.ui.components.ActionButton
import com.moda.mobile.ui.components.EmptyState
import com.moda.mobile.ui.components.RemoteImage
import com.moda.mobile.ui.components.SectionTitle
import com.moda.mobile.ui.components.appErrorText
import com.moda.mobile.ui.detail.DetailPreviewPlayer
import com.moda.mobile.ui.theme.ModaColors
import com.moda.mobile.ui.theme.ModaType
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** 「看完」角标的前景色：深底上的柔绿，与 VIP 的金、预告的靛蓝互不混淆 */
private val CompletedGreen = Color(0xFF7BD88F)

/** 已收藏时爱心的颜色。选黄不选强调蓝：蓝色在本应用里已经等于「当前选中 / 可操作」 */
private val FavoriteYellow = Color(0xFFFFC53D)

/**
 * 简介折叠时的行数。
 *
 * 5 行（原 3 行）：三行在 16sp 下只有两句话，简介那一块看着是最"空"的地方；
 * 五行的代价只是把选集轨道往下推一点，而「展开/收起」仍然由实测溢出决定，
 * 所以短简介不会多出一条点不动的按钮。
 */
private const val DESC_COLLAPSED_LINES = 5

/** 选集轨道一屏露几个：3 个是用户对着截图定的，格子宽度全部由它反算，不写死 dp */
private const val EPISODES_PER_SCREEN = 3

/**
 * 视频块与状态栏之间那一道留白。
 *
 * 用户要求：视频顶部与返回键顶部**对齐**。
 *
 * 返回那颗是 48dp 触控盒、24dp 图标居中，盒顶在 statusBarTop + 12dp，
 * 所以图标顶边 = statusBarTop + 12 + 12 = **statusBarTop + 24dp** —— 这个常量就是 24。
 * 只加在视频块上（占位 Spacer 与播放器节点两处同一个值，否则两者高度算不到一起，
 * 会漏一条或盖住标题）；返回按钮本身的位置与尺寸不动。
 */
private val VideoTopGap = 24.dp

/** VIP 角标的金：选集缩略图右上角那颗「VIP」用它 */
private val VipGold = Color(0xFFE0A32E)

/**
 * 被「流量播放提醒」拦下的那个动作。确认卡点「继续播放」时原样执行它。
 *
 * 两条入口（封面上的播放键 / 选集里点某一集）都落到这里，所以"确认之后做什么"
 * 只有一份实现；各写一次的话，早晚有一条入口确认完什么也不干。
 */
private sealed interface BlockedAction {
    /** 给小屏装地址起播（入参是续播集的 0 起下标） */
    data class StartPreview(val episodeIndex: Int) : BlockedAction

    /** 去全屏播放页看这一集（index 是它在集数表里的下标，用来钉选集高亮） */
    data class PlayEpisode(val episode: Episode, val index: Int) : BlockedAction
}

/**
 * 剧集详情：顶部小屏播放器 + 片名 + 收藏/可展开简介 + 单行横向选集轨道。
 *
 * 顶部那块**真的能播**（[DetailPreviewPlayer]）：进页面就播续播集（没有历史就第 1 集），
 * 可播放/暂停、纵滑调亮度与音量、投屏、进全屏。点集号**不**切小屏的片源——那只意味着"去全屏看这一集"。
 *
 * 页面骨架 = root Box 里「一个播放器节点 + 两层布局」，**不是**两个播放器调用点：
 *    Box(fillMaxSize) {
 *        if (!fullscreen) { Column { Spacer(16:9 占位) ; 可滚动内容 } }
 *        DetailPreviewPlayer(Modifier 随 fullscreen 在「16:9 卡片」与「整屏」之间切换)
 *    }
 * 播放器始终是 root Box 的**同一个**子节点，所以小屏 ↔ 全屏只换 Modifier：
 * 换路由/换调用点都会让 Surface 被拆掉重建，用户看到的就是一帧黑再重贴。
 * 代价是内容区要在播放器下面单独占位（Spacer 与播放器同一条 Modifier 链），
 * 那条链的顺序与数值不许各自改动。
 *
 * 内容区是一个 LazyColumn：每个 item 都是满宽，纵向滚动只归它（滚动位置记在页面这一层，
 * 进出全屏不会被丢掉）。选集是其中**一格 item**，内部是单行横向 LazyRow（一屏露 3 个、右滑看更多）。
 * 两者方向正交，各吃各的手势，不会互相抢——这也是选集能做成独立轨道的前提；
 * 反过来说，同方向的嵌套滚动（比如页面纵向里再套一个纵向网格）在触摸端必然打架，不要加。
 *
 * 轨道左右是对称留白 + 默认把当前播放集滚到正中，所以进页面第一眼看到的就是"我现在这一集"
 * 以及它前后各有哪几集。
 *
 * 地址由 VM 装（[onLoadPreview] → MainViewModel.loadDetailPreview），本页只在进页面时装一次、
 * 离开时清掉。**不在这里写历史、不判完成**：进度的写者只有全屏播放页。
 *
 * [onTierSelect] / [selectedTier] 是跨任务签名的一部分（契约 §5）。
 * 详情页仍然**不摆档位选择器**：切清晰度只发生在播放页的底部面板里，
 * 摆一排档位会在进播放器前就让人以为"这里选错了"，而它并不决定起播档位。
 */
@Composable
fun DetailScreen(
    detail: SeriesDetail?,
    loading: Boolean,
    /** 数据层错误码：文案一律由 [appErrorText] 按当前语言渲染 */
    error: AppError?,
    onTierSelect: (Tier) -> Unit,
    selectedTier: Tier?,
    onBack: () -> Unit,
    description: String = "",
    onRetry: (() -> Unit)? = null,
    isFavorite: Boolean = false,
    /** 历史落点：这部剧看过的话，小屏从那一集起播、选集高亮那一格 */
    resume: DetailResume? = null,
    /** 这部剧已看完的集号集合，用于选集卡右上角的绿色勾标 */
    completedIndexes: Set<Int> = emptySet(),
    onToggleFavorite: () -> Unit = {},
    /** 顶部小屏播放器要播的地址（VM 的 detailPreview）；null = 还没装好 */
    preview: DetailPreview? = null,
    previewLoading: Boolean = false,
    /** 装地址失败：只影响顶部那一格，选集与收藏照常可用 */
    previewError: AppError? = null,
    /** 进页面装一次，参数是续播集下标（没有历史时是 0） */
    onLoadPreview: (Int) -> Unit = {},
    /** 小屏那一格可选的清晰度（VM 的 detailPreviewTiers）：空 = 这一集拿不到档位表 */
    previewTiers: List<Tier> = emptyList(),
    /** 小屏当前在用的档位（VM 的 detailPreviewTier） */
    previewTier: Tier? = null,
    /** 切档：只换地址，播放器按新 url 换源；面板与底栏都由播放器自己画 */
    onSelectPreviewTier: (Tier) -> Unit = {},
    /** 离开页面清掉；引擎与投屏由 [DetailPreviewPlayer] 自己的 onDispose 收尾 */
    onClearPreview: () -> Unit = {},
    /** 小屏的弹幕窗口（VM 的 previewDanmakuWindow）：只喂给顶部那一格播放器 */
    danmaku: List<Danmaku> = emptyList(),
    /** 小屏换了播放位置就把弹幕窗口要过来（VM.ensurePreviewDanmaku，入参是 vid + 位置） */
    onEnsureDanmaku: (String, Long) -> Unit = { _, _ -> },
    /**
     * 当前网络类型（VM 在进入详情页时探测一次）。
     * 只有**明确是蜂窝**才谈流量提醒：WiFi / 以太网 / VPN / 无网都按"可直接起播"处理。
     */
    netKind: NetKind = NetKind.Other,
    /** 探测一次网络类型并把**新鲜结论返回**：自动起播的判定要用返回值，不能等状态回流 */
    onProbeNet: () -> NetKind = { NetKind.Other },
    /** 「流量播放提醒」（PlayerSettings.cellularPrompt）：与设置 → 播放设置里那颗 Switch 同一个键 */
    cellularPrompt: Boolean = true,
    /** 写同一个键：详情页的「不再提醒」与设置页那颗开关互为镜像，各存一份就会各说各话 */
    onSetCellularPrompt: (Boolean) -> Unit = {},
) {
    val episodes = detail?.episodes.orEmpty()

    // 只有一集（预告/花絮也算集数，只要返回 2 条以上就算多集）时不画选集：
    // 一集本来就没有可选的东西，「第 1 集」孤零零挂在下面只会让人以为别的集没加载出来
    val showEpisodes = episodes.size > 1

    // 历史落点：优先按 vid 匹配——集数表重排/换档后 episodeIndex 可能漂移，
    // vid 才是「那一集」的稳定身份，下标只在 vid 找不到时兜底。
    // cid 必须对得上：切剧瞬间 StateFlow 还留着上一部剧的值，不丢掉它会拿 A 的集号去选 B。
    val watched = resume?.takeIf { it.loaded && it.cid == detail?.cid }
    val watchedByVid = watched?.vid?.takeIf { it.isNotBlank() }
        ?.let { vid -> episodes.indexOfFirst { it.vid == vid } }
        ?: -1
    val resumeIndex = if (watchedByVid >= 0) {
        watchedByVid
    } else {
        watched?.episodeIndex?.takeIf { it in episodes.indices } ?: -1
    }

    // 续播集就是 [resumeIndex]（vid 命中优先、下标兜底，见上）：小屏从这一集起播。
    // 刻意不读 positionMs——详情页不写历史，也不续播到断点，起播位置恒为 0。

    // 用户自己点过某一集之后，迟到的历史不许再把高亮挪走
    // （高亮只影响选集格与副标题；小屏的片源只认 previewIndex，见下）
    var userTouched by remember(detail?.cid) { mutableStateOf(false) }
    var currentIndex by remember(detail?.cid) { mutableIntStateOf(resumeIndex.coerceAtLeast(0)) }
    LaunchedEffect(detail?.cid, resume?.loaded, resumeIndex) {
        if (!userTouched && resume?.loaded == true && resumeIndex >= 0) currentIndex = resumeIndex
    }

    /** 网格上方的副标题只看「选集高亮的那一集」 */
    val currentEpisode = episodes.getOrNull(currentIndex)

    /**
     * 顶部小屏在播第几集：**只认 VM 装好的那一集**，与选集高亮 [currentIndex] 刻意分开。
     *
     * 两个状态描述的是两件事：高亮 = "我最后点了哪一集"（点完就进全屏了），
     * 小屏 = "这一页现在在放哪一集"。用户要求点集号就**在本页换片源**（不跳页面），
     * 所以两者在点完之后是同一个值；分开存只是因为 preview 还要等 VM 把地址装回来。
     * address 还没到时先按续播集显示，标题行才不至于写"第 1 集"再跳一次。
     */
    val previewIndex = preview?.episodeIndex ?: resumeIndex.coerceAtLeast(0)

    /**
     * 小屏在播的那一集的 vid：弹幕接口只认 vid，而 [DetailPreview] 只有地址。
     * 取的集必须与 [previewIndex] 同一口径（就是小屏真的在放的那一集），
     * 高亮用的 [currentIndex] 不能用——用户点过选集之后它指向的是"要去全屏看的那一集"。
     */
    val previewVid = episodes.getOrNull(previewIndex)?.vid.orEmpty()

    val loadPreviewNow by rememberUpdatedState(onLoadPreview)
    val clearPreviewNow by rememberUpdatedState(onClearPreview)

    // 进页面装一次地址。必须等历史落定（[DetailResume.loaded]）再决定装哪一集：
    // 先装第 1 集、等历史到了再换第 5 集，小屏会先起播一句再从头缓冲一次。
    var previewRequested by remember(detail?.cid) { mutableStateOf(false) }

    // —— 流量提醒（蜂窝 + 提醒开启）——
    // trafficHold：这一格停在「封面 + 播放键」等用户点，地址没装、一个字节都没发。
    // consentVisible：确认卡展开。状态放在**这一层**而不是播放器里，因为选集轨道那条入口
    //   也在页面里，两处必须弹同一张卡。
    // pendingAction：被拦下的那个动作，确认后原样执行。
    var trafficHold by remember(detail?.cid) { mutableStateOf(false) }
    var consentVisible by remember(detail?.cid) { mutableStateOf(false) }
    var pendingAction by remember(detail?.cid) { mutableStateOf<BlockedAction?>(null) }

    LaunchedEffect(detail?.cid, resume?.loaded, resumeIndex, episodes.size, cellularPrompt) {
        if (previewRequested || episodes.isEmpty()) return@LaunchedEffect
        if (resume != null && !resume.loaded) return@LaunchedEffect
        // 网络类型必须在这一步就用上**新鲜**结论（取返回值，不等状态回流）：
        // 蜂窝下若按上一页留下的旧值判断，装地址这一下已经把流量花掉了
        val kind = onProbeNet()
        if (kind == NetKind.Cellular && cellularPrompt) {
            trafficHold = true
            return@LaunchedEffect
        }
        previewRequested = true
        loadPreviewNow(resumeIndex.coerceAtLeast(0))
    }

    // 离开详情页：清掉预览状态。引擎 release 与停投在 DetailPreviewPlayer 自己的 onDispose 里，
    // 这里只管 VM 那一份地址，否则下次进别的剧会先闪一帧上一部的片源。
    DisposableEffect(detail?.cid) {
        onDispose { clearPreviewNow() }
    }

    // 选集轨道的位置状态。它不是网格：整页纵向滚动由外面的 LazyColumn 管，这里只管横向。
    // 居中本身放在轨道那个 item 里（要按格宽算偏移，只有 BoxWithConstraints 里知道格宽）。
    val railState = rememberLazyListState()
    // 手动滑动过轨道也算"用户接管了"：只认 DragInteraction（程序化的 scrollToItem 不发它），
    // 否则用户自己滑到第 80 集、迟到一次历史就被拽回续播集，手感像被抢。
    LaunchedEffect(railState) {
        railState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) userTouched = true
        }
    }

    // 预告角标的判据来自上游分类词：它们是数据（片名/标签的一部分），不是界面文案，
    // 但代码里不许出现中文字面量（契约 §1），所以词组放 string 资源里按 '|' 切开用
    val trailerKeys = stringResource(R.string.m_feed_trailer_keywords).split('|')
    fun isTrailer(ep: Episode) = trailerKeys.any { it.isNotBlank() && ep.label.contains(it) }

    val playerTitle = detail?.title?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.detail_untitled)

    // 全屏是**页面内部状态**（对外签名不变）。它只决定两件事：播放器拿哪个 Modifier、
    // 下面这一屏内容是否组合。播放器节点本身**始终在 root Box 的同一个槽位**——
    // 换布局不换节点，Surface 就不会被拆掉重贴（黑帧正是从"拆掉重贴"来的）。
    var fullscreen by remember(detail?.cid) { mutableStateOf(false) }


    // 页面纵向滚动位置放在这一层：全屏时内容整段不组合，状态留在这里才不会连同位置一起丢掉
    val pageState = rememberLazyListState()
    val pageScope = rememberCoroutineScope()

    /**
     * 被拦下的动作现在真的执行。**这是"确认后做什么"的唯一实现**：
     * 封面上的播放键与选集里点某一集都从这里落地，各写一次早晚有一条忘了接。
     */
    fun executeBlocked(action: BlockedAction) {
        when (action) {
            is BlockedAction.StartPreview -> {
                // 取消过的那次也把 trafficHold 打开：取消 = 回到封面 + 播放键，不是回到自动起播
                trafficHold = true
                previewRequested = true
                loadPreviewNow(action.episodeIndex)
            }

            is BlockedAction.PlayEpisode -> {
                // 高亮与"用户接管"都推迟到这一刻：点一下又取消不该把选集高亮挪过去
                userTouched = true
                currentIndex = action.index
                // 选一集 = **在这一页换片源，不换页面**。页面是不是全屏由当前状态决定：
                // 全屏里换集就一直全屏，小屏里换就一直小屏。
                // 以前这里 push 播放页路由，于是"在详情页点下面的集号"会直接跳进横屏全屏——
                // 用户点的是这一页的选集，落进的却是另一个页面，返回路上全屏状态也一并丢了。
                previewRequested = true
                loadPreviewNow(action.index)
                if (!fullscreen) {
                    // 小屏那一格在页面顶上：点完把页面拉回顶部（只动滚动，不动路由），
                    // 否则用户点完看到的还是选集列表，不知道已经在放了
                    pageScope.launch {
                        if (pageState.layoutInfo.totalItemsCount > 0) pageState.animateScrollToItem(0)
                    }
                }
            }
        }
    }

    /**
     * 拦一次动作：需要提醒就先展开确认卡并记下它，否则直接执行。
     *
     * 判据用"进页面探测的那一次结论"（[netKind]），本页不常驻 NetworkCallback；
     * 两个条件缺一不可——不是蜂窝就没什么好提醒的，提醒关掉之后也该和 WiFi 一样直连。
     */
    fun runOrAsk(action: BlockedAction) {
        if (netKind == NetKind.Cellular && cellularPrompt) {
            pendingAction = action
            consentVisible = true
            // 确认卡画在播放器那一格（就是"视频播放那块区域"）里：页面滚下去之后
            // 点集号只会让人以为没反应，所以顺手把那一格拉回视野（只动滚动，不动路由）
            pageScope.launch {
                if (pageState.layoutInfo.totalItemsCount > 0) pageState.animateScrollToItem(0)
            }
            return
        }
        executeBlocked(action)
    }

    // 返回键分层：确认卡展开时先收起它，不直接退掉整个详情页。
    // 与播放器里那条 BackHandler 同一套规则（新增的弹层都要插进返回链）
    BackHandler(enabled = consentVisible) {
        consentVisible = false
        pendingAction = null
    }

    // 状态栏让位：**本页自己算**，不依赖 AppRoot 的 safeDrawingPadding（它对 Dest.Detail 已经不加了）。
    // 为什么不用 statusBarsPadding()：那个修饰符会扣除"上游已消费"的 inset，一旦外层也加了一层，
    // 这里就静默变成 0——实测就是这样把画面顶到了 y=0、弹幕飘进状态栏。
    // asPaddingValues() 取的是系统原始 inset，不受消费影响，所以这个数在两种宿主下都算得出来，
    // 而且内容列与播放器节点用的是**同一个值**，不会一高一低。
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    // 全屏时底色换成纯黑：播放器让出状态栏后露的就是这个底——用页面底色会在纯黑画面顶上留一条灰边。
    Box(Modifier.fillMaxSize().background(if (fullscreen) Color.Black else ModaColors.Bg)) {
        // 非全屏 = 占位 + 可滚动的内容；全屏时整段不组合，触摸也就不会在画面底下抢
        if (!fullscreen) {
            // 状态栏：**内容列与下面那颗播放器节点用同一个 [statusBarTop]**（两根子树各自排版，
            // 只加一处就是播放器盖住内容、或中间漏一条）。底部留出导航条那一条，
            // 因为外层对这一页已经不再做 safeDrawingPadding。
            Column(Modifier.fillMaxSize().padding(top = statusBarTop, bottom = navBarBottom)) {
                // 播放器占位：**与下面播放器那条 Modifier 链逐字对应**
                // （fillMaxWidth → 左右 16dp → 16:9）。数值与顺序不许各自为政，
                // 否则两边算出的高度差一截，就是"一条空洞"或者"播放器盖住标题"。
                // 它是这一列的第一个子节点：视频块顶边 = 页面顶边（状态栏之下），
                // 上面**不再有**返回条——返回回到画面左上角（见 DetailPreviewPlayer 的 onBack），
                // 所以这里也没有任何要补的高度。
                Spacer(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = VideoTopGap)
                        .aspectRatio(16f / 9f),
                )
                if (detail == null) {
                    // 加载中/失败都由卡片自己说清楚（它内部有那两个分支），
                    // 只剩"既不在加载、也没报错、就是没内容"这一种要在下面补一句 + 重试
                    if (!loading && error == null) {
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            EmptyState(
                                message = stringResource(R.string.detail_empty),
                                onRetry = onRetry ?: onBack,
                            )
                        }
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                } else {
                    // 整页只剩满宽 item（选集已经改成横向轨道，不再是网格里的一格），
                    // 所以容器是 LazyColumn 而不是 LazyVerticalGrid
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        state = pageState,
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // 片名从播放器的覆盖层挪到这里：覆盖层收起时那行字就没了，而"我在看什么剧"
                        // 是这一页最该一直可读的信息。不带集号——选集格子里写着集数，重复没有意义。
                        // 片名为空（异常数据）时整个 item 不组合，不留一条空行。
                        if (detail.title.isNotBlank()) {
                            item(key = "title") {
                                Text(
                                    text = detail.title,
                                    // Heading(18sp) → Title(22sp)：满页留白里片名是唯一
                                    // "该有分量"的一行，字号只在这一处改，别的页面不动
                                    style = ModaType.Title,
                                    color = ModaColors.Text,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                                )
                            }
                        }

                        // 收藏（唯一入口）+ 可展开的剧情简介
                        item(key = "actions") {
                            DetailActions(
                                detailCid = detail.cid,
                                description = description,
                                isFavorite = isFavorite,
                                onToggleFavorite = onToggleFavorite,
                            )
                        }

                        if (episodes.isEmpty()) {
                            item(key = "no-episodes") {
                                Text(
                                    text = stringResource(R.string.detail_no_episodes),
                                    style = ModaType.Body,
                                    color = ModaColors.Danger,
                                    modifier = Modifier.padding(top = 16.dp),
                                )
                            }
                        } else if (showEpisodes) {
                            item(key = "episodes-title") {
                                Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        SectionTitle(stringResource(R.string.detail_episodes_title))
                                        Spacer(Modifier.width(10.dp))
                                        // 「共 N 集」：格子里只剩数字，总数必须有一处写清楚
                                        Text(
                                            text = stringResource(R.string.detail_episode_count, episodes.size),
                                            style = ModaType.Caption,
                                            color = ModaColors.TextDim,
                                        )
                                    }
                                    // 分集描述（综艺看点/预告简介）从格子里搬到这一行：
                                    // 它是把格子撑高的元凶，而格子只留集号才排得下 6 列。
                                    // 固定显示「当前集」的那一条，不做逐集展示——逐集展示会变成一整个段落列表，
                                    // 又要把选集区顶到屏幕外。没有副标题时整行不组合。
                                    val summary = currentEpisode?.summary.orEmpty()
                                    if (summary.isNotBlank()) {
                                        Spacer(Modifier.height(6.dp))
                                        Text(
                                            text = stringResource(
                                                R.string.core_episode_index,
                                                (currentEpisode?.index ?: 0) + 1,
                                            ) + " · " + summary,
                                            style = ModaType.Caption,
                                            color = ModaColors.TextDim,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }

                        // 选集：单行横向轨道，一屏露 3 个、右滑看更多。
                        // 为什么不再用 3 列网格：竖着排会把播放器顶出屏幕，而"挑下一集"本来就是在
                        // 一维序列里往前找；横向轨道一眼看到"当前这集左右各是哪几集"。
                        // 手势不打架：轨道只吃横向、页面只吃纵向（正交嵌套滚动，Compose 原生支持），
                        // 不给页面再套 horizontalScroll。
                        if (showEpisodes) {
                            item(key = "episodes") {
                                // BoxWithConstraints 只为拿轨道的可用宽，格宽由它反算：
                                // 一屏 3 格 + 两个 8dp 间距 ⇒ 格宽 = (可用宽 − 16dp) / 3。
                                // 不写死 dp：320 / 360 / 411dp 的屏上都是正好一屏 3 个。
                                // 左右 16dp 页边距由外层 LazyColumn 的 contentPadding 提供，轨道内部不加，
                                // 所以第 1 集会贴到 16dp 处而不是浮在中间。
                                BoxWithConstraints(Modifier.fillMaxWidth()) {
                                    val gap = 8.dp
                                    val cellWidth = (maxWidth - gap * 2) / EPISODES_PER_SCREEN
                                    // 让某一格居中需要的**负滚动偏移**（像素）：那一格左缘本来在 0，
                                    // 想让它居中就要把内容再往左滚 (可用宽 − 格宽) / 2。
                                    val sidePaddingPx = with(LocalDensity.current) {
                                        ((maxWidth - cellWidth) / 2).roundToPx()
                                    }
                                    // 能居中就居中，到头就贴边：负偏移会被 LazyRow 自己的边界夹取吃掉
                                    // （第 1 集夹到 0 ⇒ 贴左；最后一集夹到 maxScroll ⇒ 贴右），
                                    // 所以这里不用再判断"是不是首末两集"，也就不会在两头留白。
                                    // userTouched 之后不再抢位置；下标双向夹取，历史漂移不会越界崩。
                                    LaunchedEffect(
                                        detail?.cid,
                                        resume?.loaded,
                                        currentIndex,
                                        userTouched,
                                        sidePaddingPx,
                                    ) {
                                        if (!userTouched) {
                                            railState.scrollToItem(
                                                index = currentIndex.coerceIn(0, episodes.lastIndex),
                                                scrollOffset = -sidePaddingPx,
                                            )
                                        }
                                    }
                                    LazyRow(
                                        state = railState,
                                        horizontalArrangement = Arrangement.spacedBy(gap),
                                    ) {
                                        itemsIndexed(
                                            items = episodes,
                                            // 同一部剧可能同名同 vid 的重播条目，下标一起进 key 才唯一
                                            key = { index, ep -> "ep-${ep.vid}-$index" },
                                        ) { index, ep ->
                                            EpisodeCell(
                                                episode = ep,
                                                selected = index == currentIndex,
                                                completed = index in completedIndexes,
                                                isVip = ep.isVip,
                                                isTrailer = isTrailer(ep),
                                                modifier = Modifier.width(cellWidth),
                                                onClick = {
                                                    // 进播放页这条路先过流量门：蜂窝 + 提醒开启时
                                                    // 先弹确认卡，确认之后才钉高亮 + 进播放页
                                                    runOrAsk(BlockedAction.PlayEpisode(ep, index))
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // 已有内容时的刷新/失败只在底部提示，不盖住整页：
                        // 用户看到的仍然是能用的那一屏
                        if (loading || error != null) {
                            item(key = "footer") {
                                Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                                    if (loading) {
                                        Text(
                                            text = stringResource(R.string.detail_refreshing),
                                            style = ModaType.Caption,
                                            color = ModaColors.TextDim,
                                        )
                                    }
                                    if (error != null) {
                                        Spacer(Modifier.height(10.dp))
                                        Text(appErrorText(error), style = ModaType.Body, color = ModaColors.Danger)
                                        Spacer(Modifier.height(10.dp))
                                        ActionButton(
                                            text = stringResource(R.string.detail_retry),
                                            onClick = { onRetry?.invoke() ?: onBack() },
                                            primary = true,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

        }

        // ⚠ 全文**唯一**的播放器调用点：小屏与全屏共用这一个节点，只换 Modifier。
        // 它在**所有**数据状态下都在组合里（loading / 失败 / 空态也在）——那几屏也要有画面出口，
        // 页面出口（返回）画在这个节点自己的左上角，见 DetailPreviewPlayer 的 onBack。
        // 非全屏 = 16:9 卡片（高度与上面那个 Spacer 严丝合缝，顶边就是页面顶边，不让任何高度）；
        // 全屏 = 整块屏幕，压在内容之上（内容本来就不组合，zIndex 只是保险）。
        // 这里**不换路由**：换路由就要新建 Surface，用户看到的就是一帧黑再重贴。
        DetailPreviewPlayer(
            title = playerTitle,
            // 还没拿到这部剧：不喂任何地址，卡片内部显示"正在准备播放…"或错误面板
            preview = if (detail == null) null else preview,
            loading = if (detail == null) loading else previewLoading,
            error = if (detail == null) error else previewError,
            // -1 = 这部剧没有集数：播放器据此不写"第 N 集"
            episodeIndex = if (detail == null || episodes.isEmpty()) -1 else previewIndex,
            // 重试就是让 VM 重装这一集的地址（不新造网络路径）
            onRetry = {
                if (detail == null) onRetry?.invoke() ?: onBack() else loadPreviewNow(previewIndex)
            },
            // 全屏按钮 = 只改布局。以前这里 push 播放页路由（→ 新 Surface → 黑帧），
            // 现在留在本页换一颗 Modifier。选集点集号同样是本页换片源（不跳播放页路由），
            // 于是"页面是不是全屏"只由当前状态决定，不会再被一次跳转重置
            onEnterFullscreen = { fullscreen = true },
            onExitFullscreen = { fullscreen = false },
            fullscreen = fullscreen,
            // 全屏"选集"面板的数据源。点一集仍然走页面既有的出口（进播放页看这一集）
            episodes = episodes,
            onSelectEpisode = { episode ->
                // 选集面板是第三条入口，与轨道上点集号走同一道闸
                // （下标用 indexOf 反查：面板只回传 Episode，高亮要的是集数表下标）
                runOrAsk(
                    BlockedAction.PlayEpisode(
                        episode = episode,
                        index = episodes.indexOf(episode).takeIf { it >= 0 } ?: episode.index,
                    ),
                )
            },
            // 弹幕：窗口按「小屏真的在放的那一集」装（vid 见上面的 previewVid），
            // 位置由播放器自己的轮询驱动
            // 全屏「清晰度」面板：档位表/当前档/切档出口都从这里进播放器，
            // 与根播放页那套面板共用同一个 QualitySheet，不另写一份
            tiers = previewTiers,
            selectedTier = previewTier,
            onTierSelect = onSelectPreviewTier,
            danmaku = danmaku,
            previewVid = if (detail == null) "" else previewVid,
            onEnsureDanmaku = onEnsureDanmaku,
            // 弹幕开关要连「本剧覆盖」一起写（见 DetailPreviewPlayer.toggleDanmaku）：
            // 播放页读的是 override 优先，"小屏关了、大屏还开着"就是这么来的
            cid = detail?.cid.orEmpty(),
            // 蜂窝 + 提醒开启且地址还没装：这一格当封面用。
            // 三个附加条件缺一不可：已装地址/正在装/装失败时都必须让位给真实状态，
            // 否则错误面板会被一张封面盖住，用户点不到重试
            trafficHold = trafficHold && preview == null && !previewLoading && previewError == null,
            cover = detail?.cover.orEmpty(),
            onRequestStart = {
                runOrAsk(BlockedAction.StartPreview(resumeIndex.coerceAtLeast(0)))
            },
            consentVisible = consentVisible,
            onConsentAccept = { dontAskAgain ->
                // 勾了「不再提醒」才落盘，写的是设置页那颗 Switch 的同一个键
                if (dontAskAgain) onSetCellularPrompt(false)
                consentVisible = false
                pendingAction?.let { executeBlocked(it) }
                pendingAction = null
            },
            onConsentCancel = {
                // 取消 = 收起卡片、回到封面 + 播放键：不装地址、不消耗流量
                consentVisible = false
                pendingAction = null
            },
            modifier = Modifier
                .align(Alignment.TopStart)
                .zIndex(if (fullscreen) 2f else 1f)
                .then(
                    if (fullscreen) {
                        // 全屏**不让出任何一条**：状态栏已经在 DetailPreviewPlayer 里被藏掉
                        // （hide(systemBars)），再 pad 一遍 statusBarTop 就是画面顶上一条黑边。
                        // 画面、弹幕、手势、控制层都是这颗节点的子节点，一处生效就全部对齐
                        Modifier.fillMaxSize()
                    } else {
                        // 与上面那个 Spacer 同一条链、同一个顺序：高度必须严丝合缝。
                        // top 用**同一个 [statusBarTop]**（不是 statusBarsPadding）：
                        // 两处不一致就是播放器盖住内容、或画面顶进状态栏。
                        Modifier
                            .fillMaxWidth()
                            .padding(top = statusBarTop + VideoTopGap)
                    },
                ),
        )

        // 页面出口：**root Box 的最后一个子节点**——绘制顺序与命中顺序都在最上面，
        // 不靠 zIndex 赌运气（上一版压在播放器节点之前，实测被画面盖住了）。
        // 位置与 HistoryScreen 那条顶栏逐字一致：start 4dp、top 12dp（这里再加同一个 statusBarTop，
        // 因为本页不再吃 AppRoot 的 safeDrawingPadding，两页的绝对坐标因此相同）、
        // 48dp 触控、24dp 图标、同一个图标与 tint 与 contentDescription。
        // 任何情况下都可见可点：控制层收起不影响它、弹幕层在它下面、流量确认卡（播放器内 3f）
        // 也在它下面；全屏时整段不组合（全屏的出口是 PlayerTopBar 那颗 = 退出全屏）。
        if (!fullscreen) {
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .zIndex(6f)
                    .padding(start = 4.dp, top = statusBarTop + 12.dp)
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.m_action_back),
                    tint = ModaColors.Text,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

/**
 * 播放器下方的那一条：收藏（一颗心）+ 可展开的剧情简介。
 *
 * 收藏只剩图标：不再有「收藏 / 已收藏」那两个字的按钮，把宽度让给简介与选集。
 * 但**不能删**——详情页是收藏数据唯一的产生入口，删了收藏页永远是空的。
 * 实心=已收藏、描边=未收藏，形状本身就是状态，颜色只负责加重读感。
 *
 * 简介也不能删：播放器里没有剧情简介，而这是用户决定要不要看下去的依据。
 */
@Composable
private fun DetailActions(
    detailCid: String,
    description: String,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
) {
    // top = 4dp 是"补足"而不是"起点"：网格 item 之间本来就有 8dp 的 verticalArrangement
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        // 用 Box 而不是 IconButton：IconButton 会把图标居中在 48dp 里，
        // 心形就比下面的简介文字右缩进 12dp；这里让图标贴左，和简介同一条左边缘。
        // 触控区仍是 44dp（触摸纪律的下限），图标本身 24dp。
        Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onToggleFavorite),
            contentAlignment = Alignment.CenterStart,
        ) {
            Icon(
                imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                // 无障碍：图标没有文字，状态必须进描述（复用既有键，不新增文案）
                contentDescription = if (isFavorite) {
                    stringResource(R.string.detail_fav_remove_desc)
                } else {
                    stringResource(R.string.detail_fav_add_desc)
                },
                modifier = Modifier.size(24.dp),
                tint = if (isFavorite) FavoriteYellow else ModaColors.Text,
            )
        }

        if (description.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            DescriptionBlock(detailCid = detailCid, description = description)
        }
    }
}

/**
 * 可展开的简介。
 *
 * 判断「要不要给展开按钮」不能按字数拍脑袋：同样 80 个字，英文两行、中文四行。
 * 所以折叠时用 onTextLayout 的 hasVisualOverflow 实测有没有截断，
 * 截断了才出现按钮——否则会出现「点了展开什么也没变」的死按钮。
 */
@Composable
private fun DescriptionBlock(detailCid: String, description: String) {
    var expanded by remember(detailCid, description) { mutableStateOf(false) }
    var overflows by remember(detailCid, description) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = description,
            // 15sp → 16sp，行高放宽到 1.5 倍（24sp）：简介是连续长文，行距不够时
            // 整段会糊成一块灰砖——"太空"的另一半原因其实在这里，不只是字号
            style = ModaType.Body.copy(fontSize = 16.sp, lineHeight = 24.sp),
            color = ModaColors.TextDim,
            maxLines = if (expanded) Int.MAX_VALUE else DESC_COLLAPSED_LINES,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { result -> if (!expanded) overflows = result.hasVisualOverflow },
        )
        if (overflows || expanded) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(
                    text = if (expanded) {
                        stringResource(R.string.m_feed_desc_less)
                    } else {
                        stringResource(R.string.m_feed_desc_more)
                    },
                    style = ModaType.Caption,
                    color = ModaColors.AccentSoft,
                )
            }
        }
    }
}

/**
 * 选集网格的一格：16:9 缩略图 + 一行集号。
 *
 * 为什么从"只有数字"改成带图：纯数字格在几百集的连载里只能靠记集号找人，
 * 缩略图才是"我上次看到哪儿"的第一眼线索。代价是列数从 6 降到 3——
 * 360dp 屏上格宽约 104dp、图高约 58dp，集号刚好还读得清；再密就是"图看不清、字也挤"。
 *
 * 当前集**不整格填色**（填色会把缩略图压暗）：2dp 主色描边 + 集号文字变主色。
 * 角标：预告（左上）/ VIP（右上）/ 已看完绿勾（右上，压在 VIP 左侧，两者错开不互相盖）。
 * 分集描述不进格子（它会把格子撑高），仍放在「选集」标题行下方那一行。
 */
@Composable
private fun EpisodeCell(
    episode: Episode,
    selected: Boolean,
    completed: Boolean,
    isVip: Boolean,
    isTrailer: Boolean,
    /** 宽度由轨道反算后传进来（不用 fillMaxWidth：那样一屏就不是 3 个了） */
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(8.dp)),
        ) {
            // Episode.cover 已按 vid 拼好地址；取不到时 RemoteImage 自己落成文字色块
            RemoteImage(
                url = episode.cover,
                title = episode.label,
                modifier = Modifier.fillMaxSize(),
                corner = 8.dp,
            )
            if (isTrailer) {
                CellBadge(
                    text = stringResource(R.string.core_badge_trailer),
                    color = Color.White,
                    modifier = Modifier.align(Alignment.TopStart).padding(3.dp),
                )
            }
            if (isVip) {
                CellBadge(
                    text = "VIP",
                    color = VipGold,
                    modifier = Modifier.align(Alignment.TopEnd).padding(3.dp),
                )
            }
            if (completed) {
                // 错开 VIP：绿勾贴在「VIP」左边，两者都在右上角但不重叠
                CompletedBadge(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = 26.dp, top = 4.dp),
                )
            }
            // 当前集：描边画在图片之上才看得出来（放在最后 ⇒ 覆盖在缩略图上）
            if (selected) {
                Box(
                    Modifier
                        .matchParentSize()
                        .border(2.dp, ModaColors.Accent, RoundedCornerShape(8.dp)),
                )
            }
        }
        Text(
            text = stringResource(R.string.core_episode_index, episode.index + 1),
            style = ModaType.Caption,
            color = if (selected) ModaColors.AccentSoft else ModaColors.TextDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 3.dp, bottom = 1.dp),
        )
    }
}

/** 缩略图上的小角标：深色圆角底 + 一行字（压在亮图上才读得出） */
@Composable
private fun CellBadge(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = ModaType.Caption,
        fontSize = 9.sp,
        color = color,
        maxLines = 1,
        modifier = modifier
            .background(Color(0x99000000), RoundedCornerShape(4.dp))
            .padding(horizontal = 3.dp, vertical = 1.dp),
    )
}

/**
 * 「✅ 已看完」角标：一颗绿勾图标，画在格子右上角。
 *
 * 为什么是图标不是「看完」两个字：选集格子本来就窄，文字角标要么压住集号、
 * 要么把格子撑高；一颗 11dp 的勾扫一眼就认得出，而「看完」这两个字对这个状态是冗余的。
 * 它不是可点击元素，所以可以放心写死颜色——不会进到"选中反白底"的配色问题里。
 * 底色用 80% 不透明的近黑：压在蓝底选中卡上也还能读。
 */
@Composable
private fun CompletedBadge(modifier: Modifier = Modifier) {
    Box(
        modifier
            .background(Color(0xCC0B0B0F), CircleShape)
            .padding(1.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.CheckCircle,
            // 无障碍：图标没有文字，状态必须进描述
            contentDescription = stringResource(R.string.detail_completed_desc),
            tint = CompletedGreen,
            modifier = Modifier.size(11.dp),
        )
    }
}
