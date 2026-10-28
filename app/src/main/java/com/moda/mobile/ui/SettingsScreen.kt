package com.moda.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.moda.mobile.BuildConfig
import com.moda.mobile.R
import com.moda.mobile.data.local.AuthSession
import com.moda.mobile.data.local.PlayerSettings
import com.moda.mobile.ui.theme.ModaColors
import com.moda.mobile.ui.theme.ModaType

/**
 * 设置页（手机端）= 四张分组入口卡。
 *
 * 为什么是「菜单 + 子页」而不是一屏平铺：平铺时版本号这种只能看的行和「清空收藏」
 * 这种一按就删的行混在同一列里，用户扫读时分不出哪些能按；而整页长度早就超出手机的可视高度。
 * 本页只回答「想改哪一类」，细节全在 SettingsSectionId 对应的子页里。
 *
 * 手机端与 TV 端的差别：整页是纵向 [LazyColumn]（不是焦点跟焦的 Column），每张卡都能直接点。
 * 菜单页只有这几张分组卡，不再单独摆一张授权状态卡：授权状态与「清除授权」都在
 * 「网络与数据」子页里，菜单页再摆一份等于同一句话在两个地方各说一半。
 *
 * @param session 当前授权会话；null == 未授权。兑换后立刻变，不需要重启
 * @param onOpenGroup 点分组卡：参数就是该进哪一页
 * @param onUpdatePlayerSettings 播放设置落盘的出口（AppRoot 接到 vm.updatePlayerSettings，
 *        写操作才跑在 viewModelScope 上，用户改完立刻按返回也不会把这次写取消掉）
 */
@Composable
fun SettingsScreen(
    session: AuthSession?,
    onClearHistory: () -> Unit,
    onClearFavorites: () -> Unit,
    onClearSearchHistory: () -> Unit,
    onOpenGroup: (SettingsSectionId) -> Unit,
    onUpdatePlayerSettings: ((PlayerSettings) -> Unit)? = null,
    versionName: String = BuildConfig.VERSION_NAME,
) {
    // 摘要要显示当前播放设置，所以状态提在这里（与子页共用同一套读法：见 rememberPlayerSettingsHolder）
    val holder = rememberPlayerSettingsHolder(onUpdatePlayerSettings)
    val settings = holder.value

    Column(Modifier.fillMaxSize().background(ModaColors.Bg)) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp)) {
            Text(stringResource(R.string.settings_title), style = ModaType.Title, color = ModaColors.Text)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.settings_subtitle), style = ModaType.Caption, color = ModaColors.TextDim)
        }
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SettingsEntryCard(
                    title = stringResource(SettingsSectionId.Playback.titleRes),
                    desc = stringResource(SettingsSectionId.Playback.descRes),
                    // 摘要由三个各自独立的档位文案拼成，分隔符是纯标点，不进资源表
                    summary = stringResource(settings.playerCore.labelRes) + " · " +
                        stringResource(settings.decoderMode.labelRes) + " · " +
                        speedLabelOf(settings.defaultSpeed),
                    onClick = { onOpenGroup(SettingsSectionId.Playback) },
                )
            }
            // 「分类排序」紧跟「播放设置」：两者都是"让界面按我的习惯来"，
            // 与下面两页（网络/关于：查状态、看版本）不是一类
            item {
                SettingsEntryCard(
                    title = stringResource(SettingsSectionId.HomeTabs.titleRes),
                    desc = stringResource(SettingsSectionId.HomeTabs.descRes),
                    summary = stringResource(R.string.m_settings_home_tabs_summary),
                    onClick = { onOpenGroup(SettingsSectionId.HomeTabs) },
                )
            }
            item {
                SettingsEntryCard(
                    title = stringResource(SettingsSectionId.Network.titleRes),
                    desc = stringResource(SettingsSectionId.Network.descRes),
                    // 摘要只说「已授权 / 未授权」：这是唯一影响用户能不能用的一句话，
                    // 到期时间这类凭据细节连子页都不显示（过期由后台判定，客户端自己回门）
                    summary = authStatusText(session),
                    onClick = { onOpenGroup(SettingsSectionId.Network) },
                )
            }
            item {
                SettingsEntryCard(
                    title = stringResource(SettingsSectionId.About.titleRes),
                    desc = stringResource(SettingsSectionId.About.descRes),
                    summary = stringResource(R.string.app_name) + " " + versionName,
                    onClick = { onOpenGroup(SettingsSectionId.About) },
                )
            }
        }
    }
}

/**
 * 顶层分组入口卡：左列标题 + 说明，右列当前值摘要 + 箭头。
 *
 * 用可点的 Box + 箭头而不是 Button：卡片式入口是手机设置页的通行形态，
 * 一行能承载"标题 / 说明 / 当前值"三层信息，按钮做不到。
 */
@Composable
private fun SettingsEntryCard(
    title: String,
    desc: String,
    summary: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(ModaColors.Surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = ModaType.Heading, color = ModaColors.Text)
            Spacer(Modifier.height(2.dp))
            Text(desc, style = ModaType.Caption, color = ModaColors.TextDim)
        }
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    summary,
                    style = ModaType.Body,
                    color = ModaColors.TextDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(6.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = ModaColors.TextDim,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
