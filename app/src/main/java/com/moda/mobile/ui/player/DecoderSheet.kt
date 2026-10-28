package com.moda.mobile.ui.player

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.moda.mobile.R
import com.moda.mobile.data.local.DecoderMode
import com.moda.mobile.ui.theme.ModaColors
import com.moda.mobile.ui.theme.ModaType

/**
 * 解码方式面板：两档、点选即生效，与其它面板同一个骨架。
 *
 * 硬/软解只能在内核构造时决定（Exo 的 MediaCodecSelector、IJK 的 mediacodec 选项都是 init 期写死），
 * 所以选完必定重建引擎，进度由 PlayerScreen 的 onDispose 续上。
 */
@Composable
fun DecoderSheet(
    mode: DecoderMode,
    onPick: (DecoderMode) -> Unit,
    onClose: () -> Unit,
) {
    PlayerSheet(title = stringResource(R.string.player_decoder_title), onClose = onClose) {
        SheetChipRows(
            labels = DecoderMode.entries.map { stringResource(it.labelRes) },
            selectedIndex = DecoderMode.entries.indexOf(mode),
            onPick = { index -> DecoderMode.entries.getOrNull(index)?.let(onPick) },
            perRow = 2,
        )
        Spacer(Modifier.height(10.dp))
        // 只留解码差异说明：另一条「左右键切换…」的脚注是遥控器话术，手机端不适用
        Text(stringResource(R.string.player_decoder_footnote), style = ModaType.Caption, color = ModaColors.TextDim)
    }
}
