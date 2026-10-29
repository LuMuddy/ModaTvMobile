package com.moda.mobile.ui

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import com.moda.mobile.model.AppError
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

/** 「看完」角标的前景色：深底上的柔绿，与 VIP 的金、预告的靛蓝互不混淆 */
private val CompletedGreen = Color(0xFF7BD88F)

/** 已收藏时爱心的颜色。选黄不选强调蓝：蓝色在本应用里已经等于「当前选中 / 可操作」 */
private val FavoriteYellow = Color(0xFFFFC53D)

/**
 * 简介折叠时的行数。手机上页头已经很满，简介默认只占三行，
 * 想看全的点「展开」——比默认铺开五六行把选集顶到屏幕外要好。
 */
private const val DESC_COLLAPSED_LINES = 3

/** 选集轨道一屏露几个：3 个是用户对着截图定的，格子宽度全部由它反算，不写死 dp */
private const val EPISODES_PER_SCREEN = 3

/** VIP 角标的金：选集缩略图右上角那颗「VIP」用它 */
private val VipGold = Color(0xFFE0A32E)

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
    onPlay: (Episode) -> Unit,
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
    /** 离开页面清掉；引擎与投屏由 [DetailPreviewPlayer] 自己的 onDispose 收尾 */
    onClearPreview: () -> Unit = {},
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
     * 小屏 = "这一页现在在放哪一集"。用户明确要求点集号不切小屏，所以两者本来就可能不同。
     * address 还没到时先按续播集显示，标题行才不至于写"第 1 集"再跳一次。
     */
    val previewIndex = preview?.episodeIndex ?: resumeIndex.coerceAtLeast(0)

    val loadPreviewNow by rememberUpdatedState(onLoadPreview)
    val clearPreviewNow by rememberUpdatedState(onClearPreview)

    // 进页面装一次地址。必须等历史落定（[DetailResume.loaded]）再决定装哪一集：
    // 先装第 1 集、等历史到了再换第 5 集，小屏会先起播一句再从头缓冲一次。
    var previewRequested by remember(detail?.cid) { mutableStateOf(false) }
    LaunchedEffect(detail?.cid, resume?.loaded, resumeIndex, episodes.size) {
        if (previewRequested || episodes.isEmpty()) return@LaunchedEffect
        if (resume != null && !resume.loaded) return@LaunchedEffect
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

    Box(Modifier.fillMaxSize().background(ModaColors.Bg)) {
        // 非全屏 = 占位 + 可滚动的内容；全屏时整段不组合，触摸也就不会在画面底下抢
        if (!fullscreen) {
            Column(Modifier.fillMaxSize()) {
                // 播放器占位：**与下面播放器那条 Modifier 链逐字对应**
                // （fillMaxWidth → 左右 16dp → 16:9）。数值与顺序不许各自为政，
                // 否则两边算出的高度差一截，就是"一条空洞"或者"播放器盖住标题"。
                Spacer(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
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
                                    style = ModaType.Heading,
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
                                                    // 用户已经自己选了：钉住高亮，别让迟到的历史顶掉
                                                    userTouched = true
                                                    currentIndex = index
                                                    onPlay(ep)
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
        // 它在**所有**数据状态下都在组合里（loading / 失败 / 空态也在）：
        // 非全屏时这颗卡片左上角的返回是这一页唯一的返回出口，漏掉任何一态都会让那一屏
        // 只剩系统返回手势。
        // 非全屏 = 16:9 卡片（高度与上面那个 Spacer 严丝合缝）；
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
            onBack = onBack,
            // 全屏按钮 = 只改布局。以前这里 push 播放页路由（→ 新 Surface → 黑帧），
            // 现在留在本页换一颗 Modifier；播放页路由只留给选集里"去大屏看这一集"
            onEnterFullscreen = { fullscreen = true },
            onExitFullscreen = { fullscreen = false },
            fullscreen = fullscreen,
            // 全屏"选集"面板的数据源。点一集仍然走页面既有的出口（进播放页看这一集）
            episodes = episodes,
            onSelectEpisode = { episode ->
                userTouched = true
                onPlay(episode)
            },
            modifier = Modifier
                .align(Alignment.TopStart)
                .zIndex(if (fullscreen) 2f else 1f)
                .then(
                    if (fullscreen) {
                        Modifier.fillMaxSize()
                    } else {
                        // 与上面那个 Spacer 同一条链、同一个顺序：高度必须严丝合缝
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    },
                ),
        )
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
            style = ModaType.Body,
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
