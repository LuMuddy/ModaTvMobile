package com.moda.mobile.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.moda.mobile.BuildConfig
import com.moda.mobile.R
import com.moda.mobile.data.local.AuthSession
import com.moda.mobile.data.local.DecoderMode
import com.moda.mobile.data.local.PlayerCore
import com.moda.mobile.data.local.PlayerSettings
import com.moda.mobile.data.local.PlayerSettingsStoreImpl
import com.moda.mobile.data.local.PlayerSpeedOptions
import com.moda.mobile.model.HomeTab
import com.moda.mobile.ui.theme.ModaColors
import com.moda.mobile.ui.theme.ModaType
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * 设置三个子页 + 它们共用的行组件。
 *
 * 手机端与 TV 端在这里差别最大：TV 的每一个设置项都是"按确认键循环一档"（遥控器只有方向键），
 * 手机上有 Switch / Chip / 滑动手势，所以设置项全部换成手机控件——
 * 可见的候选一眼看全、直接点中目标，不再需要"按几次才轮到我想要的值"。
 * 数据与文案资源两边共用同一份（PlayerSettings / strings_*），改的只是壳。
 */

// ==================== 播放设置 ====================

/**
 * 播放设置的读与写。
 *
 * 菜单页要用它显示「当前值摘要」，播放子页要改它，两边必须是同一份状态：
 * 各自 remember 一份的话，子页改完返回菜单，摘要要等重新读盘才更新，中间那一帧是旧值。
 */
internal class PlayerSettingsHolder(private val onWrite: (PlayerSettings) -> Unit) {
    var value by mutableStateOf(PlayerSettings())

    fun apply(next: PlayerSettings) {
        value = next
        onWrite(next)
    }
}

/**
 * @param onUpdatePlayerSettings 落盘出口。AppRoot 传进来的是 vm::updatePlayerSettings，
 *        写操作跑在 viewModelScope 上，即便用户改完立刻按返回，写也不会随组合一起被取消。
 */
@Composable
internal fun rememberPlayerSettingsHolder(
    onUpdatePlayerSettings: ((PlayerSettings) -> Unit)? = null,
): PlayerSettingsHolder {
    val context = LocalContext.current
    val store = remember(context) { PlayerSettingsStoreImpl.create(context) }
    val scope = rememberCoroutineScope()
    val holder = remember(store) {
        PlayerSettingsHolder { next ->
            val sink = onUpdatePlayerSettings
            if (sink != null) {
                sink(next)
            } else {
                // 写失败只影响下次启动读到的值，不该让设置页卡住或报错
                scope.launch { runCatching { store.save(next) } }
            }
        }
    }
    LaunchedEffect(store) {
        holder.value = store.current()
    }
    return holder
}

/**
 * 子页共用的骨架：顶栏（返回 + 标题）+ 唯一一个滚动区。
 *
 * 手机上没有物理返回键的位移焦虑，但顶栏返回按钮仍然必须有：手势返回在部分定制系统上
 * 被系统导航吃掉，只留手势等于把出口交给用户去猜。它触发的是同一套返回分发
 * （LocalOnBackPressedDispatcher），所以 AppRoot 的 BackHandler 会照常 pop —— 
 * 子页里不自己动路由栈，路由只有一个主人。
 */
@Composable
internal fun SettingsSectionScaffold(
    title: String,
    subtitle: String? = null,
    /**
     * 内容是否由骨架自己滚动，默认 true（Column + verticalScroll）。
     *
     * 内容本身是 [LazyColumn] 时必须传 false：外层 verticalScroll 会把「无限高」约束交给
     * LazyColumn，它按同向嵌套滚动直接抛异常（"measured with an infinity maximum height
     * constraint"）。关掉外层滚动后，滚动权整个交给内容自己，按钮之类的固定行也不会被滚走。
     */
    scrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    Column(Modifier.fillMaxSize().background(ModaColors.Bg)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { backDispatcher?.onBackPressed() }) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.m_action_back),
                    tint = ModaColors.Text,
                )
            }
            Text(title, style = ModaType.Title, color = ModaColors.Text)
        }
        if (subtitle != null) {
            Text(
                subtitle,
                style = ModaType.Caption,
                color = ModaColors.TextDim,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
        val contentModifier = if (scrollable) {
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 32.dp)
        } else {
            // 不滚动时也不加内边距：LazyColumn 自己带 contentPadding，两边各加一份就成了双份留白
            Modifier.fillMaxWidth().weight(1f)
        }
        Column(contentModifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            content()
        }
    }
}

/**
 * 播放设置子页：内核 / 软硬解 / 默认倍速 / 弹幕默认开关。
 *
 * 只保留 TV 端那四项：其余档位（弹幕字号、区域、画面比例）在播放页面板里就地调，
 * 它们是"这一部剧现在怎么看"而不是"下次起播的默认"，摆到设置页只会让默认值有两个家。
 */
@Composable
internal fun PlaybackSettingsSection(holder: PlayerSettingsHolder) {
    val context = LocalContext.current
    // IJK 探测只做一次：读 nativeLibraryDir 是 IO，不该跟着每次重排重跑
    val ijkUsable = remember(context) { ijkNativeLibPresent(context) }
    val settings = holder.value
    // 用 for 循环而不是 map 收集文案：stringResource 是 @Composable，写在 for 循环体里
    // 没有 lambda 边界的问题，读起来也和"枚举 → 文案"一一对应
    val coreLabels = ArrayList<String>(PlayerCore.entries.size)
    for (core in PlayerCore.entries) coreLabels.add(stringResource(core.labelRes))
    val decoderLabels = ArrayList<String>(DecoderMode.entries.size)
    for (decoder in DecoderMode.entries) decoderLabels.add(stringResource(decoder.labelRes))
    // 倍速档位的 label 是纯数字串（"1.5x"），不经过资源表，直接取
    val speedLabels = PlayerSpeedOptions.map { it.label }

    SettingsSectionScaffold(title = stringResource(SettingsSectionId.Playback.titleRes)) {
        SectionCard {
            // 内核：按 PlayerCore.entries 生成，而不是手写两个选项。
            // 枚举以后加档位时这里自动跟上，不用再回来改一遍布局
            SettingItem(label = stringResource(R.string.settings_playback_core)) {
                ChoiceChips(
                    labels = coreLabels,
                    selectedIndex = PlayerCore.entries.indexOf(settings.playerCore).coerceAtLeast(0),
                    onSelect = { index -> holder.apply(settings.copy(playerCore = PlayerCore.entries[index])) },
                )
            }
            // 选了 IJK 但这台机器没有 so 时必须如实提示：播放侧会静默回退 Exo，
            // 不说明用户会以为设置没生效
            if (settings.playerCore == PlayerCore.Ijk && !ijkUsable) {
                Text(
                    stringResource(R.string.settings_playback_core_warning),
                    style = ModaType.Caption,
                    color = ModaColors.Danger,
                )
            }
            HintText(stringResource(R.string.settings_playback_core_hint))

            SettingItem(label = stringResource(R.string.settings_playback_decoder)) {
                ChoiceChips(
                    labels = decoderLabels,
                    selectedIndex = DecoderMode.entries.indexOf(settings.decoderMode).coerceAtLeast(0),
                    onSelect = { index -> holder.apply(settings.copy(decoderMode = DecoderMode.entries[index])) },
                )
            }
            // 这段说明必须准确：ExoPlayer 没打包 ffmpeg 扩展，它的「软解」
            // 只能是挑系统自带的软件编解码器，系统没带就仍退回硬解；
            // 只有 IJK 的软解才是真正打包在 so 里的 ffmpeg
            HintText(stringResource(R.string.settings_playback_decoder_hint))

            SettingItem(label = stringResource(R.string.settings_playback_default_speed)) {
                ChoiceChips(
                    labels = speedLabels,
                    selectedIndex = PlayerSpeedOptions
                        .indexOfFirst { it.value == settings.defaultSpeed }
                        .coerceAtLeast(0),
                    onSelect = { index ->
                        holder.apply(settings.copy(defaultSpeed = PlayerSpeedOptions[index].value))
                    },
                )
            }
            HintText(stringResource(R.string.settings_playback_default_speed_hint))

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.settings_playback_danmaku),
                    style = ModaType.Body,
                    color = ModaColors.Text,
                    modifier = Modifier.weight(1f),
                )
                // 开关用 Switch 而不是 Chip：它是二值、且"当前是开还是关"要在扫读时一眼看出，
                // 两个并排的 Chip 会把"未选中的那个"读成第三种状态
                Switch(
                    checked = settings.danmakuEnabled,
                    onCheckedChange = { enabled -> holder.apply(settings.copy(danmakuEnabled = enabled)) },
                )
            }
            HintText(stringResource(R.string.settings_playback_danmaku_hint))
        }
    }
}

/**
 * 本机是否存在 IJK 的原生库。
 *
 * 为什么不问播放侧的 PlayerEngines：那是 T5 的文件，这里多一条跨任务依赖只会让
 * 两个任务的编译顺序互相绑死。判据本身很直接——IJK 的三个 so（ijkffmpeg / ijkplayer /
 * ijksdl）由打包器放进 nativeLibraryDir，本机装了哪些一眼可数。
 * 读盘失败一律当作"没有"：宁可多提示一句"实际仍在用 ExoPlayer"，也不要让用户以为切换生效了。
 */
private fun ijkNativeLibPresent(context: Context): Boolean = runCatching {
    val dir = context.applicationInfo.nativeLibraryDir ?: return@runCatching false
    File(dir).list()?.any { name -> name.contains("ijk", ignoreCase = true) } == true
}.getOrDefault(false)

// ==================== 网络与数据 ====================

/**
 * 「清空」类操作的确认意图：标题、警示文案与完成提示跟着它走。
 * 存资源 id 而不是字符串：枚举一旦把文案固化在构造里，切换系统语言时这几句不会跟着变。
 */
private enum class PendingClear(
    @param:StringRes val titleRes: Int,
    @param:StringRes val messageRes: Int,
    /** 完成提示；null = 确认之后整屏就换了（清除授权会直接退回授权门），没必要再说一句 */
    @param:StringRes val doneTextRes: Int?,
) {
    History(
        titleRes = R.string.clear_history_title,
        messageRes = R.string.clear_history_message,
        doneTextRes = R.string.clear_history_done,
    ),
    Favorites(
        titleRes = R.string.clear_favorites_title,
        messageRes = R.string.clear_favorites_message,
        doneTextRes = R.string.clear_favorites_done,
    ),
    SearchHistory(
        titleRes = R.string.clear_search_title,
        messageRes = R.string.clear_search_message,
        doneTextRes = R.string.clear_search_done,
    ),
    Completed(
        // 标题直接复用「已看完」页那一句（lib_done_clear_title）：同一个动作在两个入口
        // 各写一遍文案，早晚会有一个入口先改瞎
        titleRes = R.string.lib_done_clear_title,
        messageRes = R.string.clear_completed_message,
        doneTextRes = R.string.clear_completed_done,
    ),
    Auth(
        // 与「清空」共用一个弹窗：都是不可撤销、都要二次确认
        titleRes = R.string.clear_auth_title,
        messageRes = R.string.clear_auth_message,
        doneTextRes = null,
    ),
}

/**
 * 网络与数据子页：登录账号（只读）+ 四项数据清理 + 清除授权。
 *
 * 登录账号那一行**只显示、不可操作**：扫码 / 填码只有一个入口——启动时的授权门
 * （AuthGateScreen），本页再放一个入口就是同一个动作的第二种说法。
 *
 * 「清除授权」是这一页唯一的账号动作：真删（vm.signOut 走 Auth.signOut 清内存再清盘，
 * 然后会话置空、界面自己退回授权门），不是"本地装作未授权"。
 *
 * @param onClearAuth 即 AppRoot 传进来的 vm::signOut
 */
@Composable
internal fun NetworkSettingsSection(
    session: AuthSession?,
    onClearHistory: () -> Unit,
    onClearFavorites: () -> Unit,
    onClearSearchHistory: () -> Unit,
    onClearCompleted: () -> Unit,
    onClearAuth: () -> Unit,
) {
    // 五项（四项清数据 + 清除授权）都是不可撤销的，点一下先弹确认，确认后才真正执行
    var pendingClear by remember { mutableStateOf<PendingClear?>(null) }
    // 清空后的反馈：文案在弹窗确认那一刻就按当前语言取好（见 PendingClear.doneTextRes）
    var notice by remember { mutableStateOf<String?>(null) }

    SettingsSectionScaffold(
        title = stringResource(SettingsSectionId.Network.titleRes),
        subtitle = stringResource(R.string.settings_network_subtitle),
    ) {
        SectionCard {
            // 这里原来还有一行只读的「登录账号」，已删：设置页顶部的授权状态区
            // 已经在显示同一个账号名，同一屏再来一行是同一件事的第二种说法；
            // 删掉之后本页每一行都直接改数据，扫读时不会再把只读行读成可点行。
            SettingRow(
                label = stringResource(R.string.settings_network_clear_history),
                value = stringResource(R.string.common_clear),
                onClick = { pendingClear = PendingClear.History },
            )
            SettingRow(
                label = stringResource(R.string.settings_network_clear_favorites),
                value = stringResource(R.string.common_clear),
                onClick = { pendingClear = PendingClear.Favorites },
            )
            SettingRow(
                label = stringResource(R.string.settings_network_clear_search),
                value = stringResource(R.string.common_clear),
                onClick = { pendingClear = PendingClear.SearchHistory },
            )
            SettingRow(
                label = stringResource(R.string.settings_network_clear_completed),
                value = stringResource(R.string.common_clear),
                onClick = { pendingClear = PendingClear.Completed },
            )
            // 清除授权排在最末：它是全页唯一会把自己锁回门里的动作，
            // 与上面几行"清数据"混排时越靠后越不容易被连按误触（而且还要过一道确认）。
            // 只在真的已授权时出现：未授权时它没有任何东西可清，多一行就是多一次误按
            if (session != null) {
                SettingRow(
                    label = stringResource(R.string.settings_network_clear_auth),
                    value = stringResource(R.string.common_clear),
                    onClick = { pendingClear = PendingClear.Auth },
                )
            }
        }
        Text(
            stringResource(R.string.settings_network_clear_hint),
            style = ModaType.Caption,
            color = ModaColors.TextDim,
        )
        // 操作反馈落在卡片之下：它可能来自这一页的任意一处，
        // 挂在某一行内部会让别处触发的消息跑到看不见的位置
        notice?.let { text ->
            Text(text, style = ModaType.Body, color = ModaColors.AccentSoft)
        }
    }

    pendingClear?.let { pending ->
        // onConfirm 是普通 lambda，取不到 stringResource，所以在组合里先解析好
        val doneText = pending.doneTextRes?.let { stringResource(it) }
        AlertDialog(
            onDismissRequest = { pendingClear = null },
            title = { Text(stringResource(pending.titleRes)) },
            text = { Text(stringResource(pending.messageRes)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingClear = null
                    when (pending) {
                        PendingClear.History -> onClearHistory()
                        PendingClear.Favorites -> onClearFavorites()
                        PendingClear.SearchHistory -> onClearSearchHistory()
                        PendingClear.Completed -> onClearCompleted()
                        PendingClear.Auth -> onClearAuth()
                    }
                    if (doneText != null) notice = doneText
                }) {
                    Text(stringResource(R.string.clear_confirm_button), color = ModaColors.Danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingClear = null }) {
                    Text(stringResource(R.string.common_cancel), color = ModaColors.TextDim)
                }
            },
            containerColor = ModaColors.Surface,
        )
    }
}

// ==================== 关于 ====================

/**
 * 关于子页：五行只读信息 + 一行「检查更新」+ 一行版权声明入口。
 *
 * 版权声明入口放在这里而不是顶层菜单页：菜单页那三张卡是"进去改东西"，
 * 而这一页本来就是"看看这软件是什么"，用户想回看声明时第一反应是来这里找版本号旁边。
 * 「检查更新」同理：它是"这个软件现在是什么版本"的延伸，紧贴版本号与构建号下方。
 */
@Composable
internal fun AboutSettingsSection(
    /** 重看版权声明；真正的弹窗挂在 AppRoot 上，与启动时自动弹出的那一个是同一个 */
    onOpenCopyright: () -> Unit,
    /** 当前更新状态：这一行的值完全由它决定（检查中/发现 vX/已是最新…） */
    updateUi: UpdateUi,
    /** 点「检查更新」：manual = true，失败会在这个弹窗里说出来 */
    onCheckUpdate: () -> Unit,
    versionName: String = BuildConfig.VERSION_NAME,
    versionCode: Int = BuildConfig.VERSION_CODE,
) {
    SettingsSectionScaffold(title = stringResource(SettingsSectionId.About.titleRes)) {
        SectionCard {
            SettingRow(
                label = stringResource(R.string.settings_about_app_name),
                value = stringResource(R.string.app_name),
            )
            SettingRow(
                label = stringResource(R.string.settings_about_version),
                value = versionName,
            )
            SettingRow(
                label = stringResource(R.string.settings_about_build),
                value = versionCode.toString(),
            )
            // 「检查更新」紧贴构建号下方：用户找版本信息时视线就在这一块，
            // 值直接显示当前状态（检查中… / 发现 vX / 已是最新），点一下才发请求。
            // 没有新版本时这里显示「已是最新」，但**不弹窗**——"没检测到就没有"
            SettingRow(
                label = stringResource(R.string.update_row_title),
                value = updateRowValue(updateUi),
                onClick = onCheckUpdate,
            )
            SettingRow(
                label = stringResource(R.string.copyright_title),
                value = stringResource(R.string.settings_copyright_value),
                onClick = onOpenCopyright,
            )
        }
    }
}

/**
 * 「检查更新」那一行的当前值（这一行的值 = 更新状态机的读法）。
 *
 * - Latest 说「已是最新」：只有它代表检查真的跑完并确认过（含仓库还没有 Release）；
 * - Idle 说「点按检查」：它同时表示"还没查过""静默检查在飞""静默检查失败了"，
 *   拿它显示「已是最新」会让冷启动那几帧和联网失败后都在说谎——宁可空也不冒充；
 * - Failed 说「点按检查」：包没装成功但检查本身是成功的，说「已是最新」会与弹窗里的
 *   失败说明互相打脸，给一句邀请重试的文案更诚实；
 * - 检查中 / 已发现新版（下载中、待安装都算）各自给对应文案。
 *
 * 只做文案映射，不做任何判断：状态机在 MainViewModel，这一行不该有自己的第二套结论。
 */
@Composable
private fun updateRowValue(state: UpdateUi): String = when (state) {
    is UpdateUi.Checking -> stringResource(R.string.update_checking)
    is UpdateUi.Available -> stringResource(R.string.update_row_found, state.info.versionName)
    is UpdateUi.Downloading -> stringResource(R.string.update_row_found, state.info.versionName)
    is UpdateUi.Ready -> stringResource(R.string.update_row_found, state.info.versionName)
    is UpdateUi.Latest -> stringResource(R.string.update_row_latest)
    is UpdateUi.Idle -> stringResource(R.string.update_row_idle)
    is UpdateUi.Failed -> stringResource(R.string.update_row_idle)
}

// ==================== 分类排序 ====================

/**
 * 首页分类排序子页：19 个分类全量列出，第一项（推荐）固定，其余可上移 / 下移。
 *
 * 为什么用 [LazyColumn] 而不是骨架自带的滚动 Column：19 行、每行两个按钮，一次性测量全部行
 * 既没必要，也会让「恢复默认」被卷到最后一屏之下（见 [SettingsSectionScaffold] 的 scrollable）。
 *
 * @param order 当前顺序，只读。写入口只有 MainViewModel.moveHomeTab / resetHomeTabOrder：
 *        UI 不持有副本、也不本地重排，本页只做「读顺序 + 发指令」，顺序永远只有一份
 * @param onMove (fromIndex, toIndex)，两个下标都是 [order] 里的位置，语义与 VM 完全一致
 * @param onReset 恢复默认（删键 + 内存复位）
 * @param onLeave 离开本页的唯一出口。接线在 AppRoot，实现是「先 pop 回首页，再整页刷新一次」——
 *        分类顺序变了之后首页那一屏还是按旧顺序装的，只 pop 不重拉的话用户会看到
 *        「旧顺序的工具栏 + 新内容」对不上的一帧；整页刷新让首页内容与新的分类顺序
 *        一起落到干净状态
 */
@Composable
internal fun HomeTabsSection(
    order: List<HomeTab>,
    onMove: (Int, Int) -> Unit,
    onReset: () -> Unit,
    onLeave: () -> Unit,
) {
    // 二次确认：恢复默认会丢掉用户手调的排列，而且没有撤销
    var confirmingReset by remember { mutableStateOf(false) }

    // 系统返回键 / 侧滑手势也要走同一个出口。这条 BackHandler 晚于 AppRoot 那条注册，
    // 返回分发是「后注册先响应」，于是顶栏返回按钮（走 dispatcher）、侧滑、返回键
    // 三条路都落到 onLeave 上，pop + refreshHome 一条都不漏。
    BackHandler(onBack = onLeave)

    // 拖动状态：dragTab 记「哪个分类被按住」，dragOffsetY 记它相对自己槽位的竖直位移，
    // dragIndex 记它此刻在 order 里的下标（换位后就地更新）。
    // **顺序本身只有一份**：VM 的 homeTabOrder。这里不复制列表，越过一行边界就立刻
    // onMove 提交一次，页面渲染的永远是 VM 发下来的那一份（VM 内部 cancel 上一次落盘）。
    val listState = rememberLazyListState()
    var dragTab by remember { mutableStateOf<HomeTab?>(null) }
    var dragIndex by remember { mutableStateOf(-1) }
    var dragOffsetY by remember { mutableStateOf(0f) }

    SettingsSectionScaffold(
        title = stringResource(SettingsSectionId.HomeTabs.titleRes),
        subtitle = stringResource(SettingsSectionId.HomeTabs.descRes),
        scrollable = false,
    ) {
        // 骨架的 header（标题 + 副标题）与本内容区是同一条 Column 的兄弟节点，列表只能排在它下方。
        // 这里显式留 8dp：副标题很长时（本页描述是整句）它会折成两行，紧贴的话首行
        // 「推荐 / 固定」看上去像被压住——那是本页最重要的状态说明，必须首屏第一行完整可见。
        Spacer(Modifier.height(8.dp))
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().weight(1f),
            // 左右 16dp 与标题、下方按钮对齐；底部 8dp 让末行不贴着「恢复默认」。
            // 顶部不再叠 contentPadding：上边距已经由上面的 Spacer 给过，重复会给成两份
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // key 用枚举 name 而不是下标：拖动全过程都在改下标，key 若跟着下标走，
            // 被拖的行会在中途被当成"另一个 item"重建，手指下的手势随之断掉
            itemsIndexed(order, key = { _, tab -> tab.name }) { index, tab ->
                // 手势块只在首次组合时捕获一次，读不到最新的 index —— 用 rememberUpdatedState
                // 把当前下标递进去，拖动开始时才取得到正确的位置
                val latestIndex by rememberUpdatedState(index)
                val dragging = dragTab == tab
                HomeTabOrderRow(
                    name = stringResource(tab.titleRes),
                    pinned = index == 0,
                    dragging = dragging,
                    dragOffsetY = if (dragging) dragOffsetY else 0f,
                    onDragStart = {
                        dragTab = tab
                        dragIndex = latestIndex
                        dragOffsetY = 0f
                    },
                    onDragDelta = { dy ->
                        dragOffsetY += dy
                        val visible = listState.layoutInfo.visibleItemsInfo
                        val fromInfo = visible.firstOrNull { it.index == dragIndex }
                        if (fromInfo != null) {
                            // 被拖行的中心线现在压在谁身上，落点就是谁（行高不等也成立）
                            val center = fromInfo.offset + fromInfo.size / 2f + dragOffsetY
                            val hit = visible.firstOrNull {
                                center >= it.offset && center < it.offset + it.size
                            }
                            // 【守卫】0 号位是「推荐」：目标下标 clamp 到 ≥1，任何行都不许落到它上面
                            val target = hit?.takeIf { it.index >= 1 && it.index <= order.lastIndex }
                            // from == to 不提交（原地等于白写一次盘）
                            if (target != null && target.index != dragIndex) {
                                // 跟手：扣掉这次换位造成的位移差，卡片不会跳一下
                                dragOffsetY += fromInfo.offset - target.offset
                                val from = dragIndex
                                dragIndex = target.index
                                onMove(from, target.index)
                            }
                        }
                    },
                    onDragEnd = {
                        dragTab = null
                        dragIndex = -1
                        dragOffsetY = 0f
                    },
                )
            }
        }
        // 按钮留在列表之外：19 行滚到哪一屏都点得到
        Button(
            onClick = { confirmingReset = true },
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        ) {
            Text(stringResource(R.string.m_home_tabs_reset))
        }
    }

    if (confirmingReset) {
        AlertDialog(
            onDismissRequest = { confirmingReset = false },
            title = { Text(stringResource(R.string.m_home_tabs_reset_title)) },
            text = { Text(stringResource(R.string.m_home_tabs_reset_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingReset = false
                    onReset()
                }) {
                    Text(stringResource(R.string.m_home_tabs_reset_confirm), color = ModaColors.AccentSoft)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingReset = false }) {
                    Text(stringResource(R.string.common_cancel), color = ModaColors.TextDim)
                }
            },
            containerColor = ModaColors.Surface,
        )
    }
}

/**
 * 排序列表的一行：左侧分类名，右侧拖拽把手（「推荐」那行换成「固定」标记）。
 *
 * **「推荐不可移动」的第一道守卫就在本行**：第一项不渲染把手 = 没有手势入口，拖不起来。
 * 另外两道在别处：HomeTabsSection 的落点计算把目标下标 clamp 到 ≥1；
 * MainViewModel.moveHomeTab 对 fromIndex/toIndex == 0 直接 return。
 *
 * 拖动时这张卡用 [Modifier.offset] 跟手、用 [Modifier.zIndex] 抬到相邻行之上；
 * 别的行不需要单独做"让位动画"——onMove 已经把顺序改成新值，列表本身就换位了。
 */
@Composable
private fun HomeTabOrderRow(
    name: String,
    pinned: Boolean,
    dragging: Boolean,
    dragOffsetY: Float,
    onDragStart: () -> Unit,
    onDragDelta: (Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    // pointerInput(Unit) 的手势块只捕获一次，回调必须每次读最新的一份：
    // 直接用捕获的 lambda，拖动到第二行时读到的还是开始那一刻的那份
    val startDrag by rememberUpdatedState(onDragStart)
    val dragBy by rememberUpdatedState(onDragDelta)
    val endDrag by rememberUpdatedState(onDragEnd)

    Row(
        Modifier
            .fillMaxWidth()
            // zIndex 必须在最外层：拖动的那张卡要盖住相邻行，否则会被后画的行切掉一截
            .zIndex(if (dragging) 1f else 0f)
            .offset { IntOffset(0, if (dragging) dragOffsetY.roundToInt() else 0) }
            .clip(RoundedCornerShape(14.dp))
            .background(if (dragging) ModaColors.SurfaceHigh else ModaColors.Surface)
            .padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            name,
            style = ModaType.Body,
            color = ModaColors.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (pinned) {
            HomeTabPinnedBadge()
        } else {
            // 手势只挂在把手上，不挂整行：整行可拖会和列表自身的竖直滚动抢同一个手势
            Box(
                Modifier
                    .size(48.dp) // ≥44dp 触控区
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { startDrag() },
                            onDrag = { change, amount ->
                                change.consume()
                                dragBy(amount.y)
                            },
                            onDragEnd = { endDrag() },
                            onDragCancel = { endDrag() },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.DragHandle,
                    contentDescription = stringResource(R.string.m_home_tabs_drag_handle),
                    tint = ModaColors.TextDim,
                )
            }
        }
    }
}

/** 「固定」标记：第一项不可移动，用一枚静态小标签说清楚"为什么这行没有把手" */
@Composable
private fun HomeTabPinnedBadge() {
    Text(
        stringResource(R.string.m_home_tabs_pinned),
        style = ModaType.Caption,
        color = ModaColors.AccentSoft,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(ModaColors.SurfaceHigh)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

// ==================== 共用行组件 ====================

/**
 * 授权状态只有一句话：已授权 / 未授权。
 *
 * 剩余时长、到期时刻都刻意不显示：它们只解释「这份凭据长什么样」，不决定用户能做什么；
 * 过期与否也不再需要用户自己盯——后台判死时客户端会自己回到授权门。
 */
@Composable
internal fun authStatusText(session: AuthSession?): String = if (session == null) {
    stringResource(R.string.settings_auth_unauthorized)
} else {
    stringResource(R.string.settings_auth_authorized)
}

/** 倍速浮点 → 档位文案；落不到任何档位时退回第一档，绝不显示原始浮点 */
internal fun speedLabelOf(value: Float): String =
    PlayerSpeedOptions.firstOrNull { it.value == value }?.label ?: PlayerSpeedOptions.first().label

/** 段内说明文字。不可聚焦、不可点，只负责解释它上面那一行到底在改什么 */
@Composable
internal fun HintText(text: String) {
    Text(text, style = ModaType.Caption, color = ModaColors.TextDim)
}

/** 卡片容器：一组设置项共用一块 Surface，项与项之间用细分隔线读起来才是一组 */
@Composable
internal fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(ModaColors.Surface)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        content()
    }
}

/** 一个设置项：左侧固定标签，下面是控件区（Chip 行 / 开关等） */
@Composable
private fun SettingItem(label: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(label, style = ModaType.Body, color = ModaColors.Text)
        Spacer(Modifier.height(6.dp))
        content()
    }
}

/**
 * 一组互斥选项的横向 Chip 行。
 *
 * 为什么用 Chip 而不是下拉/分段条：候选只有 2~6 个，Chip 一次把它们全摊开，
 * "现在选的是哪个"与"还有哪些可选"在同一行里同时可见，手指一次点中目标。
 * 超宽时整行横滑（倍速有 6 档，窄屏上一行放不下）。
 */
@Composable
private fun ChoiceChips(labels: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        labels.forEachIndexed { index, label ->
            FilterChip(
                selected = index == selectedIndex,
                onClick = { onSelect(index) },
                label = { Text(label, style = ModaType.Caption) },
            )
        }
    }
}

/**
 * 只读 / 可点两用的设置行。
 *
 * onClick 为 null 时就是一行纯展示（不画箭头、不接收点击）：
 * 箭头是"这里点得动"的唯一承诺，给只读行画箭头等于骗一次点按。
 */
@Composable
private fun SettingRow(
    label: String,
    value: String,
    onClick: (() -> Unit)? = null,
) {
    val rowModifier = if (onClick != null) {
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp)
    } else {
        Modifier.fillMaxWidth().padding(vertical = 10.dp)
    }
    Row(rowModifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = ModaType.Body,
            color = ModaColors.Text,
            // 标签固定吃满左侧剩余宽度，且单行省略：它再长也不该把这一行撑成两行
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        // 值一律贴行尾右对齐。外层 Box 的 weight 只是"值最多占半行"的宽度预算，
        // 值本身没有 weight、由 textAlign = End 贴到预算右沿：
        // 短值（Moda / 1.0.0 / 已是最新）因此贴着右侧而不是从半屏处起排，
        // 长值（ExoPlayer · 硬解 · 1.0x）就地省略，两者都不会把左边的标签挤走。
        // 早先两侧都 weight(1f) 时值是从整行中点开始排的，这就是截图上那个现象。
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            Text(
                value,
                style = ModaType.Body,
                color = ModaColors.TextDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (onClick != null) {
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = ModaColors.TextDim,
            )
        }
    }
}
