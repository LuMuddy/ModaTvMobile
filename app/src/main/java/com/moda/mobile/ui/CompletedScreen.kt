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
 * 已看完页：单列卡片行（封面 + 剧名 + 集号 + 片长 + 「看完」角标 + 删除）。
 *
 * 与历史页并列但语义不同：这一页是按**集**记录的（一部剧可以看好几集），
 * 所以删除传的是整个 entry（MainViewModel.removeCompleted 要 cid + episodeIndex 两个值），
 * 屏幕不必知道"删一集"与"删一条"的区别。
 *
 * @param onDelete 删除这一集（传 entry，与 AppRoot 里的 removeCompleted(cid, episodeIndex) 对应）
 * @param onBack 顶栏左上角返回：默认空实现，调用点还没接路由栈时也不会崩
 */
@Composable
fun CompletedScreen(
    entries: List<LibraryEntry>,
    onResume: (LibraryEntry) -> Unit,
    onDelete: (LibraryEntry) -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit = {},
) {
    var confirmClear by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(ModaColors.Bg)) {
        // 返回条常驻在滚动区之外，理由同 DetailScreen：列表滑两屏后还得够得着出口
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
                stringResource(R.string.lib_done_title, entries.size),
                style = ModaType.Title,
                color = ModaColors.Text,
                modifier = Modifier.weight(1f),
            )
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
                    stringResource(R.string.lib_done_empty),
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
                items(items = entries, key = { entry -> entry.cid + "#" + entry.episodeIndex }) { entry ->
                    CompletedRow(
                        entry = entry,
                        onResume = { onResume(entry) },
                        onDelete = { onDelete(entry) },
                    )
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.lib_done_clear_title)) },
            text = { Text(stringResource(R.string.lib_done_clear_message, entries.size)) },
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
private fun CompletedRow(entry: LibraryEntry, onResume: () -> Unit, onDelete: () -> Unit) {
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    entry.title,
                    style = ModaType.Body,
                    color = ModaColors.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(6.dp))
                // 「看完」角标：这一页的重点就是"这一集已经看完了"，角标比一句话便宜
                Text(
                    stringResource(R.string.lib_done_badge),
                    style = ModaType.Caption,
                    color = ModaColors.AccentSoft,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(ModaColors.SurfaceHigh)
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                episodeLabel(entry.episodeIndex),
                style = ModaType.Caption,
                color = ModaColors.TextDim,
                maxLines = 1,
            )
            if (entry.durationMs > 0L) {
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.lib_duration, clockText(entry.durationMs)),
                    style = ModaType.Caption,
                    color = ModaColors.TextDim,
                    maxLines = 1,
                )
                // 完成态条目的 positionMs 就是片长（MainViewModel 写入时钉死的），所以这里天然画满
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

/** 片长文案：mm:ss（超过一小时 h:mm:ss） */
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

