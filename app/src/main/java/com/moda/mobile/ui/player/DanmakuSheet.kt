package com.moda.mobile.ui.player

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.moda.mobile.R
import com.moda.mobile.data.local.DanmakuRegion
import com.moda.mobile.data.local.DanmakuRegions
import com.moda.mobile.data.local.DanmakuSizeOptions
import com.moda.mobile.data.local.MarginCombinedMaxPercent
import com.moda.mobile.data.local.MarginMaxPercent
import com.moda.mobile.data.local.MarginStepPercent
import com.moda.mobile.data.local.PlayerSettings
import com.moda.mobile.ui.theme.ModaColors
import com.moda.mobile.ui.theme.ModaType

/**
 * 弹幕面板。
 *
 * TV 端五个子项靠上下键选、确认键 +1 一档档试；手机端把每个子项直接摊开成可点的样子，
 * 一次点击就是一次改值。子项与 TV 端完全一致（开关 / 字号 / 位置 / 上边距 / 下边距），一个没少。
 * 骨架与其它六个面板相同：整宽、16dp padding、40dp 等宽档位格。
 *
 * 只有「开关」是本剧覆盖（写 PlaybackOverride，按 cid 存），其余四项是全局偏好：
 * 这条分工来自数据层（PlayerSettings 是默认值、PlaybackOverride 是单剧覆盖），这里不另立规则。
 */
@Composable
fun DanmakuSheet(
    settings: PlayerSettings,
    /**
     * 当前**生效**的开关值：本剧覆盖优先，没有覆盖才等于 [settings].danmakuEnabled。
     *
     * 不能拿 [settings] 自己显示：用户在本剧关过弹幕后，全局默认仍是开的，
     * 照 settings 画开关会出现「面板里是开、画面上没有弹幕」。
     * 反过来，写回时也绝不能把这个生效值写进 settings —— 那是默认值，
     * 一次播放页的临时切换不该改掉它。
     */
    enabled: Boolean,
    onToggleEnabled: () -> Unit,
    onSettingsChange: (PlayerSettings) -> Unit,
    onClose: () -> Unit,
) {
    PlayerSheet(title = stringResource(R.string.player_danmaku_title), onClose = onClose) {
        SheetSwitchRow(
            label = stringResource(R.string.player_danmaku_enabled),
            checked = enabled,
            onCheckedChange = { onToggleEnabled() },
        )
        Spacer(Modifier.height(8.dp))

        SheetSectionTitle(stringResource(R.string.player_danmaku_size))
        SheetChipRows(
            labels = DanmakuSizeOptions.map { stringResource(it.labelRes) },
            selectedIndex = DanmakuSizeOptions.indexOfFirst { it.sizeSp == settings.danmakuSizeSp },
            onPick = { index ->
                DanmakuSizeOptions.getOrNull(index)?.let { option ->
                    onSettingsChange(settings.copy(danmakuSizeSp = option.sizeSp))
                }
            },
            perRow = 4,
        )
        Spacer(Modifier.height(12.dp))

        SheetSectionTitle(stringResource(R.string.player_danmaku_region))
        SheetChipRows(
            labels = DanmakuRegions.map { stringResource(it.labelRes) },
            selectedIndex = DanmakuRegions.indexOf(settings.danmakuRegion),
            onPick = { index ->
                DanmakuRegions.getOrNull(index)?.let { region ->
                    onSettingsChange(withRegion(settings, region))
                }
            },
            perRow = 3,
        )
        Spacer(Modifier.height(12.dp))

        // 上下边距只在「自定义边距」档位下有意义（另外五档由区域比例决定），提示一句避免误会
        SheetSectionTitle(stringResource(R.string.player_danmaku_top_margin))
        MarginRow(
            percent = settings.topRatio * 100f,
            onStep = { delta ->
                onSettingsChange(
                    settings.copy(
                        danmakuRegion = DanmakuRegion.Custom,
                        danmakuTopPercent = stepMargin(
                            current = settings.danmakuTopPercent,
                            other = settings.danmakuBottomPercent,
                            delta = delta,
                        ),
                    ),
                )
            },
        )
        Spacer(Modifier.height(6.dp))
        SheetSectionTitle(stringResource(R.string.player_danmaku_bottom_margin))
        MarginRow(
            percent = settings.bottomRatio * 100f,
            onStep = { delta ->
                onSettingsChange(
                    settings.copy(
                        danmakuRegion = DanmakuRegion.Custom,
                        danmakuBottomPercent = stepMargin(
                            current = settings.danmakuBottomPercent,
                            other = settings.danmakuTopPercent,
                            delta = delta,
                        ),
                    ),
                )
            },
        )
    }
}

/** 切到自定义档位时把当前生效的占比搬进可调边距，视觉上不跳变（照搬 TV 端 adjustDanmaku 的 Region 分支） */
private fun withRegion(settings: PlayerSettings, region: DanmakuRegion): PlayerSettings =
    if (region == DanmakuRegion.Custom) {
        settings.copy(
            danmakuRegion = region,
            danmakuTopPercent = settings.topRatio * 100f,
            danmakuBottomPercent = settings.bottomRatio * 100f,
        )
    } else {
        settings.copy(danmakuRegion = region)
    }

/** 边距步进行：左右各一个按钮，中间是百分比；行高与档位格一致（40dp） */
@Composable
private fun MarginRow(percent: Float, onStep: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onStep(-1) }) {
            Icon(Icons.Filled.Remove, contentDescription = null, tint = ModaColors.Text)
        }
        Text(percent.toInt().toString() + "%", style = ModaType.Body, color = ModaColors.Text)
        IconButton(onClick = { onStep(1) }) {
            Icon(Icons.Filled.Add, contentDescription = null, tint = ModaColors.Text)
        }
        Spacer(Modifier.width(8.dp))
    }
}

/** 上下边距合计不能吃满画面，至少留 30% 给弹幕（阈值与 TV 端同一份常量） */
private fun Float.combinedCap(other: Float): Float =
    coerceAtMost((MarginCombinedMaxPercent - other).coerceAtLeast(0f))

/**
 * 边距步进，到达区间端点后绕回另一端。
 * 单方向调整若在边界夹住就再也回不来，用户会以为按钮坏了；绕回让单键也能走遍整个区间。
 */
private fun stepMargin(current: Float, other: Float, delta: Int): Float {
    val cap = MarginMaxPercent.combinedCap(other)
    val step = delta * MarginStepPercent
    val next = current + step
    return when {
        step > 0f && next > cap -> 0f
        step < 0f && next < 0f -> cap
        else -> next.coerceIn(0f, cap)
    }
}
