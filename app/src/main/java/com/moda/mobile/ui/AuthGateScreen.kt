package com.moda.mobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moda.mobile.R
import com.moda.mobile.data.WxPoll
import com.moda.mobile.data.WxQr
import com.moda.mobile.ui.components.QrCodeImage
import com.moda.mobile.ui.theme.ModaColors
import com.moda.mobile.ui.theme.ModaType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 授权码固定 8 位：输入框、提交闸门、站点页面三处必须是同一个数 */
internal const val AUTH_CODE_LENGTH = 8

/**
 * 二维码原文模板 = 微信「在微信里确认登录」的地址前缀。
 *
 * 来源是实测，不是猜的：把服务端 wx_qr 回的 qr 图片（微信自己那张 JPEG）用 zxing 解码，
 * 得到的就是 https://open.weixin.qq.com/connect/confirm?uuid=&lt;uuid&gt;，其中 uuid 与同一次
 * wx_qr 响应里的 uuid 字段一致。所以本地拼这条原文就够了——既不用下载那张图，
 * 也就不受图片缩放/染色/暗色主题的影响（屏幕上的每一个模块都是我们自己画的）。
 *
 * 这个前缀只用来**画二维码**（屏幕上每一个模块都是自己画的）。
 * 不要拿它做「本机一键拉起微信」：ACTION_VIEW 打不开微信的这条链接，相册识别也不认，
 * 详见 AuthGateScreen 的 KDoc。
 */
internal const val WX_QR_TEXT_PREFIX = "https://open.weixin.qq.com/connect/confirm?uuid="

/**
 * 轮询间隔 1.5s。
 *
 * 为什么是这个数：微信侧从「已扫码」到「确认登录」通常 2~5 秒，1.5s 让状态行的变化
 * 看起来是实时的；再快（<1s）对结果没有帮助，只是把同一张码问得更勤；
 * 慢到 3s 时用户点完手机上的确认会盯着「已扫码」两秒以上，像卡住了。
 *
 * 节奏为什么留在这里而不是 ViewModel：VM 只暴露「问一次」的 MainViewModel.pollQr（单次、
 * 可挂起），本身不持有任何定时器；把循环写在这里，协程就跟着这一屏的组合一起生灭，
 * 离开授权门（扫码成功切界面 / 退出应用）时 Compose 会取消它，不存在门关了后端还在被打。
 */
private const val POLL_INTERVAL_MS = 1500L

/**
 * 二维码画布边长。手机屏上一屏放得下 240dp，白色底框再加 QR_PADDING 收边。
 *
 * 白框本身不能去掉：二维码对深浅反转敏感，暗底卡片上的图案会让一部分扫码器把黑白判反。
 */
private val QR_BOX = 240.dp

/** 二维码区白底内边距。只作码不贴白框边缘的收边（静区 2 模块由 QrCode.kt 自己铺） */
private val QR_PADDING = 8.dp

/**
 * 小写直接当大写显示：只改渲染、不动缓冲区，输入法合成区因此完全不受影响。
 * 长度不变，所以 OffsetMapping 用 Identity——光标位置不会错位。
 */
private object UppercaseTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText =
        TransformedText(AnnotatedString(text.text.uppercase()), OffsetMapping.Identity)
}

/**
 * 授权门的状态机。
 *
 * 为什么要有本地态（LoadingQr / QrFailed）：后端的五个 status 只在手里已经有一张 sid
 * 时才存在，而取码本身也会失败；把这两件事混进 pending 会让屏幕显示一张并不存在的码。
 */
private enum class GatePhase {
    /** 正在取码：白框里是一个转圈，状态行说「正在获取登录二维码…」 */
    LoadingQr,

    /** 取码失败：状态行 + 提示行说明原因，按钮行仍是刷新 / 退出（这里给授权码也没用） */
    QrFailed,

    /** pending：二维码在屏上，状态行说「请用微信扫一扫」 */
    WaitingScan,

    /** scanned：状态行说「已扫码，请在手机上点『确认登录』」 */
    Scanned,

    /** expired（自动重取过一次仍过期）：二维码撤下 + 请点刷新，等用户按「刷新二维码」 */
    Expired,

    /** need_code：账号未激活，授权码输入框展开，状态行说「账号未激活，请输入授权码」 */
    NeedCode,
}

/**
 * 授权门：未授权时全应用唯一的界面 —— 微信登录二维码 + 状态行 + 补位的授权码输入 + 退出。
 * 未授权时 Dest 的内容根本不进组合，这是 AppRoot 的职责。
 *
 * ## 手机端与 TV 端一样：只能由另一台设备扫
 *
 * 手机上曾经多一条「用微信打开授权链接」（Intent.ACTION_VIEW 打开二维码原文），已删，
 * 因为实测这条路根本不存在：
 *  1. 微信不给 http/https 注册 intent-filter，系统选择器里没有微信，只会落到浏览器；
 *  2. 即便在微信内置浏览器里打开，那条 confirm 链接也只是一张空壳页面 ——
 *     「确认登录」是微信**扫码模块**扫到这段 URL 时才走的原生界面，不是网页提供的；
 *  3. 截图后到「扫一扫 → 相册」识别同样不行：微信对登录码只认摄像头扫描。
 * 所以这一屏只保留二维码，并明确写清"要用另一台手机扫"——见 m_gate_scan_hint。
 *
 * ## 状态机（status → 屏幕上显示什么 → 用户能做什么）
 *
 * | 状态 | 屏幕 | 用户 |
 * |------|------|------|
 * | pending   | 二维码 + 「请用微信扫一扫」                   | 另台手机扫码 / 刷新 / 退出 |
 * | scanned   | 二维码 + 「已扫码，请在手机上点『确认登录』」   | 等手机（其余按钮仍在） |
 * | expired   | 二维码撤下 + 「二维码已过期，请点刷新」        | 刷新 / 退出 |
 * | ok        | 本屏不再渲染：会话已落盘，AppRoot 切到正常界面  | 无（门已经没了） |
 * | need_code | 二维码 + 「账号未激活，请输入授权码」+ 授权码框 | 填码 → 激活 / 刷新 / 退出 |
 * | 取码失败    | 「二维码获取失败，请点刷新」+ 红字原因         | 刷新 / 退出 |
 *
 * ## 轮询的生命期
 *
 * 轮询跑在 LaunchedEffect(ticket?.sid) 里：离开这一屏（扫码成功切界面 / 退出应用）或者
 * sid 变化时，Compose 会取消这个协程。没有任何手动 stop 标志——那种标志迟早漏一处，
 * 结果就是门关掉了、后端还在被每 1.5s 打一下，而屏幕上什么线索都没有。
 *
 * @param onRequestQr 取一张码；失败时带一条已渲染好的文案
 * @param onPollQr 轮询一次；ok 的那次会话已在数据层落盘，这里只做界面切换
 * @param onActivate 授权码激活；返回 null 表示已授权，否则是失败原因
 * @param onExit 退出应用（按返回键或点「退出」都走它）
 */
@Composable
fun AuthGateScreen(
    onRequestQr: suspend () -> QrTicketResult,
    onPollQr: suspend (String) -> QrPollResult,
    onActivate: suspend (String, String) -> String?,
    onExit: () -> Unit,
) {
    // 门的下面没有任何一层，返回键无处可穿，直接结束应用
    BackHandler(enabled = true) { onExit() }

    val scope = rememberCoroutineScope()

    var phase by remember { mutableStateOf(GatePhase.LoadingQr) }
    var ticket by remember { mutableStateOf<WxQr?>(null) }
    // need_code 那一次带回来的 token：激活要用它，但账号还没激活、它不是会话，所以只留在内存里
    var pendingToken by remember { mutableStateOf<String?>(null) }
    // 失败/提示的唯一一行：取码失败、轮询抖动、本地闸门、激活失败、拉起微信失败都写在这里。
    // 不弹窗——弹窗会把刚敲进去的码一起遮住，用户只能凭记忆重敲
    var notice by remember { mutableStateOf<String?>(null) }
    var codeBoxOpen by remember { mutableStateOf(false) }
    // 「过期后自动重取一次」的额度：每张码各有一份（手动刷新时重新领一份，见 refreshQr）
    var autoRetried by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    val failureFallback = stringResource(R.string.settings_auth_failed)
    val invalidCodeHint = stringResource(R.string.gate_code_invalid)

    /**
     * 重新取一张码。失败只报原因 + 让用户再刷新一次，**不**展开授权码框：
     * 没有扫码会话时授权码换不到任何身份（后端回「登录状态已失效」），摆个框只会让人白敲。
     */
    suspend fun loadQr() {
        phase = GatePhase.LoadingQr
        notice = null
        val result = try {
            onRequestQr()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            QrTicketResult.Failed(failureFallback)
        }
        when (result) {
            is QrTicketResult.Ok -> {
                ticket = result.ticket
                pendingToken = null
                codeBoxOpen = false
                phase = GatePhase.WaitingScan
            }
            is QrTicketResult.Failed -> {
                ticket = null
                pendingToken = null
                codeBoxOpen = false
                phase = GatePhase.QrFailed
                notice = result.text
            }
        }
    }

    // 进这一屏先自动取一张码。key 用 Unit：只有「重新进入授权门」才重取——
    // 轮询带来的每次状态变化都不该换掉用户手里正在扫的那张
    LaunchedEffect(Unit) { loadQr() }

    // 轮询：只在手里有 sid 时跑，随这一屏的组合同生共死（见 KDoc 的「轮询的生命期」）
    LaunchedEffect(ticket?.sid) {
        val sid = ticket?.sid ?: return@LaunchedEffect
        while (true) {
            val result = try {
                onPollQr(sid)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                QrPollResult.Failed(failureFallback)
            }
            when (result) {
                // 抖一下不换码：码还有效，把原因写在提示行上，下个 tick 继续问。
                // 一次网络抖动就把用户踢回「再点一次刷新」是更差的取舍
                is QrPollResult.Failed -> notice = result.text
                is QrPollResult.Ok -> when (val p = result.poll) {
                    WxPoll.Pending -> {
                        notice = null
                        phase = GatePhase.WaitingScan
                    }
                    WxPoll.Scanned -> {
                        notice = null
                        phase = GatePhase.Scanned
                    }
                    WxPoll.Expired -> {
                        // 过期只自动重取一次：后端真坏了的时候无限重取等于无限打后端，
                        // 屏幕上的二维码也会闪个不停。第二张还过期就把「刷新二维码」交给用户按
                        if (!autoRetried) {
                            autoRetried = true
                            loadQr()
                        } else {
                            // 过期也不再给授权码入口：后端要求先有扫码会话（同 loadQr 的说明）
                            ticket = null
                            codeBoxOpen = false
                            phase = GatePhase.Expired
                        }
                        return@LaunchedEffect
                    }
                    is WxPoll.NeedCode -> {
                        pendingToken = p.token
                        codeBoxOpen = true
                        notice = null
                        phase = GatePhase.NeedCode
                        // 账号状态不会因为继续轮询而改变：激活之前问多少次都是 need_code
                        return@LaunchedEffect
                    }
                    // 会话已在数据层落盘、界面也已经被 applyAuthorized 切走：
                    // 这个协程随即随组合一起被取消，return 只是让它别再多问一次
                    is WxPoll.Authorized -> return@LaunchedEffect
                }
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    fun refreshQr() {
        // 手动刷新 = 重新领一份「自动重取」额度：这一张过期时仍该自动再试一次
        autoRetried = false
        scope.launch { loadQr() }
    }

    fun submitCode() {
        if (busy) return
        // 输入框只在 need_code 那一次之后出现（见轮询的 NeedCode 分支，这是唯一入口），
        // 所以这里必然有 token
        val token = pendingToken ?: return
        // 8 位闸门：一次注定失败的请求（多一个连字符、少一位）根本不该发出去。
        // 归一化口径与数据层 Auth.activate 一致（大写 + 丢非字母数字），区别只在这里多一道长度闸
        val code = draft.uppercase().filter { c -> c in 'A'..'Z' || c in '0'..'9' }
        if (code.length != AUTH_CODE_LENGTH) {
            notice = invalidCodeHint
            return
        }
        busy = true
        notice = null
        scope.launch {
            val text = try {
                onActivate(token, code)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failureFallback
            }
            busy = false
            // 失败时只写原因：draft 原样保留，用户就地改一个字重试，不用从八位从头敲
            if (text != null) notice = text
        }
    }

    val statusText = when (phase) {
        GatePhase.LoadingQr -> stringResource(R.string.gate_qr_loading)
        GatePhase.QrFailed -> stringResource(R.string.gate_qr_failed)
        GatePhase.WaitingScan -> stringResource(R.string.gate_qr_hint)
        GatePhase.Scanned -> stringResource(R.string.gate_scanned)
        GatePhase.Expired -> stringResource(R.string.gate_expired)
        GatePhase.NeedCode -> stringResource(R.string.gate_need_code)
    }
    val statusColor = when (phase) {
        GatePhase.QrFailed, GatePhase.Expired -> ModaColors.Danger
        GatePhase.Scanned -> ModaColors.AccentSoft
        else -> ModaColors.TextDim
    }
    // 只在 uuid 变化时重算：轮询每 1.5s 改一次状态，二维码本身一个像素都不该重编码
    val qrText = ticket?.let { WX_QR_TEXT_PREFIX + it.uuid }

    // safeDrawingPadding 不能省：本应用是 edge-to-edge（setDecorFitsSystemWindows(false)），
    // 这一屏又是唯一没有 Scaffold / 压栈页壳子的整屏页。少了它，最底下那排
    // 「刷新二维码 / 退出」正好压在导航栏与手势区上——点「退出」会被系统返回手势吞掉，
    // 屏幕纹丝不动，用户看到的就是「退出去了还是这个授权页」。压栈页是同款处理（见 AppRoot）。
    Box(Modifier.fillMaxSize().background(ModaColors.Bg).safeDrawingPadding()) {
        // 小屏（含折叠 / 横屏矮窗口）上内容一定超出屏幕，所以整屏可滚：
        // 底部那排按钮永远滚得到，不会被屏幕下沿裁掉
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(R.string.gate_title),
                style = ModaType.Title,
                color = ModaColors.Text,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.m_gate_message),
                style = ModaType.Body,
                color = ModaColors.TextDim,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))

            // 二维码区：白底不是装饰——二维码对深浅反转敏感，暗色底上的图案
            // 会让一部分扫码器直接把黑白判反。白框在没码时退成暗色占位，尺寸不变，
            // 所以取码 / 过期时版面不会跳
            Box(
                Modifier
                    .size(QR_BOX)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (qrText != null) Color.White else ModaColors.SurfaceHigh)
                    .padding(QR_PADDING),
                contentAlignment = Alignment.Center,
            ) {
                if (qrText != null) {
                    QrCodeImage(text = qrText, size = QR_BOX - QR_PADDING * 2)
                } else if (phase == GatePhase.LoadingQr) {
                    CircularProgressIndicator(color = ModaColors.Accent)
                }
            }

            Spacer(Modifier.height(16.dp))
            // 状态行：这一屏最该被读到的一句话，所以只随 status 变，不随失败原因变
            Text(
                statusText,
                style = ModaType.Body,
                color = statusColor,
                textAlign = TextAlign.Center,
            )

            // 失败 / 本地闸门 / 拉起微信失败：就地显示，不换屏、不弹窗
            notice?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    it,
                    modifier = Modifier.fillMaxWidth(),
                    style = ModaType.Body,
                    color = ModaColors.Danger,
                    textAlign = TextAlign.Center,
                )
            }

            // 这条提示曾经是一个按钮（「用微信打开授权链接」），已删：微信不给 http/https
            // 注册 intent-filter，ACTION_VIEW 的系统选择器里根本没有微信；而且那条 confirm
            // 链接本身是空壳页面，微信只在「扫一扫」扫到它时才走原生确认页。
            // 相册识别也一样走不通（微信对登录码只认摄像头），所以这里只说明唯一可行的那条路。
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.m_gate_scan_hint),
                modifier = Modifier.fillMaxWidth(),
                style = ModaType.Caption,
                color = ModaColors.TextDim,
                textAlign = TextAlign.Center,
            )

            if (codeBoxOpen) {
                Spacer(Modifier.height(16.dp))
                Text(
                    // 框只在 need_code 之后出现，所以这里只有一句说明，不存在第二种口径
                    stringResource(R.string.gate_code_hint),
                    style = ModaType.Body,
                    color = ModaColors.TextDim,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = draft,
                    // 缓冲区照收（不改写、不拒收——改写会顶掉输入法合成区，拒收会把合法字母一起吞掉）。
                    // 合法性由提交前的闸门把守；这里只兜一个上限，防止长按粘贴进来一屏字符
                    onValueChange = { draft = it.take(AUTH_CODE_LENGTH * 2) },
                    visualTransformation = UppercaseTransformation,
                    enabled = !busy,
                    singleLine = true,
                    // 明文显示：授权码要一个字符一个字符核对，遮成圆点只会让人重敲
                    textStyle = ModaType.Body.copy(letterSpacing = 4.sp, textAlign = TextAlign.Center),
                    placeholder = {
                        Text(
                            stringResource(R.string.gate_code_placeholder),
                            style = ModaType.Body,
                            color = ModaColors.TextDim,
                        )
                    },
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Done,
                        // 软键盘只给 ASCII；大写由 visualTransformation 兜底
                        keyboardType = KeyboardType.Ascii,
                    ),
                    keyboardActions = KeyboardActions(onDone = { submitCode() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { submitCode() },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ModaColors.AccentSoft,
                        contentColor = Color.White,
                    ),
                ) {
                    Text(stringResource(if (busy) R.string.gate_activating else R.string.gate_activate))
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = { refreshQr() },
                    enabled = !busy,
                    modifier = Modifier.weight(1f).height(48.dp),
                ) {
                    Text(stringResource(R.string.gate_refresh_qr), style = ModaType.Body, color = ModaColors.Text)
                }
                // 退出不跟 busy：它是这一屏唯一的逃生口，激活请求在飞时也必须点得动。
                // 把它和「刷新」一起按 busy 关掉，等于在最需要离开的时候把人锁在门里
                TextButton(
                    onClick = onExit,
                    modifier = Modifier.weight(1f).height(48.dp),
                ) {
                    Text(stringResource(R.string.gate_exit), style = ModaType.Body, color = ModaColors.TextDim)
                }
            }
        }
    }
}
