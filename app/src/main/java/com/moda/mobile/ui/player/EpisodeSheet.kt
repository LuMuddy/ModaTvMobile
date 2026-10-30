package com.moda.mobile.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.moda.mobile.R
import com.moda.mobile.model.Episode
import com.moda.mobile.ui.components.RemoteImage
import com.moda.mobile.ui.theme.ModaColors
import com.moda.mobile.ui.theme.ModaType

/** 面板宽度 = 屏宽的 1/3（横屏约 260dp）：够放一整块 16:9 缩略图卡片，又不至于把画面压掉大半 */
private const val PanelWidthRatio = 0.33f

/**
 * 选集：**贴右边的竖直接管条，一行一集**（dialog 形态）。
 *
 * 形状演进记在这里，别再改回去：
 *  · 最早是"半边转盘"：只画集号，看不出这一集是什么，长片名（《剧名》预告片_08）直接被挤成乱码；
 *  · 然后试过底部整宽的缩略图网格：图有了，但把画面压掉大半，选集本该是"边看边换"；
 *  · 现在这版：贴右侧一条竖列，**一行 = 一块卡片（16:9 缩略图在上、一行文字在下）**，
 *    手指从上往下扫，画面其余部分照旧看得见。
 *
 * 列表从当前集开始（几百集的剧，从第 1 集滚到第 180 集是不可能的事），当前集整块描边高亮。
 */
@Composable
fun EpisodeSheet(
    episodes: List<Episode>,
    currentEpisodeIndex: Int,
    onSelect: (Episode) -> Unit,
    onClose: () -> Unit,
) {
    // 没有集数表时入口本来就不该出现，这里是第二道闸：空表开出一张空面板只会让人以为坏了
    if (episodes.isEmpty()) return
    // 预告角标的判据来自上游分类词：它们是数据（片名/标签的一部分），不是界面文案，
    // 代码里不许出现中文字面量，所以词组放 string 资源里按 '|' 切开用（同详情页）
    val trailerKeys = stringResource(R.string.m_feed_trailer_keywords).split('|')
    fun isTrailer(episode: Episode) = trailerKeys.any { it.isNotBlank() && episode.label.contains(it) }

    val current = currentEpisodeIndex.coerceIn(0, episodes.lastIndex)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = current)
    val panelWidth = LocalConfiguration.current.screenWidthDp.dp * PanelWidthRatio

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = true,
        ),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                // 点面板外部关闭：内容铺满整屏时，Compose 自带的 dismissOnClickOutside 已经
                // 没有"外部"可判，所以自己用这层透明底兜住点击（用户要求所有弹窗都能点外部关）
                .pointerInput(Unit) { detectTapGestures { onClose() } },
            contentAlignment = Alignment.CenterEnd,
        ) {
            // 选集是独立窗口：不在这里再藏一次系统栏，横屏那条导航栏就会压在面板右边缘上
            HideSystemBarsInDialogWindow()
            Column(
                Modifier
                    .fillMaxHeight()
                    .width(panelWidth)
                    .background(ModaColors.Surface)
                    // 面板自己吃掉点击：否则点在面板空白处会穿到上面那层遮罩，把面板关掉
                    .pointerInput(Unit) { detectTapGestures { } },
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 10.dp, top = 8.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.player_episode_panel_title, episodes.size),
                        style = ModaType.Caption,
                        color = ModaColors.TextDim,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Box(
                        Modifier.size(36.dp).clickable(onClick = onClose),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.common_close),
                            tint = ModaColors.TextDim,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // 同一部剧里同名同 vid 的重播条目是可能的，下标一起进 key 才唯一
                    itemsIndexed(episodes, key = { index, episode -> "${episode.vid}#$index" }) { index, episode ->
                        EpisodeCard(
                            episode = episode,
                            selected = index == current,
                            // 已看完的绿勾不在这一版：播放页拿不到完成列表，与其猜不如不画
                            isVip = episode.isVip,
                            isTrailer = isTrailer(episode),
                            onClick = { onSelect(episode) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 一集 = 一块卡片：16:9 缩略图在上，一行文字在下，**外面包一层圆角底 + 描边**。
 *
 * 为什么要包：不包的话一列"图贴图、字贴图"，看着像没排版；包起来之后每集是一个可点的对象，
 * 描边也能当选中态用（当前集整块 2dp 主色，而不是把缩略图压暗）。
 * 文字用 label（正片是"第 15 集"，预告是它的片名），空串才退化成集号——一行装不下就省略号。
 */
@Composable
private fun EpisodeCard(
    episode: Episode,
    selected: Boolean,
    isVip: Boolean,
    isTrailer: Boolean,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) CardSelectedBg else CardBg)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) ModaColors.Accent else CardBorder,
                shape = RoundedCornerShape(10.dp),
            )
            .clickable(onClick = onClick)
            .padding(4.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(7.dp)),
        ) {
            // Episode.cover 已按 vid 拼好地址；取不到时 RemoteImage 自己落成文字色块
            RemoteImage(
                url = episode.cover,
                title = episode.label,
                modifier = Modifier.fillMaxSize(),
                corner = 7.dp,
            )
            if (isTrailer) {
                CardBadge(
                    text = stringResource(R.string.core_badge_trailer),
                    color = Color.White,
                    modifier = Modifier.align(Alignment.TopStart).padding(3.dp),
                )
            }
            if (isVip) {
                CardBadge(
                    text = "VIP",
                    color = CardVipGold,
                    modifier = Modifier.align(Alignment.TopEnd).padding(3.dp),
                )
            }
        }
        Text(
            text = episode.label.ifBlank { (episode.index + 1).toString() },
            style = ModaType.Caption,
            fontSize = 12.sp,
            color = if (selected) ModaColors.Accent else ModaColors.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(start = 2.dp, top = 4.dp, bottom = 1.dp),
        )
    }
}

/** 缩略图上的小角标：深色圆角底 + 一行字（压在亮图上才读得出） */
@Composable
private fun CardBadge(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = ModaType.Caption,
        fontSize = 9.sp,
        color = color,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0x99000000))
            .padding(horizontal = 3.dp, vertical = 1.dp),
    )
}

/** 卡片底色：用主题的 SurfaceHigh（比面板的 Surface 亮一档），才看得出"这是一块" */
private val CardBg = ModaColors.SurfaceHigh

private val CardSelectedBg = Color(0xFF262B35)

/** 未选中时的描边：一档极淡的白，只为切开边界，不抢缩略图 */
private val CardBorder = Color(0x22FFFFFF)

private val CardVipGold = Color(0xFFE0A32E)
