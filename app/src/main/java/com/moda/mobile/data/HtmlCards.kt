package com.moda.mobile.data

import com.moda.mobile.model.VideoItem

/**
 * 从 v.qq.com 的页面 HTML 里直接取出**整张卡片**。
 *
 * 为什么不再回自家接口补全：页面的内联脚本里本来就有每一张卡的完整信息
 * （实测首页与频道页各 15 张，字段齐到能直接上屏）：
 *
 *     {playVid:"a41029l3z8d", cid:"mzc00200bektv93",
 *      smallCoverPic:"...276x386/0?imageMogr2/thumbnail/175x",
 *      coverPic:"...1920x800/0?...", mzTitle:"敦煌英雄",
 *      sourceTitle:"敦煌英雄", subTitle:"雷佳音白发守孤城，章宇举义归唐"}
 *
 * 以前的实现只用正则抠 `/x/cover/<cid>`，然后为每个 cid 打一次自家的 `eps`
 * 去补标题与封面——17 个频道 + 轮播一共 180 次请求，而其中绝大部分数据
 * 这份 HTML 已经给过了。现在一次页面请求出一屏卡片，首页对后端零请求。
 *
 * **拿不到的只有「集数」**：episode_all / video_num 在 HTML 里出现 0 次，
 * 卡片上的 cornerInfo 也恒为 void 0。所以首页卡片的集数角标退化成不显示，
 * 想看集数只能进详情页（那里由 eps 给集数，那条路没变）。
 *
 * 字段口径：
 * - 标题用 mzTitle / sourceTitle，不用 title —— HTML 里的 title 混着
 *   「会员拍脸图（中心）」这类 UI 文案，拿它当剧名会直接把角标文字画到卡片上。
 * - cover 用 smallCoverPic（竖版海报，与卡片 3:4 裁切一致）；
 *   coverPic 是 1920x800 的横版主视觉，不是同一张构图。
 * - vid 用 playVid：卡片主视觉要靠它拼横版剧照，不填就只能退回竖版图。
 */
internal object HtmlCards {

    /** 一条卡片记录的开始标记。cid 长度放宽到 6 位以上，避免误吃短 id */
    private val CID_ANCHOR = Regex("(?:^|[,{\\s])cid:\"([a-z0-9]{6,})\"")

    /** 极简字段提取：只认 `key:"value"` 形态，够覆盖 v.qq.com 这份内联脚本 */
    private fun pattern(name: String) =
        Regex("(?:^|[,{\\s])" + name + "\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")

    private fun field(text: String, name: String): String =
        pattern(name).find(text)?.groupValues?.get(1)?.unescape().orEmpty()

    /**
     * playVid 在卡片对象里出现在 cid **之前**，只能往 cid 前面那段找；
     * 而且必须取最后一个，否则会把上一张卡的 playVid 当成这张的（实测会把
     * 第二张卡配上第一张卡的 vid）。
     */
    private fun lastField(text: String, name: String): String =
        pattern(name).findAll(text).lastOrNull()?.groupValues?.get(1)?.unescape().orEmpty()

    private fun String.unescape(): String = replace("\\\"", "\"").replace("\\\\", "\\")

    /** 内容类型码：1 电影 / 2 电视剧 / 3 动漫 / 4 体育赛事 / 9 纪录片 / 10 综艺栏目 / 29 汽车 / 106 少儿 / 111 文化历史 */
    private val CATEGORY = Regex("(?:^|[,{\\s])categoryType:\"([0-9]+)\"")

    /** 推荐理由数组：`decisionReasons:[{...,text:"动作电影榜第1名"},…]` */
    private val REASONS = Regex("decisionReasons:\\[(.*?)\\]", RegexOption.DOT_MATCHES_ALL)
    private val REASON_TEXT = Regex("text:\"([^\"]*)\"")

    /**
     * 从推荐理由里挑一个能上角标的短标签。
     *
     * v.qq.com 的候选长这样：`动作电影榜第1名` / `热搜总榜第1名` / `9.8分` / `豆瓣评分 8.0` /
     * `在追破500万` / `讨论破1000万` / `实时热度超2.4万`。后三行太长，画到 3 列卡片的右上角
     * 会把整张图压掉，所以只留「…榜第N名」和「…分」两形，其余一律丢弃。
     * 榜位优先于评分：它是一个「现在就该看」的理由，评分是静态属性。
     */
    private fun pickBadge(tail: String): String {
        val block = REASONS.find(tail)?.groupValues?.get(1) ?: return ""
        val texts = REASON_TEXT.findAll(block).map { it.groupValues[1] }.toList()
        texts.firstOrNull { it.contains("榜第") && it.endsWith("名") }?.let { return it }
        return texts.firstOrNull { it.endsWith("分") && it.length <= 8 }.orEmpty()
    }

    fun parse(html: String, limit: Int): List<VideoItem> {
        val anchors = CID_ANCHOR.findAll(html).toList()
        if (anchors.isEmpty()) return emptyList()
        val out = ArrayList<VideoItem>(limit)
        val seen = HashSet<String>(anchors.size)
        for ((i, m) in anchors.withIndex()) {
            val cid = m.groupValues[1]
            // 同一个 cid 在页面里会出现多次（同一张卡挂在多个模块下），只取第一次
            if (!seen.add(cid)) continue
            // 字段分在 cid 两侧：playVid 在 cid 之前，标题 / 海报 / 副标题都在 cid 之后。
            // 捞错一侧的代价是「第二张卡配上了第一张卡的剧名」——窗口混在一起搜就会这样。
            val head = html.substring((m.range.first - 700).coerceAtLeast(0), m.range.first)
            val next = anchors.getOrNull(i + 1)?.range?.first ?: html.length
            val tailEnd = next.coerceAtMost(m.range.last + 1800)
            if (tailEnd <= m.range.last) continue
            val tail = html.substring(m.range.last, tailEnd)
            val title = field(tail, "mzTitle").ifBlank { field(tail, "sourceTitle") }
            // 没有标题的卡没法上屏：宁可少一张，也不要画一条只有图的空卡
            if (title.isBlank()) continue
            out += VideoItem(
                cid = cid,
                vid = lastField(head, "playVid"),
                title = title,
                cover = field(tail, "smallCoverPic").ifBlank { field(tail, "coverPic") },
                subtitle = field(tail, "subTitle"),
                // 角标：分类（类型码，UI 侧取资源）+ 榜位/评分（上游原文，属于数据不是文案）
                badge = pickBadge(tail),
                categoryType = CATEGORY.findAll(head).lastOrNull()
                    ?.groupValues?.get(1)?.toIntOrNull() ?: 0,
            )
            if (out.size >= limit) break
        }
        return out
    }
}
