package com.moda.mobile.ui.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.outlined.SubtitlesOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.moda.mobile.R
import com.moda.mobile.model.Tier
import com.moda.mobile.model.TierName
import com.moda.mobile.ui.theme.ModaColors
import com.moda.mobile.ui.theme.ModaType
import kotlin.math.roundToLong

/**
 * 底部控制条占用的高度。
 * 弹幕层按它让位，否则弹幕会压在进度条与按钮上——与 TV 端 OsdBottomInset 同一个理由。
 */
val ControlsBottomInset = 112.dp

/**
 * 面板尺寸的唯一来源。
 *
 * 用户对播放页面板的原话是「大大小小」：选集是大宽面板、倍速是偏左下的小卡片、
 * 其余各不相同。七个面板现在共用下面这几个常量，任何面板改尺寸都必须改这里，
 * 不允许在面板内部写自己的数字。
 */
private val SheetContentPadding = 16.dp
private val SheetTitleHeight = 44.dp
private val ChipHeight = 40.dp
private val ChipCorner = 8.dp
private val ChipGap = 8.dp

/** 右下角簇里的一颗按钮：44dp 触控高度，圆角与面板档位同一档 */
private val BottomActionHeight = 44.dp
private val BottomActionCorner = 10.dp

/** 面板最大高度占屏高的比例：内容超出就在面板内部滚动，不把整屏顶满 */
private const val SheetMaxHeightRatio = 0.7f

/** 面板内容可用的最大高度（七个面板都从这里取，保证同一块屏上一样高） */
@Composable
fun playerSheetMaxContentHeight(): Dp =
    LocalConfiguration.current.screenHeightDp.dp * SheetMaxHeightRatio

/**
 * 播放页的面板身份：一个枚举值 = 一张半屏面板。
 *
 * 与 TV 端 OsdSlot 的九个槽位一一对应，差别只有三处：
 *  · TV 的「播放/暂停」由确认键承担，手机端它是底栏第一行最左的按钮，不占面板；
 *  · TV 的 Intro / Outro 两个「记录点」槽位合并成 [Marks] 一个面板（面板里仍保留两个记录动作）；
 *  · [More] 是手机端新加的「收纳面板」：它自己不控制任何东西，只把 [MorePanels] 里的五项叫出来。
 *
 * 入口位置见 [PlayerTopBar] 与 [PlayerBottomBar]：投屏在顶栏右上（锁定左侧）、
 * 倍速/码率/选集/更多在右下角簇、弹幕总开关在进度条行尾。
 * 没有一整排八项文字入口——那是遥控器扫读的形态，触屏上只会挤掉进度条。
 */
enum class PlayerPanel {
    Episode,
    Quality,
    Speed,
    Danmaku,
    Aspect,
    Core,
    Decoder,
    Marks,
    More,
}

/** 「更多」面板里的五项，顺序固定；每一项打开的就是与它同名的那个面板 */
val MorePanels: List<PlayerPanel> = listOf(
    PlayerPanel.Danmaku,
    PlayerPanel.Aspect,
    PlayerPanel.Core,
    PlayerPanel.Decoder,
    PlayerPanel.Marks,
)

/** 文案键已经全部在 T1 移植的 strings_player.xml 里，这里只做「枚举 → 资源」的唯一映射 */
@Composable
fun panelLabel(panel: PlayerPanel): String = when (panel) {
    PlayerPanel.Episode -> stringResource(R.string.player_episodes)
    PlayerPanel.Quality -> stringResource(R.string.player_quality)
    PlayerPanel.Speed -> stringResource(R.string.player_speed)
    PlayerPanel.Danmaku -> stringResource(R.string.player_danmaku)
    PlayerPanel.Aspect -> stringResource(R.string.player_aspect)
    PlayerPanel.Core -> stringResource(R.string.player_core_title)
    PlayerPanel.Decoder -> stringResource(R.string.player_decoder_title)
    // 片头/片尾是同一个面板：标签由两个已有键拼出，不为一个斜杠新增文案键
    PlayerPanel.Marks -> stringResource(R.string.player_intro) + "/" + stringResource(R.string.player_outro)
    PlayerPanel.More -> stringResource(R.string.m_player_more)
}

/** 毫秒转 时:分:秒 / 分:秒（与 TV 端 Osd.formatClock 同一份实现） */
fun formatClock(ms: Long): String {
    val total = (ms.coerceAtLeast(0L) / 1000f).roundToLong()
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

/** 档位显示名的唯一解析点：资源走 stringResource，上游原文原样返回（同 TV 端 tierNameText） */
@Composable
fun tierNameText(name: TierName): String = when (name) {
    is TierName.Upstream -> name.text
    is TierName.Local -> stringResource(name.resId)
}

/**
 * 宿主的 Activity。
 *
 * 横屏申请、系统栏显隐、亮度调节三件事都只能由 Activity/Window 做，
 * 而 Compose 里拿到的 Context 可能是 ContextThemeWrapper 等包装层，必须顺链往上找。
 */
fun findActivity(context: Context): Activity? {
    var ctx: Context = context
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

/**
 * 面板骨架：七个面板唯一的出口。
 *
 * 统一的是四件事：整宽（横屏下 M3 默认只给 640dp 并居中，两侧会留大片空）、
 * 同一高度的标题栏 + 44dp 关闭触控区、16dp 内容 padding、70% 屏高的上限（超出内部滚动）。
 * 底色与圆角由 ModalBottomSheet 统一（ModaColors.Surface + 默认顶部圆角）。
 *
 * [scrollable] = false 给选集用：它内部是 Lazy 网格，套 verticalScroll 会让网格拿到
 * 无限高约束而直接抛异常，滚动交给网格自己。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerSheet(
    title: String,
    onClose: () -> Unit,
    scrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val maxHeight = playerSheetMaxContentHeight()
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheetState,
        // Dp.Unspecified = 占满可用宽度：手机横屏下七个面板才会一样宽
        sheetMaxWidth = Dp.Unspecified,
        containerColor = ModaColors.Surface,
        contentColor = ModaColors.Text,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .then(
                    if (scrollable) {
                        Modifier.verticalScroll(rememberScrollState())
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = SheetContentPadding),
        ) {
            // 手柄区由 ModalBottomSheet 的默认 DragHandle 提供（七个面板同高），这里只排标题栏
            Row(
                Modifier.fillMaxWidth().height(SheetTitleHeight),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = ModaType.Heading,
                    color = ModaColors.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                // 关闭：44dp 触控区，比一行小字好按，同时把标题栏高度钉死
                Box(
                    Modifier.size(SheetTitleHeight).clip(CircleShape).clickable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.common_close),
                        tint = ModaColors.TextDim,
                    )
                }
            }
            Spacer(Modifier.height(ChipGap))
            content()
            Spacer(Modifier.height(SheetContentPadding))
        }
    }
}

/**
 * 面板里的一格档位。
 * 高度 40dp、圆角 8dp、选中 = 主色底白字：七个面板共用，宽度由调用方的 weight 决定，
 * 于是同一行的格子永远等宽。
 */
@Composable
fun SheetOption(
    label: String,
    selected: Boolean,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .height(ChipHeight)
            .clip(RoundedCornerShape(ChipCorner))
            .background(if (selected) ModaColors.Accent else Color(0x26FFFFFF))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = ModaType.Body,
            color = when {
                !enabled -> ModaColors.TextDim.copy(alpha = 0.5f)
                selected -> Color.White
                else -> ModaColors.Text
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 一组等宽档位格：每行 [perRow] 个，行距与格距都是 8dp。
 * 末行不足 perRow 时用空白撑住，否则最后一格会被 weight 拉成两倍宽。
 */
@Composable
fun SheetChipRows(
    labels: List<String>,
    selectedIndex: Int,
    onPick: (Int) -> Unit,
    perRow: Int = 3,
    enabled: (Int) -> Boolean = { true },
) {
    Column(verticalArrangement = Arrangement.spacedBy(ChipGap)) {
        labels.indices.chunked(perRow).forEach { row ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ChipGap),
            ) {
                row.forEach { index ->
                    SheetOption(
                        label = labels[index],
                        selected = index == selectedIndex,
                        enabled = enabled(index),
                        modifier = Modifier.weight(1f),
                        onClick = { onPick(index) },
                    )
                }
                repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** 面板里的一行「文字 + 开关」 */
@Composable
fun SheetSwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = ModaType.Body, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** 面板里的小节标题 */
@Composable
fun SheetSectionTitle(text: String) {
    Text(text, style = ModaType.Caption, color = ModaColors.TextDim)
    Spacer(Modifier.height(6.dp))
}

/**
 * 顶栏：返回（最左）+ 标题/集号（可截断）+ 投屏 + 锁定（最右），整宽一条。
 *
 * 标题原来贴在进度条上方、返回与锁定又并排挤在右下角——三样东西都在视线以外。
 * 现在统一收到顶部：眼睛回到画面顶部就能读到「在播什么」，返回与锁定各占一端。
 * 两端图标与标题之间各留 12dp，长标题 ellipsis 截断而不是换行（换行会把顶栏撑成两行）。
 * 只给自己这一截压暗（自上而下收黑），不铺整屏遮罩。
 *
 * 右侧图标顺序固定：[投屏] [锁定]。锁定永远是最后一个，投屏贴在它左边——
 * 锁屏是「别再让我误触」，投屏是「换个屏幕看」，两者的轻重不一样，位置也就不能对调。
 * 弹幕**不在**顶栏：底栏功能行里已经有它，顶栏再放一颗就是同一个入口两个位置。
 */
@Composable
fun PlayerTopBar(
    title: String,
    episodeLabel: String,
    locked: Boolean,
    casting: Boolean,
    castDeviceName: String?,
    onBack: () -> Unit,
    onToggleLock: () -> Unit,
    onCastClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 集号并进同一行文本：两段分开排会长标题把集号挤到看不见的地方
    val line = if (episodeLabel.isNotBlank()) title + " · " + episodeLabel else title
    Box(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color(0xB3000000), Color(0x00000000)))),
    ) {
        Row(
            Modifier.fillMaxWidth().height(SheetTitleHeight).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.search_back),
                    tint = ModaColors.Text,
                )
            }
            Text(
                text = line,
                style = ModaType.Heading,
                color = ModaColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            )
            // 投屏中把「正在投到哪台」直接写在图标旁边：投到电视之后手机是黑的，
            // 不写出来用户没法确认自己投成功了没有
            if (casting && castDeviceName != null) {
                CastStatusChip(
                    deviceName = castDeviceName,
                    modifier = Modifier.widthIn(max = 200.dp).padding(end = 8.dp),
                )
            }
            // 投屏入口：投屏中图标换主色（实心圆底也跟着提亮），一眼看出「现在在电视上放」
            Box(
                Modifier
                    .size(SheetTitleHeight)
                    .clip(CircleShape)
                    .background(if (casting) ModaColors.Accent.copy(alpha = 0.22f) else Color(0x33FFFFFF))
                    .clickable(onClick = onCastClick),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Cast,
                    contentDescription = stringResource(R.string.m_player_cast_title),
                    tint = if (casting) ModaColors.Accent else Color.White,
                )
            }
            Spacer(Modifier.width(4.dp))
            // 锁定态给一个实心圆底 + 实心锁形：比单纯换 tint 更容易一眼看出「现在是锁着的」
            Box(
                Modifier
                    .size(SheetTitleHeight)
                    .clip(CircleShape)
                    .background(if (locked) ModaColors.Accent else Color(0x33FFFFFF))
                    .clickable(onClick = onToggleLock),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (locked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                    contentDescription = null,
                    tint = Color.White,
                )
            }
        }
    }
}

/**
 * 底栏两行：
 *  第一行 = 播放/暂停 + 当前/总时长 + 进度 Slider（weight 1f）+ 弹幕快捷开关；
 *  第二行 = 右下角簇：倍速 / 码率（清晰度）/ 选集 / 更多，整行右对齐、间隔 8dp。
 *
 * 两行各占一条高度，所以「进度条被挤」和「簇压住进度条末端」两件事都不会发生：
 * Slider 是第一行里唯一的可伸缩元素，第二行从行首空到行尾、只在末端排四个实心块。
 * 弹幕快捷开关留在第一行行尾，与时间/进度条同一条基线，只切总开关；
 * 字号/区域/边距仍走「更多 → 弹幕」那张完整面板。
 * 进度条用 material3 Slider：同时支持拖动与点按轨道跳转，触摸语义由组件自己保证。
 * 拖动期间只更新预览（[onSeekPreview]），松手才真正 seek（[onSeekCommit]）——
 * 否则每一帧拖动都会打一次 seek，播放器会被拖到卡死。
 */
@Composable
fun PlayerBottomBar(
    playing: Boolean,
    positionMs: Long,
    durationMs: Long,
    speedLabel: String?,
    onTogglePlay: () -> Unit,
    onSeekPreview: (Long) -> Unit,
    onSeekCommit: (Long) -> Unit,
    onOpenPanel: (PlayerPanel) -> Unit,
    danmakuEnabled: Boolean,
    onToggleDanmaku: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val totalSec = if (durationMs > 0L) durationMs / 1000f else 1f
    var dragging by remember { mutableStateOf(false) }
    var dragSec by remember { mutableFloatStateOf(0f) }
    val shownMs = if (dragging) (dragSec * 1000f).toLong() else positionMs
    val shownSec = (shownMs / 1000f).coerceIn(0f, totalSec)

    Box(
        modifier
            .fillMaxWidth()
            .background(
                // 自下而上收黑：同样只压自己这一截
                Brush.verticalGradient(
                    listOf(Color(0x00000000), Color(0xB3000000), Color(0xE6000000)),
                ),
            ),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onTogglePlay) {
                    Icon(
                        imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = ModaColors.Text,
                    )
                }
                Text(
                    text = formatClock(shownMs) + " / " + formatClock(durationMs),
                    style = ModaType.Caption,
                    color = ModaColors.Text,
                )
                Slider(
                    value = shownSec,
                    onValueChange = { value ->
                        dragging = true
                        dragSec = value
                        onSeekPreview((value * 1000f).toLong())
                    },
                    onValueChangeFinished = {
                        dragging = false
                        onSeekCommit((dragSec * 1000f).toLong())
                    },
                    valueRange = 0f..totalSec,
                    colors = SliderDefaults.colors(
                        thumbColor = ModaColors.Accent,
                        activeTrackColor = ModaColors.Accent,
                        inactiveTrackColor = Color(0x59FFFFFF),
                    ),
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                // 倍速只作状态提示，放在时间旁边而不是右侧角落：底栏右侧不放任何悬浮元素
                if (speedLabel != null) {
                    Text(speedLabel, style = ModaType.Caption, color = ModaColors.Text)
                }
                // 弹幕快捷开关：一行之内最靠右，44dp 触控区；只切总开关（与面板里的
                // 「弹幕开关」同一语义），面板里的字号/区域/边距一概不动
                Box(
                    Modifier
                        .size(SheetTitleHeight)
                        .clip(CircleShape)
                        .clickable(onClick = onToggleDanmaku),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (danmakuEnabled) Icons.Filled.Subtitles else Icons.Outlined.SubtitlesOff,
                        contentDescription = stringResource(R.string.player_danmaku_enabled),
                        tint = if (danmakuEnabled) ModaColors.Text else ModaColors.TextDim,
                    )
                }
            }
            // 右下角簇：倍速 / 码率 / 选集 / 更多，整行右对齐、间距 8dp。
            // 它排在进度条那一行的**下一行**：两行各自独占高度，所以既不会挤窄进度条
            // （Slider 是第一行唯一的可伸缩元素），也不会盖住进度条的末端。
            Row(
                Modifier.fillMaxWidth().padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ChipGap, Alignment.End),
            ) {
                BottomActionChip(stringResource(R.string.player_speed)) {
                    onOpenPanel(PlayerPanel.Speed)
                }
                // 「码率」就是清晰度：档位列表与文案键全部沿用清晰度那一套
                BottomActionChip(stringResource(R.string.player_quality)) {
                    onOpenPanel(PlayerPanel.Quality)
                }
                BottomActionChip(stringResource(R.string.player_episodes)) {
                    onOpenPanel(PlayerPanel.Episode)
                }
                BottomActionChip(stringResource(R.string.m_player_more)) {
                    onOpenPanel(PlayerPanel.More)
                }
            }
        }
    }
}

/**
 * 右下角簇里的一颗动作按钮：44dp 高的实心圆角块（触控区达标），宽度由文字撑开。
 */
@Composable
private fun BottomActionChip(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .heightIn(min = BottomActionHeight)
            .clip(RoundedCornerShape(BottomActionCorner))
            .background(Color(0x33000000))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = ModaType.Body, color = ModaColors.Text, maxLines = 1)
    }
}

/**
 * 「更多」面板：右下角簇三颗常驻按钮之外的功能全部收在这里。
 * 每一项打开的就是与它同名的那张面板，功能一个都不丢：
 * 弹幕（完整面板）· 比例 · 播放内核 · 解码方式 · 片头/片尾。
 */
@Composable
fun MoreSheet(onPick: (PlayerPanel) -> Unit, onClose: () -> Unit) {
    PlayerSheet(title = stringResource(R.string.m_player_more), onClose = onClose) {
        Column(verticalArrangement = Arrangement.spacedBy(ChipGap)) {
            MorePanels.forEach { panel ->
                SheetOption(
                    label = panelLabel(panel),
                    selected = false,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onPick(panel) },
                )
            }
        }
    }
}

/**
 * 清晰度面板。
 *
 * 不可用档在这里是**不可点 + 标签里直接标「不可用」**：
 * TV 端用「按下确认再给一句回执」是因为遥控器上看不出能不能选，
 * 触屏上禁用态如果不写原因，看起来就是「点不动」。
 */
@Composable
fun QualitySheet(
    tiers: List<Tier>,
    selected: Tier?,
    onPick: (Tier) -> Unit,
    onClose: () -> Unit,
) {
    PlayerSheet(title = stringResource(R.string.player_quality), onClose = onClose) {
        if (tiers.isEmpty()) {
            Text(stringResource(R.string.player_no_quality), style = ModaType.Caption)
            return@PlayerSheet
        }
        val labels = tiers.map { tier ->
            val name = tierNameText(tier.display)
            if (tier.available) name else name + " · " + stringResource(R.string.player_unavailable)
        }
        val selectedIndex = tiers.indexOfFirst { tier ->
            selected != null && selected.fno == tier.fno && selected.defn == tier.defn
        }
        SheetChipRows(
            labels = labels,
            selectedIndex = selectedIndex,
            onPick = { index -> tiers.getOrNull(index)?.let(onPick) },
            perRow = 3,
            enabled = { index -> tiers[index].available },
        )
    }
}

/**
 * 片头/片尾面板。
 *
 * 能力与 TV 端的 Intro / Outro 两个槽位完全对应，一个都没少：
 *  · 书签按钮 = TV 的「确认键记录当前位置」（重按一次就是重新量）；
 *  · 开关 = 需求里的「片头片尾跳过开关」，它只作用于本次播放：
 *    跳过不跳过的**事实依据仍然是「有没有可信记录」**（IntroOutroRules 的三条阈值原样照搬）。
 */
@Composable
fun MarksSheet(
    introMs: Long,
    outroMs: Long,
    skipIntro: Boolean,
    skipOutro: Boolean,
    onSkipIntroChange: (Boolean) -> Unit,
    onSkipOutroChange: (Boolean) -> Unit,
    onRecordIntro: () -> Unit,
    onRecordOutro: () -> Unit,
    onClose: () -> Unit,
) {
    val title = stringResource(R.string.player_intro) + "/" + stringResource(R.string.player_outro)
    PlayerSheet(title = title, onClose = onClose) {
        MarkRow(
            label = stringResource(R.string.player_intro),
            recordedMs = introMs,
            checked = skipIntro,
            onCheckedChange = onSkipIntroChange,
            onRecord = onRecordIntro,
        )
        Spacer(Modifier.height(6.dp))
        MarkRow(
            label = stringResource(R.string.player_outro),
            recordedMs = outroMs,
            checked = skipOutro,
            onCheckedChange = onSkipOutroChange,
            onRecord = onRecordOutro,
        )
    }
}

@Composable
private fun MarkRow(
    label: String,
    recordedMs: Long,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onRecord: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(SheetTitleHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = ModaType.Body, modifier = Modifier.width(56.dp))
        // 0 表示还没记录：给一个占位符，免得用户以为「记过了但显示成 00:00」
        Text(
            text = if (recordedMs > 0L) formatClock(recordedMs) else "--:--",
            style = ModaType.Body,
            color = if (recordedMs > 0L) ModaColors.Text else ModaColors.TextDim,
        )
        IconButton(onClick = onRecord) {
            Icon(
                imageVector = Icons.Filled.BookmarkAdd,
                contentDescription = label,
                tint = ModaColors.TextDim,
            )
        }
        Spacer(Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * 播放失败面板。文案由 T4 的 playerErrorText 从错误码翻出来（全仓唯一的错误码翻译点），
 * 这里只负责画和给一个可点的重试。
 */
@Composable
fun PlayerErrorPane(
    message: String,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit,
) {
    Box(modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.player_play_failed), style = ModaType.Heading, color = ModaColors.Danger)
            Spacer(Modifier.height(12.dp))
            Text(message, style = ModaType.Caption)
            Spacer(Modifier.height(24.dp))
            val shape = RoundedCornerShape(12.dp)
            Box(
                Modifier
                    .clip(shape)
                    .background(ModaColors.Accent)
                    .clickable(onClick = onRetry)
                    .padding(horizontal = 34.dp, vertical = 12.dp),
            ) {
                Text(stringResource(R.string.player_retry), style = ModaType.Body)
            }
        }
    }
}
