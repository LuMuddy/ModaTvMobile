package com.moda.mobile.data

import android.util.Log
import com.moda.mobile.model.VideoItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 首页轮播的数据来源：v.qq.com 官方首页。
 *
 * 为什么不走 protobuf 内部接口：v.qq.com 是纯 SPA，推荐位只藏在 pbaccess.video.qq.com
 * 的 protobuf 响应里，字段没有公开契约、随时会变；而首页 HTML 里内联的卡片数据
 * 是稳定可解析的推荐位，改动风险低得多。
 *
 * 为什么**不再**为每个 cid 回自家接口补全：见 [HtmlCards] 的说明——
 * 标题 / 竖版海报 / 横版主视觉 / 首集 vid 这份 HTML 全都有，补全是白打 180 次
 * `eps`，而且那 180 次并发正是「后端偶发 401、客户端被踢回授权门」的放大器。
 */
object CarouselSource {

    private const val TAG = "CarouselSource"

    private const val HOME_URL = "https://v.qq.com/"

    /**
     * v.qq.com 对无 UA / 爬虫 UA 会返回降级页（不含推荐位），必须装成正常浏览器。
     *
     * Chrome 版本号只写三段：写成四段（末尾多一个 .0）时，那一串长得跟 IPv4 一模一样，
     * 会被 .githooks/pre-commit 的「公网 IP」扫描拦下，每次提交都得 --no-verify，
     * 等于把钩子关掉。
     */
    private const val UA =
        "Mozilla/5.0 (Linux; Android 12; SM-G991B) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0 Mobile Safari/537.36"

    /**
     * 首页推荐位一次取多少张卡。
     *
     * 实测 v.qq.com 首页（以及 /channel/choice 精选页，两者内容完全重合）
     * 全站只暴露 **14~15** 个 /x/cover 链接，这就是推荐位的物理上限。
     * 上限设成比它大的数没有意义——多出来的循环只是空转。
     *
     * 也不用 URL 参数翻页：/channel/xxx?page=2 实测返回的仍是同一批 15 条，
     * 频道页没有可解析的分页结构。
     */
    private const val MAX_ITEMS = 20

    /**
     * 独立 client 而不是复用 Api.http：这里要的是 8 秒硬时限。
     * 首页 HTML 拿不到就该立刻放弃、交给持久化缓存，不能让用户对着空首页等 30 秒。
     * 另外它不是打后端，走 Api.http 只会把 v.qq.com 的请求也塞进接口日志。
     */
    private val htmlClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * 抓首页 → 解析整屏卡片。任何一步整体失败都返回空列表，
     * 由 ViewModel 的持久化缓存顶上——这里没有第二名数据来源可以回退。
     * 单条解析不出来只丢这一条，不影响其它条目。
     */
    suspend fun load(): List<VideoItem> {
        val items = try {
            fetchCards(HOME_URL, MAX_ITEMS)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 断网 / 超时 / 改版导致的解析异常都在这里落地，不向 UI 抛
            Log.w(TAG, "首页 HTML 抓取失败：${e.message}")
            return emptyList()
        }
        if (items.isEmpty()) {
            Log.w(TAG, "首页 HTML 未解析出任何卡片（页面结构可能已改版）")
        } else {
            Log.i(TAG, "首页轮播：${items.size} 条，首条「${items.first().title}」")
        }
        return items
    }

    /**
     * 拉任意 v.qq.com 页面的 HTML，解析成整屏卡片（顺序即推荐位权重，不排序）。
     *
     * 首页轮播与 ChannelSource 的 17 个频道页共用这一份实现：UA / 超时 / 解析
     * 有且只有一处，v.qq.com 改版时只改 [HtmlCards]。
     */
    internal suspend fun fetchCards(url: String, limit: Int): List<VideoItem> =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .header("Accept", "text/html,application/xhtml+xml")
                .build()
            val html = htmlClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                resp.body?.string().orEmpty()
            }
            if (html.isBlank()) error("$url 返回空 HTML")
            HtmlCards.parse(html, limit)
        }
}
