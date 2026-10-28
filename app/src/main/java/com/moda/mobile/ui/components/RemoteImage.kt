package com.moda.mobile.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.moda.mobile.data.Repo
import com.moda.mobile.ui.theme.ModaColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 只为了拿封面图，用已有的 okhttp 手搓一个最小图片加载器；
 * 不引 Coil（契约 §0.2：不引图片加载库），失败一律回落到首字色块。
 * 签名是跨任务接口（T6 的库页也会调），加参数只能加带默认值的。
 */
private val imageClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
}

/**
 * 按 KB 计量的内存缓存，上限 64MB。
 *
 * 12MB 是明显低估：一张 1080x1512 的高清解码图按 ARGB_8888 就要 6.2MB，
 * 只装得下 1 张，网格滚动时几乎每次都要重新下载解码，观感上"图片一直在闪"。
 * 64MB 约等于 10 张高清图或 170 张缩略图，覆盖一屏加滚动缓冲，
 * 又不会和播放器的解码缓冲抢内存（手机端比电视端更该守住这条）。
 */
private val imageCache = object : LruCache<String, Bitmap>(64 * 1024) {
    override fun sizeOf(key: String, value: Bitmap): Int = maxOf(1, value.byteCount / 1024)
}

/**
 * 解码目标边长上限（px）。
 *
 * 缩略图是 260x364，手机 2 列卡片的实际像素宽也就 500 出头；
 * 但原图档实测有 770x1080，甚至遇到过 28524x10408 的超大图（4MB）。
 * 直接全尺寸解码在中低端机上会 OOM，收敛到 1280：单张约 3.5MB，
 * 64MB 缓存仍能放 18 张，命中率不受影响；再大的原图仍被这道上限挡住。
 */
private const val MAX_DECODE_EDGE = 1280

private suspend fun loadBitmap(url: String): Bitmap? = withContext(Dispatchers.IO) {
    imageCache.get(url)?.let { return@withContext it }
    runCatching {
        val request = Request.Builder()
            .url(url)
            .header("Referer", "https://v.qq.com/")
            .build()
        imageClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val bytes = response.body?.bytes() ?: return@use null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            // 按长边算采样比，保证超大原图也落在 MAX_DECODE_EDGE 以内
            var sample = 1
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            while (longest / (sample * 2) >= MAX_DECODE_EDGE) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        }
    }.getOrNull()?.also { imageCache.put(url, it) }
}

/**
 * 封面图，三级回退，任何一级失败都不会白屏：
 *
 *   1. 高清原图：Repo.hiResCover(cid) → eps 的 meta.pic（原图 /0，实测 770x1080）
 *   2. 缩略图：url 参数，即 item.cover（/260，实测 260x364 / 35KB）
 *   3. 色块：CoverBlock 首字兜底
 *
 * 为什么第 1 级按需开启（preferHiRes）：eps 是重接口，会返回整部剧的全部 vids。
 * 列表一屏十几张卡逐个调 eps 会让首屏明显变慢，而小卡用 /260 的图显示时放大倍数不到 1x，
 * 本来就清晰。所以只有大图（详情页头图、首屏少数几张）才值得换高清——
 * 这是有意的取舍，不是漏改。
 */
@Composable
fun RemoteImage(
    url: String,
    title: String,
    modifier: Modifier = Modifier,
    corner: Dp = 12.dp,
    contentScale: ContentScale = ContentScale.Crop,
    cid: String = "",
    preferHiRes: Boolean = false,
    /** 图加载完成（成功或回落到色块）时回调，供调用方做「垫底图」之类的连续性处理 */
    onLoaded: (Boolean) -> Unit = {},
) {
    // 同步先探一次内存缓存。Lazy 网格回收后重建时 produceState 的初值恒为 null，
    // 即便图早就在缓存里也要等一次协程调度；那一帧会先画成文字色块，
    // 视觉上就是「滑下去再滑回来，图片闪成文字又变回图片」。同步命中即可不闪。
    val cached = remember(url) { if (url.isBlank()) null else imageCache.get(url) }
    val bitmap by produceState<Bitmap?>(initialValue = cached, url, cid, preferHiRes) {
        // 第 1 级倾向高清，但**先看 url 本身是不是已经是原图**：
        // 详情头图的 cover 就是 eps.meta.pic（/0 结尾原图），再调 hiResCover(cid)
        // 等于为重接口 eps 再打一次，大图还会长时间停在色块。已经够大就直接用。
        val urlIsAlreadyHiRes = url.contains("/0") || url.contains("/1080")
        if (preferHiRes && cid.isNotBlank() && !urlIsAlreadyHiRes) {
            val hi = runCatching { Repo.hiResCover(cid) }.getOrDefault("")
            if (hi.isNotBlank()) {
                loadBitmap(hi)?.let { hit ->
                    value = hit
                    return@produceState
                }
            }
        }
        // 第 2 级：直接用传入的地址
        value = if (url.isBlank()) null else loadBitmap(url)
    }
    val current = bitmap
    // 只在「有图/无图」状态真正翻转时回调一次，避免每次重组都触发
    LaunchedEffect(current) { onLoaded(current != null) }
    if (current != null) {
        Image(
            bitmap = current.asImageBitmap(),
            contentDescription = title,
            // 原图是竖版（770x1080），卡片是 3:4 的裁切区：必须 Crop 居中裁切，
            // 不能 Fit/FillBounds，否则会拉伸变形。
            contentScale = contentScale,
            modifier = modifier
                .clip(RoundedCornerShape(corner))
                .background(ModaColors.SurfaceHigh)
                .fillMaxSize(),
        )
    } else {
        // 第 3 级：色块兜底
        CoverBlock(title = title, modifier = modifier, corner = corner)
    }
}
