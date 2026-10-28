package com.moda.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.moda.mobile.R
import com.moda.mobile.model.LibraryEntry
import com.moda.mobile.ui.components.RemoteImage
import com.moda.mobile.ui.theme.ModaColors
import com.moda.mobile.ui.theme.ModaType

/**
 * 历史记录页：单列卡片行（封面 + 剧名 + 集号 + 进度 + 删除），顶部只有「清空全部」。
 *
 * 手机端与 TV 端的两处差异：
 * 1. 触摸端不用网格 + 焦点跟焦那一套，改成一列卡片行：一行能同时放下封面、
 *    三段文字和一颗删除按钮，删除不必再靠"焦点落到哪一格"来定位（TV 端那套
 *    posterFocus/onMoveDown 在手指上完全没有对应物）。
 * 2. 「已看完」页的入口在首页顶部工具栏，不在这条顶栏上：历史页顶栏只放「返回 + 清空」，
 *    页内不再叠第二个出口。
 *
 * 屏幕自身无状态：条目流来自 MainViewModel，屏幕只负责画和转发点击。
 *
 * @param onResume 点条目直接续播（历史页语义：用户已经选过这一集了）
 * @param onDelete 删除单条（传 cid，与 MainViewModel.removeHistory 同形）
 * @param onClear 清空全部（已经过确认弹窗）
 * @param onOpenCompleted 进入「已看完」页。入口已移到首页顶部工具栏，本参数保留只是为了
 *   不改跨任务的调用签名（AppRoot 仍在传它），页内已无任何 UI 引用。
 * @param onBack 顶栏左上角返回：默认空实现，调用点还没接路由栈时也不会崩
 */
@Composable
fun HistoryScreen(
    entries: List<LibraryEntry>,
    onResume: (LibraryEntry) -> Unit,
    onDelete: (String) -> Unit,
    onClear: () -> Unit,
    onOpenCompleted: () -> Unit = {},
    onBack: () -> Unit = {},
) {
    // 清空是不可撤销的批量删除：先弹一次确认，确认后才真正调用 onClear
    var confirmClear by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(ModaColors.Bg)) {
        // 返回条与「清空全部」同在这条不滚动的顶栏里：列表滑两屏之后，
        // 放进 LazyColumn 的任何一颗都跟着滚走了，出口必须常驻（同 DetailScreen 的返回条）
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.m_action_back),
                    tint = ModaColors.Text,
                )
            }
            // 不额外加 Spacer：48dp 触控区已把 24dp 图标居中，图标到标题的视觉间距就是 12dp
            Text(
                stringResource(R.string.lib_hist_title, entries.size),
                style = ModaType.Title,
                color = ModaColors.Text,
                modifier = Modifier.weight(1f),
            )
            // 「已看完」入口已移到首页顶部工具栏；这条顶栏上只剩「清空全部」一个动作，
            // 所以它用 Danger 而不是次要灰：删数据是这个页面上唯一不可撤销的事
            TextButton(onClick = { confirmClear = true }, enabled = entries.isNotEmpty()) {
                Text(
                    stringResource(R.string.lib_clear_all),
                    style = ModaType.Body,
                    color = if (entries.isEmpty()) ModaColors.TextDim else ModaColors.Danger,
                )
            }
        }

        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.lib_hist_empty),
                    style = ModaType.Body,
                    color = ModaColors.TextDim,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(items = entries, key = { entry -> entry.cid }) { entry ->
                    HistoryRow(
                        entry = entry,
                        onResume = { onResume(entry) },
                        onDelete = { onDelete(entry.cid) },
                    )
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            // 标题复用设置页那条同义文案：同一件事不该有两套说法
            title = { Text(stringResource(R.string.clear_history_title)) },
            text = { Text(stringResource(R.string.lib_hist_clear_message, entries.size)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    onClear()
                }) {
                    Text(stringResource(R.string.clear_confirm_button), color = ModaColors.Danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text(stringResource(R.string.common_cancel), color = ModaColors.TextDim)
                }
            },
            containerColor = ModaColors.Surface,
        )
    }
}

@Composable
private fun HistoryRow(entry: LibraryEntry, onResume: () -> Unit, onDelete: () -> Unit) {
    // 整行可点 = 续播：手指落到哪都是"打开这一条"，删除是行内唯一的小目标，
    // 两者不会混淆（TV 端要靠"焦点停在海报还是删除键上"区分，触摸端没有这层含义）
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(ModaColors.Surface)
            .clickable(onClick = onResume)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RemoteImage(
            url = entry.cover,
            title = entry.title,
            modifier = Modifier.size(width = 72.dp, height = 96.dp),
            corner = 10.dp,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.title,
                style = ModaType.Body,
                color = ModaColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                episodeLabel(entry.episodeIndex),
                style = ModaType.Caption,
                color = ModaColors.TextDim,
                maxLines = 1,
            )
            if (entry.durationMs > 0L) {
                Spacer(Modifier.height(4.dp))
                // 时间点对时间点比"已看 32 分钟"精确，也和播放器控制层的时钟格式一致
                Text(
                    clockText(entry.positionMs) + " / " + clockText(entry.durationMs),
                    style = ModaType.Caption,
                    color = ModaColors.AccentSoft,
                    maxLines = 1,
                )
                // 进度条在时间文字下方、同一个 Column 里：左边缘因此对齐标题文字，而不是封面
                Spacer(Modifier.height(6.dp))
                EntryProgress(positionMs = entry.positionMs, durationMs = entry.durationMs)
            }
        }
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = onDelete) {
            Text(stringResource(R.string.lib_delete), style = ModaType.Body, color = ModaColors.TextDim)
        }
    }
}

/** 集号按人话显示：库里存的是 0 起下标，用户看到的是「第几集」 */
@Composable
private fun episodeLabel(episodeIndex: Int): String =
    if (episodeIndex < 0) {
        stringResource(R.string.lib_episode_unknown)
    } else {
        stringResource(R.string.lib_episode_no, episodeIndex + 1)
    }

/** 进度文案：mm:ss（超过一小时 h:mm:ss）。与播放器控制层同一个格式 */
private fun clockText(ms: Long): String {
    val total = ms.coerceAtLeast(0L) / 1000L
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    val mm = if (m < 10L) "0" + m else m.toString()
    val ss = if (s < 10L) "0" + s else s.toString()
    return if (h > 0L) h.toString() + ":" + mm + ":" + ss else mm + ":" + ss
}

/**
 * 一条 3dp 的细进度条：轨道 SurfaceHigh，进度 Accent，比例夹在 0..1。
 *
 * 只在 durationMs > 0 时被调用：片长未知就没有「进度」这个分母，
 * 画空轨或画满都是把「不知道」说成一个具体数值。跳片头/快进会让 positionMs
 * 越过 durationMs，慢倍速也会让两者不严格相等，所以比例必须夹取而不是直接除。
 */
@Composable
private fun EntryProgress(positionMs: Long, durationMs: Long) {
    val ratio = (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    Box(
        Modifier
            .fillMaxWidth()
            .height(3.dp)
            // 轨道与进度一起裁成 2dp 圆角：细长条上直角比 3dp 的高度更显眼
            .clip(RoundedCornerShape(2.dp))
            .background(ModaColors.SurfaceHigh),
    ) {
        Box(
            Modifier
                .fillMaxWidth(ratio)
                .fillMaxHeight()
                .background(ModaColors.Accent),
        )
    }
}

