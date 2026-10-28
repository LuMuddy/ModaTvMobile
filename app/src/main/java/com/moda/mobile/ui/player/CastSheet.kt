package com.moda.mobile.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.moda.mobile.R
import com.moda.mobile.ui.theme.ModaColors
import com.moda.mobile.ui.theme.ModaType

/** 面板里一行设备的高度：与 [SheetTitleHeight] 同一档，触控区够 44dp */
private val CastRowHeight = 44.dp

/** 行尾动作按钮的宽度：所有行等宽，列表才不会像一堆参差的碎块 */
private val CastActionWidth = 116.dp

/**
 * 投屏设备面板。
 *
 * 只做三件事：发现设备、重搜、空态；外加「当前正在投到哪台 + 停止」这一行。
 * **不发起投屏播放**——投什么地址、失败怎么提示，全由调用方决定（详情页的小播放器与
 * 播放页要投的地址不是同一个）。所以这里既没有播放器引用，也不读任何播放状态。
 *
 * 面板每次进入组合都自动发现一次（[LaunchedEffect] 的 key 是重搜计数，重进面板即是新的一次），
 * 「重新搜索」只是把这个计数 +1。发现最长 3 秒（[CastController.discover] 自己的预算），
 * 到点必然落到空态，不会有「转圈转到天荒地老」。
 *
 * [failureText] 由调用方在投屏失败后填入；面板不自己判断成功与否。
 */
@Composable
fun CastSheet(
    controller: CastController,
    current: CastDevice?,
    onPick: (CastDevice) -> Unit,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
    failureText: String? = null,
    discoveryTimeoutMs: Long = 3000L,
) {
    var discovering by remember { mutableStateOf(true) }
    var devices by remember { mutableStateOf<List<CastDevice>>(emptyList()) }
    var searchTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(controller, searchTick, discoveryTimeoutMs) {
        discovering = true
        devices = controller.discover(discoveryTimeoutMs)
        discovering = false
    }

    // 当前正在投的那台不再出现在「可选设备」里：对它唯一有意义的动作是停止，已经在上一行
    val others = devices.filterNot { it.location == current?.location }

    PlayerSheet(title = stringResource(R.string.m_player_cast_title), onClose = onDismiss) {
        current?.let { device ->
            Row(
                Modifier.fillMaxWidth().height(CastRowHeight),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.m_player_cast_connected, device.friendlyName),
                    style = ModaType.Body,
                    color = ModaColors.Accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                SheetOption(
                    label = stringResource(R.string.m_player_cast_stop),
                    selected = true,
                    modifier = Modifier.width(CastActionWidth),
                    onClick = onStop,
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        when {
            discovering -> Row(
                Modifier.fillMaxWidth().height(CastRowHeight),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = ModaColors.Accent,
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.m_player_cast_searching),
                    style = ModaType.Body,
                    color = ModaColors.TextDim,
                )
            }

            // 空态：把「为什么没找到」直接写出来（同一 Wi-Fi + 电视要开 DLNA），
            // 否则用户只会看到一张空面板，以为 App 坏了
            others.isEmpty() -> {
                if (current == null) {
                    Text(
                        text = stringResource(R.string.m_player_cast_empty),
                        style = ModaType.Body,
                        color = ModaColors.TextDim,
                    )
                    Spacer(Modifier.height(12.dp))
                }
                SheetOption(
                    label = stringResource(R.string.m_player_cast_retry),
                    selected = false,
                    modifier = Modifier.width(CastActionWidth),
                    onClick = { searchTick++ },
                )
            }

            else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                others.forEach { device ->
                    CastDeviceRow(device = device, onPick = onPick)
                }
            }
        }

        if (failureText != null) {
            Spacer(Modifier.height(12.dp))
            Text(failureText, style = ModaType.Caption, color = ModaColors.Danger)
        }
    }
}

/** 一行设备：左边设备名（长名字 ellipsis），右边「连接」 */
@Composable
private fun CastDeviceRow(device: CastDevice, onPick: (CastDevice) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(CastRowHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = device.friendlyName,
            style = ModaType.Body,
            color = ModaColors.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        SheetOption(
            label = stringResource(R.string.m_player_cast_connect),
            selected = false,
            modifier = Modifier.width(CastActionWidth),
            onClick = { onPick(device) },
        )
    }
}

/**
 * 投屏状态提示条：投屏图标 + 「正在投屏到 <设备名>」。
 *
 * 顶栏里贴着投屏图标显示；控制层自动隐藏时，播放页会单独把它留在右上角——
 * 「正在投屏」是个持续状态，不该跟着 OSD 一起消失。
 */
@Composable
fun CastStatusChip(deviceName: String, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xB3000000))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Cast,
            contentDescription = null,
            tint = ModaColors.Accent,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = stringResource(R.string.m_player_cast_connected, deviceName),
            style = ModaType.Caption,
            color = ModaColors.Accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
