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
 * 引用归零时「先静音、后释放」：没有画面在显示它 = 立刻 pause（离开就不该还有声），
 * 引擎再留 [ReleaseGraceMs] 等下一页来挂载（横竖屏切换会重建 Activity，这段过渡远长于一帧）；
 * 宽限期内挂载回来就接着播，超时才真 release。于是"小屏 → 全屏"既不重建、也不留声。
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
     *
     * **真正的释放时机由路由决定**（见 [releaseIfDetached]），这里只是兜底保险丝：
     * 万一将来某条路径 detach 之后再没人 acquire，别让引擎永远留在内存里。
     *
     * 为什么时限救不了这件事：attached 归零到下一页 acquire 之间隔着**一整个网络往返**——
     * 播放页的引擎是在 PlayerUi.Ready 分支里取的，而 Ready 要等 VM 跑完
     * episodeInfo → pickBestTier → playLink。实测这段能超过 2.5s（日志里
     * 「宽限期到期，释放引擎」之后 217ms 才「新建并起播」），所以任何固定时限都可能输给一个慢请求。
     *
     * 放这么大也不换来一声多余的响：归零那一刻已经**先 pause 了**（见 [scheduleRelease]），
     * 宽限期里只是一台静音的引擎躺在内存里。
     */
    private const val ReleaseGraceMs = 10_000L

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

    /** 归零时是否由我们按了暂停（决定挂载回来要不要恢复播放） */
    private var suspendedByGrace = false

    /** 按暂停之前它是否在播：用户自己暂停、投屏中暂停的引擎，挂载回来不许自己动起来 */
    private var wasPlaying = false

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
            // 宽限期内它被我们静音了：复用它的这一页就是要它接着播
            resumeIfSuspendedByGrace()
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
        // 新引擎跟上一台的暂停/播放状态无关
        suspendedByGrace = false
        wasPlaying = false
        // start 只出现在这里：复用路径永远走不到，所以同一集只会拉一次流。
        // 这条日志是「一次 start」的验收证据：详情 → 全屏那条路上它只应出现一次，
        // 后面跟着的都是"复用引擎"
        Log.i(TAG, "新建并起播 key=" + key + " startMs=" + resumeAt)
        fresh.start(url, hls = hls, startMs = resumeAt, speed = speed)
        return fresh
    }

    /** 有新的 Composable 开始显示这个引擎：取消宽限期释放，并把它从"宽限期静音"里唤醒 */
    fun attach() {
        cancelPendingRelease()
        attached++
        resumeIfSuspendedByGrace()
    }

    /**
     * 显示它的 Composable 走了。
     * 归零时**先静音**（[scheduleRelease] 里同步 pause），再等宽限期才 release：
     * 声音不等宽限期，引擎才等。
     */
    fun detach() {
        if (attached > 0) attached--
        if (attached == 0) scheduleRelease()
    }

    /**
     * 路由已经离开「观看」这件事时调用：没有任何画面在显示它，就立刻释放。
     *
     * 这是引擎**真正的**释放时机。它问的是「现在在哪个页面」，不是一个时限，
     * 因此不会输给播放页那次网络往返：详情 ↔ 播放是同一次观看的两端，
     * 两页都在期间谁 detach 都不释放；到了别的页面才释放。
     *
     * attached > 0 时什么都不做——那时还有画面在用它（这一帧刚被移除、onDispose 还没跑到时
     * 也走这条，交给 [ReleaseGraceMs] 的兜底）。
     */
    fun releaseIfDetached() {
        if (attached > 0) return
        cancelPendingRelease()
        val entry = current ?: return
        Log.i(TAG, "已离开观看页，立即释放引擎 vid=" + entry.vid)
        current = null
        suspendedByGrace = false
        wasPlaying = false
        releaseEngine(entry.engine)
    }

    /**
     * 立刻丢弃当前引擎（出错后「重试」用）。
     * 坏掉的实例状态不可靠，不能被下一次 acquire 复用；下一帧 acquire 会真的新建。
     */
    fun discard() {
        cancelPendingRelease()
        suspendedByGrace = false
        wasPlaying = false
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
        // 归零 = 屏幕上没有任何画面在显示它。声音必须**当场**停（"离开就不该还有声"），
        // 但引擎先留着：横竖屏切换/路由切换这段空窗足够长，重建一次就是重新拉一次流。
        // isPlaying 一定要在 pause 之前读——pause 之后它立刻就是 false，读到的就再也不是
        // "离开时它到底在不在播"这件事了（那正是挂载回来该不该续播的唯一依据）。
        wasPlaying = entry.engine.isPlaying
        suspendedByGrace = true
        entry.engine.pause()
        cancelPendingRelease()
        pendingRelease = scope.launch {
            delay(ReleaseGraceMs)
            pendingRelease = null
            // 宽限期内可能已经 attach 过又 detach 过，也可能换了引擎：两条都要重新确认
            if (attached > 0 || current !== entry) return@launch
            Log.i(TAG, "宽限期到期，释放引擎 vid=" + entry.vid)
            current = null
            suspendedByGrace = false
            wasPlaying = false
            releaseEngine(entry.engine)
        }
    }

    /**
     * 宽限期内被静音的引擎，一旦又有画面挂载就接着播。
     *
     * 只有「暂停是我们按的」且「按之前它真的在播」两条同时成立才恢复：
     * 用户自己按过暂停、或者正在投屏（本地本来就是暂停）的引擎，挂载回来不许自己动起来。
     */
    private fun resumeIfSuspendedByGrace() {
        val entry = current ?: return
        if (!suspendedByGrace) return
        val shouldResume = wasPlaying
        suspendedByGrace = false
        wasPlaying = false
        if (shouldResume) {
            Log.i(TAG, "宽限期内挂载，恢复播放 vid=" + entry.vid)
            entry.engine.play()
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
