package com.moda.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moda.mobile.R
import com.moda.mobile.model.AppError
import com.moda.mobile.model.Episode
import com.moda.mobile.model.SeriesDetail
import com.moda.mobile.model.Tier
import com.moda.mobile.ui.components.ActionButton
import com.moda.mobile.ui.components.EmptyState
import com.moda.mobile.ui.components.ErrorState
import com.moda.mobile.ui.components.LoadingState
import com.moda.mobile.ui.components.RemoteImage
import com.moda.mobile.ui.components.SectionTitle
import com.moda.mobile.ui.components.appErrorText
import com.moda.mobile.ui.detail.DetailPreviewPlayer
import com.moda.mobile.ui.theme.ModaColors
import com.moda.mobile.ui.theme.ModaType

/** 「看完」角标的前景色：深底上的柔绿，与 VIP 的金、预告的靛蓝互不混淆 */
private val CompletedGreen = Color(0xFF7BD88F)

/** 已收藏时爱心的颜色。选黄不选强调蓝：蓝色在本应用里已经等于「当前选中 / 可操作」 */
private val FavoriteYellow = Color(0xFFFFC53D)

/**
 * 简介折叠时的行数。手机上页头已经很满，简介默认只占三行，
 * 想看全的点「展开」——比默认铺开五六行把选集顶到屏幕外要好。
 */
private const val DESC_COLLAPSED_LINES = 3

/**
 * 选集格子在整个网格里的起始下标。
 *
 * 值 = 它前面那几个满宽 item 的个数，逐项对齐现在的 item 顺序：
 *   0 播放器 [player] / 1 片名 [title] / 2 收藏+简介 [actions] / 3 选集标题 [episodes-title]
 * 1 号那项在片名为空时不组合，所以真正用的是按同一条件推算出来的值（见 episodeItemStart）：
 * 只改这边不改那边，续播滚动会落在隔壁一集上。
 */
private const val EPISODE_ITEM_START = 4

/**
 * 「不滚也看得见」的格子数上限：续播集落在这个范围内就不滚。
 *
 * 3 列之后行高约 90dp（16:9 缩略图 ≈ 58dp + 一行集号），首屏在 640~800dp 高的机器上
 * 大约看到 2.5~4 行，取 3 行 = 9 格。**宁可估小**：估大的代价是"为了一个已经在屏幕上的格子
 * 把播放器顶出去"，估小的代价只是用户往下滑一下。
 */
private const val EPISODE_VISIBLE_WITHOUT_SCROLL = 9

/** VIP 角标的金：选集缩略图右上角那颗「VIP」用它 */
private val VipGold = Color(0xFFE0A32E)

/**
 * 剧集详情：顶部小屏播放器 + 海报/标题/收藏/可展开简介 + 多行选集网格。
 *
 * 顶部那块**真的能播**（[DetailPreviewPlayer]）：进页面就播续播集（没有历史就第 1 集），
 * 可播放/暂停、纵滑调亮度与音量、投屏、进全屏。它就是「两种播放形态」里的小屏形态，
 * 与全屏播放页各自有一条投屏入口。点集号**不**切小屏的片源——那只意味着"去全屏看这一集"。
 *
 * 与 TV 端的结构差别只有一处，但它是本质的：**选集从单行横轨改成多行网格**。
 * 手机上没有遥控器的左右键，横轨末端的集数要么靠连续滑动才够得着，
 * 要么用户根本不知道后面还有；网格上下滑一次能看过几十集。
 * 因此整页就是一个 LazyVerticalGrid（**6 列**），小屏播放器、页头与区块标题各用满宽 span 占一行：
 * 页头走得跟普通列表一样，而选集区落成 6 列密集排法（每格 40dp、只写集号），
 * 一屏能扫到五行以上集号。不用「LazyColumn 套 LazyVerticalGrid」，
 * 同方向嵌套滚动在触摸端会抢手势——这也是选集没法做成独立小网格的原因。
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

    /**
     * 选集格子的真实起始下标。
     *
     * [EPISODE_ITEM_START] 是按"播放器 / 片名 / 收藏+简介 / 选集标题"四个满宽 item 数的；
     * 片名为空时那个 item 不组合，这里必须跟着减 1——差一格就会把续播滚到隔壁那一集，
     * 也就是"滚了，但停在错的地方"这种最难查的表现。
     */
    val episodeItemStart = EPISODE_ITEM_START - if (detail?.title.isNullOrBlank() == true) 1 else 0

    val gridState = rememberLazyGridState()
    // 进入页面直接落到续播那一集：几百集的连载不滚的话，用户得自己往下滑几十屏才找得到自己看到哪。
    // 但续播集本来就在首屏里时不滚：为了一个已经看得见的格子把播放器顶出屏幕，
    // 是把最该看的东西换一个没有收益的位移（阈值见 EPISODE_VISIBLE_WITHOUT_SCROLL）。
    // 只给下标、不给像素偏移：靠近首尾时 scrollToItem 自己贴边，写死偏移反而会在两端越界。
    // 下标夹取到 [0, 末集]：集数表换了/历史对不上时 resumeIndex 可能落在范围外，不夹会直接崩。
    LaunchedEffect(detail?.cid, resume?.loaded, resumeIndex, episodeItemStart) {
        if (showEpisodes && resumeIndex >= EPISODE_VISIBLE_WITHOUT_SCROLL && !userTouched) {
            gridState.scrollToItem(
                (episodeItemStart + resumeIndex).coerceIn(0, episodeItemStart + episodes.lastIndex),
            )
        }
    }

    // 预告角标的判据来自上游分类词：它们是数据（片名/标签的一部分），不是界面文案，
    // 但代码里不许出现中文字面量（契约 §1），所以词组放 string 资源里按 '|' 切开用
    val trailerKeys = stringResource(R.string.m_feed_trailer_keywords).split('|')
    fun isTrailer(ep: Episode) = trailerKeys.any { it.isNotBlank() && ep.label.contains(it) }

    Column(Modifier.fillMaxSize().background(ModaColors.Bg)) {
        // 有内容时返回按钮在顶部小屏播放器的左上角（见 [DetailPreviewPlayer]）。
        // 加载中 / 失败 / 空态根本没有那块区域，返回条必须自己补上——
        // 否则那三种状态里屏幕上没有任何出口，只剩系统返回手势一条路。
        if (detail == null) {
            Row(
                Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.search_back),
                        tint = ModaColors.Text,
                    )
                }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                // 拿不到详情时也要留得下出口：有 onRetry 就重试，没有就返回上一页
                detail == null && error != null -> ErrorState(
                    message = appErrorText(error),
                    onRetry = onRetry ?: onBack,
                )
                detail == null && loading -> LoadingState(stringResource(R.string.detail_loading))
                detail == null -> EmptyState(
                    message = stringResource(R.string.detail_empty),
                    onRetry = onRetry ?: onBack,
                )
                else -> LazyVerticalGrid(
                    // 3 列：列数是**选集区**的排法，不是整页的排版（其余都是满宽 span）。
                    // 每格 = 16:9 缩略图 + 一行集号：360dp 屏上格宽约 104dp、图高约 58dp，
                    // 411dp 屏上约 125dp 宽。再密（4 列以上）缩略图就只剩马赛克，集号也挤不出。
                    columns = GridCells.Fixed(3),
                    state = gridState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // 顶部就是这一页的第一个满宽 item：进详情页先看到画面，而不是一张剧照
                    item(span = { GridItemSpan(maxLineSpan) }, key = "player") {
                        DetailPreviewPlayer(
                            title = detail.title.ifBlank { stringResource(R.string.detail_untitled) },
                            preview = preview,
                            loading = previewLoading,
                            error = previewError,
                            // -1 = 这部剧没有集数：播放器据此不写"第 N 集"
                            episodeIndex = if (episodes.isEmpty()) -1 else previewIndex,
                            // 重试就是让 VM 重装这一集的地址（不新造网络路径）
                            onRetry = { loadPreviewNow(previewIndex) },
                            onBack = onBack,
                            // 全屏播"小屏正在播的这一集"：点这颗键的意图是"放大看清"，不是换集。
                            // 没有集数时退回第一集；一集都没有就什么也不做（屏幕上已有空态说明）
                            onEnterFullscreen = {
                                val target = episodes.getOrNull(previewIndex) ?: episodes.firstOrNull()
                                if (target != null) {
                                    userTouched = true
                                    onPlay(target)
                                }
                            },
                        )
                    }

                    // 片名从播放器的覆盖层挪到这里：覆盖层收起时那行字就没了，而"我在看什么剧"
                    // 是这一页最该一直可读的信息。不带集号——选集格子里写着集数，重复没有意义。
                    // 片名为空（异常数据）时整个 item 不组合，不留一条空行。
                    if (detail.title.isNotBlank()) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "title") {
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
                    item(span = { GridItemSpan(maxLineSpan) }, key = "actions") {
                        DetailActions(
                            detailCid = detail.cid,
                            description = description,
                            isFavorite = isFavorite,
                            onToggleFavorite = onToggleFavorite,
                        )
                    }

                    if (episodes.isEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "no-episodes") {
                            Text(
                                text = stringResource(R.string.detail_no_episodes),
                                style = ModaType.Body,
                                color = ModaColors.Danger,
                                modifier = Modifier.padding(top = 16.dp),
                            )
                        }
                    } else if (showEpisodes) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "episodes-title") {
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

                    if (showEpisodes) {
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
                                onClick = {
                                    // 用户已经自己选了：钉住高亮，别让迟到的历史顶掉
                                    userTouched = true
                                    currentIndex = index
                                    onPlay(ep)
                                },
                            )
                        }
                    }

                    // 已有内容时的刷新/失败只在底部提示，不盖住整页：
                    // 用户看到的仍然是能用的那一屏
                    if (loading || error != null) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "footer") {
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
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
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
                preferHiRes = false,
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
