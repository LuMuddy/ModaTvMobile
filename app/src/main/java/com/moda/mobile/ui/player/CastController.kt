package com.moda.mobile.ui.player

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.net.DatagramPacket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL

/**
 * 一台可投屏的 DLNA 渲染器（MediaRenderer）。
 *
 * [location] 是 device description 的地址，同时充当去重键：同一台电视可能既回
 * MediaRenderer 的 M-SEARCH 又回别的应答，只有 LOCATION 能唯一标识一台设备。
 * [controlUrl] 已经按 [location] 的 scheme/host/port 拼成绝对地址，SOAP 直接往这里 POST。
 */
data class CastDevice(
    val friendlyName: String,
    val location: String,
    val controlUrl: String,
)

/**
 * DLNA 投屏：发现（SSDP）+ 控制（AVTransport SOAP），全部手写，只用 java.net。
 *
 * 为什么不引库：仓库的依赖纪律是「不加不必要的依赖」，而手机端要的只有三件事——
 * 找到电视、把地址塞给电视、让电视停下。Cling / jUPnP 会带进一整套 UPnP 栈
 * （gENA 订阅、描述缓存、XML 绑定），为三个 SOAP 动作付这个体积不划算。
 *
 * 三条实现约束，任何改动都必须守住：
 *  · 网络调用一律在 [Dispatchers.IO]：Android 的主线程网络访问是直接抛异常；
 *  · 每个 HttpURLConnection 都设 connectTimeout / readTimeout：局域网里「电视没开机」
 *    表现为包被丢弃而不是拒绝连接，不设超时会一直挂着；
 *  · discover 不抛异常：无网、无组播、无设备一律返回空表，UI 只认「空表 = 空态」。
 */
object CastController {

    private const val Tag = "CastController"

    private const val SsdpHost = "239.255.255.250"
    private const val SsdpPort = 1900
    private const val SearchTarget = "urn:schemas-upnp-org:device:MediaRenderer:1"
    private const val AvTransport = "urn:schemas-upnp-org:service:AVTransport:1"

    private const val ConnectTimeoutMs = 1500
    private const val ReadTimeoutMs = 2000

    /** 发现总预算里留给 SSDP 收包的比例：MX 是 2 秒，剩下的时间够并发拉 description */
    private const val SsdpBudgetRatio = 0.6f

    /** 单次 receive 的最长阻塞：睡满整个预算会让「有 3 台电视」和「一台都没有」等一样久 */
    private const val ReceiveSliceMs = 300L

    private const val MinSliceMs = 400L

    /**
     * 发现设备。默认 3 秒（MX: 2 秒 + 拉 description 的时间），超时返回空表。
     *
     * 顺序：组播 M-SEARCH → 收 LOCATION（按 LOCATION 去重）→ 并发拉每个 LOCATION 的
     * device description → 解析 friendlyName 与 AVTransport 的 controlURL。
     */
    suspend fun discover(timeoutMs: Long = 3000L): List<CastDevice> = withContext(Dispatchers.IO) {
        val budget = timeoutMs.coerceAtLeast(MinSliceMs)
        val started = System.currentTimeMillis()
        // SSDP 先花掉 60%，剩下的留给 description：单台电视的描述请求往往要几百毫秒
        val ssdpDeadline = started + (budget * SsdpBudgetRatio).toLong().coerceAtLeast(MinSliceMs)
        val overallDeadline = started + budget
        try {
            val locations = searchLocations(ssdpDeadline)
            if (locations.isEmpty()) {
                emptyList()
            } else {
                // 并发拉：串行会让 N 台电视的等待线性叠加，3 秒预算必爆
                coroutineScope {
                    locations.map { location ->
                        async { fetchDevice(location, overallDeadline) }
                    }.awaitAll().filterNotNull()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 无网 / 无组播 / 系统不给 socket：空表就是「没找到」，不把异常抛给 UI
            Log.w(Tag, "发现设备失败: " + e.message)
            emptyList()
        }
    }

    /**
     * 投屏：先 SetAVTransportURI 把地址写进电视，再 Play 让它开播。
     *
     * 两次请求有一次失败就返回 false，由 UI 提示「投屏失败」——这里不重试、不抛异常。
     */
    suspend fun play(device: CastDevice, url: String, title: String): Boolean = withContext(Dispatchers.IO) {
        try {
            // 先带 DIDL-Lite 元数据（电视上能显示片名），
            // 少数电视不认（UpnpClass 对不上就静默拒收），此时退回空串再试一次
            val withMeta = soap(device, "SetAVTransportURI", setUriArguments(url, didlLite(title, url)))
            val accepted = withMeta || soap(device, "SetAVTransportURI", setUriArguments(url, ""))
            accepted && soap(device, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(Tag, "投屏失败: " + e.message)
            false
        }
    }

    /** 让电视停下。离开播放页时也会调它，失败静默——此时页面已经拆了，没有地方能报错 */
    suspend fun stop(device: CastDevice) {
        withContext(Dispatchers.IO) {
            soap(device, "Stop", "<InstanceID>0</InstanceID>")
        }
    }

    // ———————————————————— SSDP 发现 ————————————————————

    /**
     * 发 M-SEARCH 并收应答，返回去重后的 LOCATION 列表。
     *
     * 报文照抄 UPnP Device Architecture 1.0 的格式，四件事都不能省：
     *  · HOST 必须是组播地址 + 1900；
     *  · MAN 的值要带双引号（"ssdp:discover"），漏引号是一堆设备唯一的拒收理由；
     *  · MX 是「多久之内回我」，2 秒是 DLNA 的通行值；
     *  · ST 限定 MediaRenderer，能投屏的设备才会回。
     */
    private fun searchLocations(deadline: Long): List<String> {
        val message = buildString {
            append("M-SEARCH * HTTP/1.1\r\n")
            append("HOST: ").append(SsdpHost).append(':').append(SsdpPort).append("\r\n")
            append("MAN: \"ssdp:discover\"\r\n")
            append("MX: 2\r\n")
            append("ST: ").append(SearchTarget).append("\r\n")
            append("\r\n")
        }.toByteArray(Charsets.UTF_8)

        val socket = MulticastSocket()
        try {
            // 局域网里跨一层 AP 也要发得出去，TTL=4 是 DLNA 的常用值
            socket.timeToLive = 4
            val group = InetAddress.getByName(SsdpHost)
            // UDP 会丢包，同一条 M-SEARCH 连发两次，不额外增加等待时间
            repeat(2) { socket.send(DatagramPacket(message, message.size, group, SsdpPort)) }

            val found = LinkedHashSet<String>()
            val buffer = ByteArray(4096)
            while (true) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0L) break
                socket.soTimeout = left.coerceAtMost(ReceiveSliceMs).toInt()
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (e: SocketTimeoutException) {
                    // 这一片没收到，回到循环顶部重新算剩余预算
                    continue
                }
                val response = String(packet.data, 0, packet.length, Charsets.UTF_8)
                val location = headerOf(response, "LOCATION") ?: continue
                // 按 LOCATION 去重：一台电视回几条应答都只算一台
                found.add(location)
            }
            return found.toList()
        } finally {
            socket.close()
        }
    }

    /** 取应答头里的一行（大小写不敏感：不同设备的应答大小写并不统一） */
    private fun headerOf(response: String, name: String): String? {
        for (line in response.lineSequence()) {
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            if (line.substring(0, colon).trim().equals(name, ignoreCase = true)) {
                return line.substring(colon + 1).trim().takeIf { it.isNotBlank() }
            }
        }
        return null
    }

    /** 拉一台设备的 description 并解析出 friendlyName + AVTransport controlURL */
    private fun fetchDevice(location: String, deadline: Long): CastDevice? {
        var conn: HttpURLConnection? = null
        return try {
            val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(MinSliceMs.toLong())
            val c = (URL(location).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = remaining.coerceAtMost(ConnectTimeoutMs.toLong()).toInt()
                readTimeout = remaining.coerceAtMost(ReadTimeoutMs.toLong()).toInt()
                setRequestProperty("Accept", "text/xml, application/xml")
            }
            conn = c
            var parsed: Pair<String, String?>? = null
            c.inputStream.use { input -> parsed = parseDescription(input) }
            // 取不到名字或控制地址的应答一律当「这台设备不支持投屏」丢掉，不进列表
            val name = parsed?.first ?: return null
            val control = parsed?.second ?: return null
            if (name.isBlank() || control.isBlank()) return null
            // controlURL 常是相对路径（/upnp/control/AVTransport1），必须按 LOCATION 拼成绝对地址
            val absolute = resolve(location, control) ?: return null
            CastDevice(friendlyName = name, location = location, controlUrl = absolute)
        } catch (e: Exception) {
            Log.w(Tag, "拉取设备描述失败(" + location + "): " + e.message)
            null
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * 相对地址 → 绝对地址。用 URI.resolve 而不是自己拼字符串：
     * controlURL 可能是 "/x/y"（换 path）、"x/y"（相对当前目录）或已经是绝对地址，
     * 三种形态 resolve 都能正确定位。
     */
    private fun resolve(location: String, controlUrl: String): String? = try {
        URI(location).resolve(controlUrl.replace(" ", "%20")).toString()
    } catch (e: Exception) {
        Log.w(Tag, "拼装控制地址失败: " + e.message)
        null
    }

    /**
     * 解析 device description XML。
     *
     * 用 XmlPullParser 而不是正则：description 的缩进、命名空间前缀、属性顺序各厂都不一样，
     * 正则一旦按某台电视的排版写死，换台电视就静默失效。
     *
     * 只取两样东西：根设备（第一个出现的）friendlyName，以及 serviceList 里
     * serviceType 含 AVTransport 的那条 service 的 controlURL。
     */
    private fun parseDescription(input: InputStream): Pair<String, String?>? {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        // 编码交给 XML 声明自己说：电视给的 description 不一定是 UTF-8
        parser.setInput(input, null)
        var friendlyName: String? = null
        var avTransportControl: String? = null
        var currentTag: String? = null
        var insideAvTransport = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    currentTag = parser.name
                    if (parser.name == "service") insideAvTransport = false
                }
                XmlPullParser.TEXT -> {
                    val text = parser.text?.trim().orEmpty()
                    if (text.isNotEmpty()) {
                        when (currentTag) {
                            // 内嵌设备（deviceList）里也有 friendlyName，第一个才是这台电视的名字
                            "friendlyName" -> if (friendlyName == null) friendlyName = text
                            "serviceType" -> if (text.contains("AVTransport", ignoreCase = true)) {
                                insideAvTransport = true
                            }
                            "controlURL" -> if (insideAvTransport && avTransportControl == null) {
                                avTransportControl = text
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "service") insideAvTransport = false
                    currentTag = null
                }
            }
            event = parser.next()
        }
        return friendlyName?.let { it to avTransportControl }
    }

    // ———————————————————— AVTransport SOAP ————————————————————

    private fun setUriArguments(url: String, metaDataXml: String): String =
        "<InstanceID>0</InstanceID>" +
            "<CurrentURI>" + escapeXml(url) + "</CurrentURI>" +
            "<CurrentURIMetaData>" + escapeXml(metaDataXml) + "</CurrentURIMetaData>"

    /**
     * 发一条 SOAP 动作，2xx 视为成功。
     *
     * SOAPACTION 的值必须整体带双引号、服务名大小写照抄：不少电视对这两点敏感，
     * 漏引号直接回 400，而 400 的响应体里经常什么线索都没有。
     */
    private fun soap(device: CastDevice, action: String, arguments: String): Boolean {
        val envelope = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>" +
            "<u:" + action + " xmlns:u=\"" + AvTransport + "\">" + arguments +
            "</u:" + action + "></s:Body></s:Envelope>"
        var conn: HttpURLConnection? = null
        return try {
            val c = (URL(device.controlUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = ConnectTimeoutMs
                readTimeout = ReadTimeoutMs
                doOutput = true
                setRequestProperty("SOAPACTION", "\"" + AvTransport + "#" + action + "\"")
                setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            }
            conn = c
            c.outputStream.use { out -> out.write(envelope.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            if (code !in 200..299) Log.w(Tag, action + " 被拒绝: HTTP " + code)
            code in 200..299
        } catch (e: Exception) {
            Log.w(Tag, action + " 失败: " + e.message)
            false
        } finally {
            conn?.disconnect()
        }
    }

    /** 最小可用的 DIDL-Lite：只用来让电视显示片名，不给它任何额外承诺 */
    private fun didlLite(title: String, url: String): String =
        "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
            "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
            "<item id=\"0\" parentID=\"-1\" restricted=\"1\">" +
            "<dc:title>" + escapeXml(title) + "</dc:title>" +
            "<upnp:class>object.item.videoItem</upnp:class>" +
            "<res protocolInfo=\"http-get:*:video/*:*\">" + escapeXml(url) + "</res>" +
            "</item></DIDL-Lite>"

    /** XML 文本转义：地址里的 & 不转义会让整份 SOAP 报文变成非法 XML */
    private fun escapeXml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
