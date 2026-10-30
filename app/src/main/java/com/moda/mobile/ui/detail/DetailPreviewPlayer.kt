package com.moda.mobile.ui.detail

import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.outlined.SubtitlesOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.moda.mobile.R
import com.moda.mobile.data.local.AspectMode
import com.moda.mobile.data.local.PlaybackOverride
import com.moda.mobile.data.local.PlaybackOverrideStoreImpl
import com.moda.mobile.data.local.PlayerSettings
import com.moda.mobile.data.local.PlayerSettingsStoreImpl
import com.moda.mobile.model.AppError
import com.moda.mobile.model.Danmaku
import com.moda.mobile.model.Episode
import com.moda.mobile.model.PlayerError
import com.moda.mobile.model.Tier
import com.moda.mobile.ui.DetailPreview
import com.moda.mobile.ui.components.RemoteImage
import com.moda.mobile.ui.components.appErrorText
import com.moda.mobile.ui.components.playerErrorText
import com.moda.mobile.ui.player.AspectSheet
import com.moda.mobile.ui.player.CastController
import com.moda.mobile.ui.player.CastDevice
import com.moda.mobile.ui.player.CastSheet
import com.moda.mobile.ui.player.CastStatusChip
import com.moda.mobile.ui.player.ControlsBottomInset
import com.moda.mobile.ui.player.DanmakuOverlay
import com.moda.mobile.ui.player.EpisodeSheet
import com.moda.mobile.ui.player.PlaybackSession
import com.moda.mobile.ui.player.PlayerBottomBar
import com.moda.mobile.ui.player.PlayerEngine
import com.moda.mobile.ui.player.PlayerPanel
import com.moda.mobile.ui.player.PlayerSheet
import com.moda.mobile.ui.player.PlayerTopBar
import com.moda.mobile.ui.player.QualitySheet
import com.moda.mobile.ui.player.SheetOption
import com.moda.mobile.ui.player.SpeedSheet
import com.moda.mobile.ui.player.findActivity
import com.moda.mobile.ui.player.formatClock
import com.moda.mobile.ui.player.panelLabel
import com.moda.mobile.ui.player.tierNameText
import com.moda.mobile.ui.speedLabelOf
import com.moda.mobile.ui.theme.ModaColors
import com.moda.mobile.ui.theme.ModaType
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 四角图标的触控区：44dp 是触摸纪律的下限，再小就不是"按不到"而是"不敢按" */
private val CornerTouchSize = 44.dp

/**
 * 小屏底行占用的高度，弹幕层按它让位。
 *
 * 不直接复用全屏页的 [ControlsBottomInset]（112dp）：那是"进度条行 + 右下角簇"两行的高度，
 * 而这一格整体才 16:9（360dp 宽的屏上约 185dp 高），让出 112dp 就没地方飘弹幕了。
 * 这里的数字来自底行本身：44dp 的播放/暂停按钮 + 上下 12/8 的内边距 = 64dp。
 */
private val PreviewControlsBottomInset = 64.dp

/**
 * 手指移动超过这个距离才算纵滑，小于它仍然判单击（播放/暂停）。
 *
 * 6dp 刻意**低于**系统的 touchSlop（手机上约 8dp）：这一格嵌在详情页的 LazyVerticalGrid 里，
 * 纵向手势同时被父亲的滚动容器盯着。child 在 Main 阶段先拿到事件，只有比滚动容器更早
 * consume，纵滑才稳定落在"调亮度/音量"上，而不是把整页拖着走。
 * 再大一点（全屏页用的是 12dp）在这里就会出现"手指拖了一半，页面先滚了"。
 */
private val DragSlop = 6.dp

/** 纵滑满量程对应的屏高比例：0.6 屏高走完 0%→100%，单手够得着也不会一抖到底 */
private const val LevelDragHeightRatio = 0.6f

/** 松手后指示器停留时长：拖完立刻消失等于没给反馈 */
private const val LevelAutoHideMs = 800L

/** 控件层无操作自动收起：与全屏播放页同一个 3 秒，用户不必学两套时序 */
private const val ControlsAutoHideMs = 3000L

/** 双击窗口：两次抬手落在这个窗口内就算双击（与系统的 300ms 同一量级） */
private const val DoubleTapMs = 300L

/** 锁定态点屏幕后，那颗解锁键在屏上的存活时间（与大屏同一个节奏） */
private const val LockHintMs = 2_500L

/** 双击后中央那个大图标停留多久：够看清"刚才是暂停还是继续"，又不至于挡画面 */


/** 纵滑调的是亮度还是音量（判定用手势起点 x，见手势层注释） */
private enum class LevelKind { Brightness, Volume }

/**
 * 详情页顶部的小屏播放器：16:9、真的能播、上下各一条贴边渐变带。
 *
 * 控件默认收起，**只有返回常驻**：剩下的「投屏 / 倍速 / 弹幕」与底行，点一下画面才出现，
 * 3 秒无操作自动收起，面板开着时不收。返回不跟着收起——它是这一格唯一的出口，
 * 藏起来用户下一次就找不到了；而且它的尺寸/边距与历史页、搜索页那颗逐字一致
 * （48dp 触控、24dp 图标、start 4 / top 12、单图标无圆底），二级页的出口在各处必须是同一个东西。
 * 所以单击画面的语义是**显隐控制层**，不再兼做播放/暂停——播放/暂停是底行最左那颗按钮。
 *
 * 弹幕走全 App 那一份 [DanmakuOverlay]（不另写一套），窗口由 [danmaku] 喂进来、
 * 由**这一格自己的播放位置**驱动（[onEnsureDanmaku]）：详情页不写历史、不判完成，
 * 弹幕也不例外——它只是"顺手看一眼"时的伴生信息。
 *
 * 两条带子都是整宽、贴卡片边缘、**无圆角无左右 margin**：圆角只来自卡片自己的 clip(14dp)。
 * 背景是渐变而不是一块深色矩形——浮在画面里的深色块在片头黑底时会露出一条硬边，像贴纸。
 *
 * 底行 = 播放/暂停 · 当前时间 · 进度条 · 总时长 · 全屏。全屏**并在这行里**，不单独浮在画面中：
 * 一颗浮块贴着进度条上方，在 16:9 的高度里两者都读不清。
 *
 * 小屏这一层**没有**选集、清晰度/内核/解码/比例面板：那些是"换个屏幕看"的需求，
 * 由 [fullscreen] 那一支（大屏控件集）提供。小屏也不写历史、不判
 * [com.moda.mobile.data.local.IntroOutroRules] 的片头片尾——进度始终只由全屏播放页负责，
 * 否则同一部剧会有两个写者。
 * 全屏面板同样不碰内核/解码/片头片尾：那几项的生效路径要么重建引擎、要么写 DataStore，
 * 都不该从这一页发起（播放页路由还在，那些入口在那儿）。
 *
 * 引擎只用 ExoPlayer：小屏不是排查解码问题的地方（换内核/软解是播放页的面板），
 * 起不来就给一块错误面板 + 重试，外加一个直链→HLS 的自动回退。
 *
 * [onSpeedChange] 只把选择同步给调用方（默认空实现）：倍速是否需要跨页持久化由上层决定，
 * 本组件不碰 DataStore。
 *
 * [fullscreen] 只换布局、不换引擎：同一个节点在「16:9 卡片」与「整块屏幕」之间改 Modifier，
 * 控件集从小屏四角换成 [com.moda.mobile.ui.player] 的 PlayerTopBar / PlayerBottomBar + 面板。
 * **这一段里没有任何 release / acquire / start**：小屏 ↔ 全屏来回切，引擎与它的 Surface
 * 自始至终是同一个（logcat 里「新建并起播」整段只该出现一次）。谁把这里改成"换路由/换组件"，
 * 用户看到的就是一帧黑再重贴。
 */
// Slider 的 thumb/track 重载在 material3 1.4 仍是 @ExperimentalMaterial3Api：
// 只在这一处用（小屏进度条要播放器样式，见 PreviewSliderThumb / PreviewSliderTrack）
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailPreviewPlayer(
    /** 只喂给投屏元数据（电视上显示片名）；覆盖层不画标题，片名由页面自己排 */
    title: String,
    /** 要播的地址；null = 还没装好 */
    preview: DetailPreview?,
    loading: Boolean,
    /** VM 侧的错误码（换直链失败）：文案走 [appErrorText] */
    error: AppError?,
    /** 小屏在播第几集（0 起下标）：只喂投屏元数据的「标题 · 第 N 集」，覆盖层不画标题 */
    episodeIndex: Int,
    onRetry: () -> Unit,
    onEnterFullscreen: () -> Unit,
    /** 退出全屏：**只改布局**（不换路由、不碰引擎）。顶栏返回、返回键、后退层都用它 */
    onExitFullscreen: () -> Unit = {},
    /** 全屏是页面布局状态，由详情页持有；播放器只按它切换自己那颗 Modifier */
    fullscreen: Boolean = false,
    /** 全屏「选集」面板的数据源；点一集仍然走页面既有的出口（本页换片源，保持全屏） */
    episodes: List<Episode> = emptyList(),
    onSelectEpisode: (Episode) -> Unit = {},
    /**
     * 全屏「清晰度」面板的档位表：与正在播的地址同一来源（VM 的 detailPreviewTiers）。
     * 空 = 这一集拿不到档位表，面板如实显示"无可选"，不摆一排按不动的按钮。
     */
    tiers: List<Tier> = emptyList(),
    /** 当前在用的档位；面板高亮它、底栏那颗圆钮写它的名字 */
    selectedTier: Tier? = null,
    /** 切档：只换地址（VM 拿新 playLink），播放器按新 url 换源，布局不动 */
    onTierSelect: (Tier) -> Unit = {},
    onSpeedChange: (Float) -> Unit = {},
    /** 小屏的弹幕窗口（VM 的 previewDanmakuWindow）：与全屏播放页那一份刻意分开，见 VM 里的说明 */
    danmaku: List<Danmaku> = emptyList(),
    /**
     * 这一格在播哪一集的 vid。DetailPreview 只带地址，vid 由详情页从集数表里按
     * 「小屏在播第几集」的同一口径取出来传进来；空串 = 没有集数表，不装弹幕。
     */
    previewVid: String = "",
    /** 拉/换弹幕窗口：由这一格自己的播放位置驱动，窗口阈值与节流都在 VM 里 */
    onEnsureDanmaku: (String, Long) -> Unit = { _, _ -> },

    /**
     * 这一页在看的剧 cid：弹幕开关按它读写「本剧覆盖」（与根播放页同一个 DataStore 键）。
     * 空串 = 拿不到剧身份，此时开关只改这一格的本地状态，不落盘。
     */
    cid: String = "",
    /**
     * 蜂窝 + 「流量播放提醒」开启，且地址还没装：这一格先展示封面 + 播放键。
     *
     * 由详情页按「网络类型 + 提醒开关」算好传进来，播放器不自己探测网络：
     * 两个判据来源早晚会分叉，而这条分叉的代价是用户的流量。
     */
    trafficHold: Boolean = false,
    /** 封面图（SeriesDetail.cover）：trafficHold 时铺在 16:9 里，让用户看见"这是哪部剧" */
    cover: String = "",
    /** 点了封面上的播放键：由详情页决定直接起播还是先弹确认卡 */
    onRequestStart: () -> Unit = {},
    /**
     * 确认卡是否展开。状态由详情页持有：选集轨道那条入口也在页面里，
     * 两处必须弹同一张卡——各 remember 一份的结果就是"点集号弹不出来"。
     */
    consentVisible: Boolean = false,
    /** 确认卡「继续播放」；入参 = 勾了「不再提醒」（由详情页写进设置，播放器不碰设置域的键名） */
    onConsentAccept: (dontAskAgain: Boolean) -> Unit = {},
    /** 确认卡「取消」：收起卡片、回到封面 + 播放键，不装地址、不消耗流量 */
    onConsentCancel: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val activity = remember(context) { findActivity(context) }
    val scope = rememberCoroutineScope()

    // —— 地址与内核 ——
    val primaryUrl = preview?.url.orEmpty()
    val hlsUrl = preview?.hlsUrl
    // 直链先挂一次就静默换 HLS，并且记住这个选择：否则每次重组都会把失败的那条再试一遍
    var preferHls by remember(primaryUrl) { mutableStateOf(false) }
    // 显式标注 String：hlsUrl 是 String?，靠 isNullOrBlank 的契约做智能转换太脆
    val playUrl: String = if (preferHls) hlsUrl ?: primaryUrl else primaryUrl
    // Exo 必须显式知道 MIME：m3u8 当 MP4 交给它必然失败。按后缀判（去掉 query 再判），
    // 这样"直链为空、地址本身就是 HLS"那条路也自动走对
    val useHls = playUrl.substringBefore('?').endsWith(".m3u8", ignoreCase = true)

    var attempt by remember(primaryUrl) { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(false) }
    /** 这一格有没有真的播过：没有它，刚挂载那一下（playing 还是初值 false）就会顶出中央播放键 */
    var hasPlayed by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(false) }
    var playError by remember(primaryUrl, attempt) { mutableStateOf<PlayerError?>(null) }
    var speed by remember { mutableFloatStateOf(1f) }
    var speedSheetVisible by remember { mutableStateOf(false) }

    // —— 控件层显隐（返回除外）——
    // 默认收起：这一格是"顺手看一眼"的位置，四个角一直挂着按钮会永久压住画面。
    // 但**返回不收**——它是这一格唯一的出口，藏起来用户下一次就找不到了。
    var controlsVisible by remember { mutableStateOf(false) }
    // 任何交互都 +1：自动收起的计时以它为准，key 变了旧计时自然被取消（同全屏页 controlsTick）
    var controlsTick by remember { mutableIntStateOf(0) }

    // —— 弹幕 ——
    // 开关 / 字号 / 区域全 App 只有一份（moda_player_settings）：这一格读的写的都是同一个键，
    // 否则会出现"小屏关了、进全屏又开着"。播放器组件直读 store 是本仓库的既有做法（见 PlayerScreen），
    // 这里沿用它，不往详情页的签名里再加一条设置通道。
    val settingsStore = remember(context) { PlayerSettingsStoreImpl.create(context) }
    // 「本剧覆盖」与播放页读写的是同一个 DataStore（键名 danmaku_<cid>）：弹幕开关要两个域
    // 写同一个值，否则"小屏关了、大屏还开着"（播放页读的是 override 优先）
    val overrideStore = remember(context) { PlaybackOverrideStoreImpl.create(context) }
    var settings by remember { mutableStateOf(PlayerSettings()) }
    LaunchedEffect(settingsStore) {
        settings = settingsStore.current()
    }
    /**
     * 本剧覆盖里只关心弹幕那一格。
     *
     * 大屏读的是 `override.danmakuEnabled ?: settings.danmakuEnabled`，小屏原来只读全局，
     * 于是「大屏里关掉这部剧的弹幕 → 退回来点开小屏」又是开的：同一部剧在两个屏幕上两个状态。
     * 两处必须同一口径。
     */
    var danmakuOverride by remember(cid) { mutableStateOf(PlaybackOverride()) }
    LaunchedEffect(cid, overrideStore) {
        danmakuOverride = if (cid.isBlank()) PlaybackOverride() else overrideStore.get(cid)
    }
    /** 这一格真正生效的弹幕开关：本剧覆盖优先，没有覆盖才回落设置页里的默认值 */
    val danmakuEnabled = danmakuOverride.danmakuEnabled ?: settings.danmakuEnabled

    // —— 进度 ——
    // 位置/时长只能轮询（PlayerEngine 只有 getter、没有进度回调），500ms 一次够拖动条用
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    // 拖动中只改预览值，松手才 seek：拖动时每帧 seek 会把播放器拖到卡死
    var scrubbing by remember { mutableStateOf(false) }
    var scrubMs by remember { mutableLongStateOf(0L) }

    /**
     * 引擎来自 [PlaybackSession]，不再由本组件自己 new：点全屏后这一格被拆掉、播放页挂载，
     * 两边共用同一个引擎，画面不重新起播、不重新缓冲。
     *
     * 重建的 key 仍是「地址 + 重试次数」：换地址必须换引擎——PlayerEngine 的 start 是"起播"，
     * 不是"换源"；同一实例上换源会把上一段缓冲一起带进来。重试则先 discard() 再 ++，见错误面板。
     * 小屏拿不到 vid（DetailPreview 只带地址），会话按「去掉 vkey 的地址」认身份，
     * 播放页那边同一条规则，所以同一集能对上。
     */
    val engine: PlayerEngine? = remember(playUrl, attempt) {
        if (playUrl.isBlank()) {
            null
        } else {
            PlaybackSession.acquire(
                context = context,
                vid = "",
                url = playUrl,
                hls = useHls,
                startMs = 0L,
                speed = speed,
            )
        }
    }

    // —— 投屏 ——
    // casting 非空 = 正在投到哪台电视；投屏期间本地引擎保持暂停
    var casting by remember { mutableStateOf<CastDevice?>(null) }
    // 投屏前的位置：停止投屏后本地从这里接着播，不回到片头
    var castResumeMs by remember { mutableLongStateOf(0L) }
    var castSheetVisible by remember { mutableStateOf(false) }
    var castFailure by remember { mutableStateOf(false) }

    // —— 全屏（大屏控件集）——
    // 全屏只是**换布局**：这一段状态里没有任何 release / acquire / start，
    // 引擎与它的 Surface 全程不动（换源才该重建，换屏幕不该）。
    var locked by remember { mutableStateOf(false) }
    // 锁定态点屏幕后那颗解锁键：2.5 秒后自己收（与大屏同一条节奏）
    var lockHintTick by remember { mutableIntStateOf(0) }
    var lockHintVisible by remember { mutableStateOf(false) }
    // 一次只开一张底部面板（null = 都没有）
    var panel by remember { mutableStateOf<PlayerPanel?>(null) }
    // 画面比例：这颗播放器自己的显示参数，不落盘（本页没有 settings），退出页面即回到 Fit
    var aspectMode by remember { mutableStateOf(AspectMode.Fit) }

    // —— 单击 / 双击的区分（详见手势层）——
    // 记"上一次抬手的时刻"与那个待决的单击任务：双击窗口内再点一下就取消掉它
    var lastTapAt by remember { mutableLongStateOf(0L) }
    var tapJob by remember { mutableStateOf<Job?>(null) }


    // —— 纵滑（亮度/音量）——
    var brightness by remember { mutableFloatStateOf(0.5f) }
    var volume by remember { mutableFloatStateOf(0.5f) }
    var levelKind by remember { mutableStateOf<LevelKind?>(null) }
    var levelValue by remember { mutableFloatStateOf(0f) }
    var levelTick by remember { mutableIntStateOf(0) }

    val audioManager = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }

    // 手势回调活在 pointerInput 的协程里，读局部委托状态拿到的是最新值；
    // 但跨重组的"最新参数"仍要经 rememberUpdatedState，否则回调会用到旧回调/旧地址
    val brightnessNow by rememberUpdatedState(brightness)
    val volumeNow by rememberUpdatedState(volume)
    val preferHlsNow by rememberUpdatedState(preferHls)
    val hlsNow by rememberUpdatedState(hlsUrl)

    fun applyBrightness(value: Float) {
        brightness = value
        val window = activity?.window ?: return
        val attrs = window.attributes
        // 0f 会让屏幕全黑：从 0.01f 起步，避免用户把亮度拖到看不见任何东西
        attrs.screenBrightness = value.coerceIn(0.01f, 1f)
        window.attributes = attrs
    }

    fun applyVolume(value: Float) {
        volume = value
        val am = audioManager ?: return
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        am.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            (value * max).roundToInt().coerceIn(0, max),
            0,
        )
    }

    // 局部函数必须声明在 DisposableEffect 之前：局部函数不能先使用后声明。
    // 参数名与 PlayerEngine.Listener 保持一致会遮住同名状态，所以统一经这几个函数落回状态。
    fun setPlayingState(value: Boolean) {
        playing = value
        if (value) hasPlayed = true
    }

    fun setBufferingState(value: Boolean) {
        buffering = value
    }

    /** 控制层里的播放/暂停（单击画面的语义已经改成显隐控制层） */
    fun togglePlay() {
        val e = engine ?: return
        if (e.isPlaying) e.pause() else e.play()
    }

    /**
     * 弹幕总开关的唯一落点：写的是**本剧覆盖**，不写 [PlayerSettings]。
     *
     * `settings.danmakuEnabled` 是「设置 → 播放设置 → 弹幕」那一项，是默认值：
     * 播放页里的一次临时切换不许改写它，否则用户在设置里看到的"默认"会被一次播放悄悄改掉
     * （与 core / decoder 同一条约定：默认值与单剧覆盖两处不要混用）。
     *
     * 口径与全屏播放页 PlayerScreen.toggleDanmaku 完全一致：和全局默认相同就存 null（删键），
     * 所以"小屏切的"与"大屏切的"是同一格覆盖，进哪个屏幕都是同一个状态。
     *
     * 乐观更新（先改本地渲染、再落盘）：这一格是"顺手看一眼"的位置，为一次写盘把图标卡住不值得。
     */
    fun toggleDanmaku() {
        val stored = (!danmakuEnabled).takeIf { it != settings.danmakuEnabled }
        danmakuOverride = danmakuOverride.copy(danmakuEnabled = stored)
        if (cid.isBlank()) return
        scope.launch {
            try {
                // 先 read 再 copy：core / decoder 那两项是播放页的，不许在这里被整条覆盖清掉
                overrideStore.save(cid, overrideStore.get(cid).copy(danmakuEnabled = stored))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 静默：覆盖没写进去最多是"下次进来回到默认"，不影响这一格
            }
        }
    }

    /** 「我动过」的唯一入口：显示控制层 + 重置自动收起计时 */
    fun touchControls() {
        controlsVisible = true
        controlsTick++
    }

    fun handleError(err: PlayerError) {
        // 直链先挂且有 HLS 备选：静默换流，保住"能看"比先弹一块错误面板体验好
        if (!preferHlsNow && !hlsNow.isNullOrBlank()) {
            preferHls = true
            playError = null
        } else {
            playError = err
        }
    }

    // 引用计数只跟"这一格"走，不跟 engine 走：换集 / 切清晰度都会换引擎实例，画面上却始终
    // 有内容。挂在 engine 上的话，换流那一帧的顺序是「acquire 建新引擎 → 旧 effect dispose
    // detach 到 0 → 会话 scheduleRelease 把**新**引擎 pause 掉」；那一刻新引擎还没报 isPlaying，
    // 于是宽限期结束也不会续播——表现就是"选了新的一集，画面停在那儿要手动点播放"（踩过）。
    DisposableEffect(Unit) {
        PlaybackSession.attach()
        onDispose { PlaybackSession.detach() }
    }

    DisposableEffect(engine) {
        val e = engine ?: return@DisposableEffect onDispose { }
        // 复用来的引擎可能已经在播（从播放页返回）：状态先跟引擎对齐一次，
        // 否则底行那颗按钮会一直显示成"播放中"的反面，直到下一次回调才纠正
        setPlayingState(e.isPlaying)
        val listener = object : PlayerEngine.Listener {
            override fun onPlayingChanged(playing: Boolean) {
                setPlayingState(playing)
            }

            override fun onBufferingChanged(buffering: Boolean) {
                setBufferingState(buffering)
            }

            override fun onEnded() {
                // 小屏播完就停在最后一帧：连播/换集是全屏页的事，这里不自己推进
                setPlayingState(false)
            }

            override fun onError(error: PlayerError) {
                handleError(error)
            }

            override fun onVideoSizeChanged(width: Int, height: Int) {
                // 外框固定 16:9、画面按 FIT 缩放：宽银幕的两条黑边是刻意留的，这里不需要动作
            }
        }
        e.listener = listener
        onDispose {
            // 只摘自己装的那一个 listener：切换时播放页可能已经把它自己的装上了，
            // 直接置 null 会把对方的回调抹掉（两者挂载/卸载顺序不保证）
            if (e.listener === listener) e.listener = null
            // 不 release、也不 detach：引擎归会话，切到播放页时它还要接着播；
            // 离开这一格的 detach 在上面那个 DisposableEffect(Unit) 里
        }
    }

    // 切后台必须停声：详情页不是播放页，没有"切走了还想听"的场景。
    // 这里只 pause 不 release——真正的 release 归 PlaybackSession（引用归零 800ms 后）
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, engine) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) engine?.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    /**
     * 亮度恢复：写的是宿主 Activity 的 window，离开详情页必须还原成"跟随系统"，
     * 否则用户在详情页调暗之后，整个 App 都停在那个人为亮度上（-1f = BRIGHTNESS_OVERRIDE_NONE）。
     * 与全屏播放页同一条规矩。
     */
    DisposableEffect(activity) {
        onDispose {
            val window = activity?.window ?: return@onDispose
            val attrs = window.attributes
            attrs.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            window.attributes = attrs
        }
    }

    // 起播已移交给 PlaybackSession.acquire（只有真的新建引擎才 start）：
    // 复用同一集时必须什么都不做，否则这里一 start 就又从 0 拉一次流。

    // 换引擎、换倍速都套一次：新实例的默认倍速是 1f
    LaunchedEffect(engine, speed) {
        engine?.setSpeed(speed)
    }

    // 纵滑起点取系统当前值：拖动是"相对当前"的加减，不是从 50% 跳到某个值
    LaunchedEffect(activity) {
        val current = activity?.window?.attributes?.screenBrightness ?: -1f
        brightness = if (current in 0.01f..1f) current else 0.5f
    }
    LaunchedEffect(audioManager) {
        val am = audioManager ?: return@LaunchedEffect
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        volume = am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
    }
    // 松手后 800ms 收掉指示器：tick 每松一次 +1，旧计时被新 tick 取消
    LaunchedEffect(levelTick) {
        if (levelTick == 0) return@LaunchedEffect
        delay(LevelAutoHideMs)
        levelKind = null
    }

    // 锁定时单击整屏：给一个可见的解锁出口，2.5 秒后自己收
    LaunchedEffect(lockHintTick) {
        if (lockHintTick == 0) return@LaunchedEffect
        lockHintVisible = true
        delay(LockHintMs)
        lockHintVisible = false
    }



    // 控制层 3 秒无操作自动收起。面板开着时不收：那会儿用户在看面板，
    // 收起只会让关掉面板后这一格变成"什么都没有"。
    LaunchedEffect(controlsVisible, controlsTick, castSheetVisible, speedSheetVisible, panel) {
        if (!controlsVisible || castSheetVisible || speedSheetVisible || panel != null) return@LaunchedEffect
        delay(ControlsAutoHideMs)
        controlsVisible = false
    }

    // 返回键分层：先关投屏面板 → 再关底部面板 → 再解锁 → 再收控制层 → 最后退全屏。
    // 全屏时这条接管系统返回；非全屏 enabled = false，交回 AppRoot 那条（pop 详情页），
    // 于是"按钮退一层、返回键退两层"不会发生。
    BackHandler(enabled = fullscreen) {
        when {
            castSheetVisible -> castSheetVisible = false
            panel != null -> panel = null
            locked -> locked = false
            // 控制层展开时**不再先收它**：用户要求"全屏第一下返回就回到小屏"。
            // 进全屏默认是展开控制层的（见下面的 LaunchedEffect(fullscreen)），多一档
            // "先收控制层"会让返回按三次才退得出去，手感就是"返回键没反应"。
            else -> onExitFullscreen()
        }
    }

    /**
     * 全屏 = 横屏 + 隐藏系统栏；退出全屏 / 离开页面都要还原。
     *
     * 申请与恢复成对写在**同一个** DisposableEffect 里，触发点从"页面销毁"改成 fullscreen 变化：
     * 退出全屏与离开页面都会走 onDispose，所以方向、系统栏一定都能回去
     * （沿用播放页那一套写法，只是把 key 换成 fullscreen）。
     * 清单里 configChanges 吞掉了 orientation，这一来一回不会重建 Activity，
     * 播放器状态与 Surface 也就跟着留下来。
     */
    DisposableEffect(activity, fullscreen) {
        val window = activity?.window
        val controller = window?.let { WindowInsetsControllerCompat(it, it.decorView) }
        if (fullscreen) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            // 从屏幕边缘上划可以临时唤出系统栏，但不会退出沉浸式
            controller?.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            // 全屏就是**整块屏幕都是画面**：状态栏与导航条一起藏（Type.systemBars()），
            // 不再单独 show 状态栏——留着它，画面顶上永远挂一条 24dp 的黑边，
            // 用户会觉得"这根本没全屏"。看时间电量不是播放页的职责。
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // 页内全屏期间系统栏必须**一直是藏的**：切后台回来、或用户从边缘临时唤出系统栏后
    // 焦点回到本页，系统会把栏放回来——不补这一下，用户看到的就是"全屏里还有状态栏"。
    // 与 PlayerScreen 的那条 ON_RESUME 同一条口径（两处不一致就会出现"一边藏一边露"）。
    val fullscreenNow by rememberUpdatedState(fullscreen)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && fullscreenNow) {
                val window = activity?.window
                if (window != null) {
                    WindowInsetsControllerCompat(window, window.decorView)
                        .hide(WindowInsetsCompat.Type.systemBars())
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 进出全屏的状态落点：进全屏先亮一次控制层（第一眼只有画面的话，看不出这是能点的播放器），
    // 之后交给同一条 3 秒自动收起的计时——它是 touchControls() 的副作用，不需要另起一个计时器。
    // 退全屏把大屏才有的状态清掉——锁与面板不该在 16:9 那格里继续生效。
    // **只有全屏这一支亮控制层**：小屏（非全屏）仍然是"点一下画面才出现"，那是详情页的既定语义。
    LaunchedEffect(fullscreen) {
        if (fullscreen) {
            touchControls()
        } else {
            locked = false
            panel = null
        }
    }

    // 面板是独立窗口（ModalBottomSheet 里面是一个 Dialog）：它一拿焦点，系统就按那个窗口的
    // 默认设置把状态栏与导航栏放回来。面板内部各自 hide 过一次（PlayerSheet 里的
    // HideSystemBarsInDialogWindow），这里补"焦点落回本窗口之后"的那一次——
    // 否则从面板回来，全屏里会一直挂着系统栏（与大屏 PlayerScreen 同一条口径）。
    LaunchedEffect(panel, castSheetVisible, speedSheetVisible, fullscreen) {
        if (!fullscreen) return@LaunchedEffect
        if (panel != null || castSheetVisible || speedSheetVisible) return@LaunchedEffect
        val window = activity?.window ?: return@LaunchedEffect
        WindowInsetsControllerCompat(window, window.decorView)
            .hide(WindowInsetsCompat.Type.systemBars())
    }

    // 进度轮询：拖动期间不覆盖预览值，其余时间跟随引擎（PlayerEngine 只有 getter）
    LaunchedEffect(engine) {
        val e = engine ?: return@LaunchedEffect
        while (true) {
            if (!scrubbing) positionMs = e.positionMs
            durationMs = e.durationMs
            delay(500)
        }
    }

    // 弹幕窗口跟着**这一格自己的播放位置**走：上面那条轮询每 500ms 更新一次 positionMs，
    // 这里只把它转给 VM——换窗阈值在 VM 里（越过窗口起点 30s 才重装），
    // 所以这一句不会变成每秒两次 HTTP。引擎还没到或拿不到 vid（没有集数表）时不发。
    val ensureDanmakuNow by rememberUpdatedState(onEnsureDanmaku)
    LaunchedEffect(engine, previewVid, positionMs) {
        if (engine != null && previewVid.isNotBlank()) ensureDanmakuNow(previewVid, positionMs)
    }

    // 电视上要显示的名字：与全屏页同一拼法（标题 · 集号），也就是 metadata 里的 dc:title。
    // episodeIndex < 0 表示这部剧根本没有集数（上游没返回）：那时整段集号不组合，
    // 否则标题行会写出一句"第 0 集"
    val episodeLabel = if (episodeIndex >= 0) {
        stringResource(R.string.core_episode_index, episodeIndex + 1)
    } else {
        ""
    }
    val castTitle = if (episodeLabel.isNotBlank()) title + " · " + episodeLabel else title

    /** 投屏的唯一出口。地址优先级：MP4 直链 → HLS，各试一次 */
    fun startCast(device: CastDevice) {
        val e = engine ?: return
        if (playUrl.isBlank()) return
        castFailure = false
        scope.launch {
            // 换一台电视：先把上一台停下，否则两台会同时播同一集
            casting?.takeIf { it.location != device.location }?.let { previous ->
                CastController.stop(previous)
                casting = null
            }
            val alt = hlsUrl?.takeIf { it.isNotBlank() }
            val ok = CastController.play(device, playUrl, castTitle) ||
                (alt != null && CastController.play(device, alt, castTitle))
            if (ok) {
                castResumeMs = e.positionMs
                // 本地必须停下来：两端同时出声是最容易被当成 bug 的失败形态
                e.pause()
                casting = device
                castSheetVisible = false
            } else {
                castFailure = true
            }
        }
    }

    /** 停止投屏：先让电视停下，再把本地恢复到投屏前的位置继续播 */
    fun stopCast() {
        val device = casting ?: return
        casting = null
        castFailure = false
        scope.launch {
            CastController.stop(device)
            // 回到投屏前的位置继续：用户不该因为投了一次屏就丢掉进度
            engine?.seekTo(castResumeMs)
            engine?.play()
        }
    }

    /**
     * 离开详情页要停投。
     *
     * onDispose 里不能挂起：组合已被拆除、rememberCoroutineScope 也随之取消，
     * 在这里等一个挂起请求等于什么都没发出去。所以自己开一个 IO scope 做一次
     * fire-and-forget 的 Stop——只发一条 SOAP，没有任何人需要它的结果。
     */
    val castingNow by rememberUpdatedState(casting)
    DisposableEffect(Unit) {
        onDispose {
            castingNow?.let { device ->
                CoroutineScope(Dispatchers.IO).launch { CastController.stop(device) }
            }
        }
    }

    val previewErrorText = if (error != null) appErrorText(error) else null
    val engineErrorText = playError?.let { playerErrorText(it) }
    // 有错误就没有画面，此时不挂手势层：让详情页的纵向滚动能从这一格正常划过去
    val interactive = engine != null && previewErrorText == null && engineErrorText == null

    // 画面比例（全屏「更多 → 比例」）：Fit/Fill/Zoom 交给 Exo 的 resizeMode，16:9 / 4:3 锁一个
    // 宽高比的盒子 + FILL。两者都只是显示参数，切换不重建引擎（与播放页同一份规则）
    val fixedRatio: Float? = when (aspectMode) {
        AspectMode.Ratio16x9 -> 16f / 9f
        AspectMode.Ratio4x3 -> 4f / 3f
        else -> null
    }
    val resizeMode = when (aspectMode) {
        AspectMode.Fit -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        AspectMode.Fill -> AspectRatioFrameLayout.RESIZE_MODE_FILL
        AspectMode.Zoom -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        AspectMode.Ratio16x9, AspectMode.Ratio4x3 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
    }

    Box(
        modifier
            // 同一个节点、两种外框：非全屏是 16:9 卡片（高度必须与详情页那个 Spacer 一致），
            // 全屏是整块屏幕。**只换 Modifier**，所以 Surface 不会被拆掉重建
            .then(
                if (fullscreen) {
                    Modifier.fillMaxSize()
                } else {
                    // 直角、左右铺满、**外框固定 16:9**：宽银幕片子按 FIT 缩放在框内居中，
                    // 上下留黑边——这是用户明确要的（"永远 16:9 固定高度，宽银幕就留黑边"）。
                    // 别改成跟随 videoSize，那会让块高随片子跳、下面内容跟着上下移动。
                    Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                },
            )
            .background(Color.Black),
    ) {
        // 1) 画面 / 加载 / 错误，三选一
        when {
            previewErrorText != null -> PreviewPlaceholder(
                message = previewErrorText,
                onRetry = onRetry,
                modifier = Modifier.fillMaxSize(),
            )

            engineErrorText != null -> PreviewPlaceholder(
                message = engineErrorText,
                // 出错的那台引擎状态不可靠，先让会话立刻丢掉它：
                // 否则下一次 acquire 因为地址没变会把它原样交回来，重试等于没重试
                onRetry = {
                    PlaybackSession.discard()
                    attempt++
                },
                modifier = Modifier.fillMaxSize(),
            )

            // 蜂窝 + 流量提醒开启：**地址还没装**，这一格先当封面用。
            // 排在这里是关键——若落到下面 engine == null 那一支，用户看到的会是
            // "正在准备播放…"的转圈：看起来已经开播了，其实我们正在等他按播放键。
            trafficHold -> TrafficHoldCover(
                cover = cover,
                title = title,
                onStart = onRequestStart,
                modifier = Modifier.fillMaxSize(),
            )

            engine == null -> PreviewPlaceholder(
                message = if (loading) stringResource(R.string.core_player_preparing) else null,
                showSpinner = loading,
                onRetry = null,
                modifier = Modifier.fillMaxSize(),
            )

            else -> VideoSurface(
                engine = engine,
                resizeMode = resizeMode,
                modifier = if (fixedRatio != null) {
                    Modifier.fillMaxSize().aspectRatio(fixedRatio)
                } else {
                    Modifier.fillMaxSize()
                },
            )
        }

        // 1b) 弹幕：直接复用全屏播放页那一份 [DanmakuOverlay]，不另写一套。
        //     排在画面之后、手势层与控件层之前：Compose 的命中测试是「后画的先拿到」，
        //     所以飘动的弹幕永远不会替用户吃掉一次点击。
        //     控制层可见时把底行让出去（让多少见 [PreviewControlsBottomInset]），弹幕不压进度条。
        //     enabled 跟随总开关：关掉时 Overlay 内部直接返回，不注册帧循环、零开销。
        DanmakuOverlay(
            danmaku = danmaku,
            positionMs = positionMs,
            playing = playing,
            modifier = Modifier.fillMaxSize(),
            bottomInset = when {
                !controlsVisible && panel == null -> 0.dp
                fullscreen -> ControlsBottomInset
                else -> PreviewControlsBottomInset
            },
            enabled = danmakuEnabled,
            fontSizeSp = settings.danmakuSizeSp,
            topRatio = settings.topRatio,
            bottomRatio = settings.bottomRatio,
        )

        // 2) 手势层：**一段** pointerInput，单击与纵滑在同一段里分派。
        //    不叠 detectTapGestures + detectDragGestures 两个检测器——它们互不知情，
        //    谁先拿到同一次按下取决于注册顺序，正是 AGENTS.md 里禁止的「两套手势抢同一片区域」。
        //    排在四角按钮之前：Compose 的命中测试是"后画的先拿到"，所以返回/投屏/倍速/全屏
        //    永远优先吃掉触摸；awaitFirstDown 只接未被消费的按下，按在按钮上不会顺带暂停画面。
        //    单击 = 显隐控制层（小屏与页内全屏**同一段手势**：小屏的播放/暂停在底行那颗按钮上，
        //    不再兼做单击，见 AGENTS.md「触摸纪律」）。
        //    只做纵滑，不做双击快进、不做横滑 seek——小屏和详情页的纵向滚动共用一块屏幕，
        //    再叠一条横向手势就会和"选下一集要往下滑"互相抢。
        if (interactive) {
            Box(
                Modifier
                    .fillMaxSize()
                    // 显式 0：控制层是 1，命中永远先给控制层（绘制顺序上也排在它们之前）
                    .zIndex(0f)
                    .pointerInput(engine) {
                        val slop = DragSlop.toPx()
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            var accumY = 0f
                            var dragging = false
                            var kind: LevelKind? = null
                            var level = 0f
                            var lifted = false
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (change.changedToUpIgnoreConsumed()) {
                                    lifted = true
                                    break
                                }
                                if (!change.pressed) break
                                accumY += change.positionChange().y
                                if (!dragging) {
                                    if (abs(accumY) <= slop) continue
                                    dragging = true
                                    // 拖动也算"一次交互"：控制层跟着出来，并把 3 秒计时重置
                                    touchControls()
                                    // 判定用**手势起点**的 x（down.position），不是实时 x：
                                    // 手指在拖动中越过中线，不该把"正在调音量"改成"正在调亮度"
                                    kind = if (down.position.x < size.width / 2f) {
                                        LevelKind.Brightness
                                    } else {
                                        LevelKind.Volume
                                    }
                                    level = if (kind == LevelKind.Brightness) brightnessNow else volumeNow
                                    accumY = 0f
                                }
                                // 到这里这一句手势归拖动所有：consume 掉，别让详情页的纵向滚动
                                // 同时把整页拖着走
                                change.consume()
                                // 0.6 屏高 ≈ 满量程：物理距离与 0..1 成正比，贴着底部也调得动
                                level = (level - accumY / (size.height * LevelDragHeightRatio))
                                    .coerceIn(0f, 1f)
                                accumY = 0f
                                val current = kind
                                if (current != null) {
                                    levelKind = current
                                    levelValue = level
                                    if (current == LevelKind.Brightness) {
                                        applyBrightness(level)
                                    } else {
                                        applyVolume(level)
                                    }
                                }
                            }
                            // 抬手时还没越过斜率 = 一次"点"：再按双击窗口分两种。
                            // **纵滑优先**：一旦越过 slop 就是拖动，既不切显隐也不算单击。
                            // 手势被系统打断（来电/划走）不算单击，只有真的"抬起"才动作。
                            if (dragging) {
                                levelTick++
                            } else if (lifted) {
                                val now = SystemClock.uptimeMillis()
                                if (now - lastTapAt <= DoubleTapMs) {
                                    // 双击 = 播放/暂停。暂停后画面中央留一颗播放键（见下面那一层），
                                    // 所以这里不再需要"亮一下就消失"的图标。
                                    // 锁定态也照样能双击播放/暂停（用户要求：锁只挡误触，
                                    // 不挡"我就要暂停一下"这个明确动作）
                                    lastTapAt = 0L
                                    tapJob?.cancel()
                                    togglePlay()
                                } else {
                                    // 单击：先看锁没锁。锁着就给解锁入口（整屏只有它），
                                    // 没锁才做控制层显隐**取反**。
                                    // 未上锁这条路得先等一个双击窗口，不等的话每次双击都会先闪一下控制层
                                    lastTapAt = now
                                    tapJob?.cancel()
                                    if (locked) {
                                        lockHintTick++
                                    } else {
                                        tapJob = scope.launch {
                                            delay(DoubleTapMs)
                                            if (controlsVisible) controlsVisible = false else touchControls()
                                        }
                                    }
                                }
                            }
                        }
                    },
            )
        }

        // 小屏这一屏：左上返回 + 右上投屏/倍速/弹幕 + 底行（播放/暂停 · 时间 · 进度条 · 总时长 · 全屏）。
        // 返回是这一屏里**唯一常驻**的控件（不随 controlsVisible 显隐）。
        // 全屏时整段不组合——全屏用下面那套大屏控件（ui/player），两套不会同时在屏上，
        // 也就不会出现"两条进度条"的争抢。
        if (!fullscreen) {
            // 3) 顶部控制带：只承载右侧那三颗（投屏/倍速/弹幕），整宽、贴卡片边缘、
            //    **无圆角无左右 margin**。背景自上而下收黑（0xCC000000 → 全透明）：
            //    不是一块浮在画面上的深色贴纸，而是画面自己暗下去——片头黑底时看不出边界。
            //    top 12 / 右 12 是固定的：带子里没有常驻内容，显隐与 CastStatusChip 的出现
            //    都不会把这三颗图标推来推去。
            if (controlsVisible) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        // 1 = 控制层，压在手势层（0）之上
                        .zIndex(1f)
                        .background(
                            Brush.verticalGradient(listOf(Color(0xCC000000), Color(0x00000000))),
                        )
                        // 带子空白处自己吃掉点击：否则点在图标之间的缝隙会穿透到下面那层
                        // "点画面显隐"，表现为"点控制条反而把它收起来了"
                        .pointerInput(Unit) { detectTapGestures { } }
                        .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 投屏中额外挂一条「正在投屏到 X」：投到电视之后手机是黑的，
                    // 不写出来用户没法确认自己投成功了没有
                    casting?.let { device ->
                        CastStatusChip(deviceName = device.friendlyName, modifier = Modifier.widthIn(max = 132.dp))
                    }
                    CornerIcon(
                        icon = Icons.Filled.Cast,
                        description = stringResource(R.string.m_player_cast_title),
                        tint = if (casting != null) ModaColors.Accent else Color.White,
                        onClick = {
                            castFailure = false
                            castSheetVisible = true
                        },
                    )
                    CornerIcon(
                        icon = Icons.Filled.Speed,
                        description = stringResource(R.string.player_speed),
                        onClick = { speedSheetVisible = true },
                    )
                    // 弹幕开关：切的是本剧覆盖，设置页里那一项是默认值、不被这里改写。
                    // 初始状态 = 覆盖 ?: 默认值，与大屏播放页读同一份，
                    // 所以进去是开的还是关的，两个屏幕永远一致（见上面 danmakuEnabled）。
                    CornerIcon(
                        icon = if (danmakuEnabled) {
                            Icons.Filled.Subtitles
                        } else {
                            Icons.Outlined.SubtitlesOff
                        },
                        description = stringResource(R.string.player_danmaku_enabled),
                        tint = if (danmakuEnabled) Color.White else Color.White.copy(alpha = 0.55f),
                        onClick = {
                            toggleDanmaku()
                            touchControls()
                        },
                    )
                }
            }

            // 5) 底部控制带：整宽、贴卡片边缘、无圆角无 margin（圆角只来自卡片 clip(14dp)），
            //    背景自下而上收黑（全透明 → 0xE6000000）。
            //    一行排完：播放/暂停 · 当前时间 · 进度条 · 总时长 · 全屏。
            //    全屏**并进这一行**而不是浮在画面里：它原本紧贴在进度条上方，两条虚线一样近，
            //    挤在 16:9 的那点高度上谁都读不清；进来之后右侧只有 12dp 内边距。
            if (controlsVisible) {
                val maxMs = durationMs.coerceAtLeast(1L)
                val shownMs = if (scrubbing) scrubMs else positionMs
                Row(
                    Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        // 1 = 控制层，压在手势层（0）之上。**这一条是"点得到"的关键**：
                        // 行内 Slider/按钮之外的缝隙原本没有命中目标，点击会落到手势层被当成
                        // "点画面"——现在由这一行的 pointerInput 兜住，落在控制条上永远只属于控制条。
                        .zIndex(1f)
                        .background(
                            Brush.verticalGradient(listOf(Color(0x00000000), Color(0xE6000000))),
                        )
                        .pointerInput(Unit) { detectTapGestures { } }
                        .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 播放/暂停：与全屏页一样是"控制层里的第一颗"，不再是单击画面的语义
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .clickable {
                                togglePlay()
                                touchControls()
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            // 与全屏页底栏同一条约定：这颗图标不单独写描述，时长与进度紧挨着它
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(26.dp),
                        )
                    }
                    // 时间文字给最小宽度 + 居中：0:07 → 1:02:07 时宽度变化不会让整行抖一下
                    Text(
                        text = formatClock(shownMs),
                        style = ModaType.Caption,
                        color = Color.White,
                        maxLines = 1,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.widthIn(min = 44.dp),
                    )
                    Slider(
                        value = shownMs.coerceIn(0L, maxMs).toFloat(),
                        valueRange = 0f..maxMs.toFloat(),
                        // 拖：只更新预览值（shownMs 走 scrubMs），并重置收起计时
                        onValueChange = { value ->
                            scrubbing = true
                            scrubMs = value.toLong()
                            touchControls()
                        },
                        // 松手才 seek：拖动中每帧 seek 会把播放器拖死（与全屏页同规矩）
                        onValueChangeFinished = {
                            engine?.seekTo(scrubMs)
                            positionMs = scrubMs
                            scrubbing = false
                            touchControls()
                        },
                        // 仍是唯一的可伸缩元素：多了全屏一颗之后也不许把进度条挤没
                        modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                        // 轨道与圆点自己画（见下面两个私有组件）：M3 默认是主题紫粗轨 + 带光晕的
                        // 大圆点，塞进 16:9 的这一格里既笨重又看不出"播到哪儿"。
                        // 手势语义一个字没改：拖动/点击仍由 Slider 自己处理，这里只换画法。
                        thumb = { PreviewSliderThumb() },
                        track = { state -> PreviewSliderTrack(state.coercedValueAsFraction) },
                    )
                    Text(
                        text = formatClock(durationMs),
                        style = ModaType.Caption,
                        color = Color.White,
                        maxLines = 1,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.widthIn(min = 44.dp),
                    )
                    // 全屏：行尾一颗，44dp 触控区 / 22dp 图标，与行内其它元素同一条中线
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onEnterFullscreen),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Fullscreen,
                            contentDescription = stringResource(R.string.danmaku_region_full),
                            tint = Color.White,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
        }

        // 5b) 全屏控件集：**直接复用 ui/player/Controls.kt**（PlayerTopBar / PlayerBottomBar 与
        //     PlayerSheet 系列），不在这里另写一套大屏控件。
        //     引擎与 Surface 一律不动：这一整段只画控件，不 acquire / 不 start / 不 release。
        if (fullscreen) {
            // **必须跟着 controlsVisible 显隐**：少了这一层判断，3 秒自动收起与"单击画面"
            // 只是改了状态、没人看——真机上的表现就是"点画面没反应、控制层也永远不收"。
            // （根播放页那条同位置有 controlsVisible 判断，这里当初只写了 fullscreen。）
            if (!locked && controlsVisible) {
                PlayerTopBar(
                    title = title,
                    episodeLabel = episodeLabel,
                    // 这个形参是"当前锁没锁"，由 locked 画锁形；本分支只在未锁时组合，
                    // 所以恒传 false，点一下就是"锁上"（锁定后整屏只剩解锁入口）
                    locked = false,
                    casting = casting != null,
                    castDeviceName = casting?.friendlyName,
                    // 顶栏那颗返回 = 退出全屏（不是退出页面）：与系统返回键的分层一致
                    onBack = onExitFullscreen,
                    // 锁上就顺手把控制层收掉：锁定的意义就是"别再让我误触"
                    onToggleLock = {
                        locked = true
                        controlsVisible = false
                    },
                    onCastClick = {
                        castFailure = false
                        castSheetVisible = true
                    },
                    modifier = Modifier.align(Alignment.TopCenter).zIndex(2f),
                )
                PlayerBottomBar(
                    playing = playing,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    // 倍速只作状态提示；1.0 不写，否则底栏永远挂一句"1.0x"
                    speedLabel = if (speed != 1f) speedLabelOf(speed) else null,
                    // 右下角圆钮写当前值（1.0x 也写）
                    speedText = speedLabelOf(speed),
                    // 码率圆钮写当前档位名：与根播放页同一口径（拿不到就退回「清晰度」）
                    qualityText = selectedTier?.let { tierNameText(it.display) },
                    onTogglePlay = {
                        togglePlay()
                        touchControls()
                    },
                    // 下一集**恒置灰**：换集 = 换地址 = 换源，而换源必然重建引擎，
                    // 本轮明令"引擎与 Surface 都不许动"，所以这颗按钮在这里不承担切集
                    // （切集的正路是选集轨道 / 选集面板 → 本页换片源）
                    onNextEpisode = {},
                    nextEpisodeEnabled = false,
                    // 拖动预览由 PlayerBottomBar 自己画（它内部有 dragging 状态），这里只重置收起计时
                    onSeekPreview = { touchControls() },
                    onSeekCommit = { ms ->
                        engine?.seekTo(ms)
                        positionMs = ms
                        touchControls()
                    },
                    onOpenPanel = { next ->
                        // 没有集数表时"选集"面板开出来是空表：干脆不响应，别给一张空白面板
                        if (next != PlayerPanel.Episode || episodes.isNotEmpty()) {
                            touchControls()
                            panel = next
                        }
                    },
                    // 弹幕开关与上面那格小屏是**同一个**本剧覆盖，也是同一个窗口：
                    // 全屏后引擎与这一集的 vid 都没变，弹幕照旧飘
                    danmakuEnabled = danmakuEnabled,
                    onToggleDanmaku = {
                        toggleDanmaku()
                        touchControls()
                    },
                    // 这一页能生效的收纳面板只剩「比例」（弹幕/内核/解码/片头片尾的数据与
                    // 生效路径都在播放页），所以底栏那颗不叫「更多」，直接就是「比例」：
                    // 多点一次「更多」才看见唯一一项，等于给用户加了一道路障
                    morePanel = PlayerPanel.Aspect,
                    moreIcon = Icons.Filled.AspectRatio,
                    moreDescription = stringResource(R.string.player_aspect),
                    // 「退出全屏」：这一页的全屏是**页内状态**，退的是布局而不是页面，
                    // 所以右下角簇末尾也要有一颗写明动作的入口。它与顶栏那颗返回、
                    // 系统返回键是同一个落点（onExitFullscreen），三处不会各退一层
                    onExitFullscreen = onExitFullscreen,
                    modifier = Modifier.align(Alignment.BottomCenter).zIndex(2f),
                )
            }
            // 锁定态不再在这里常驻一颗"左中"的解锁键：解锁入口统一是
            // "点屏幕 → 右侧中部出现 2.5 秒"（见下面 6c），与根播放页同一个位置感

            // 底部面板：一次只开一个。ModalBottomSheet 挂在窗口上，天然盖住手势层与控制层。
            // 只摆这一页**真的能生效**的那几张：弹幕 / 内核 / 解码 / 片头片尾的数据与生效路径
            // 都在播放页（VM 与 PlayerSettings），在这里摆出来就是一排按不动的按钮。
            when (panel) {
                PlayerPanel.Episode -> EpisodeSheet(
                    episodes = episodes,
                    currentEpisodeIndex = episodeIndex,
                    onSelect = { episode ->
                        panel = null
                        onSelectEpisode(episode)
                    },
                    onClose = { panel = null },
                )

                PlayerPanel.Quality -> QualitySheet(
                    // 档位表来自 episodeInfo（VM 的 detailPreviewTiers），与正在播的地址同一来源；
                    // 切档 = 换地址 = 换一条流，旧引擎在会话宽限期后释放，画面不黑
                    tiers = tiers,
                    selected = selectedTier,
                    onPick = { tier ->
                        panel = null
                        onTierSelect(tier)
                        touchControls()
                    },
                    onClose = { panel = null },
                )

                PlayerPanel.Speed -> SpeedSheet(
                    speed = speed,
                    onPick = { value ->
                        // 即改即生效；面板照样在调用点收掉（SpeedSheet 被三处复用，不自关）
                        speed = value
                        engine?.setSpeed(value)
                        onSpeedChange(value)
                        panel = null
                    },
                    onClose = { panel = null },
                )

                PlayerPanel.Aspect -> AspectSheet(
                    mode = aspectMode,
                    onPick = { mode -> aspectMode = mode },
                    onClose = { panel = null },
                )

                // 「更多」：同一个 PlayerSheet 骨架（同宽、同标题栏、同 chip 尺寸），
                // 只放上面几张之外的、这一页真能生效的项（目前只有画面比例）
                PlayerPanel.More -> PlayerSheet(
                    title = stringResource(R.string.m_player_more),
                    onClose = { panel = null },
                ) {
                    SheetOption(
                        label = panelLabel(PlayerPanel.Aspect),
                        selected = false,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { panel = PlayerPanel.Aspect },
                    )
                }

                PlayerPanel.Danmaku,
                PlayerPanel.Core,
                PlayerPanel.Decoder,
                PlayerPanel.Marks,
                null -> Unit
            }
        }

        // 6) 换集加载：**与刚进详情页那一屏长得一模一样**（同一个 PreviewPlaceholder：
        //    22dp 环 + 同一句「正在准备播放」），并且铺一层压底——换集时旧那一集还在播，
        //    不盖住的话两集的画面与提示会叠在一起，看起来像串台。
        //    engine == null 那一档由上面的分支自己画，这里只管"换集"这一种。
        if (interactive && engine != null && loading) {
            PreviewPlaceholder(
                message = stringResource(R.string.core_player_preparing),
                onRetry = null,
                showSpinner = true,
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xCC000000)),
            )
        }

        // 6a) 普通缓冲：首帧没出来 / 网络抖动，一颗细环就够（不带文案，免得每抖一下都跳字）
        if (interactive && engine != null && !loading && buffering && !playing) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center).size(22.dp),
                color = Color.White.copy(alpha = 0.9f),
                strokeWidth = 2.dp,
            )
        }

        // 6b) 暂停中的中央播放键：小屏与页内全屏共用这一颗。
        //     双击暂停之后它就是"继续播"的落点；常驻而不是闪一下，因为暂停是持续状态。
        // 锁定态同样要出这颗键：锁只挡误触，不挡"我在暂停"这个状态（与大屏一致）
        if (interactive && hasPlayed && !playing && !buffering && !loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(Color(0x66000000))
                        .clickable { togglePlay() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = stringResource(R.string.m_player_play),
                        tint = Color.White,
                        modifier = Modifier.size(32.dp),
                    )
                }
            }
        }

        // 6c) 锁定时唯一的出口：点屏幕后在右侧中部出现一颗解锁键，2.5 秒后自己收。
        //     小屏这一格锁上之后如果没有它，就只剩"按系统返回键"能解开（踩过）。
        if (interactive && locked && lockHintVisible) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(end = 12.dp)
                    .zIndex(3f),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(Color(0x66000000))
                        .clickable {
                            locked = false
                            lockHintVisible = false
                            touchControls()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.LockOpen,
                        contentDescription = stringResource(R.string.m_player_unlock),
                        tint = Color.White,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }

        // 7) 纵滑指示：图标 + 百分比，松手 800ms 后收掉
        levelKind?.let { kind ->
            LevelIndicator(kind = kind, value = levelValue, modifier = Modifier.fillMaxSize())
        }

        // 8) 两块面板。PlayerSheet 用的是 ModalBottomSheet（挂在窗口上），
        //    所以放进这一格里也不会被 16:9 的圆角裁掉
        if (castSheetVisible) {
            CastSheet(
                controller = CastController,
                current = casting,
                onPick = { device -> startCast(device) },
                onStop = {
                    stopCast()
                    castSheetVisible = false
                },
                onDismiss = { castSheetVisible = false },
                failureText = if (castFailure) stringResource(R.string.m_player_cast_failed) else null,
            )
        }
        if (speedSheetVisible) {
            SpeedSheet(
                speed = speed,
                onPick = { value ->
                    // 即改即生效：引擎与状态同步改
                    speed = value
                    engine?.setSpeed(value)
                    onSpeedChange(value)
                    // 收面板放在**调用点**：SpeedSheet 被小屏/全屏/播放页三处复用，
                    // 组件自己不负责关（关法各不相同），谁开谁关
                    speedSheetVisible = false
                },
                onClose = { speedSheetVisible = false },
            )
        }

        // 9) 流量确认卡：**盖在播放器这块 16:9 区域内**，不是全屏 AlertDialog，
        //    也不是页面级弹窗——要拦的就是"这一格要不要开始耗流量"，
        //    卡片浮到别处，用户看到的就只是"点了一下，屏幕上多了一个框"。
        if (consentVisible) {
            TrafficConsentCard(
                onAccept = onConsentAccept,
                onCancel = onConsentCancel,
                modifier = Modifier.fillMaxSize().zIndex(3f),
            )
        }
    }
}

/**
 * 小屏的画面。
 *
 * 与全屏页的 VideoSurface 是同一份写法，但只保留 Exo 分支：小屏不做内核切换。
 * PlayerEngine 接口不暴露播放器实例，而 ExoEngine.attachSurface 对 Exo 是空体——
 * 画面只有把 PlayerView 绑到 ExoPlayer 上才出得来，所以这里是一次"被迫的反射"，
 * 取不到就返回 null（PlayerView.player = null 只是黑屏，不会崩）。
 */
@Composable
private fun VideoSurface(
    engine: PlayerEngine,
    /** 比例档位翻出来的 Exo resizeMode：换它只改一次缩放入参，不重建 Surface */
    resizeMode: Int = AspectRatioFrameLayout.RESIZE_MODE_FIT,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                // 控制器关闭：四角控件由本组件自己提供
                useController = false
                keepScreenOn = true
            }
        },
        update = {
            it.player = exoPlayerOf(engine)
            it.resizeMode = resizeMode
        },
        // 引擎还归会话所有（切到播放页那一瞬间）就不要摘：播放页刚把同一个 ExoPlayer
        // 绑到它自己的 PlayerView 上，这一摘会让新画面黑一帧到一秒——看起来正是"又在缓冲"
        onRelease = { if (!PlaybackSession.isCurrent(engine)) it.player = null },
        modifier = modifier,
    )
}

/** 与 PlayerScreen 同一段反射（见 [VideoSurface] 的说明） */
private fun exoPlayerOf(engine: PlayerEngine): Player? = try {
    val field = engine.javaClass.getDeclaredField("player")
    field.isAccessible = true
    field.get(engine) as? Player
} catch (t: Throwable) {
    Log.w("DetailPreviewPlayer", "取 ExoPlayer 实例失败（渲染视图接口缺口）：" + t.message)
    null
}

/** 四角按钮：44dp 触控区 + 半透明圆底，压在亮画面上也读得出图标 */
@Composable
private fun CornerIcon(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    tint: Color = Color.White,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .size(CornerTouchSize)
            .clip(CircleShape)
            .background(Color(0x59000000))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = tint,
            modifier = Modifier.size(22.dp),
        )
    }
}

/**
 * 加载/失败时占住画面的一块占位。
 *
 * 为什么要它：失败时若只留一块黑，用户分不清"在缓冲"和"这条路走不通了"。
 * 这里一定给一句话，能重试的再给一个重试。绝不无限转圈。
 */
@Composable
private fun PreviewPlaceholder(
    message: String?,
    onRetry: (() -> Unit)?,
    modifier: Modifier = Modifier,
    showSpinner: Boolean = false,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (showSpinner) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    color = ModaColors.Accent,
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.height(10.dp))
            }
            if (message != null) {
                Text(
                    text = message,
                    style = ModaType.Caption,
                    color = Color.White.copy(alpha = 0.86f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
            if (onRetry != null) {
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = onRetry) {
                    Text(
                        text = stringResource(R.string.detail_retry),
                        style = ModaType.Caption,
                        color = ModaColors.AccentSoft,
                    )
                }
            }
        }
    }
}

/**
 * 蜂窝 + 流量提醒开启时的"待起播"画面：封面照原样铺满 16:9，中间一颗播放键。
 *
 * 为什么是封面不是黑屏：这一格此刻**故意**没有装地址，黑屏加一句话会被读成"坏了"；
 * 封面既回答了"我在看哪部剧"，也把"要点一下才开始"变成了形状上的暗示。
 * 播放键 56dp：手指尺寸的圆底比图标本身更能说明这是一颗按钮（触控区就是这颗 56dp）。
 */
@Composable
private fun TrafficHoldCover(
    cover: String,
    title: String,
    onStart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        // 取不到封面时 RemoteImage 自己落成首字色块（附属功能失败不阻断主流程）
        RemoteImage(
            url = cover,
            title = title,
            modifier = Modifier.fillMaxSize(),
            corner = 14.dp,
        )
        Box(
            Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(Color(0x99000000))
                .clickable(onClick = onStart),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = stringResource(R.string.detail_play),
                tint = Color.White,
                modifier = Modifier.size(30.dp),
            )
        }
    }
}

/**
 * 「当前是移动网络」确认卡：半透明遮罩 + 一句说明 + 「不再提醒」+ 继续/取消。
 *
 * 高度是硬约束：它必须装在 16:9 那一格里（360dp 宽的屏上约 185dp 高），所以
 * 用 12dp 内边距、说明最多两行、勾选行 36dp、按钮行贴底——多一行就把它撑出画面。
 *
 * 「不再提醒」只是这张卡的临时勾选态：**点了「继续播放」才落盘**。
 * 勾了又取消不该改变设置，否则用户"点了个取消，以后就再也不提醒了"。
 */
@Composable
private fun TrafficConsentCard(
    onAccept: (dontAskAgain: Boolean) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var dontAskAgain by remember { mutableStateOf(false) }
    Box(
        modifier
            // 遮罩自己吃掉点击：否则点在卡片外面会穿到下面的手势层/画面
            .background(Color(0xC0000000))
            .pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(horizontal = 12.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xF21B1B20))
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Text(
                text = stringResource(R.string.m_detail_traffic_message),
                style = ModaType.Caption,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            // 勾选行整行可点（36dp + 上下 4dp 内边距 ≈ 44dp 触控下限）：
            // 只有 18dp 的方框自己能点的话，手指基本按不上
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { dontAskAgain = !dontAskAgain }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 自绘小方框而不是 material3 Checkbox：后者带 48dp 最小触控区，
                // 一行就吃掉这一格四分之一的高度
                Box(
                    Modifier
                        .size(18.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (dontAskAgain) ModaColors.Accent else Color.Transparent)
                        .border(
                            width = 1.dp,
                            color = if (dontAskAgain) ModaColors.Accent else Color.White.copy(alpha = 0.55f),
                            shape = RoundedCornerShape(4.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (dontAskAgain) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(13.dp),
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.m_detail_traffic_dont_ask),
                    style = ModaType.Caption,
                    color = Color.White.copy(alpha = 0.86f),
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onCancel) {
                    Text(
                        text = stringResource(R.string.m_detail_traffic_cancel),
                        style = ModaType.Caption,
                        color = Color.White.copy(alpha = 0.86f),
                    )
                }
                TextButton(onClick = { onAccept(dontAskAgain) }) {
                    Text(
                        text = stringResource(R.string.m_detail_traffic_play),
                        style = ModaType.Caption,
                        color = ModaColors.AccentSoft,
                    )
                }
            }
        }
    }
}

/**
 * 小屏进度条的圆点：12dp 纯白实心，**没有光晕、没有外扩内边距**。
 *
 * 不直接用 M3 默认 thumb：默认那颗按规范是 4dp 圆点 + halo + 一块 20dp 的 thumb 轨道高度，
 * 在 16:9 这一格里显得又粗又模糊，而且自带左右各一圈留白，进度条看着比实际短。
 * 拖动/点击的命中区不靠这颗：Slider 自己在整条轨道上接管手势，所以圆点画小不等于难点。
 */
@Composable
private fun PreviewSliderThumb() {
    Box(Modifier.size(12.dp).clip(CircleShape).background(Color.White))
}

/**
 * 小屏进度条的轨道：3dp 高、全圆角，未播段白色 25% 不透明、已播段纯白。
 *
 * [fraction] 用 SliderState.coercedValueAsFraction（已按 valueRange 归一化）：
 * 自己按 positionMs/durationMs 算会与 Slider 的夹取/步进各说各话，拖动预览时尤其明显。
 */
@Composable
private fun PreviewSliderTrack(fraction: Float) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(3.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.25f)),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .fillMaxHeight()
                .background(Color.White),
        )
    }
}

/**
 * 亮度/音量指示：一个图标 + 百分比 + 一根 4dp 细条（照搬全屏页的形态，缩到这一格的尺度）。
 * 小屏没有 OSD 可看，纵滑若不给反馈，用户不知道自己到底调了多少。
 */
@Composable
private fun LevelIndicator(kind: LevelKind, value: Float, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xB3000000))
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = if (kind == LevelKind.Brightness) Icons.Filled.BrightnessMedium else Icons.Filled.VolumeUp,
                contentDescription = null,
                tint = Color.White,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = (value.coerceIn(0f, 1f) * 100).roundToInt().toString() + "%",
                style = ModaType.Caption,
                color = Color.White,
            )
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier
                    .width(96.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0x59FFFFFF)),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(value.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .background(Color.White),
                )
            }
        }
    }
}
