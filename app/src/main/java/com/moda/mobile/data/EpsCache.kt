package com.moda.mobile.data

import android.util.Log
import com.moda.mobile.BuildConfig
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * eps 结果的进程内复用 + 同一 cid 的单飞（in-flight 合并）。
 *
 * 为什么必须有这一层：首页一次加载 = 轮播 15 条 + 17 个频道页各 11~17 条补全，
 * 而同一个 cid 会同时出现在轮播、精选页和它自己的频道页上。实测日志里
 * 敦煌英雄 mzc00200bektv93 被打了 4 次、仙逆 mzc00200aaogpgh 3 次，
 * 180 次 eps 里 30~40 次是同一个 cid 的重复请求。详情页的 Repo.series 与
 * Repo.hiResCover 也会各自为同一个 cid 再打一次。
 *
 * 为什么放数据层而不是 data/local：这是「上游同一份数据的短期复用」，不是用户数据，
 * 进程结束即丢，没有落盘的意义，也不该占一个 DataStore 文件。
 *
 * [why] 只进 debug 日志：之前日志里只有 `a=eps {"cid":"mzc00200..."}`，
 * 看不出这条请求是轮播要的、哪个频道要的，还是详情页要的。
 */
internal object EpsCache {

    private const val TAG = "EpsCache"

    /**
     * 与整页频道缓存同为 10 分钟：一轮首页加载内必然命中，用户手动刷新时
     * [Repo.invalidateHomeCache] 会连带清空它——「刷新」的语义必须包含这份复用。
     */
    private const val TTL_MS = 10 * 60_000L

    /** 安全带：长会话里不让这个 map 只增不减。满了整体清掉，比引入一份 LRU 便宜得多 */
    private const val MAX_ENTRIES = 1_000

    private class Entry(val eps: JSONObject, val at: Long)

    private val entries = ConcurrentHashMap<String, Entry>()

    /**
     * 每个 cid 一把锁：并发的两个分支（精选页 / 电影频道）只让一条真打网络，
     * 另一条等它的结果。锁本身不清（只有几百个 cid，清了反而会在同一 cid 上
     * 造出两把锁、把单飞破掉）。
     */
    private val locks = ConcurrentHashMap<String, Mutex>()

    /**
     * 取一个 cid 的 eps 原始 JSON。
     *
     * 失败**不缓存**：详情页要靠这个异常决定「回退到单集模式」，
     * 一次抖动被负缓存住会让详情页在 10 分钟内反复失败。
     */
    suspend fun eps(cid: String, why: String = ""): JSONObject {
        fresh(cid)?.let {
            if (BuildConfig.DEBUG) Log.d(TAG, "复用 eps $cid（${why.ifBlank { "未标注" }}）")
            return it
        }
        val lock = locks.computeIfAbsent(cid) { Mutex() }
        return lock.withLock {
            // 等锁期间别的分支可能刚拉完同一个 cid：这里必须再查一次，
            // 否则单飞只在第一个等待者身上生效，第三个分支照样再打一遍
            fresh(cid)?.let {
                if (BuildConfig.DEBUG) Log.d(TAG, "复用 eps $cid（${why.ifBlank { "未标注" }}）")
                return@withLock it
            }
            if (BuildConfig.DEBUG) Log.d(TAG, "实发 eps $cid ← ${why.ifBlank { "未标注" }}")
            Api.eps(cid).also { put(cid, it) }
        }
    }

    /** 与整页缓存一起作废：手动刷新 / 重新授权之后必须重新问一遍上游 */
    fun clear() = entries.clear()

    private fun fresh(cid: String): JSONObject? {
        val e = entries[cid] ?: return null
        if (System.currentTimeMillis() - e.at > TTL_MS) {
            entries.remove(cid)
            return null
        }
        return e.eps
    }

    private fun put(cid: String, json: JSONObject) {
        if (entries.size >= MAX_ENTRIES) entries.clear()
        entries[cid] = Entry(json, System.currentTimeMillis())
    }
}
