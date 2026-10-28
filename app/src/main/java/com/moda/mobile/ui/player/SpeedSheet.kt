package com.moda.mobile.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.moda.mobile.R
import com.moda.mobile.data.local.PlayerSpeedOptions

/**
 * 倍速面板。
 * 六个档位走统一的 SheetChipRows（3 档两行、等宽、40dp 高），
 * 点一下立即生效（对齐 TV 端「选中即生效」的语义）。
 * 档位标签是 PlayerSpeedOptions 里的数字串（"0.5x"），属于数据，不进资源表。
 */
@Composable
fun SpeedSheet(
    speed: Float,
    onPick: (Float) -> Unit,
    onClose: () -> Unit,
) {
    PlayerSheet(title = stringResource(R.string.player_speed), onClose = onClose) {
        val selected = PlayerSpeedOptions.indexOfFirst { it.value == speed }
        SheetChipRows(
            labels = PlayerSpeedOptions.map { it.label },
            selectedIndex = selected,
            onPick = { index ->
                PlayerSpeedOptions.getOrNull(index)?.let { onPick(it.value) }
            },
            perRow = 3,
        )
    }
}
