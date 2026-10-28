package com.moda.mobile.ui.player

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 全应用唯一的播放引擎持有者：详情页小屏与全屏播放页共用同一个实例。
 *
 * 为什么需要它：两页各自 `remember { PlayerEngines.create(...) }` 时，
 * 「详情页小屏 → 点全屏」必然走一遍「详情页离开组合 → release → 播放页新建 → start 重新拉流」，
 * 用户看到的就是又转一圈缓冲。引擎必须活得比任何一个 Composable 都久。
 *
 * 身份 = 地址里 scheme/host 之后的那段路径（见 [keyOf]）。为什么不直接用 vid：详情页那一侧
 * 拿不到 vid（`DetailPreview` 只带地址），而两侧对同一集的「档位 → 直链」是同一条链路
 * （Repo.episodeInfo → pickBestTier → playLink），路径因此完全一致；
 * query 里的 vkey 每次请求现签、host 由上游挑 CDN，这两段剥掉才能对上。
 * 同一集换档位/清晰度路径不同，仍然会重建——那本来就该重建
 * （PlayerEngine 的 start 是「起播」，不是「换源」）。
 */
object PlaybackSession {

    private const val TAG = "PlaybackSession"

    /**
     * detach 归零后等这么久才真的 release。
     *
     * 路由切换时两个 Composable 的挂载/卸载顺序不保证：「详情页卸载」可能先于
     * 「播放页挂载」，中间是一段没有任何人 attach 的空窗。若归零即 release，
     * 播放页拿到的是被释放的引擎（或者更糟：它自己又新建一个，等于从头缓冲）。
     * 800ms 远大于一次切换的耗时，又短到退回首页时听不出明显残响。
     */
    private const val ReleaseGraceMs = 800L

    /** release 必须回主线程；immediate 让「已经在主线程」的调用同步执行，不迟一帧 */
    private val scope = CoroutineScope(Dispatchers.Main.immediate)

    /** 正在等宽限期结束的释放任务；acquire / attach 一来就取消 */
    private var pendingRelease: Job? = null

    /** 当前唯一的条目：同一时刻只允许一个引擎 */
    private var current: Entry? = null

    /**
     * 有多少个 Composable 正在显示这个引擎。
     *
     * 刻意记在会话上而不是每个引擎一份：换引擎（重试 / 换内核 / 切清晰度）时旧引擎会被
     * 立刻释放，而旧那一页的 DisposableEffect 之后才 detach——计数若跟着引擎走，
     * 这次 detach 会落在新引擎头上，把正在播的引擎判成「没人看」。
     */
    private var attached = 0

    private class Entry(
        val engine: PlayerEngine,
        val key: String,
        /** 真 vid；小屏那一侧拿不到，先记空串，等播放页复用同一台引擎时补上 */
        var vid: String,
        val core: String,
        val hardware: Boolean,
    )

    val engine: PlayerEngine? get() = current?.engine

    fun positionMs(): Long = current?.engine?.positionMs ?: 0L

    /**
     * 取引擎。
     *
     * 同一条流（同 vid + 同档位 + 同内核/软硬解）直接把现有实例交回去——**不 start、不 seek**，
     * 位置一直在走，这就是「点全屏不再转圈」的落点；只有真的新建引擎时才用 [startMs] 起播。
     *
     * [core] / [hardware] 只能构造期决定（Exo 的 MediaCodecSelector、IJK 的 mediacodec 选项），
     * 所以它们也是复用条件的一部分：判等不上就重建，位置仍按「同一 vid 接着播」处理。
     */
    fun acquire(
        context: Context,
        vid: String,
        url: String,
        hls: Boolean,
        startMs: Long,
        speed: Float,
        core: String = "exo",
        hardware: Boolean = true,
    ): PlayerEngine {
        val key = keyOf(url)
        val existing = current
        if (existing != null && existing.key == key && existing.core == core && existing.hardware == hardware) {
            cancelPendingRelease()
            // 小屏建的那台引擎没有 vid：播放页复用它的同时把真 vid 补上，
            // 之后（换档位/换内核）才认得出"还是这一集"，从而接着当前进度而不是回到片头
            if (existing.vid.isBlank()) existing.vid = vid
            Log.i(TAG, "复用引擎 vid=" + vid + " attached=" + attached + " pos=" + existing.engine.positionMs)
            return existing.engine
        }
        cancelPendingRelease()
        // 换流前先读位置：release 之后 positionMs 归零。
        // 同一 vid 换地址（切清晰度 / 换内核 / 回退 HLS / 出错重试）要从用户现在看的地方接着播，
        // 不能用调用方在进页时算好的 startMs，否则一次换档就回到片头。
        val resumeAt = if (existing != null && vid.isNotBlank() && existing.vid == vid) {
            existing.engine.positionMs.takeIf { it > 0L } ?: startMs
        } else {
            startMs
        }
        existing?.let {
            // 换内容：旧引擎必须先停。两个引擎同时出声是这条路上最容易被当成 bug 的失败形态
            Log.i(TAG, "换引擎 → " + key + "（core=" + core + " hw=" + hardware + " resumeAt=" + resumeAt + "）")
            releaseEngine(it.engine)
            current = null
        }
        val fresh = PlayerEngines.create(context, core, hardware)
        current = Entry(engine = fresh, key = key, vid = vid, core = core, hardware = hardware)
        // start 只出现在这里：复用路径永远走不到，所以同一集只会拉一次流。
        // 这条日志是「一次 start」的验收证据：详情 → 全屏那条路上它只应出现一次，
        // 后面跟着的都是"复用引擎"
        Log.i(TAG, "新建并起播 key=" + key + " startMs=" + resumeAt)
        fresh.start(url, hls = hls, startMs = resumeAt, speed = speed)
        return fresh
    }

    /** 有新的 Composable 开始显示这个引擎：取消宽限期释放 */
    fun attach() {
        cancelPendingRelease()
        attached++
    }

    /** 显示它的 Composable 走了：归零后先等宽限期，不立刻 release */
    fun detach() {
        if (attached > 0) attached--
        if (attached == 0) scheduleRelease()
    }

    /**
     * 立刻丢弃当前引擎（出错后「重试」用）。
     * 坏掉的实例状态不可靠，不能被下一次 acquire 复用；下一帧 acquire 会真的新建。
     */
    fun discard() {
        cancelPendingRelease()
        val entry = current ?: return
        current = null
        releaseEngine(entry.engine)
    }

    /**
     * 某个画面解绑自己的渲染 View 之前先问一句。
     *
     * 切换时新旧两个画面会短暂共存，且卸载顺序不保证：旧画面若在这时把
     * `player = null` 摘掉，新画面已经把同一个 ExoPlayer 绑上去了，画面就会黑一帧到一秒
     * （看起来正是「又要缓冲了」）。引擎还归会话所有就不摘。
     */
    fun isCurrent(candidate: PlayerEngine?): Boolean =
        candidate != null && current?.engine === candidate

    private fun scheduleRelease() {
        val entry = current ?: return
        cancelPendingRelease()
        pendingRelease = scope.launch {
            delay(ReleaseGraceMs)
            pendingRelease = null
            // 宽限期内可能已经 attach 过又 detach 过，也可能换了引擎：两条都要重新确认
            if (attached > 0 || current !== entry) return@launch
            Log.i(TAG, "宽限期到期，释放引擎 vid=" + entry.vid)
            current = null
            releaseEngine(entry.engine)
        }
    }

    private fun cancelPendingRelease() {
        pendingRelease?.cancel()
        pendingRelease = null
    }

    private fun releaseEngine(engine: PlayerEngine) {
        // 先摘 listener 再 release：release 期间可能同步回调，那时对应的组合可能已经在销毁
        engine.listener = null
        engine.release()
    }

    /**
     * 流的身份：scheme 与 host 之后的路径，去掉 query / fragment。
     *
     * 两层都要剥：
     *  · query 里的 vkey 是每次请求现签的，同一集两次拿到的地址 query 必然不同；
     *  · host 由上游按请求挑 CDN，同一个文件可能落在不同域名上。
     * 剩下的是 `<guid>.f<档位>.mp4` 这一段——由 vid + 档位唯一决定，两侧完全一致。
     *
     * 只留文件名会更短，但 HLS 的 `index.m3u8` 这类通用名会在两集之间撞车；
     * 撞车的后果是「拿上一集的引擎去播本集的地址」，比不复用糟糕得多，所以保留整条路径。
     */
    private fun keyOf(url: String): String {
        val noQuery = url.substringBefore('?').substringBefore('#').trim()
        val withoutScheme = noQuery.substringAfter("://", noQuery)
        val path = withoutScheme.substringAfter('/', "")
        return path.ifBlank { withoutScheme.ifBlank { noQuery } }
    }
}
