package com.moda.mobile.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * 详情页自动起播要区分的那三种网络。
 *
 * 口径只有一个：**明确是蜂窝**才算 [Cellular]。WiFi / 以太网 / VPN / 无网 / 拿不到服务
 * 一律按 [Other] 处理（＝可直接起播）——用户原话是「WiFi 下默认直接加载视频」，
 * 所以这张表里只允许出现一处「要提醒」，其余全部通向"照常起播"。
 *
 * 把"没有网络"当成蜂窝会让离线用户先看到一张流量确认卡；把"未知"当成蜂窝同样是猜。
 * 宁可漏提醒一次，也不要每次进详情页都先拦一下。
 */
enum class NetKind {
    Wifi,
    Cellular,
    Other;

    companion object {
        /**
         * 探测一次当前网络类型。
         *
         * 只看**当前活跃网络**的 capabilities，不注册 NetworkCallback：详情页只需要
         * 「进页面这一次」的判断，而常驻监听要管的生命周期（切网、多网络共存、切后台）
         * 远多于它换来的一个一次性答案。
         *
         * 判据的顺序有意义：先排除 WIFI / ETHERNET / VPN，最后才认 CELLULAR。
         * VPN 必须排在 CELLULAR 之前——连了 VPN 时底层传输往往仍是蜂窝，但用户眼里的
         * 场景是"我在用 WiFi/VPN"，这时弹流量卡只会被当成误报。
         */
        fun probe(context: Context): NetKind {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return Other
            val caps = try {
                val network = cm.activeNetwork ?: return Other
                cm.getNetworkCapabilities(network) ?: return Other
            } catch (e: SecurityException) {
                // 极端情况（权限被改过）：按"可直接起播"处理。附属判定失败不许挡住主流程
                return Other
            }
            return when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Wifi
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Other
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> Other
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Cellular
                else -> Other
            }
        }
    }
}
