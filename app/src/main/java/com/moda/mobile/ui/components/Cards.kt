package com.moda.mobile.ui.components

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.moda.mobile.R
import com.moda.mobile.model.LibraryEntry
import com.moda.mobile.model.VideoItem
import com.moda.mobile.ui.theme.ModaColors
import com.moda.mobile.ui.theme.ModaType
import java.util.Locale

/**
 * 手机端的点击面：一律 [Modifier.clickable] + 显式 indication（水波纹）。
 *
 * 这是契约 §6 的要求，也是与 TV 端最大的差别：那边每张卡都要自己画焦点框、
 * 自绘放大与描边，手机端一个 clickable 就同时给了"按下有反馈"和"读屏能激活"。
 * 形状先 clip 再 background 再 clickable：水波纹会被裁在圆角之内。
 */
@Composable
private fun Modifier.tapCard(
    corner: androidx.compose.ui.unit.Dp,
    background: Color,
    onClick: () -> Unit,
): Modifier = this
    .clip(RoundedCornerShape(corner))
    .background(background)
    .clickable(interactionSource = null, indication = LocalIndication.current, onClick = onClick)

/**
 * 网格卡片：竖版海报（3:4）+ 标题（+ 可选的副标题）。
 *
 * 手机竖屏 2 列，宽度交给网格算；[width] 保留成可选参数只为兼容
 * TV 端同名组件的调用形态（传 null 表示跟网格列宽走），手机端所有调用点都传 null。
 */
@Composable
fun VideoCard(
    item: VideoItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: androidx.compose.ui.unit.Dp? = null,
    titleMaxLines: Int = 2,
    showSubtitle: Boolean = true,
) {
    val corner = 12.dp
    val sized = if (width == null) modifier else modifier.width(width)
    Column(sized.tapCard(corner, ModaColors.Surface, onClick)) {
        Box(Modifier.fillMaxWidth()) {
            RemoteImage(
                url = item.cover,
                title = item.title,
                cid = item.cid,
                modifier = Modifier.fillMaxWidth().aspectRatio(3f / 4f),
                corner = 0.dp,
            )
            // 角标竖排：分类在上，榜位/评分在下。两个都压在右上角同一个角上，
            // 分开 align 会互相盖住，所以它们必须共处一个 Column。
            Column(
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                horizontalAlignment = Alignment.End,
            ) {
                // 分类：由上游的类型码取资源（1 电影 / 2 电视剧 / 3 动漫 …），
                // 复用 tab_* 那组文案——它们是同一个词，重复定义只会多一份要同步的翻译
                val categoryRes = categoryRes(item.categoryType)
                val category = if (categoryRes == 0) "" else stringResource(categoryRes)
                if (category.isNotBlank()) {
                    Badge(text = category)
                }
                // 榜位 / 评分：上游原文（"动作电影榜第1名" / "9.8分"），属于数据不是界面文案。
                // 用中性深色而不是主题强调色：它是补充信息，不该跟分类抢同一种视觉权重
                if (item.badge.isNotBlank()) {
                    if (category.isNotBlank()) Spacer(Modifier.height(4.dp))
                    Badge(text = item.badge, color = Color(0xA6000000))
                }
            }
        }
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(
                text = item.title,
                style = ModaType.Body,
                color = ModaColors.Text,
                maxLines = titleMaxLines,
                overflow = TextOverflow.Ellipsis,
            )
            if (showSubtitle && item.subtitle.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = item.subtitle,
                    style = ModaType.Caption,
                    color = ModaColors.TextDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 上游内容类型码 → 分类文案资源。0 = 未知（老缓存 / 后端条目），UI 侧不画角标。
 *
 * 码值是 v.qq.com 卡片里的 categoryType 实测取值（HtmlCards 解析）。
 * 文案复用 tab_*：分类角标说的就是这几个词，另开一组只会多一份要同步的翻译。
 */
private fun categoryRes(categoryType: Int): Int = when (categoryType) {
    1 -> R.string.tab_movie
    2 -> R.string.tab_tv
    3 -> R.string.tab_cartoon
    4 -> R.string.tab_sports
    9 -> R.string.tab_doco
    10 -> R.string.tab_variety
    29 -> R.string.tab_auto
    106 -> R.string.tab_child
    111 -> R.string.tab_art
    else -> 0
}

/**
 * 首页「继续观看」卡片：海报（下沿叠进度条）+ 剧名 + 集号/进度。
 *
 * 与 VideoCard 不合并：这张卡不画 badge、不画副标题，高度只有海报 + 两行字，
 * 一屏能多露一条；而且它的点击语义是"直接续播"，与"进详情"是两件事。
 */
@Composable
fun ContinueWatchingCard(
    entry: LibraryEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: androidx.compose.ui.unit.Dp? = null,
) {
    val corner = 12.dp
    val sized = if (width == null) modifier else modifier.width(width)
    Column(sized.tapCard(corner, ModaColors.Surface, onClick)) {
        Box(Modifier.fillMaxWidth()) {
            RemoteImage(
                url = entry.cover,
                title = entry.title,
                modifier = Modifier.fillMaxWidth().aspectRatio(3f / 4f),
                corner = 0.dp,
            )
            // 进度条压在图片下沿：它必须和海报是一体的，
            // 挪到文字区就成了第三行小字，扫一眼读不出看到哪了
            if (entry.durationMs > 0L) {
                WatchProgressBar(
                    positionMs = entry.positionMs,
                    durationMs = entry.durationMs,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(
                text = entry.title,
                style = ModaType.Body,
                color = ModaColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                // 集号与进度压在同一行：两行文字会把卡高撑过海报，一屏就少露半条
                text = entryEpisodeLabel(entry.episodeIndex) + watchProgressSuffix(entry),
                style = ModaType.Caption,
                color = ModaColors.TextDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 进度条：positionMs / durationMs 夹到 0..1，脏数据不会把条画到卡片外面 */
@Composable
private fun WatchProgressBar(positionMs: Long, durationMs: Long, modifier: Modifier = Modifier) {
    val fraction = if (durationMs > 0L) {
        (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    // 4dp：手机屏上够细但仍在眼底，深色槽保证任何海报上都看得出这是进度条
    Box(modifier.fillMaxWidth().height(4.dp).background(Color.Black.copy(alpha = 0.72f))) {
        Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(ModaColors.Accent))
    }
}

/**
 * 集号按人话显示：库里存的是 0 起下标，用户看到的是「第几集」。
 * 是 @Composable 而不是纯函数：文案要跟系统语言走，只能在这里取资源。
 */
@Composable
private fun entryEpisodeLabel(episodeIndex: Int): String =
    if (episodeIndex < 0) {
        stringResource(R.string.core_episode_unknown)
    } else {
        stringResource(R.string.core_episode_index, episodeIndex + 1)
    }

/** 进度后缀：「 · 32:15 / 47:38」；没有时长信息时返回空串，不显示半截文案 */
private fun watchProgressSuffix(entry: LibraryEntry): String {
    if (entry.durationMs <= 0L) return ""
    return " · " + watchClock(entry.positionMs) + " / " + watchClock(entry.durationMs)
}

private fun watchClock(ms: Long): String {
    val total = ms.coerceAtLeast(0L) / 1000L
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) {
        String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    } else {
        String.format(Locale.US, "%02d:%02d", m, s)
    }
}

/**
 * 选集格子（详情页多行网格的一格）。
 *
 * 与 TV 端的差别只有排版策略：那边是单行横轨、有描述时固定 190dp；
 * 手机是 2 列多行网格，宽度交给网格算，描述固定两行截断（契约 §8：不照搬 190dp 卡宽）。
 * 没有描述时那一行整块不画——描述只出现在综艺/花絮类条目上，
 * 同一部剧里要么都有要么都没有，不会出现同排两格高度打架的情况。
 */
@Composable
fun EpisodeChip(
    index: Int,
    isVip: Boolean,
    isTrailer: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    summary: String = "",
) {
    val corner = 10.dp
    Column(
        modifier.tapCard(
            corner = corner,
            background = if (selected) ModaColors.Accent else ModaColors.SurfaceHigh,
            onClick = onClick,
        ).padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.core_episode_index, index + 1),
                style = ModaType.Body,
                color = Color.White,
                maxLines = 1,
            )
            if (isVip) {
                Spacer(Modifier.width(6.dp))
                Badge("VIP", color = Color(0xFFE0A32E))
            }
            if (isTrailer) {
                Spacer(Modifier.width(6.dp))
                Badge(stringResource(R.string.core_badge_trailer), color = Color(0xFF5C6BC0))
            }
        }
        if (summary.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            // 契约：分集描述在手机上两行截断，不照搬 TV 的固定卡宽
            Text(
                text = summary,
                style = ModaType.Caption,
                color = Color.White.copy(alpha = 0.72f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 热词 / 历史共用的可点词条：点一下直接搜 */
@Composable
fun WordChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .tapCard(corner = 20.dp, background = ModaColors.SurfaceHigh, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            text = text,
            style = ModaType.Body,
            color = ModaColors.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
