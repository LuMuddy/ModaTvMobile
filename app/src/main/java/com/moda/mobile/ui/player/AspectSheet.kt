package com.moda.mobile.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.moda.mobile.R
import com.moda.mobile.data.local.AspectMode
import com.moda.mobile.data.local.AspectModes

/**
 * 画面比例面板：点一下即改 resizeMode，不重建播放器（与 TV 端 AspectPanel 一致）。
 * 与其它面板同骨架：整宽、3 档一行、等宽 40dp 格；16:9 / 4:3 这两档在手机端靠
 * 「锁比例盒子 + FILL」实现，见 PlayerScreen 的 VideoSurface 调用点。
 */
@Composable
fun AspectSheet(
    mode: AspectMode,
    onPick: (AspectMode) -> Unit,
    onClose: () -> Unit,
) {
    PlayerSheet(title = stringResource(R.string.player_aspect_title), onClose = onClose) {
        SheetChipRows(
            labels = AspectModes.map { stringResource(it.labelRes) },
            selectedIndex = AspectModes.indexOf(mode),
            onPick = { index -> AspectModes.getOrNull(index)?.let(onPick) },
            perRow = 3,
        )
    }
}
