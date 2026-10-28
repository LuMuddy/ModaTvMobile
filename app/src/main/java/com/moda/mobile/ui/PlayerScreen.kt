package com.moda.mobile.ui

import android.content.ContentValues
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.media.AudioManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.PixelCopy
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.moda.mobile.data.local.DecoderMode
import com.moda.mobile.data.local.IntroOutro
import com.moda.mobile.data.local.IntroOutroRules
import com.moda.mobile.data.local.IntroOutroStoreImpl
import com.moda.mobile.data.local.PlaybackOverride
import com.moda.mobile.data.local.PlaybackOverrideStoreImpl
import com.moda.mobile.data.local.PlayerCore
import com.moda.mobile.data.local.PlayerSettings
import com.moda.mobile.data.local.PlayerSettingsStoreImpl
import com.moda.mobile.model.Danmaku
import com.moda.mobile.model.Episode
import com.moda.mobile.model.PlayerError
import com.moda.mobile.model.Tier
import com.moda.mobile.ui.components.playerErrorText
import com.moda.mobile.ui.player.AspectSheet
import com.moda.mobile.ui.player.CastController
import com.moda.mobile.ui.player.CastDevice
import com.moda.mobile.ui.player.CastSheet
import com.moda.mobile.ui.player.CastStatusChip
import com.moda.mobile.ui.player.PlayerBottomBar
import com.moda.mobile.ui.player.PlayerTopBar
import com.moda.mobile.ui.player.ControlsBottomInset
import com.moda.mobile.ui.player.CoreSheet
import com.moda.mobile.ui.player.DanmakuOverlay
import com.moda.mobile.ui.player.DanmakuSheet
import com.moda.mobile.ui.player.DecoderSheet
import com.moda.mobile.ui.player.EpisodeSheet
import com.moda.mobile.ui.player.MarksSheet
import com.moda.mobile.ui.player.MoreSheet
import com.moda.mobile.ui.player.PlaybackSession
import com.moda.mobile.ui.player.PlayerEngine
import com.moda.mobile.ui.player.PlayerEngines
import com.moda.mobile.ui.player.PlayerErrorPane
import com.moda.mobile.ui.player.PlayerPanel
import com.moda.mobile.ui.player.PlayerSideAction
import com.moda.mobile.ui.player.QualitySheet
import com.moda.mobile.ui.player.SpeedSheet
import com.moda.mobile.ui.player.findActivity
import com.moda.mobile.ui.theme.ModaColors
import com.moda.mobile.ui.theme.ModaType
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 控制层无操作自动隐藏。TV 端是 5 秒，手机端取 3 秒：
 * 触屏的显隐是「点一下就有」，隐藏得晚反而会挡住字幕与画面下缘。
 */
private const val ControlsAutoHideMs = 3_000L

/** 双击左右半屏的快退/快进步长，与 TV 端左右键同一步长 */
private const val SeekStepMs = 10_000L

/** 进度采样间隔：与 TV 端同一份节流常量（300ms 采样、1000ms 上报一次） */
private const val TickMs = 300L
private const val ProgressReportMs = 1_000L

/** 缓冲持续多久才值得弹动画：短于此的抖动用户根本来不及看见 */
private const val BufferingShowDelayMs = 400L

/** 跳转提示与亮度/音量浮层的存活时间 */
private const val SeekHintMs = 900L
private const val LevelHintMs = 800L
private const val LockHintMs = 2_500L

/** 截图回执（成功/失败）在屏上的存活时间：与锁屏提示同一个节奏 */
private const val ShotHintMs = 2_500L

/**
 * 「这一集以前播过」的判定阈值，与 TV 端同一个数。
 * startPositionMs 正是 VM 的 resumePosition()：只有历史里有同一集时才 > 0，
 * 5 秒下限把「点进去立刻退出」从「看过」里摘出去。
 */
private const val HasPlayedThresholdMs = 5_000L

/** 判定拖动方向的位移阈值（dp）：小于它不认方向，避免手指抖动抢走单击 */
private const val DragSlopDp = 12f

/**
 * 拿到 ExoEngine 内部的 ExoPlayer 实例。
 *
 * 与 TV 端同一处**被迫的反射**：PlayerEngine 接口不暴露播放器实例，
 * 而 ExoEngine.attachSurface 对 Exo 是空体——画面只有把 PlayerView 绑到 ExoPlayer 上才出得来。
 * 取不到就返回 null（PlayerView.player = null 只是黑屏，不会崩），并在日志里留一行。
 */
private fun exoPlayerOf(engine: PlayerEngine): Player? = try {
    val field = engine.javaClass.getDeclaredField("player")
    field.isAccessible = true
    field.get(engine) as? Player
} catch (t: Throwable) {
    Log.w("PlayerScreen", "取 ExoPlayer 实例失败（渲染视图接口缺口）：" + t.message)
    null
}

/** 内核枚举 → PlayerEngine 一侧的字符串；映射只出现在这一处（同 TV 端 coreKeyOf） */
private fun coreKeyOf(core: PlayerCore): String = when (core) {
    PlayerCore.Ijk -> "ijk"
    PlayerCore.Exo -> "exo"
}

/** 纵向拖动调的是亮度还是音量 */
private enum class LevelKind { Brightness, Volume }

/**
 * 截图要抓的那一层画面。
 *
 * 画面到底落在哪种 View 上由内核决定（IJK = 裸 SurfaceView，Exo = PlayerView 内部的
 * SurfaceView 或 TextureView），截图那一侧不该知道这件事，所以由 [VideoSurface] 创建 View 时
 * 把实例填进来，点击时再取。用普通持有对象而不是 State：它只在点击那一刻被读一次，
 * 写成 State 反而会让每次重组多一轮不可见的状态写入。
 */
private class CaptureSource {
    // 填/读分别发生在 View 创建（主线程）与点击（主线程），@Volatile 只为让语义显式
    @Volatile var surfaceView: SurfaceView? = null
    @Volatile var textureView: TextureView? = null
}

/**
 * 视频渲染面（与 TV 端 VideoSurface 同一份实现，只去掉焦点相关的属性）。
 *  · Exo：画面挂在 PlayerView 上，必须绑定具体的 ExoPlayer 实例；
 *  · IJK：画面直接渲染到 SurfaceHolder，用裸 SurfaceView + attachSurface(holder)。
 */
@Composable
private fun VideoSurface(
    engine: PlayerEngine,
    core: PlayerCore,
    resizeMode: Int,
    capture: CaptureSource,
    modifier: Modifier = Modifier,
) {
    when (core) {
        PlayerCore.Ijk -> AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    // 截图源：IJK 的画面就是直接画在这个 Surface 上的
                    capture.surfaceView = this
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(h: SurfaceHolder) {
                            engine.attachSurface(h)
                        }

                        override fun surfaceChanged(h: SurfaceHolder, format: Int, w: Int, hh: Int) {
                            // 尺寸变化后部分机型会换一个 Surface，重新交一次句柄才不出黑屏
                            engine.attachSurface(h)
                        }

                        override fun surfaceDestroyed(h: SurfaceHolder) = Unit
                    })
                }
            },
            modifier = modifier,
        )

        // 控制器关闭：播放页的按钮全部由自己的控制层提供，不要 PlayerView 那套
        PlayerCore.Exo -> AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    keepScreenOn = true
                }
            },
            update = {
                it.player = exoPlayerOf(engine)
                it.resizeMode = resizeMode
                // 截图源：PlayerView 内部可能是 SurfaceView 也可能是 TextureView（surface_type），
                // 两种都记下来——截图时按拿到的那一种走对应的拷贝方式
                when (val view = it.videoSurfaceView) {
                    is SurfaceView -> capture.surfaceView = view
                    is TextureView -> capture.textureView = view
                    else -> Unit
                }
            },
            onRelease = {
                // 引擎还归会话所有（退回详情页那一瞬间）就不要摘：小屏刚把同一个 ExoPlayer
                // 绑到它自己的 PlayerView 上，这一摘会让小屏黑一帧到一秒——看起来正是"又在缓冲"
                if (!PlaybackSession.isCurrent(engine)) it.player = null
                capture.surfaceView = null
                capture.textureView = null
            },
            modifier = modifier,
        )
    }
}

/**
 * 抓一帧当前画面。
 *
 * 用 PixelCopy 而不是截 Activity 全屏：PixelCopy 拷的是播放器正在渲染的那层 Surface，
 * 抓到的就是**视频帧本身**，控制层、弹幕、进度条都不会混进来。
 * 拿不到帧就返回 null 而不是抛：没有 View（还没 attach）、还没出首帧
 * （ERROR_SOURCE_NO_DATA）、安全解码输出（DRM）都是这样——调用方只要一条「没截到」的回执。
 */
private fun captureFrame(source: CaptureSource, onResult: (Bitmap?) -> Unit) {
    val handler = Handler(Looper.getMainLooper())
    try {
        val surfaceView = source.surfaceView
        if (surfaceView != null && surfaceView.width > 0 && surfaceView.height > 0) {
            val frame = Bitmap.createBitmap(
                surfaceView.width,
                surfaceView.height,
                Bitmap.Config.ARGB_8888,
            )
            PixelCopy.request(
                surfaceView,
                frame,
                { result ->
                    // 回调在主线程：成功就把这张位图交出去，失败当场回收，不留垃圾
                    if (result == PixelCopy.SUCCESS) {
                        onResult(frame)
                    } else {
                        frame.recycle()
                        onResult(null)
                    }
                },
                handler,
            )
            return
        }
        // Exo 配 surface_type=texture_view 时才走这条：内容本来就在内存里，不用 PixelCopy
        val textureView = source.textureView
        if (textureView != null && textureView.isAvailable) {
            onResult(textureView.bitmap)
            return
        }
    } catch (t: Throwable) {
        Log.w("PlayerScreen", "截图抓帧失败：" + t.message)
    }
    onResult(null)
}

/**
 * 把一帧写进系统相册的 Pictures/ModaTv。
 *
 * 只从 API 29 起调用：Q 起往自己的 MediaStore 记录里写不需要任何权限；Q 以下必须
 * 先拿 WRITE_EXTERNAL_STORAGE，而播放页明确不申请运行时权限，所以那条路不开
 * （低版本按钮置灰并给一句原因，见 PlayerScreen 里的 takeScreenshot）。
 * 返回 Boolean 而不是抛异常：截图失败不该把播放页带进错误态。
 */
private fun saveShotToGallery(context: Context, bitmap: Bitmap): Boolean = try {
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        // 文件名只要求唯一，时间戳就够；不起中文名，免得各机型对文件名的清洗规则不一样
        put(MediaStore.Images.Media.DISPLAY_NAME, "ModaTv_" + System.currentTimeMillis() + ".jpg")
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ModaTv")
        // pending：写完才让它出现在相册里，否则用户可能读到半张图
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
    if (uri == null) {
        false
    } else {
        val written = resolver.openOutputStream(uri)?.use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
        } == true
        if (written) {
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                null,
                null,
            )
        } else {
            // 写失败就把这条空记录删掉，别在相册里留一个点不开的条目
            resolver.delete(uri, null, null)
        }
        written
    }
} catch (t: Throwable) {
    Log.w("PlayerScreen", "截图落库失败：" + t.message)
    false
}

/** 双击快退/快进的居中反馈：一个方向箭头 + 累计跳转量（照搬 TV 端 SeekHint 的形态） */
@Composable
private fun SeekHint(accumMs: Long, modifier: Modifier = Modifier) {
    val forward = accumMs >= 0
    val seconds = (abs(accumMs) / 1000).toInt()
    Box(modifier, contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (forward) "▶▶" else "◀◀",
                style = ModaType.Body.copy(fontSize = 34.sp),
                color = Color.White.copy(alpha = 0.92f),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = stringResource(R.string.player_seek_seconds, seconds),
                style = ModaType.Body.copy(fontSize = 24.sp),
                color = Color.White.copy(alpha = 0.92f),
            )
        }
    }
}

/** 缓冲指示器：半透明深色圆底 + 白色细环 + 90° 弧匀速转圈（900ms 一圈，同 TV 端） */
@Composable
private fun BufferingIndicator(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "buffering")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "spin",
    )
    Box(modifier, contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(64.dp).background(Color.Black.copy(alpha = 0.28f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(40.dp)) {
                val strokePx = 3.dp.toPx()
                val inset = strokePx / 2f
                val ring = Size(size.width - strokePx, size.height - strokePx)
                drawCircle(
                    color = Color.White.copy(alpha = 0.20f),
                    radius = ring.width / 2f,
                    center = center,
                    style = Stroke(strokePx),
                )
                drawArc(
                    color = Color.White.copy(alpha = 0.92f),
                    startAngle = angle,
                    sweepAngle = 90f,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = ring,
                    style = Stroke(width = strokePx, cap = StrokeCap.Round),
                )
            }
        }
    }
}

/**
 * 亮度/音量浮层：一个图标 + 百分比 + 一根 4dp 的细条。
 * 手机端没有 OSD 可看，纵向拖动若不给反馈，用户不知道自己到底调了多少。
 */
@Composable
private fun LevelIndicator(kind: LevelKind, value: Float, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xB3000000))
                .padding(horizontal = 26.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = if (kind == LevelKind.Brightness) Icons.Filled.BrightnessMedium else Icons.Filled.VolumeUp,
                contentDescription = null,
                tint = Color.White,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = (value.coerceIn(0f, 1f) * 100).roundToInt().toString() + "%",
                style = ModaType.Body,
                color = Color.White,
            )
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier
                    .width(132.dp)
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

/**
 * 播放页：横屏全屏 + 手势 + 底部控制层 + 底部面板。
 *
 * 与 TV 端的差异只有「交互层」：
 *  电视：遥控器按键 → OsdSlot 索引 → L1 控制条 / L2 浮窗；
 *  手机：触摸手势 → controlsVisible/panel 状态 → 底部控制层 / ModalBottomSheet。
 * 播放内核、清晰度、倍速、比例、片头片尾判定、进度上报全部照搬，见各段注释。
 *
 * 16 个实参的名字与顺序被 AppRoot 的调用点钉死（PORT_CONTRACT §5），不得改名或移动；
 * 新增能力只能以带默认值的参数追加在末尾。
 */
@Composable
fun PlayerScreen(
    url: String,
    title: String,
    // 片头/片尾按剧记录、不分集，cid 是这部剧的唯一键
    cid: String,
    episodeLabel: String,
    tiers: List<Tier>,
    selectedTier: Tier?,
    danmaku: List<Danmaku>,
    startPositionMs: Long,
    onTierChange: (Tier) -> Unit,
    onProgress: (Long, Long) -> Unit,
    onEnded: () -> Unit,
    onBack: () -> Unit,
    // 可选兜底流：仅当 MP4 直链起播失败时切过去重播一次
    hlsUrl: String? = null,
    episodes: List<Episode> = emptyList(),
    currentEpisodeIndex: Int = 1,
    onSelectEpisode: (Episode) -> Unit = {},
) {
    val context = LocalContext.current
    val activity = remember(context) { findActivity(context) }
    val audioManager = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }

    // 重试即换 key 重建引擎：播放器进入错误态后内部状态不可靠（同 TV 端）
    var attempt by remember { mutableIntStateOf(0) }
    // 默认走 MP4 直链，起播失败才切整片 HLS，且只切一次，避免两路地址互相循环
    var fellBackToHls by remember(url, hlsUrl) { mutableStateOf(false) }
    val useHls = fellBackToHls && hlsUrl != null
    val playUrl = if (useHls) hlsUrl!! else url

    var playing by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(true) }
    var bufferingShown by remember { mutableStateOf(false) }
    // key 用布尔值是故意的：缓冲在 400ms 内恢复时协程被取消，动画完全不会出现
    LaunchedEffect(buffering) {
        if (buffering) {
            delay(BufferingShowDelayMs)
            bufferingShown = true
        } else {
            bufferingShown = false
        }
    }

    var positionMs by remember { mutableLongStateOf(startPositionMs) }
    var durationMs by remember { mutableLongStateOf(0L) }
    // 只存错误码：文案在渲染点按当前语言取
    var playError by remember(url, attempt) { mutableStateOf<PlayerError?>(null) }
    // 失败重试要接着上次位置播，而不是回到片头
    var resumeFrom by remember(url) { mutableLongStateOf(startPositionMs) }
    // 引擎没有 playbackState，播到结尾这件事只能自己记
    var ended by remember { mutableStateOf(false) }

    // —— 控制层 / 面板 / 锁定 ——
    // 进播放页默认不显示控制层：用户是来看剧的，不是来看菜单的
    var controlsVisible by remember { mutableStateOf(false) }
    // 每次交互 +1，自动隐藏的计时以它为准（可取消计时：key 变了旧协程就被取消）
    var controlsTick by remember { mutableIntStateOf(0) }
    var locked by remember { mutableStateOf(false) }
    var lockHintTick by remember { mutableIntStateOf(0) }
    var lockHintVisible by remember { mutableStateOf(false) }
    // 同一时刻只有一个面板（TV 端 five-boolean 互斥表的触屏等价物）
    var panel by remember { mutableStateOf<PlayerPanel?>(null) }

    // —— 截图 ——
    // 画面落在哪种 View 上由 VideoSurface 创建时填进来，点击那一刻才读
    val captureSource = remember { CaptureSource() }
    // 截图回执：0 = 不显示；非 0 = 要显示的文案资源键（成功 / 没截到 / 系统版本不够）
    var shotHintRes by remember { mutableIntStateOf(0) }
    var shotHintTick by remember { mutableIntStateOf(0) }

    // —— 投屏 ——
    // casting 非空 = 正在投到哪台电视；投屏期间本地引擎保持暂停
    var casting by remember { mutableStateOf<CastDevice?>(null) }
    // 投屏前的位置：停止投屏后本地从这里接着播，不回到片头
    var castResumeMs by remember { mutableLongStateOf(0L) }
    var castSheetVisible by remember { mutableStateOf(false) }
    // 只记「上一次投屏失败了」：失败原因无法细分（电视不认就一声不吭），面板负责把这句话显示出来
    var castFailure by remember { mutableStateOf(false) }

    // —— 手势状态 ——
    // 拖动期间为 true：进度预览不写回引擎，也不向上报进度
    var dragSeeking by remember { mutableStateOf(false) }
    var seekAccumMs by remember { mutableLongStateOf(0L) }
    var seekHintTick by remember { mutableIntStateOf(0) }
    var brightness by remember { mutableFloatStateOf(0.5f) }
    var volume by remember { mutableFloatStateOf(0.5f) }
    var levelKind by remember { mutableStateOf<LevelKind?>(null) }
    var levelValue by remember { mutableFloatStateOf(0f) }
    var levelTick by remember { mutableIntStateOf(0) }

    // 手势回调里读的是「最新值」而不是组合时的快照：pointerInput 只在 locked 变化时重启，
    // 若直接捕获 positionMs/durationMs，拖动过程中它们一直是旧的
    val positionNow by rememberUpdatedState(positionMs)
    val durationNow by rememberUpdatedState(durationMs)
    val brightnessNow by rememberUpdatedState(brightness)
    val volumeNow by rememberUpdatedState(volume)

    // 设置从 DataStore 读进来，读到之前先用默认值
    val settingsStore = remember(context) { PlayerSettingsStoreImpl.create(context) }
    val introOutroStore = remember(context) { IntroOutroStoreImpl.create(context) }
    // 播放页里改的内核/软硬解只记在本剧（moda_playback_override），全局设置只当默认值
    val overrideStore = remember(context) { PlaybackOverrideStoreImpl.create(context) }
    var settings by remember { mutableStateOf(PlayerSettings()) }
    LaunchedEffect(settingsStore) {
        val loaded = settingsStore.current()
        // defaultSpeed 是「每次起播用哪个倍速」，speed 是「这次会话正在用哪个倍速」；
        // 会话开始把 speed 播种成 defaultSpeed，之后用户改的只动 speed
        settings = loaded.copy(speed = loaded.defaultSpeed)
    }

    var playbackOverride by remember(cid) { mutableStateOf(PlaybackOverride()) }
    LaunchedEffect(cid) {
        playbackOverride = if (cid.isBlank()) PlaybackOverride() else overrideStore.get(cid)
    }

    var marks by remember(cid) { mutableStateOf(IntroOutro()) }
    var marksReady by remember(cid) { mutableStateOf(false) }
    LaunchedEffect(cid) {
        marks = if (cid.isBlank()) IntroOutro() else introOutroStore.get(cid)
        marksReady = true
    }
    // 需求里的「片头片尾跳过开关」。默认开（有可信记录就跳，与 TV 端行为一致），
    // 只作用于本次播放：跳过与否的**事实依据仍然是「有没有可信记录」**，开关只是额外让路的机会
    var skipIntro by remember(cid) { mutableStateOf(true) }
    var skipOutro by remember(cid) { mutableStateOf(true) }

    val scope = rememberCoroutineScope()
    // 标记写失败只是这一次没记住，播放页不该因此进错误态
    suspend fun persistMark(isIntro: Boolean, ms: Long) {
        if (cid.isBlank()) return
        try {
            if (isIntro) introOutroStore.setIntro(cid, ms) else introOutroStore.setOutro(cid, ms)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 静默
        }
    }

    /** IJK 是否真的可用：首次调用会真的 new 一个播放器验 so，包进 remember 避免重复付这笔开销 */
    val ijkUsable = remember(context) { PlayerEngines.isIjkAvailable() }

    // 本剧实际要用的内核/解码：本剧覆盖优先，没有覆盖才回落设置页默认值；单一出口
    val wantedCore = playbackOverride.core ?: settings.playerCore
    val wantedDecoder = playbackOverride.decoder ?: settings.decoderMode
    val effectiveDanmakuEnabled = playbackOverride.danmakuEnabled ?: settings.danmakuEnabled
    // 工厂会静默回退：用户选 IJK 但包里没 so 时，渲染视图也必须跟着回落，否则必然黑屏
    val effectiveCore = if (wantedCore == PlayerCore.Ijk && ijkUsable) PlayerCore.Ijk else PlayerCore.Exo
    val hardware = wantedDecoder == DecoderMode.Hardware

    /**
     * 当前集的身份：集数表里 index == currentEpisodeIndex 的那一集（与 [nextEpisode] 同一口径）。
     * 只给会话判断"是不是同一集"用——同一 vid 换档位/换内核/回退 HLS/重试时，
     * 会话据此从旧引擎现在的进度接着播，而不是回到调用方进页时算好的 startMs。
     */
    val currentEpisodeVid = remember(episodes, currentEpisodeIndex) {
        episodes.firstOrNull { it.index == currentEpisodeIndex }?.vid.orEmpty()
    }

    /**
     * 引擎来自 [PlaybackSession]（重建的 key 仍是：地址、重试次数、内核、软硬解；
     * 后两个只能在构造时决定——Exo 的 MediaCodecSelector、IJK 的 mediacodec 选项都是 init 期写死）。
     *
     * 从详情页小屏点全屏过来时，那一格已经建好引擎并在播，这里拿到的是同一个实例：
     * 会话不 start、不 seek，位置接着走——这就是"不再转圈"的落点。
     * [resumeFrom] 只在会话真的新建引擎时才当 startMs 用。
     */
    val engine = remember(playUrl, attempt, effectiveCore, hardware) {
        PlaybackSession.acquire(
            context = context,
            vid = currentEpisodeVid,
            url = playUrl,
            hls = useHls,
            startMs = resumeFrom,
            speed = settings.speed,
            core = coreKeyOf(effectiveCore),
            hardware = hardware,
        )
    }

    /**
     * 「这一集以前播过吗」——本集起播那一刻的 startPositionMs 快照。
     * key 必须取「这一集的身份」而不是「这次播放的参数」：切清晰度会把 startPositionMs
     * 改写成当前进度（VM 的 changeTier），那时它必然 > 0，同一集播到一半就突然变成「播过」。
     */
    val initialStartMs = remember(cid, episodeLabel) { startPositionMs }
    val hasPlayedBefore = initialStartMs >= HasPlayedThresholdMs

    /**
     * 下一集：口径与 MainViewModel 的 nextEpisode 完全一致——[Episode.index] 是 0 基下标，
     * 取 index == currentEpisodeIndex + 1 的那一集。找不到（末集，或集数表还没到手）就是 null，
     * 按钮据此置灰；切集本身仍走 onSelectEpisode，这里不另造一套切集逻辑。
     */
    val nextEpisode = remember(episodes, currentEpisodeIndex) {
        episodes.firstOrNull { it.index == currentEpisodeIndex + 1 }
    }

    fun updateSettings(next: PlayerSettings) {
        val previous = settings
        settings = next
        scope.launch { settingsStore.save(next) }
        // 倍速要实时生效；换内核/切清晰度/回退 HLS 重建引擎时会重新应用
        if (next.speed != previous.speed) engine.setSpeed(next.speed)
    }

    fun applyOverride(next: PlaybackOverride) {
        playbackOverride = next
        if (cid.isBlank()) return
        scope.launch {
            try {
                overrideStore.save(cid, next)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 静默：这一集继续按刚选的值播，下次进来回到默认而已
            }
        }
    }

    // 电视上要显示的名字：与顶栏同一拼法（标题 · 集号），就是 metadata 里的 dc:title
    val castTitle = if (episodeLabel.isNotBlank()) title + " · " + episodeLabel else title

    /**
     * 弹幕总开关的唯一落点：底栏「弹幕」面板里的开关与进度条行尾的快捷开关都走这里。
     * 与全局默认相同就存 null（删键），与 core/decoder 同一条「恢复默认」约定。
     */
    fun toggleDanmaku() {
        applyOverride(
            playbackOverride.copy(
                danmakuEnabled = (!effectiveDanmakuEnabled).takeIf { it != settings.danmakuEnabled },
            ),
        )
    }

    fun touchControls() {
        controlsVisible = true
        controlsTick++
    }

    fun togglePlay() {
        if (playError != null) return
        if (engine.isPlaying) {
            engine.pause()
        } else {
            // 播到结尾后再点播放是「重播这一集」，得先把游标拨回开头
            if (ended) {
                engine.seekTo(0L)
                ended = false
            }
            engine.play()
        }
    }

    /**
     * 截图：抓当前视频帧并落进系统相册（Pictures/ModaTv）。
     *
     * 只支持 API 29+：更低的版本要先拿 WRITE_EXTERNAL_STORAGE，而播放页不做运行时权限申请。
     * 低版本按钮仍然可点，点下去给一句原因——置灰又点不动的按钮，用户问不出为什么不给用。
     * 失败一律落成一条回执，不进播放页的错误态（截图失败跟「这一集播不了」不是一回事）。
     */
    fun takeScreenshot() {
        touchControls()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            shotHintRes = R.string.m_player_shot_need_q
            shotHintTick++
            return
        }
        captureFrame(captureSource) { frame ->
            if (frame == null) {
                shotHintRes = R.string.m_player_shot_failed
                shotHintTick++
            } else {
                // 写盘放 IO 线程：一张 1080p JPEG 在主线程压完，控制层会明显卡一下
                scope.launch {
                    val saved = withContext(Dispatchers.IO) { saveShotToGallery(context, frame) }
                    frame.recycle()
                    shotHintRes = if (saved) {
                        R.string.m_player_shot_saved
                    } else {
                        R.string.m_player_shot_failed
                    }
                    shotHintTick++
                }
            }
        }
    }

    /** 双击左右半屏的唯一出口：夹在 0..片长之间，而且只报「实际跳了多远」 */
    fun seekBy(deltaMs: Long) {
        val from = engine.positionMs
        val target = (from + deltaMs)
            .coerceAtLeast(0L)
            .let { if (durationMs > 0) it.coerceAtMost(durationMs) else it }
        val actual = target - from
        if (actual == 0L) return
        engine.seekTo(target)
        positionMs = target
        seekAccumMs += actual
        seekHintTick++
    }

    fun recordMark(isIntro: Boolean) {
        val now = engine.positionMs
        // 0 秒没有意义：既不能当标记跳，写进去也只是个空记录
        if (now <= 0L) return
        marks = if (isIntro) marks.copy(introMs = now) else marks.copy(outroMs = now)
        scope.launch { persistMark(isIntro, now) }
    }

    /**
     * 投屏的唯一出口。地址优先级：MP4 直链 → HLS，各试一次。
     * 与本地播放的顺序相反是有意的：本地是「起不来才换 HLS」，投屏则先把最通用的直链交给电视。
     */
    fun startCast(device: CastDevice) {
        castFailure = false
        scope.launch {
            // 换一台电视：先把上一台停下，否则两台会同时播同一集
            casting?.takeIf { it.location != device.location }?.let { previous ->
                CastController.stop(previous)
                casting = null
            }
            val ok = CastController.play(device, url, castTitle) ||
                (!hlsUrl.isNullOrBlank() && CastController.play(device, hlsUrl!!, castTitle))
            if (ok) {
                castResumeMs = engine.positionMs
                // 本地必须停下来：两端同时出声是最容易被当成 bug 的失败形态
                engine.pause()
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
            engine.seekTo(castResumeMs)
            positionMs = castResumeMs
            engine.play()
        }
    }

    fun applyBrightness(value: Float) {
        brightness = value
        val window = activity?.window ?: return
        val attrs = window.attributes
        // 0f 会让屏幕全黑，从 0.01f 起步，避免用户把亮度拖到看不见任何东西
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

    // 进页面的亮度/音量起点取系统当前值：拖动是「相对当前」的加减，不是从 50% 跳到某个值
    LaunchedEffect(activity) {
        val current = activity?.window?.attributes?.screenBrightness ?: -1f
        brightness = if (current in 0.01f..1f) current else 0.5f
    }
    LaunchedEffect(audioManager) {
        val am = audioManager ?: return@LaunchedEffect
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        volume = am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
    }

    /**
     * 横屏 + 全屏（需求 5）。
     * 申请与恢复必须成对：退出播放页时把方向还回 UNSPECIFIED、把系统栏放出来，
     * 否则回到竖屏列表页会横着显示、且状态栏永远不出现。
     */
    DisposableEffect(activity) {
        val window = activity?.window
        val controller = window?.let { WindowInsetsControllerCompat(it, it.decorView) }
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        // 从屏幕边缘上划可以临时唤出系统栏，但不会退出沉浸式
        controller?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            // 亮度只作用于本 Activity 的窗口：离开播放页必须还原成「跟随系统」，
            // 否则用户在播放页调暗过之后，整个 App 都停在那个人为亮度上。
            // -1f 就是 BRIGHTNESS_OVERRIDE_NONE（系统默认值）。
            val leaving = activity?.window
            if (leaving != null) {
                val attrs = leaving.attributes
                attrs.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                leaving.attributes = attrs
            }
        }
    }

    /**
     * 离开播放页必须停止投屏（用户要求）。
     *
     * onDispose 里不能挂起：组合已经被拆除、rememberCoroutineScope 也随之取消，
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

    /**
     * 切后台必须让声音停下来（需求 6）。
     *
     * 这里选择 pause 而不是 release：release 会让回前台时必须重建内核、重新缓冲，
     * 用户接个电话回来画面要重跑一遍「正在准备播放」；pause 已经满足「不允许音频继续跑」，
     * 而真正的 release 由下面 engine 的 DisposableEffect 在退出页面时负责。
     */
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, engine) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    val last = engine.positionMs
                    if (last > 0L) resumeFrom = last
                    engine.pause()
                }
                Lifecycle.Event.ON_RESUME -> {
                    // 回到前台系统栏可能已经回来了，重新进沉浸式
                    val window = activity?.window
                    if (window != null) {
                        WindowInsetsControllerCompat(window, window.decorView)
                            .hide(WindowInsetsCompat.Type.systemBars())
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    /**
     * 回调参数名与 [PlayerEngine.Listener] 保持一致（否则 Kotlin 会报命名不一致的告警），
     * 但那样就会遮住同名的状态变量，所以经这三个局部函数落回状态（同 TV 端写法）。
     * 必须声明在 DisposableEffect 之前：局部函数不能先使用后声明。
     */
    fun setPlayingState(value: Boolean) {
        playing = value
    }

    fun setBufferingState(value: Boolean) {
        buffering = value
    }

    fun notifyEnded() {
        ended = true
        // onEnded 只调一次：VM 侧还有一层「同一个 vid 只处理一次」的闸门（endedVid）
        onEnded()
    }

    DisposableEffect(engine) {
        // 引用计数 +1：这一页正在显示它。归零后会话还有 800ms 宽限——
        // 「播放页卸载 → 详情页小屏挂载」正是靠这段窗口不重建引擎
        PlaybackSession.attach()
        // 复用来的引擎（从详情页小屏过来）可能已经在播：状态先跟引擎对齐一次。
        // 不这么做的话首帧会显示成"暂停 + 缓冲中"，直到下一次回调才纠正，
        // 而这正好是用户第一眼看到的东西
        setPlayingState(engine.isPlaying)
        if (engine.isPlaying) setBufferingState(false)
        val listener = object : PlayerEngine.Listener {
            override fun onPlayingChanged(playing: Boolean) {
                setPlayingState(playing)
            }

            override fun onBufferingChanged(buffering: Boolean) {
                setBufferingState(buffering)
            }

            override fun onEnded() {
                notifyEnded()
            }

            override fun onError(error: PlayerError) {
                // 直链先挂且有 HLS 兜底时静默切换：保住能看，比先弹报错体验好
                if (!useHls && hlsUrl != null) {
                    val last = engine.positionMs
                    if (last > 0L) resumeFrom = last
                    fellBackToHls = true
                    playError = null
                    controlsVisible = true
                } else {
                    playError = error
                    // 错误面板是模态层：把手势/面板状态一次清干净，返回键一次生效
                    panel = null
                    controlsVisible = false
                }
            }

            override fun onVideoSizeChanged(width: Int, height: Int) {
                // 画面比例由 resizeMode / 锁比例盒子处理，这里不需要额外动作
            }
        }
        engine.listener = listener
        onDispose {
            // 退出/换内核前补一次进度，外部据此写播放记录；引擎已出错时位置可能是 0，别覆盖断点
            val last = engine.positionMs
            val dur = engine.durationMs
            if (last > 0L) resumeFrom = last
            onProgress(resumeFrom, dur)
            // 只摘自己装的那一个 listener：退回详情页时小屏可能已经装上了它自己的，
            // 直接置 null 会把对方的回调抹掉（两者挂载/卸载顺序不保证）
            if (engine.listener === listener) engine.listener = null
            // 不 release：引擎归会话，退回详情页时小屏还要用它接着播。
            // 真释放发生在最后一次 detach 之后 800ms（彻底离开那条路）
            PlaybackSession.detach()
        }
    }

    // 起播已移交给 PlaybackSession.acquire（只有真的新建引擎才 start）：
    // 复用同一集时这里再 start 一次，就等于从头拉第二遍流。
    // 换集/换流时仍要复位"已播完"这个只属于本页的状态。
    LaunchedEffect(engine, playUrl) {
        ended = false
    }

    // 换内核 / 软硬解 / 切清晰度 / 回退 HLS / 重试都会重建引擎，新实例必须重新套上当前倍速
    LaunchedEffect(engine, settings.speed) {
        engine.setSpeed(settings.speed)
    }

    /**
     * 自动跳片头/片尾 + 进度上报（照搬 TV 端的判定循环）。
     *
     * 这个循环同时承担每秒一次的进度上报，所以任何分支都**不能 return**——
     * 一旦退出，整页就再也写不进断点了。
     */
    val liveMarks = if (marksReady) marks else IntroOutro()
    LaunchedEffect(engine, liveMarks.introMs, liveMarks.outroMs, skipIntro, skipOutro) {
        var sinceReport = 0L
        // 片头只自动跳一次：跳完再判会跟用户的回看拉扯
        var introDone = false
        // 片尾同理，只触发一次，避免 onEnded 之前本帧内反复调用
        var outroFired = false
        // 本集是否已经上报过完成；幂等的权威闸门在 VM 的 completedVids
        var completedReported = false
        while (true) {
            // 拖动预览期间不覆盖 positionMs：屏上要显示手指指到的地方
            if (!dragSeeking) {
                positionMs = engine.positionMs
            }
            durationMs = engine.durationMs

            if (durationMs > 0L && !dragSeeking) {
                // 片头先算，它还要给片尾当参照（片尾不能压在片头 MIN_GAP_MS 以内）
                val effectiveIntro = IntroOutroRules.effectiveIntro(liveMarks.introMs, durationMs)

                if (skipIntro && !introDone) {
                    introDone = true
                    if (IntroOutroRules.shouldSkipIntro(
                            hasPlayedBefore = hasPlayedBefore,
                            positionMs = positionMs,
                            effectiveIntroMs = effectiveIntro,
                        )
                    ) {
                        engine.seekTo(effectiveIntro)
                        positionMs = effectiveIntro
                    }
                }

                // 片尾：到点算这一集看完，交给外部连播下一集
                val outro = if (skipOutro) {
                    IntroOutroRules.effectiveOutroWithIntro(
                        recordedMs = liveMarks.outroMs,
                        durationMs = durationMs,
                        introMs = effectiveIntro,
                    )
                } else {
                    0L
                }
                if (outro > 0L && !outroFired &&
                    positionMs >= outro - IntroOutroRules.INTRO_SKIP_TOLERANCE_MS
                ) {
                    outroFired = true
                    // 完成结算的唯一入口是 onProgress：显式上报「位置 = 片长」，
                    // 让 VM 内部的 isCompleted 判定成立，95% 与跳片尾两条来源共用同一落点
                    if (!completedReported) {
                        completedReported = true
                        onProgress(durationMs, durationMs)
                    }
                    onEnded()
                }

                if (!completedReported && IntroOutroRules.isCompleted(positionMs, durationMs)) {
                    completedReported = true
                    onProgress(positionMs, durationMs)
                }
            }

            sinceReport += TickMs
            if (sinceReport >= ProgressReportMs) {
                sinceReport = 0L
                // 拖动中的位置是「预览值」，不能当成观看进度写进历史
                if (!dragSeeking) onProgress(positionMs, durationMs)
            }
            delay(TickMs)
        }
    }

    /**
     * 控制层 3 秒无操作自动隐藏（需求 3 的第一条）。
     *
     * 计时以 [controlsTick] 为 key：任何交互都会 +1，旧协程当场被取消并重开一轮，
     * 这就是「可取消的计时」——面板打开或锁定期间干脆不计时。
     */
    LaunchedEffect(controlsVisible, controlsTick, panel, castSheetVisible, locked, dragSeeking) {
        // dragSeeking 也参与：手指按在进度条上拖了 3 秒以上时不能把手底下的控制层收走
        // 面板（含投屏面板）打开期间干脆不计时：面板关掉时按钮还在原处，用户不用先点一下画面
        if (!controlsVisible || panel != null || castSheetVisible || locked || dragSeeking) return@LaunchedEffect
        delay(ControlsAutoHideMs)
        controlsVisible = false
    }

    // 双击/横滑的跳转提示：1 秒内没有新的跳转就收走
    LaunchedEffect(seekHintTick) {
        if (seekHintTick == 0) return@LaunchedEffect
        delay(SeekHintMs)
        seekAccumMs = 0L
        seekHintTick = 0
    }

    // 亮度/音量浮层：松手后 800ms 收走（拖动期间 levelTick 不变，所以它是「松手才计时」）
    LaunchedEffect(levelTick) {
        if (levelTick == 0) return@LaunchedEffect
        delay(LevelHintMs)
        levelKind = null
    }

    // 锁定时单击整屏：给一个可见的解锁出口，否则锁上就再也解不开
    LaunchedEffect(lockHintTick) {
        if (lockHintTick == 0) return@LaunchedEffect
        lockHintVisible = true
        delay(LockHintMs)
        lockHintVisible = false
    }

    // 截图回执：连点两次只重新计时，不会叠出两条
    LaunchedEffect(shotHintTick) {
        if (shotHintTick == 0) return@LaunchedEffect
        delay(ShotHintMs)
        shotHintRes = 0
    }

    BackHandler {
        when {
            // 分层退出：先关面板（投屏面板也在这一层），再关控制层，最后才离开播放页
            castSheetVisible -> castSheetVisible = false
            panel != null -> panel = null
            controlsVisible -> controlsVisible = false
            else -> onBack()
        }
    }

    // 画面比例：Fit/Fill/Zoom 交给 Exo 的 resizeMode；16:9 / 4:3 靠锁一个宽高比的盒子 + FILL 实现，
    // 盒内仍用 FILL —— 盒子决定画幅，FILL 让它铺满盒子，切档时不重建引擎
    val fixedRatio: Float? = when (settings.aspectMode) {
        AspectMode.Ratio16x9 -> 16f / 9f
        AspectMode.Ratio4x3 -> 4f / 3f
        else -> null
    }
    val resizeMode = when (settings.aspectMode) {
        AspectMode.Fit -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        AspectMode.Fill -> AspectRatioFrameLayout.RESIZE_MODE_FILL
        AspectMode.Zoom -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        AspectMode.Ratio16x9, AspectMode.Ratio4x3 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // 1) 画面
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            // AndroidView 的 factory 只在节点首次进入组合时跑一次，换内核就换了 View 类型，
            // 不换节点会留下上一个内核的 View；key(engine) 强制重建整棵渲染子树
            key(engine) {
                VideoSurface(
                    engine = engine,
                    core = effectiveCore,
                    resizeMode = resizeMode,
                    capture = captureSource,
                    modifier = if (fixedRatio != null) {
                        Modifier.fillMaxSize().aspectRatio(fixedRatio)
                    } else {
                        Modifier.fillMaxSize()
                    },
                )
            }
        }

        // 2) 弹幕：关闭时 enabled=false，Overlay 内部直接返回，绘制开销为零
        DanmakuOverlay(
            danmaku = danmaku,
            positionMs = positionMs,
            playing = playing,
            modifier = Modifier.fillMaxSize(),
            // 控制层可见时把底部让出去，弹幕不压进度条
            bottomInset = if (controlsVisible || panel != null) ControlsBottomInset else 0.dp,
            enabled = effectiveDanmakuEnabled,
            fontSizeSp = settings.danmakuSizeSp,
            topRatio = settings.topRatio,
            bottomRatio = settings.bottomRatio,
        )

        // 3) 手势层。故意排在控制层之前：Compose 的命中测试是「后画的先拿到」，
        //    所以底部按钮、进度条、面板永远优先吃掉触摸，手势只在空白画面上生效。
        Box(
            Modifier
                .fillMaxSize()
                // 单击显隐 / 双击左右快退快进。
                // detectTapGestures 一旦提供 onDoubleTap，单击就必须等双击超时才回调——
                // 这正好保证「双击不会同时触发一次单击」，两者不会互相打架。
                .pointerInput(locked, panel) {
                    detectTapGestures(
                        onTap = {
                            if (locked) {
                                lockHintTick++
                            } else if (controlsVisible) {
                                controlsVisible = false
                            } else {
                                touchControls()
                            }
                        },
                        onDoubleTap = { offset ->
                            if (!locked) {
                                // 左半屏快退、右半屏快进，步长 10 秒
                                seekBy(if (offset.x >= size.width / 2f) SeekStepMs else -SeekStepMs)
                            }
                        },
                    )
                }
                // 横滑 seek / 纵滑亮度音量。与上面的 tap 检测是两条独立的 pointerInput，
                // 拖动一旦超过触摸斜率就 consume 掉位移，tap 的「原地抬起」条件不成立，不会误判成单击。
                .pointerInput(locked) {
                    if (!locked) {
                        var axis = 0
                        var accumX = 0f
                        var accumY = 0f
                        var startMs = 0L
                        var targetMs = 0L
                        var startX = 0f
                        var pendingLevel = 0f
                        var pendingKind: LevelKind? = null
                        detectDragGestures(
                            onDragStart = { offset ->
                                axis = 0
                                accumX = 0f
                                accumY = 0f
                                startX = offset.x
                                startMs = positionNow
                                targetMs = positionNow
                                pendingKind = null
                                pendingLevel = 0f
                            },
                            onDrag = { change, delta ->
                                change.consume()
                                accumX += delta.x
                                accumY += delta.y
                                if (axis == 0) {
                                    val slop = DragSlopDp.dp.toPx()
                                    if (abs(accumX) > slop || abs(accumY) > slop) {
                                        axis = if (abs(accumX) >= abs(accumY)) 1 else 2
                                        if (axis == 2) {
                                            // 左半区调亮度、右半区调音量
                                            pendingKind = if (startX < size.width / 2f) {
                                                LevelKind.Brightness
                                            } else {
                                                LevelKind.Volume
                                            }
                                            pendingLevel = if (pendingKind == LevelKind.Brightness) {
                                                brightnessNow
                                            } else {
                                                volumeNow
                                            }
                                            accumY = 0f
                                        } else {
                                            // 拖动期间只做预览，不 seek：每帧 seek 会把播放器拖死
                                            dragSeeking = true
                                            touchControls()
                                        }
                                    }
                                }
                                when (axis) {
                                    // 横滑：全宽 = 整片时长，物理距离与片长成正比，长片也滑得动
                                    1 -> {
                                        val dur = durationNow
                                        if (dur > 0L) {
                                            val frac = (accumX / size.width).coerceIn(-1f, 1f)
                                            targetMs = (startMs + (frac * dur).toLong())
                                                .coerceIn(0L, dur)
                                            positionMs = targetMs
                                        }
                                    }
                                    // 纵滑：半屏高度 = 满量程，单手够得着也不会一抖到底
                                    2 -> {
                                        val value = (pendingLevel - accumY / (size.height * 0.6f))
                                            .coerceIn(0f, 1f)
                                        accumY = 0f
                                        pendingLevel = value
                                        val kind = pendingKind
                                        if (kind != null) {
                                            levelKind = kind
                                            levelValue = value
                                            if (kind == LevelKind.Brightness) {
                                                applyBrightness(value)
                                            } else {
                                                applyVolume(value)
                                            }
                                        }
                                    }
                                    else -> Unit
                                }
                            },
                            onDragEnd = {
                                if (axis == 1) {
                                    if (targetMs != startMs) {
                                        engine.seekTo(targetMs)
                                        positionMs = targetMs
                                        // 提示报的是实际位移：被片头片尾夹住时不报满额
                                        seekAccumMs = targetMs - startMs
                                        seekHintTick++
                                    }
                                    dragSeeking = false
                                }
                                if (axis == 2) levelTick++
                                axis = 0
                                pendingKind = null
                            },
                            onDragCancel = {
                                // 拖动被取消（来电、切后台）不能把预览位置留在屏上
                                if (axis == 1) {
                                    dragSeeking = false
                                    positionMs = engine.positionMs
                                }
                                if (axis == 2) levelTick++
                                axis = 0
                                pendingKind = null
                            },
                        )
                    }
                },
        )

        // 4) 缓冲指示：只在「还没有出画面」时显示（起播阶段由 PlayerUi.Loading 覆盖）
        AnimatedVisibility(
            visible = playError == null && bufferingShown && durationMs > 0L,
            enter = fadeIn(tween(160)),
            exit = fadeOut(tween(220)),
        ) {
            BufferingIndicator(modifier = Modifier.fillMaxSize())
        }

        // 5) 双击/横滑的跳转反馈
        if (seekHintTick > 0) {
            SeekHint(accumMs = seekAccumMs, modifier = Modifier.fillMaxSize())
        }

        // 6) 亮度/音量浮层
        levelKind?.let { kind ->
            LevelIndicator(kind = kind, value = levelValue, modifier = Modifier.fillMaxSize())
        }

        if (fellBackToHls) {
            Text(
                stringResource(R.string.player_hls_fallback),
                style = ModaType.Caption,
                color = ModaColors.TextDim,
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
            )
        }

        // 截图回执：与播放页其余提示同一形态（半透明底 + 一行字），2.5 秒后自己收走。
        // 故意不挂在控制层下面：点完截图控制层可能刚好自动收起，回执不能跟着一起消失。
        if (shotHintRes != 0) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 64.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xCC000000))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(
                    text = stringResource(shotHintRes),
                    style = ModaType.Caption,
                    color = ModaColors.Text,
                )
            }
        }

        // 7) 控制层：顶栏（返回 / 标题+集号 / 投屏 / 锁定）+ 底栏两行
        //    （播放暂停 / 时间 / 进度 / 弹幕开关 一行为一行，倍速 / 码率 / 选集 / 更多 右下角簇一行）。
        //    两段共用同一个 controlsVisible，同进同退；都排在手势层之后，所以按钮与进度条优先拿到触摸。
        //    底栏四角没有孤悬图标：返回、投屏、锁定都在顶栏。
        if (controlsVisible && playError == null) {
            PlayerTopBar(
                title = title,
                episodeLabel = episodeLabel,
                locked = locked,
                casting = casting != null,
                castDeviceName = casting?.friendlyName,
                onCastClick = {
                    touchControls()
                    castFailure = false
                    castSheetVisible = true
                },
                onBack = onBack,
                onToggleLock = {
                    val next = !locked
                    locked = next
                    // 锁上就立刻收起控制层：锁定的意义就是「别再让我误触」
                    if (next) controlsVisible = false else touchControls()
                },
                modifier = Modifier.align(Alignment.TopCenter),
            )
            PlayerBottomBar(
                playing = playing,
                positionMs = positionMs,
                durationMs = durationMs,
                speedLabel = if (settings.speed != 1f) settings.speedLabel else null,
                onTogglePlay = { togglePlay() },
                // 下一集：只把点击交回既有通路（onSelectEpisode），末集 nextEpisode 为 null → 置灰
                onNextEpisode = { nextEpisode?.let { episode -> onSelectEpisode(episode) } },
                nextEpisodeEnabled = nextEpisode != null,
                // 拖动中只更新预览，松手才真 seek（见 PlayerBottomBar 的注释）
                onSeekPreview = { ms ->
                    dragSeeking = true
                    positionMs = ms
                },
                onSeekCommit = { ms ->
                    dragSeeking = false
                    engine.seekTo(ms)
                    positionMs = ms
                },
                onOpenPanel = { next ->
                    touchControls()
                    panel = next
                },
                danmakuEnabled = effectiveDanmakuEnabled,
                onToggleDanmaku = { toggleDanmaku() },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
            // 画面右侧竖排（优酷同一形态）：截图贴右边缘、垂直居中偏上，与控制层同进同退。
            // 现有锁定入口在顶栏最右，竖排按钮压不到它「上面」，所以落位按可量化的那条
            // ——右侧竖排 + 居中偏上（padding 把整颗往上抬半个偏移量的两倍）。
            // 以后再往这一列加按钮就排在截图上/下，仍然只占一列，不往底栏四角挂图标。
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 12.dp, bottom = 96.dp),
            ) {
                PlayerSideAction(
                    imageVector = Icons.Filled.PhotoCamera,
                    contentDescription = stringResource(R.string.m_player_screenshot),
                    // 29 以下写相册需要运行时权限，播放页不申请：置灰，点下去给原因
                    enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
                    onClick = { takeScreenshot() },
                )
            }
        }

        // 8) 投屏中的状态提示：控制层收起、且没锁屏时仍然留在右上角。
        //    「正在投屏」是持续状态，不该跟着 OSD 一起消失；锁屏时整屏只留解锁入口，连它也不画。
        if (!controlsVisible && !locked) {
            casting?.let { device ->
                CastStatusChip(
                    deviceName = device.friendlyName,
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 12.dp, end = 12.dp),
                )
            }
        }

        // 9) 锁定时唯一可见的出口
        if (locked && lockHintVisible) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .clip(CircleShape)
                        .background(Color(0xB3000000))
                        .clickable {
                            locked = false
                            lockHintVisible = false
                            touchControls()
                        }
                        .padding(20.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.LockOpen,
                        contentDescription = null,
                        tint = Color.White,
                    )
                }
            }
        }

        // 10) 播放失败（错误码 → 文案的唯一翻译点）
        playError?.let { error ->
            PlayerErrorPane(
                message = playerErrorText(error),
                modifier = Modifier.fillMaxSize(),
                onRetry = {
                    playError = null
                    // 出错的那台引擎状态不可靠，先让会话立刻丢掉它：
                    // 否则下一次 acquire 因为地址没变会把它原样交回来，重试等于没重试
                    PlaybackSession.discard()
                    attempt++
                },
            )
        }

        // 11) 底部面板：一次只开一个，ModalBottomSheet 的遮罩天然挡住手势与控制层
        when (panel) {
            PlayerPanel.Episode -> EpisodeSheet(
                episodes = episodes,
                currentEpisodeIndex = currentEpisodeIndex,
                onSelect = { episode ->
                    panel = null
                    onSelectEpisode(episode)
                },
                onClose = { panel = null },
            )

            PlayerPanel.Quality -> QualitySheet(
                tiers = tiers,
                selected = selectedTier,
                onPick = { tier ->
                    panel = null
                    onTierChange(tier)
                },
                onClose = { panel = null },
            )

            PlayerPanel.Speed -> SpeedSheet(
                speed = settings.speed,
                onPick = { value -> updateSettings(settings.copy(speed = value)) },
                onClose = { panel = null },
            )

            PlayerPanel.Danmaku -> DanmakuSheet(
                settings = settings.copy(danmakuEnabled = effectiveDanmakuEnabled),
                // 总开关只有一个落点（toggleDanmaku）：进度条行尾的快捷开关与这里改的是同一件事
                onToggleEnabled = { toggleDanmaku() },
                onSettingsChange = { next -> updateSettings(next) },
                onClose = { panel = null },
            )

            PlayerPanel.Aspect -> AspectSheet(
                mode = settings.aspectMode,
                onPick = { mode -> updateSettings(settings.copy(aspectMode = mode)) },
                onClose = { panel = null },
            )

            PlayerPanel.Core -> CoreSheet(
                effectiveCore = effectiveCore,
                ijkUsable = ijkUsable,
                onPick = { core ->
                    applyOverride(playbackOverride.copy(core = core.takeIf { it != settings.playerCore }))
                },
                onClose = { panel = null },
            )

            PlayerPanel.Decoder -> DecoderSheet(
                mode = wantedDecoder,
                onPick = { mode ->
                    applyOverride(playbackOverride.copy(decoder = mode.takeIf { it != settings.decoderMode }))
                },
                onClose = { panel = null },
            )

            PlayerPanel.Marks -> MarksSheet(
                introMs = marks.introMs,
                outroMs = marks.outroMs,
                skipIntro = skipIntro,
                skipOutro = skipOutro,
                onSkipIntroChange = { skipIntro = it },
                onSkipOutroChange = { skipOutro = it },
                onRecordIntro = { recordMark(true) },
                onRecordOutro = { recordMark(false) },
                onClose = { panel = null },
            )

            // 「更多」自己不控制任何东西，只是把另外五张面板叫出来：选中即换面板
            PlayerPanel.More -> MoreSheet(
                onPick = { next -> panel = next },
                onClose = { panel = null },
            )

            null -> Unit
        }

        // 12) 投屏面板：顶栏入口，不在 PlayerPanel 的八个功能里，所以独立于上面那个 when。
        //     发现/重搜/空态都在 CastSheet 内部，这里只负责「投什么地址」和「失败怎么说」。
        if (castSheetVisible) {
            CastSheet(
                controller = CastController,
                current = casting,
                onPick = { device -> startCast(device) },
                onStop = { stopCast() },
                onDismiss = { castSheetVisible = false },
                failureText = if (castFailure) stringResource(R.string.m_player_cast_failed) else null,
            )
        }
    }
}
