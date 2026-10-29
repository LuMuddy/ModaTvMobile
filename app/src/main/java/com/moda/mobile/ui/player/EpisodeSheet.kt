package com.moda.mobile.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import com.moda.mobile.model.Episode
import com.moda.mobile.ui.components.RemoteImage
import com.moda.mobile.ui.theme.ModaColors
import com.moda.mobile.ui.theme.ModaType

/** 当前集的高亮色：只用于描边与集号，不整卡填色——填色会压住缩略图，等于把图白画了 */
private val CurrentRingWidth = 2.dp
private val EpisodeCorner = 8.dp
private val ThumbRatio = 16f / 9f

/**
 * 选集面板：带缩略图的两列网格。
 *
 * 缩略图地址由 model/Types.kt 的 Episode.cover 按 vid 拼好（腾讯图床 16:9 原图），
 * 976 集也只是拼字符串、不发请求；图片交给 T4 的 RemoteImage（自带内存缓存与色块回落），
 * 加载失败时它会静默画色块，不会出现空白或崩溃。
 *
 * 与 TV 端 EpisodePanel 的差别只是版式：那边是照顾遥控器一维导航的单行横轨 + 缩略图卡，
 * 手机端换成两列网格——触屏上「点哪一集就是哪一集」，网格比横滑条少一次「先滑后点」。
 * 骨架（整宽 / 标题栏 / 70% 屏高上限）与其它六个面板一致；网格高度由 PlayerSheet 约束，
 * 集数再多也只 compose 可视窗口内的格子。
 */
@Composable
fun EpisodeSheet(
    episodes: List<Episode>,
    currentEpisodeIndex: Int,
    onSelect: (Episode) -> Unit,
    onClose: () -> Unit,
) {
    // 没有集数表时入口本来就不该出现，这里是第二道闸：空表开出一张空 sheet 只会让人以为坏了
    if (episodes.isEmpty()) return
    // 预告判据与详情页同一份关键词资源（上游分类词是数据，不是界面文案）
    val trailerKeys = stringResource(R.string.m_feed_trailer_keywords).split('|')
    PlayerSheet(
        title = stringResource(R.string.player_episode_panel_title, episodes.size),
        onClose = onClose,
        // 网格自己滚：套 verticalScroll 会让 Lazy 网格拿到无限高约束而抛异常
        scrollable = false,
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(episodes) { episode ->
                // episode.index 是 0 基下标（Repo.buildEpisodes 用 mapIndexed 生成），
                // 所以比对直接用下标、上屏才 +1——与 TV 端 EpisodePanel / VM 连播判定同一口径
                val current = episode.index == currentEpisodeIndex
                val isTrailer = trailerKeys.any { it.isNotBlank() && episode.label.contains(it) }
                Column(Modifier.fillMaxWidth()) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(ThumbRatio)
                            .clip(RoundedCornerShape(EpisodeCorner))
                            .then(
                                if (current) {
                                    Modifier.border(
                                        CurrentRingWidth,
                                        ModaColors.Accent,
                                        RoundedCornerShape(EpisodeCorner),
                                    )
                                } else {
                                    Modifier
                                },
                            )
                            .clickable { onSelect(episode) },
                    ) {
                        RemoteImage(
                            url = episode.cover,
                            title = episode.label,
                            modifier = Modifier.fillMaxSize(),
                            corner = EpisodeCorner,
                        )
                        if (isTrailer) {
                            EpisodeBadge(
                                text = stringResource(R.string.core_badge_trailer),
                                color = TrailerBadgeColor,
                                modifier = Modifier.align(Alignment.TopStart),
                            )
                        }
                        if (episode.isVip) {
                            EpisodeBadge(
                                text = "VIP",
                                color = VipBadgeColor,
                                modifier = Modifier.align(Alignment.TopEnd),
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.player_episode_number, episode.index + 1),
                        style = ModaType.Caption,
                        color = if (current) ModaColors.Accent else ModaColors.Text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 2.dp),
                    )
                }
            }
        }
    }
}

/** 预告的靛蓝、VIP 的金：与详情页/卡片上的角标同色，跨页面不打架 */
private val TrailerBadgeColor = Color(0xFF5C6BC0)
private val VipBadgeColor = Color(0xFFE0A32E)

/** 缩略图角标：压在图角上，字号走 Caption，不抢主体 */
@Composable
private fun EpisodeBadge(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .padding(6.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(color)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        Text(text, style = ModaType.Caption, color = Color.White, maxLines = 1)
    }
}
