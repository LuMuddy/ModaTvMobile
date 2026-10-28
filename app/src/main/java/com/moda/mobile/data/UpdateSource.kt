package com.moda.mobile.data

import android.util.Log
import com.moda.mobile.model.AppError
import com.moda.mobile.model.UpdateInfo
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject

/**
 * 版本检查的取数层：只读**手机端专属**的 version-mobile.json。
 *
 * 为什么不再读 `version.json` / `releases.atom`：这两份都是 **TV 端**的发布票据。
 * 手机端和 TV 端的版本号本来就不该同步，共用一份票据的直接后果是真机复现过的那次故障——
 * v1.0.0 的手机弹出了 TV 的 v1.0.2 更新说明（「登录改为微信扫码…电视上出二维码」），
 * 点「立即更新」再去下 `ModaMobile1.0.2.apk`，而那个资产根本不存在。
 * 版本信息的来源必须和「这是谁的新版本」对齐，否则文案、版本号、APK 三者各说各话。
 *
 * **atom feed 兜底已删除，且不得恢复。** 它读的是 TV 仓库的 releases.atom，
 * 内容与手机端无关：拿它当「新版」的唯一结果就是把 TV 的版本号冒充成手机端的新版。
 * 手机端不参与这条路径——宁可「暂时收不到更新」，也不能推一个装不上的包。
 *
 * 为什么 APK 地址靠版本号拼、不查 API：发布时 asset 固定命名成 `ModaMobile<version>.apk`，
 * 于是「版本号 → 直链」是一条确定的函数关系，少一次网络往返。
 */
object UpdateSource {

    private const val TAG = "UpdateSource"

    private const val OWNER = "LuMuddy"
    private const val REPO = "ModaTv"

    /**
     * 手机端专属的版本票据：版本名 + versionCode + 更新日志。
     *
     * 文件名带 `-mobile` 后缀是**契约的一部分**，不是命名偏好：同一版 Release 上同时挂着
     * TV 端的 `version.json`，两者只差一个后缀，拼错一个字母就退回到「手机读 TV 版本号」
     * 那次的故障，而且现场看起来一切正常（版本号有值、弹窗能弹），极难复现定位。
     *
     * `/releases/latest/download/` 恒指向最新一版，不用先解析 feed 再拼版本号，
     * 少一处拼接就少一处错。
     */
    private const val VERSION_JSON_URL =
        "https://github.com/$OWNER/$REPO/releases/latest/download/version-mobile.json"

    /**
     * 下载镜像：只代理 release 的 asset 路径。
     * 早先的 ghfast.top 已经停服（TLS 直接断开，实测连不上），换 gh-proxy.com（实测 Range 206）。
     * 镜像随时可能没，所以它只是**候选之一**，直连永远排在后面兜底（见 [apkUrls]）。
     */
    private const val MIRROR_PREFIX = "https://gh-proxy.com/"

    /**
     * 更新日志的上屏上限。
     * generate_release_notes 生成的是整轮提交列表，长版本动辄数万字，
     * 一次塞进 Compose Text 会让整屏测量的代价随正文长度线性增长，而没人会在手机上读完。
     */
    private const val NOTES_MAX = 6000

    /**
     * 「没有可用更新」的唯一表示：空版本名 + 无 versionCode。
     *
     * 为什么是一种返回值而不是一次抛错：资产缺失（404 / JSON 坏 / files 为空）不是故障，
     * 是「手机端还没发布过版本」这件事的正常表达。抛错会让检查这条链路进入 catch 分支，
     * 错误语义被下游误读成「检查失败」；而这里出去的 UpdateInfo 会走
     * [com.moda.mobile.data.local.VersionRules.isNewer] 的正常判定——空版本名解析成 0，
     * 永远不比本机版本新，于是 UI 落到「已是最新」。用户看到的必须是这一句。
     */
    private val NO_UPDATE = UpdateInfo(versionName = "", notes = "", apkUrl = "", versionCode = null)

    /**
     * 最新一版的版本号 / 更新日志 / APK 直链；没有可用资产时返回 [NO_UPDATE]。
     *
     * 失败语义只有两种，且必须分清：
     * - **网络异常**（断网、超时、连接被重置）→ 抛 [ApiException]（[AppError.Network]），
     *   原样上抛。把断网伪装成「已是最新」是在对用户撒谎，也让真正的网络问题失去一切线索。
     * - **资产缺失**（HTTP 非 2xx / 响应体为空 / JSON 解析失败 / files 为空 / 版本名不可用）
     *   → 返回 [NO_UPDATE]，**不抛**。这正是「手机端还没挂 version-mobile.json」时的那条路径。
     *
     * 绝不抛裸异常：数据层不承担文案，裸 IOException 落到 ViewModel 只能变成「未知错误」。
     */
    suspend fun latest(): UpdateInfo = withContext(Dispatchers.IO) {
        fetchVersionJson() ?: NO_UPDATE
    }

    /**
     * 取 version-mobile.json 并解析成 [UpdateInfo]；任何「资产不存在 / 不可信」都归 null。
     *
     * 复用 [Api.http] 而不是自建客户端：连接池、DNS 缓存、超时口径只有一处真值。
     * 再 new 一个 OkHttpClient 会多一套线程池与连接池，且超时值改一处漏一处。
     */
    private fun fetchVersionJson(): UpdateInfo? {
        val req = Request.Builder().url(VERSION_JSON_URL).get().build()
        val raw = try {
            Api.http.newCall(req).execute().use { resp ->
                // 非 2xx 归「资产缺失」而不是「网络故障」：404 是 GitHub 明确答复
                // 「这个 Release 上没有这个文件」，不是链路没通。错误码只进日志。
                if (!resp.isSuccessful) {
                    Log.i(TAG, "version-mobile.json 取不到：HTTP ${resp.code}，按「没有可用更新」处理")
                    return null
                }
                resp.body?.string().orEmpty()
            }
        } catch (e: IOException) {
            // 网络故障一律上抛：用户分得清「连不上」和「真的没有新版」
            Log.w(TAG, "版本检查网络失败：${e.message}")
            throw ApiException(AppError.Network, e)
        }
        if (raw.isBlank()) {
            Log.i(TAG, "version-mobile.json 是空响应体，按「没有可用更新」处理")
            return null
        }
        return try {
            parse(raw)
        } catch (e: JSONException) {
            // 被中间层插了页、截断、或者根本不是 JSON：内容不可信，不猜版本号
            Log.i(TAG, "version-mobile.json 解析失败：${e.message}")
            null
        }
    }

    /**
     * 把票据解析成 [UpdateInfo]；内容不足以拼出一条可信的下载路径时返回 null。
     *
     * 三个字段各有取舍：
     * - `versionName` 缺了或没有数字 → null。它是 APK 直链的唯一组成部分，
     *   一个非数字的「版本名」会以版本号的身份进入比较逻辑，比的是垃圾值。
     * - `versionCode` 缺了 → 留 null 交给 [com.moda.mobile.data.local.VersionRules] 回落比版本名，
     *   绝不在这里编一个 0：0 会被读成「比本机旧」。
     * - `files` 为空数组 → null。这一版什么资产都没挂，就算有版本号也没有可下载的东西；
     *   弹一个「立即更新」出去，用户点下去只会看到下载失败。
     */
    private fun parse(json: String): UpdateInfo? {
        val obj = JSONObject(json)

        val files = obj.optJSONArray("files")
        if (files != null && files.length() == 0) {
            Log.i(TAG, "version-mobile.json 的 files 为空，按「没有可用更新」处理")
            return null
        }

        // tag 是 v1.2.3，VersionName 是 1.2.3；剥一次，下游就不会出现「有的地方带 v 有的不带」
        val name = obj.optString("versionName").trim().let {
            if (it.startsWith("v") || it.startsWith("V")) it.substring(1) else it
        }
        if (name.isBlank() || name.none { it.isDigit() }) {
            Log.i(TAG, "version-mobile.json 里没有可用的 versionName（${obj.optString("versionName")}）")
            return null
        }

        return UpdateInfo(
            versionName = name,
            notes = stripHtml(obj.optString("notes")),
            apkUrl = "https://github.com/$OWNER/$REPO/releases/download/v$name/ModaMobile$name.apk",
            versionCode = obj.optInt("versionCode", 0).takeIf { it > 0 },
        )
    }

    /**
     * 一次下载的候选源，按尝试顺序排：镜像在前、直连在后。
     *
     * 镜像对国内链路快得多，但它是个第三方服务，随时会没（ghfast.top 就是这样没的）；
     * 直连 github 返回 302 之后同样能下完，只是慢。两个都留才有「总有一个能通」，
     * 只剩一个就等于把 App 的自我更新绑在别人的可用性上——而这正是用户看到「下载失败」的那次故障。
     * 镜像前缀只改走哪条链路，不改「下的是谁家的资产」：目标永远是手机端的 APK。
     */
    fun apkUrls(info: UpdateInfo): List<String> = listOf(MIRROR_PREFIX + info.apkUrl, info.apkUrl)

    // 块级收尾先换成换行，否则 <li>a</li><li>b</li> 会连成一行 "a b"
    private val BLOCK_END = Regex("(?i)<\\s*br\\s*/?\\s*>|</\\s*(p|li|div|h[1-6]|tr)\\s*>")
    private val ANY_TAG = Regex("<[^>]*>")
    private val INLINE_BLANK = Regex("[ \\t\\u00a0]+")
    private val MANY_BREAKS = Regex("\\n{3,}")

    /**
     * 票据里的 notes 可能是 GitHub 的 HTML 正文。
     * 上屏只需要纯文本：标签一律去掉，实体还原，空白折叠，最后按 [NOTES_MAX] 截断。
     */
    private fun stripHtml(html: String): String {
        if (html.isBlank()) return ""
        val text = html.replace(BLOCK_END, "\n")
            .replace(ANY_TAG, "")
            .let { unescape(it) }
            .replace(INLINE_BLANK, " ")
            .replace(MANY_BREAKS, "\n\n")
            .trim()
        return if (text.length <= NOTES_MAX) text else text.take(NOTES_MAX) + "…"
    }

    // &amp; 最后还原，否则 "&amp;lt;" 会被先解成 "<" 再当成标签吃掉
    private fun unescape(s: String): String = s
        .replace("&nbsp;", " ")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&amp;", "&")
}
