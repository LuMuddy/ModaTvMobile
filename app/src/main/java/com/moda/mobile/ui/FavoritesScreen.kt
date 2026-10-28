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
import androidx.compose.material3.AlertDialog
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
 * 收藏页：单列卡片行（封面 + 剧名 + 删除），顶部「清空全部」。
 *
 * 点条目进详情而不是直接播放——收藏是追剧语义，没有"该看哪一集"的答案，
 * 这一条与 TV 端一致，是数据含义决定的，与交互形态无关。
 *
 * @param onOpen 点条目进详情
 * @param onDelete 删除单条（传 cid，与 MainViewModel.removeFavorite 同形）
 * @param onClear 清空全部（已经过确认弹窗）
 */
@Composable
fun FavoritesScreen(
    entries: List<LibraryEntry>,
    onOpen: (LibraryEntry) -> Unit,
    onDelete: (String) -> Unit,
    onClear: () -> Unit,
) {
    var confirmClear by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(ModaColors.Bg)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.lib_fav_title, entries.size),
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
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        stringResource(R.string.lib_fav_empty),
                        style = ModaType.Body,
                        color = ModaColors.TextDim,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(6.dp))
                    // 空态里补一句"怎么才能有收藏"：手机上没有遥控器提示，用户第一次进来只会看到一片空
                    Text(
                        stringResource(R.string.lib_fav_empty_hint),
                        style = ModaType.Caption,
                        color = ModaColors.TextDim,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(items = entries, key = { entry -> entry.cid }) { entry ->
                    FavoriteRow(
                        entry = entry,
                        onOpen = { onOpen(entry) },
                        onDelete = { onDelete(entry.cid) },
                    )
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.clear_favorites_title)) },
            text = { Text(stringResource(R.string.lib_fav_clear_message, entries.size)) },
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
private fun FavoriteRow(entry: LibraryEntry, onOpen: () -> Unit, onDelete: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(ModaColors.Surface)
            .clickable(onClick = onOpen)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RemoteImage(
            url = entry.cover,
            title = entry.title,
            modifier = Modifier.size(width = 64.dp, height = 86.dp),
            corner = 10.dp,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                // 收藏可能是在详情页点"收藏"时进来的，标题一定有值；
                // 空标题只可能来自旧数据，回落到「未命名」而不是画一行空白
                entry.title.takeIf { it.isNotBlank() } ?: stringResource(R.string.detail_untitled),
                style = ModaType.Body,
                color = ModaColors.Text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            // 收藏卡多半没有片长（在详情页点收藏时还没播过），所以这条通常不出现；
            // 但从播放页收进来的那一条就该和历史页长得一样
            if (entry.durationMs > 0L) {
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

