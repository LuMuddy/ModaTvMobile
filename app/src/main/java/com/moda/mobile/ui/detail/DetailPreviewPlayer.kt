package com.moda.mobile.ui.detail

import android.content.Context
import android.media.AudioManager
import android.util.Log
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.moda.mobile.R
import com.moda.mobile.model.AppError
import com.moda.mobile.model.PlayerError
import com.moda.mobile.ui.DetailPreview
import com.moda.mobile.ui.components.appErrorText
import com.moda.mobile.ui.components.playerErrorText
import com.moda.mobile.ui.player.CastController
import com.moda.mobile.ui.player.CastDevice
import com.moda.mobile.ui.player.CastSheet
import com.moda.mobile.ui.player.CastStatusChip
import com.moda.mobile.ui.player.PlayerEngine
import com.moda.mobile.ui.player.PlayerEngines
import com.moda.mobile.ui.player.SpeedSheet
import com.moda.mobile.ui.player.findActivity
import com.moda.mobile.ui.theme.ModaColors
import com.moda.mobile.ui.theme.ModaType
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 四角图标的触控区：44dp 是触摸纪律的下限，再小就不是"按不到"而是"不敢按" */
private val CornerTouchSize = 44.dp

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

/** 纵滑调的是亮度还是音量（判定用手势起点 x，见手势层注释） */
private enum class LevelKind { Brightness, Volume }

/**
 * 详情页顶部的小屏播放器：16:9、真的能播、盖一层常驻的四角控件。
 *
 * 为什么四角按钮**常驻**而不是单击显隐：全屏播放页可以靠一行 OSD 把所有入口收起来，
 * 因为那里整屏就是播放器；这里只有 16:9 一格，把返回和全屏藏起来，用户下一次就找不到了。
 * 所以单击画面只做一件事——播放/暂停。
 *
 * 这里**没有**底部进度条、播放键、选集、清晰度/内核/解码/比例面板：那些是"换个屏幕看"的
 * 需求，全屏页已经有一份；小屏再摆一套，两边的规则会各自演进（尤其档位与内核覆盖）。
 * 小屏也不写历史、不判 [com.moda.mobile.data.local.IntroOutroRules] 的片头片尾——
 * 进度始终只由全屏播放页负责，否则同一部剧会有两个写者。
 *
 * 引擎只用 ExoPlayer：小屏不是排查解码问题的地方（换内核/软解是播放页的面板），
 * 起不来就给一块错误面板 + 重试，外加一个直链→HLS 的自动回退。
 *
 * [onSpeedChange] 只把选择同步给调用方（默认空实现）：倍速是否需要跨页持久化由上层决定，
 * 本组件不碰 DataStore。
 */
@Composable
fun DetailPreviewPlayer(
    title: String,
    /** 要播的地址；null = 还没装好 */
    preview: DetailPreview?,
    loading: Boolean,
    /** VM 侧的错误码（换直链失败）：文案走 [appErrorText] */
    error: AppError?,
    /** 小屏在播第几集（0 起下标）：标题行与全屏按钮都用它 */
    episodeIndex: Int,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    onEnterFullscreen: () -> Unit,
    onSpeedChange: (Float) -> Unit = {},
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
    var buffering by remember { mutableStateOf(false) }
    var playError by remember(primaryUrl, attempt) { mutableStateOf<PlayerError?>(null) }
    var speed by remember { mutableFloatStateOf(1f) }
    var speedSheetVisible by remember { mutableStateOf(false) }

    /**
     * 引擎重建的 key：地址与重试次数。换地址必须换引擎——PlayerEngine 的 start 是"起播"，
     * 不是"换源"；同一实例上换源会把上一段缓冲一起带进来。
     */
    val engine: PlayerEngine? = remember(playUrl, attempt) {
        if (playUrl.isBlank()) null else PlayerEngines.create(context, "exo", true)
    }

    // —— 投屏 ——
    // casting 非空 = 正在投到哪台电视；投屏期间本地引擎保持暂停
    var casting by remember { mutableStateOf<CastDevice?>(null) }
    // 投屏前的位置：停止投屏后本地从这里接着播，不回到片头
    var castResumeMs by remember { mutableLongStateOf(0L) }
    var castSheetVisible by remember { mutableStateOf(false) }
    var castFailure by remember { mutableStateOf(false) }

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
    }

    fun setBufferingState(value: Boolean) {
        buffering = value
    }

    /** 单击画面的唯一动作：播放/暂停。错误态下没有画面，这一层压根不挂 */
    fun togglePlay() {
        val e = engine ?: return
        if (e.isPlaying) e.pause() else e.play()
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

    DisposableEffect(engine) {
        val e = engine ?: return@DisposableEffect onDispose { }
        e.listener = object : PlayerEngine.Listener {
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
                // 画面按 16:9 外框 FIT 缩放，这里不需要额外动作
            }
        }
        onDispose {
            // 先摘 listener 再 release：release 期间可能同步回调，那时这一格可能已在销毁。
            // 这一步就是"离开详情页必须停声"的落点：引擎实例只活在这次组合里
            e.listener = null
            e.release()
        }
    }

    // 切后台必须停声：详情页不是播放页，没有"切走了还想听"的场景。
    // 这里只 pause 不 release——真正的 release 归上面那个 DisposableEffect
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

    LaunchedEffect(engine, playUrl) {
        val e = engine ?: return@LaunchedEffect
        if (playUrl.isBlank()) return@LaunchedEffect
        e.start(playUrl, hls = useHls, startMs = 0L, speed = speed)
    }

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

    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(14.dp))
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
                onRetry = { attempt++ },
                modifier = Modifier.fillMaxSize(),
            )

            engine == null -> PreviewPlaceholder(
                message = if (loading) stringResource(R.string.core_player_preparing) else null,
                showSpinner = loading,
                onRetry = null,
                modifier = Modifier.fillMaxSize(),
            )

            else -> VideoSurface(engine = engine, modifier = Modifier.fillMaxSize())
        }

        // 2) 手势层：**一段** pointerInput，单击与纵滑在同一段里分派。
        //    不叠 detectTapGestures + detectDragGestures 两个检测器——它们互不知情，
        //    谁先拿到同一次按下取决于注册顺序，正是 AGENTS.md 里禁止的「两套手势抢同一片区域」。
        //    排在四角按钮之前：Compose 的命中测试是"后画的先拿到"，所以返回/投屏/倍速/全屏
        //    永远优先吃掉触摸；awaitFirstDown 只接未被消费的按下，按在按钮上不会顺带暂停画面。
        //    单击 = 播放/暂停（小屏不做"单击显隐控制层"：角落按钮是常驻的）。
        //    只做纵滑，不做双击快进、不做横滑 seek——小屏和详情页的纵向滚动共用一块屏幕，
        //    再叠一条横向手势就会和"选下一集要往下滑"互相抢。
        if (interactive) {
            Box(
                Modifier
                    .fillMaxSize()
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
                            // 抬手时还没越过斜率 = 单击。手势被系统打断（来电/划走）不算单击，
                            // 只有真的"抬起"才切播放——否则一次被打断的滑动会莫名其妙暂停画面
                            if (dragging) levelTick++ else if (lifted) togglePlay()
                        }
                    },
            )
        }

        // 3) 左上角：返回 + **同一行**的「片名 · 第 N 集」，整页仅此一处文字。
        //    标题不另起一行：这一格只有 16:9（手机竖屏约 184dp 高），多占一行会把文字压向画面中央，
        //    左上角也只剩下一个孤零零的返回键。
        //    end = 108dp 是给右上角那两颗 44dp 图标留的位置（44+8+44 + 两侧 6dp 内边距），
        //    标题只吃剩下的宽度：长片名在这里 ellipsis，不会钻到投屏/倍速图标底下。
        Row(
            Modifier
                .align(Alignment.TopStart)
                .padding(start = 4.dp, top = 8.dp, end = 108.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CornerIcon(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                description = stringResource(R.string.search_back),
                onClick = onBack,
            )
            // 8dp 是图标圆底与文字之间的呼吸位；再大标题就离开返回键的视线范围了
            Spacer(Modifier.width(8.dp))
            Text(
                text = listOf(title, episodeLabel).filter { it.isNotBlank() }.joinToString(" · "),
                // 降一档：Caption（12sp）+ 主文字色。Heading/Body 在这块画面里会把标题顶成第二条字幕
                style = ModaType.Caption,
                color = ModaColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // fill = false：占"剩余宽度"但不强行撑满，短片名不会把这一行拉出一段空白
                modifier = Modifier.weight(1f, fill = false),
            )
        }

        // 4) 右上角：投屏 + 倍速（间距 8dp）。投屏中额外挂一条「正在投屏到 X」——
        //    投到电视之后手机是黑的，不写出来用户没法确认自己投成功了没有
        Row(
            Modifier.align(Alignment.TopEnd).padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
        }

        // 5) 右下角：全屏一颗——进既有播放页播"小屏正在播的这一集"
        CornerIcon(
            icon = Icons.Filled.Fullscreen,
            description = stringResource(R.string.danmaku_region_full),
            onClick = onEnterFullscreen,
            modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),
        )

        // 6) 缓冲指示：首帧还没出来时给一个细环，免得这一格看起来就是坏了
        if (interactive && buffering && !playing) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center).size(22.dp),
                color = Color.White.copy(alpha = 0.9f),
                strokeWidth = 2.dp,
            )
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
                    // 即改即生效：引擎与状态同步改，面板本身不负责关（与全屏页同一个语义）
                    speed = value
                    engine?.setSpeed(value)
                    onSpeedChange(value)
                },
                onClose = { speedSheetVisible = false },
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
private fun VideoSurface(engine: PlayerEngine, modifier: Modifier = Modifier) {
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
            it.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        },
        onRelease = { it.player = null },
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
