package com.moda.mobile.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.material3.OutlinedButton
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
import com.moda.mobile.ui.components.Badge
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
 * 选集格子的高度。
 *
 * 40dp 是密集排法的上限附近：一屏能扫到五行以上集号才叫「能挑集」，
 * 一旦回到 44dp 以上、或者让分集描述进到格子里，一屏就只剩三四行。
 * 格子高度写成常量而不是靠内容撑开，是为了让它不受角标/描述影响——行高恒定，6 列才不会歪。
 */
private val EpisodeCellHeight = 40.dp

/** 选集格子在整个网格里的起始下标：播放器外观区、页头、区块标题各占一个满宽 item */
private const val EPISODE_ITEM_START = 3

/**
 * 「不滚也看得见」的集数上限：续播集落在这个范围内就不滚。
 *
 * 12 是按当前布局估出来的近似值，不是量出来的：页头（海报 + 标题 + 主按钮 + 折叠简介）
 * 大约吃掉首屏的六成，剩下的空间按每行 6 格、行高 40dp + 8dp 间距算，还放得下两行。
 * 布局大改（页头变矮、格子变高、列数变了）时这个数要跟着调——
 * 它的唯一职责是「续播集已经在一屏里就别动滚动位置」，估小了会把页头白顶出去。
 */
private const val EPISODE_VISIBLE_WITHOUT_SCROLL = 12

/** 预告角标的靛蓝，与 components/Cards.kt 里 EpisodeChip 用的是同一个色值 */
private val TrailerIndigo = Color(0xFF5C6BC0)

/** VIP 角标的金，与页头 VIP 标同色 */
private val VipGold = Color(0xFFE0A32E)

/**
 * 断点时间格式化：不足 1 小时 mm:ss（补零），≥1 小时 h:mm:ss（小时不补零），0 返回空串。
 *
 * 为什么 0 返回空串而不是 "00:00"：调用方用空串表示「这集没有断点，别显示时间」，
 * 显示 "00:00" 会被读成「看到了开头」，和「没看过」是两件事。
 */
private fun formatProgress(ms: Long): String {
    if (ms <= 0L) return ""
    val totalSeconds = ms / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    fun pad(v: Long) = if (v < 10L) "0$v" else "$v"
    return if (hours > 0L) "$hours:${pad(minutes)}:${pad(seconds)}" else "${pad(minutes)}:${pad(seconds)}"
}

/**
 * 剧集详情：竖版海报头图 + 标题/元信息 + 主按钮 + 可展开的简介 + 多行选集网格。
 *
 * 与 TV 端的结构差别只有一处，但它是本质的：**选集从单行横轨改成多行网格**。
 * 手机上没有遥控器的左右键，横轨末端的集数要么靠连续滑动才够得着，
 * 要么用户根本不知道后面还有；网格上下滑一次能看过几十集。
 * 因此整页就是一个 LazyVerticalGrid（**6 列**），顶部播放器外观区、页头与区块标题各用满宽 span 占一行：
 * 页头走得跟普通列表一样，而选集区落成 6 列密集排法（每格 40dp、只写集号），
 * 一屏能扫到五行以上集号。不用「LazyColumn 套 LazyVerticalGrid」，
 * 同方向嵌套滚动在触摸端会抢手势——这也是选集没法做成独立小网格的原因。
 *
 * [onTierSelect] / [selectedTier] 是跨任务签名的一部分（契约 §5）。
 * 详情页仍然**不摆档位选择器**：切清晰度只发生在播放页的底部面板里，
 * 摆一排档位会在进播放器前就让人以为"这里选错了"，而它并不决定起播档位。
 * 但 [selectedTier] 有值时会把档位名写进顶部「播放器外观」区的左下角——
 * 那是「你现在看的是这一档」的陈述，不是一个可点的选项。
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
    /** 历史落点：这部剧看过的话，主按钮写成「继续播放 mm:ss」、选集高亮那一格 */
    resume: DetailResume? = null,
    /** 这部剧已看完的集号集合，用于选集卡右上角的绿色勾标 */
    completedIndexes: Set<Int> = emptySet(),
    onToggleFavorite: () -> Unit = {},
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

    // 「继续播放 mm:ss」的判定必须**只认 vid 命中**，不能退回下标：
    // positionMs 与 vid/episodeIndex 出自同一条历史记录，只对那一集有意义。
    // 按下标匹配的话，集数表重排后 A 集的断点会被画到 B 集上，
    // 用户点下去 resumePosition 又不会真的跳过去——表现就是「每集都显示同一串时间，点了却不续播」。
    val continueIndex = watchedByVid
    val continueEpisode = episodes.getOrNull(continueIndex)
    // 该集已经看完时不显示断点：看完的集从"还剩多少"这个语义上已经没有续播意义
    val continueMs = if (continueEpisode != null && continueIndex !in completedIndexes) {
        watched?.positionMs ?: 0L
    } else {
        0L
    }
    val continueProgress = formatProgress(continueMs)
    val hasResume = continueEpisode != null && continueProgress.isNotEmpty()

    // 用户自己点过某一集之后，迟到的历史不许再把高亮挪走
    var userTouched by remember(detail?.cid) { mutableStateOf(false) }
    var currentIndex by remember(detail?.cid) { mutableIntStateOf(resumeIndex.coerceAtLeast(0)) }
    LaunchedEffect(detail?.cid, resume?.loaded, resumeIndex) {
        if (!userTouched && resume?.loaded == true && resumeIndex >= 0) currentIndex = resumeIndex
    }

    /** 网格上方的副标题、页头 VIP 标都只看「当前这一集」 */
    val currentEpisode = episodes.getOrNull(currentIndex)

    val gridState = rememberLazyGridState()
    // 进入页面直接落到续播那一集：几百集的连载不滚的话，用户得自己往下滑几十屏才找得到自己看到哪。
    // 但续播集本来就在首屏里时不滚：为了一个已经看得见的格子把页头（海报、「继续播放」主按钮）
    // 顶出屏幕，是把主操作藏起来换一个没有收益的位移。
    // 只给下标、不给像素偏移：靠近首尾时 scrollToItem 自己贴边，写死偏移反而会在两端越界。
    // 下标夹取到 [0, 末集]：集数表换了/历史对不上时 resumeIndex 可能落在范围外，不夹会直接崩。
    LaunchedEffect(detail?.cid, resume?.loaded, resumeIndex) {
        if (showEpisodes && resumeIndex >= EPISODE_VISIBLE_WITHOUT_SCROLL && !userTouched) {
            gridState.scrollToItem(
                (EPISODE_ITEM_START + resumeIndex)
                    .coerceIn(0, EPISODE_ITEM_START + episodes.lastIndex),
            )
        }
    }

    // 预告角标的判据来自上游分类词：它们是数据（片名/标签的一部分），不是界面文案，
    // 但代码里不许出现中文字面量（契约 §1），所以词组放 string 资源里按 '|' 切开用
    val trailerKeys = stringResource(R.string.m_feed_trailer_keywords).split('|')
    fun isTrailer(ep: Episode) = trailerKeys.any { it.isNotBlank() && ep.label.contains(it) }

    Column(Modifier.fillMaxSize().background(ModaColors.Bg)) {
        // 有内容时返回按钮在顶部「播放器外观」区的左上角（见 [DetailPlayerHero]）。
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
                    // 6 列：选集区就是这张网格（播放器外观区/页头/标题/底部提示都用满宽 span），
                    // 所以列数是选集区的排法，不是整页的排版
                    columns = GridCells.Fixed(6),
                    state = gridState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                    // 格间距 8dp：360dp 宽的屏上每格约 48dp，再大就挤不进 6 列
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "player") {
                        DetailPlayerHero(
                            detail = detail,
                            currentEpisode = currentEpisode,
                            tierLabel = selectedTier?.let { tierNameText(it.display) }.orEmpty(),
                            playable = episodes.isNotEmpty(),
                            onBack = onBack,
                            // 有断点从断点那一集起播，没断点从第 1 集起播，与页头主按钮同一条规则。
                            // 不自己传 positionMs：断点由 VM 在 push 时查历史得出
                            onPlay = {
                                val target = episodes.getOrNull(continueIndex) ?: episodes.firstOrNull()
                                if (target != null) {
                                    userTouched = true
                                    onPlay(target)
                                }
                            },
                        )
                    }

                    item(span = { GridItemSpan(maxLineSpan) }, key = "header") {
                        DetailHeader(
                            detail = detail,
                            description = description,
                            episodes = episodes,
                            currentEpisode = currentEpisode,
                            hasResume = hasResume,
                            continueProgress = continueProgress,
                            continueIndex = continueIndex,
                            isFavorite = isFavorite,
                            onPlay = { ep ->
                                userTouched = true
                                onPlay(ep)
                            },
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
                                number = ep.index + 1,
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
 * 顶部「播放器外观」区：16:9 横版剧照 + 一层自下而上的深色渐变，**不内嵌播放**。
 *
 * 为什么不做内嵌预览：在详情页里放一个真的播放器，控制层（进度 / 清晰度 / 弹幕）
 * 要么整份复制一遍、要么一个都不给，两条路都不划算；而用户点这块区域的意图从来只有一个——
 * 进全屏播放。所以这里只是一张「播放器长什么样」的壳：中央一颗播放键、左下角这一屏在播什么，
 * 点哪儿都走同一个 [onPlay] 进既有的播放页。
 *
 * [tierLabel] 为空表示用户还没选过档位：那一截整段不组合，不留「· 」这种空尾巴。
 */
@Composable
private fun DetailPlayerHero(
    detail: SeriesDetail,
    currentEpisode: Episode?,
    tierLabel: String,
    /** 没有可播的集时不画中间那颗键：点不动的按钮比没有按钮更像是坏了 */
    playable: Boolean,
    onBack: () -> Unit,
    onPlay: () -> Unit,
) {
    // 横版剧照：detail.cover（eps 的 meta.pic 原图）优先；为空时按 vid 拼腾讯图床的横版剧照，
    // 与 VideoItem.wideCover 是同一条地址规律。SeriesDetail 上没有竖版海报字段，
    // 两条都取不到时 RemoteImage 自己落成文字色块——这一块永远不会是纯黑。
    val heroCover = detail.cover.ifBlank {
        currentEpisode?.cover?.takeIf { it.isNotBlank() }
            ?: detail.episodes.firstOrNull()?.cover.orEmpty()
    }
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(14.dp))
            .background(ModaColors.SurfaceHigh)
            // 整块都是进播放页的入口；左上角的返回按钮自己吃掉点击，不会顺带起播
            .clickable(enabled = playable, onClick = onPlay),
    ) {
        RemoteImage(
            url = heroCover,
            title = detail.title,
            preferHiRes = true,
            modifier = Modifier.fillMaxSize(),
            corner = 0.dp,
        )
        // 渐变兜住可读性：剧照下半部分常常是亮的，白字直接压上去会糊成一片
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to Color.Transparent,
                        0.45f to Color(0x59000000),
                        1.0f to Color(0xE6000000),
                    ),
                ),
        )
        Box(
            Modifier
                .align(Alignment.TopStart)
                .padding(6.dp)
                .background(Color(0x59000000), CircleShape),
        ) {
            // IconButton 默认 48dp，触控区已经过 44dp 线；这里只给图标垫一层半透明圆底，
            // 免得返回箭头落在浅色剧照上读不出来
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.search_back),
                    tint = Color.White,
                )
            }
        }
        if (playable) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(64.dp)
                    .background(Color(0x73000000), CircleShape)
                    .clickable(onClick = onPlay),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = stringResource(R.string.detail_play),
                    tint = Color.White,
                    modifier = Modifier.size(38.dp),
                )
            }
        }
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
        ) {
            Text(
                text = detail.title.ifBlank { stringResource(R.string.detail_untitled) },
                style = ModaType.Heading,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val episodeLabel = currentEpisode
                ?.let { stringResource(R.string.core_episode_index, it.index + 1) }
                .orEmpty()
            val meta = listOf(episodeLabel, tierLabel).filter { it.isNotBlank() }.joinToString(" · ")
            if (meta.isNotEmpty()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = meta,
                    style = ModaType.Caption,
                    color = Color.White.copy(alpha = 0.86f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 页头：海报 + 标题/元信息 + 主按钮 + 收藏 + 可展开的简介。
 *
 * 主按钮紧跟标题，是进详情页后第一个想按的东西；只有一集时它是整页唯一的播放入口
 * （下面不会再有选集段）。有断点时文案变成「继续播放 mm:ss」，与 TV 端同一条规则。
 */
@Composable
private fun DetailHeader(
    detail: SeriesDetail,
    description: String,
    episodes: List<Episode>,
    currentEpisode: Episode?,
    hasResume: Boolean,
    continueProgress: String,
    continueIndex: Int,
    isFavorite: Boolean,
    onPlay: (Episode) -> Unit,
    onToggleFavorite: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(Modifier.fillMaxWidth()) {
            RemoteImage(
                url = detail.cover,
                title = detail.title,
                // 头图就是这一页最大的那张图，值得走一次 eps 原图（见 RemoteImage 的取舍说明）
                preferHiRes = true,
                modifier = Modifier.width(112.dp).aspectRatio(3f / 4f),
                corner = 12.dp,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        text = detail.title.ifBlank { stringResource(R.string.detail_untitled) },
                        style = ModaType.Title,
                        color = ModaColors.Text,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (currentEpisode?.isVip == true) {
                        Spacer(Modifier.width(8.dp))
                        Badge("VIP", color = VipGold)
                    }
                }
                Spacer(Modifier.height(8.dp))
                // 元信息：集数 · 分类 · 主演。三段都可能有缺失，逐段判空再拼，
                // 不留下「 · · 」这种空分隔
                val metaParts = ArrayList<String>(3)
                if (episodes.isNotEmpty()) {
                    metaParts.add(stringResource(R.string.detail_episode_count, episodes.size))
                }
                if (detail.category.isNotBlank()) metaParts.add(detail.category)
                if (detail.actors.isNotEmpty()) metaParts.add(detail.actors.joinToString(" / "))
                if (metaParts.isNotEmpty()) {
                    Text(
                        text = metaParts.joinToString(" · "),
                        style = ModaType.Caption,
                        color = ModaColors.TextDim,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (episodes.isNotEmpty()) {
                Button(
                    onClick = {
                        // 有断点从断点那一集起播，没断点从第 1 集起播。
                        // 不自己传 positionMs：断点由 VM 的 resumePosition 在 push 时查历史得出，
                        // 这里塞一个快照反而会和真实历史打架
                        val target = episodes.getOrNull(continueIndex) ?: episodes.first()
                        onPlay(target)
                    },
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ModaColors.Accent,
                        contentColor = Color.White,
                    ),
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                        tint = Color.White,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (hasResume) {
                            stringResource(R.string.detail_continue_play, continueProgress)
                        } else {
                            stringResource(R.string.detail_play)
                        },
                        style = ModaType.Heading,
                        color = Color.White,
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.width(10.dp))
            }
            // 收藏是这个数据唯一的产生入口：没有它收藏页永远是空的。
            // 返回交给屏幕上方的返回条与系统返回手势，这里不再摆第二个返回按钮
            OutlinedButton(
                onClick = onToggleFavorite,
                modifier = Modifier.height(48.dp),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, if (isFavorite) FavoriteYellow else ModaColors.SurfaceHigh),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = if (isFavorite) FavoriteYellow else ModaColors.Text,
                ),
            ) {
                Icon(
                    imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                    contentDescription = if (isFavorite) {
                        stringResource(R.string.detail_fav_remove_desc)
                    } else {
                        stringResource(R.string.detail_fav_add_desc)
                    },
                    modifier = Modifier.size(18.dp),
                    tint = if (isFavorite) FavoriteYellow else ModaColors.Text,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (isFavorite) {
                        stringResource(R.string.m_feed_fav_remove)
                    } else {
                        stringResource(R.string.m_feed_fav_add)
                    },
                    style = ModaType.Body,
                    color = if (isFavorite) FavoriteYellow else ModaColors.Text,
                    maxLines = 1,
                )
            }
        }

        if (description.isNotBlank()) {
            Spacer(Modifier.height(14.dp))
            DescriptionBlock(detailCid = detail.cid, description = description)
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
 * 选集网格的一格：只有集号，四种状态全部画成角标。
 *
 * 为什么不再用 components 里的 [com.moda.mobile.ui.components.EpisodeChip]：
 * 那个组件把分集描述按两行画在格子里，格高随内容变（综艺能到 60dp 以上），
 * 6 列网格上一屏就只剩三行集号——这正是用户截图里嫌浪费的那件事。
 * 密集排法里一格的职责只有两件：看得见集号、点得到。描述交给网格上方那一行。
 *
 * 四个角各管一件事，互不打架，也都不参与排版（格子高度是常量）：
 * 左上「预告」（分类）、右上绿勾（已看完）、右下 VIP、整格主色底（当前集）。
 */
@Composable
private fun EpisodeCell(
    number: Int,
    selected: Boolean,
    completed: Boolean,
    isVip: Boolean,
    isTrailer: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(EpisodeCellHeight)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) ModaColors.Accent else ModaColors.SurfaceHigh)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            // 格子里只放数字：48dp 宽的格子塞不下「第 128 集」，而集号本身就是这一格的唯一信息
            text = number.toString(),
            style = ModaType.Body,
            color = Color.White,
            maxLines = 1,
        )
        if (isTrailer) {
            Text(
                text = stringResource(R.string.core_badge_trailer),
                style = ModaType.Caption,
                fontSize = 9.sp,
                // 选中格是主色底：靛蓝压蓝底读不出来，这时候让位给白字
                color = if (selected) Color.White else TrailerIndigo,
                maxLines = 1,
                modifier = Modifier.align(Alignment.TopStart).padding(start = 3.dp, top = 1.dp),
            )
        }
        if (completed) {
            CompletedBadge(Modifier.align(Alignment.TopEnd).padding(1.dp))
        }
        if (isVip) {
            Text(
                text = "VIP",
                style = ModaType.Caption,
                fontSize = 9.sp,
                color = VipGold,
                maxLines = 1,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 3.dp, bottom = 1.dp),
            )
        }
    }
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
