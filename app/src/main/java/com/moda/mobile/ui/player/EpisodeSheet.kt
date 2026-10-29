package com.moda.mobile.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.moda.mobile.R
import com.moda.mobile.model.Episode
import com.moda.mobile.ui.theme.ModaColors
import com.moda.mobile.ui.theme.ModaType
import kotlin.math.cos
import kotlin.math.sin

/** 面板宽度 = 屏宽的 1/5：一条贴右边的窄带，不占画面 */
private const val PanelWidthRatio = 0.2f

/** 转盘上相邻两集之间的角间距（弧度）：约 17°，一屏能看见 ±5 集 */
private const val DialStepRad = 0.30f

/** 滚一个"齿"要拖过的屏高比例：趟得太灵敏会一次跳过十几集 */
private const val DialDragRatio = 0.06f

/**
 * 选集：**贴右边的竖条 + 半边转盘**（dialog 形态，不再是底部面板）。
 *
 * 为什么不再是底栏 sheet：选集是"边看边换"，弹一整块面板会把画面压掉大半。
 * 现在它像一台转盘机——集数沿半圆弧排开、中间那一格是当前项，手指上下拨动转盘就换集，
 * 松手即切到中间那一集。宽度只吃屏宽的 1/5，画面其余部分照旧看得见。
 *
 * 转盘的圆心放在**右边界外侧**，弧线因此向左凸：中间那格贴着面板中线，上下两端向右边收，
 * 视觉上就是"半边转盘"。可见项按角度限制在 ±6 项，集数再多也只画这几格。
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
    val panelWidth = LocalConfiguration.current.screenWidthDp.dp * PanelWidthRatio
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = true,
        ),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
            Column(
                Modifier
                    .fillMaxHeight()
                    .width(panelWidth)
                    // 面板自己不消费点击也能挡住穿过去的点击吗？不能——Dialog 的 scrim 在外层，
                    // 这里是对话框内容，所以点面板空白处只是"什么都不做"，不会关掉也不该穿到画面
                    .background(ModaColors.Surface),
            ) {
                // 标题只写集数总数：宽度 1/5 屏，写"选集 · 共 64 集"会被挤成三行
                Row(
                    Modifier.fillMaxWidth().padding(start = 8.dp, top = 8.dp, end = 4.dp),
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
                EpisodeDial(
                    episodes = episodes,
                    initialIndex = currentEpisodeIndex.coerceIn(0, episodes.lastIndex),
                    onCommit = { index -> episodes.getOrNull(index)?.let(onSelect) },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }
        }
    }
}

/**
 * 半边转盘本体：Canvas 直接画集号，不摆按钮。
 *
 * 集数是"画"出来的而不是"布局"出来的：一个 Canvas 里按角度算位置，省掉几十个可组合节点，
 * 也就不用管 Lazy 网格的滚动状态与转盘的旋转怎么对齐。
 *
 * 交互只有两条：上下拖（每拖过 [DialDragRatio] 屏高滚一格）、抬手（中间那一集就播）。
 * 抬手即播是有意的——转盘"拨到哪就是哪"，再补一次"确认"只是多一步。
 */
@Composable
private fun EpisodeDial(
    episodes: List<Episode>,
    initialIndex: Int,
    onCommit: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 中心项由自己持有：手势回调活在协程里，读外部传入的值会停在组合时那一帧
    var center by remember(episodes.size) { mutableIntStateOf(initialIndex) }
    val commitNow by rememberUpdatedState(onCommit)
    val measurer = rememberTextMeasurer()
    val dim = TextStyle(color = ModaColors.TextDim, fontSize = 13.sp)
    val hot = TextStyle(color = ModaColors.Accent, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)

    BoxWithConstraints(modifier) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        // 圆心在右边界之外 0.55R：弧顶（第 0 项）落在面板内约 1/4 宽的位置，向左凸出
        val radius = widthPx * 0.80f
        val cx = widthPx + radius * 0.55f
        val cy = heightPx / 2f
        val unitPx = heightPx * DialDragRatio

        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(episodes.size) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        var accum = 0f
                        var moved = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (change.changedToUpIgnoreConsumed()) break
                            val dy = change.positionChange().y
                            change.consume()
                            accum += dy
                            moved = moved || accum != 0f
                            // 向上拖 = 往后翻（同一条滚轮的手感：内容跟着手指走）
                            while (accum <= -unitPx) {
                                accum += unitPx
                                center = (center + 1).coerceAtMost(episodes.lastIndex)
                            }
                            while (accum >= unitPx) {
                                accum -= unitPx
                                center = (center - 1).coerceAtLeast(0)
                            }
                        }
                        if (moved) commitNow(center)
                    }
                },
        ) {
            // 只画中心前后各 6 项：再多也落在可视弧段外，画了也是浪费
            for (d in -6..6) {
                val index = center + d
                if (index !in episodes.indices) continue
                val angle = d * DialStepRad
                val x = cx - radius * cos(angle)
                val y = cy + radius * sin(angle)
                val isCenter = d == 0
                val layout = measurer.measure(
                    text = AnnotatedString(episodes[index].label.ifBlank { (index + 1).toString() }),
                    style = if (isCenter) hot else dim,
                )
                // 越靠两端越淡：转盘的"边缘"靠透明度收，不靠硬裁切
                val fade = 1f - (kotlin.math.abs(d) / 8f)
                drawText(
                    textLayoutResult = layout,
                    topLeft = Offset(
                        x - layout.size.width / 2f,
                        y - layout.size.height / 2f,
                    ),
                    alpha = fade.coerceIn(0.25f, 1f),
                )
            }
            // 中心那一格的定位三角：宽度只有 1/5 屏，没有它就看不出"现在停在哪一集"
            val markerX = cx - radius - 6f
            drawCircle(
                color = ModaColors.Accent,
                radius = 3f,
                center = Offset(markerX, cy),
            )
            // 上下两端各一条渐隐线，暗示"还能继续拨"
            drawLine(
                color = Color(0x33FFFFFF),
                start = Offset(0f, 2f),
                end = Offset(widthPx, 2f),
                strokeWidth = 2f,
            )
            drawLine(
                color = Color(0x33FFFFFF),
                start = Offset(0f, heightPx - 2f),
                end = Offset(widthPx, heightPx - 2f),
                strokeWidth = 2f,
            )
        }
    }
}
