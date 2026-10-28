package com.moda.mobile.ui.player

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.moda.mobile.R
import com.moda.mobile.data.local.PlayerCore
import com.moda.mobile.ui.theme.ModaColors
import com.moda.mobile.ui.theme.ModaType

/**
 * 内核面板：两档、点选即生效，与其它面板同一个骨架。
 *
 * 高亮的是**实际在跑的那个**（[effectiveCore]）而不是设置里的偏好：
 * IJK 缺 so 时工厂已静默回退 Exo，面板若把 IJK 标成当前，用户排查解码问题会被这条假信息带偏。
 */
@Composable
fun CoreSheet(
    effectiveCore: PlayerCore,
    ijkUsable: Boolean,
    onPick: (PlayerCore) -> Unit,
    onClose: () -> Unit,
) {
    PlayerSheet(title = stringResource(R.string.player_core_title), onClose = onClose) {
        SheetChipRows(
            labels = PlayerCore.entries.map { stringResource(it.labelRes) },
            selectedIndex = PlayerCore.entries.indexOf(effectiveCore),
            onPick = { index -> PlayerCore.entries.getOrNull(index)?.let(onPick) },
            perRow = 2,
        )
        if (!ijkUsable) {
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(R.string.player_core_ijk_missing),
                style = ModaType.Caption,
                color = ModaColors.Danger,
            )
        }
    }
}
