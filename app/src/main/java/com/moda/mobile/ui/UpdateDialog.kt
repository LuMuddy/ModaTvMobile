package com.moda.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.moda.mobile.R
import com.moda.mobile.model.AppError
import com.moda.mobile.model.UpdateInfo
import com.moda.mobile.ui.theme.ModaColors
import com.moda.mobile.ui.theme.ModaType
import com.moda.mobile.ui.update.UpdateInstaller
import java.io.File

/**
 * 版本更新弹窗的状态机，MainViewModel 是唯一写入口。
 *
 * 七种状态对应「检查 → 展示 → 下载 → 拉起安装器」的每一段，界面只看状态、不自己推断时序：
 * - [Idle]：没有可展示的东西。**不渲染弹窗**（"没检测到就没有"，不造"已是最新"弹窗）。
 *   它同时表示「还没查过」「静默检查正在飞」「静默检查失败了」——所以它不是"已是最新"。
 * - [Latest]：检查成功跑完、确认当前已是最新（或仓库还没有 Release）。**不渲染弹窗**，
 *   只用来让「关于」页那一行说实话。与 [Idle] 的区别就是"查成功了"与"没查/没查成"。
 * - [Checking]：手动检查在飞，只在「关于」页那一行显示「检查中…」，同样不弹窗。
 * - [Available]：查到了比本机新的版本，弹窗展示 [UpdateInfo.notes] 与三个出口。
 * - [Downloading]：下载中，按钮行只剩「取消下载」，进度由 [UpdateProgress] 走另一条通道。
 * - [Ready]：APK 已落在 cache 里；[apk] 就是待交给系统安装器的那一个文件。
 * - [Failed]：包没拿到手（下载失败），或包已下好却没能交到安装器手里。
 *   检查阶段的失败**不在这里**——查不到就算没有更新，落 [Latest]，于是这一屏根本不会出现。
 *
 * 「首版仓库里还没有 ModaMobile<version>.apk」这条路径为什么不会卡住界面：
 * 检查阶段（拉 atom feed / version.json）失败 → VM 落 [Latest] → AppRoot 的 when 直接 Unit，
 * 弹窗压根不渲染；下载阶段 404 → UpdateDownloader 抛异常 → VM 落 [Failed]，这里是
 * 「下载失败，可重试 + 以后再说」两个真实出口。本文件里没有任何"一直转圈"的分支：
 * 只有 [Downloading] 会画进度条，而它由 VM 的协程保证一定会走到 [Ready] 或 [Failed]。
 *
 * 为什么 [Failed] 带 [AppError] 却不带文案：文案只能由 UI 层用 stringResource 解析，
 * ViewModel 不持有任何语言（与 HomeRefreshNotice.messageRes 同一个理由）。
 */
sealed interface UpdateUi {
    data object Idle : UpdateUi

    data object Latest : UpdateUi

    data object Checking : UpdateUi

    data class Available(val info: UpdateInfo) : UpdateUi

    data class Downloading(val info: UpdateInfo) : UpdateUi

    data class Ready(val info: UpdateInfo, val apk: File) : UpdateUi

    data class Failed(val info: UpdateInfo, val error: AppError) : UpdateUi
}

/**
 * 下载进度快照。
 *
 * 单独一条通道而不是塞进 [UpdateUi]：进度每秒要更新几十次，而状态机每次变化都要重排整排按钮；
 * 混在一起会让按钮行跟着进度一起重组。
 *
 * [percent] 为 -1 表示服务端没给 Content-Length（[total] = 0），此时只能显示不确定进度。
 */
data class UpdateProgress(val downloaded: Long = 0L, val total: Long = 0L) {
    val percent: Int
        get() = if (total > 0L) ((downloaded * 100L) / total).toInt().coerceIn(0, 100) else -1

    /** 进度条的确定值（0f..1f）；[percent] 为 -1（长度未知）时进度条走不确定态，这里给 0 */
    val fraction: Float
        get() = if (total > 0L) downloaded.toFloat() / total.toFloat() else 0f
}

/**
 * 版本更新弹窗（手机端：居中 Dialog + 底部按钮行）。
 *
 * 触摸端与 TV 端的差别只在"怎么点到"：这里没有焦点链、没有遥控器方向键，
 * 所有出口都是普通按钮；正文超过高度上限时自己滚（[Modifier.verticalScroll]）。
 *
 * @param state 当前状态；[UpdateUi.Idle] / [UpdateUi.Latest] / [UpdateUi.Checking] 由调用方决定不渲染，这里不兜
 * @param progress 下载进度（collectAsState 读，见 MainViewModel 里为什么不能直接写 mutableStateOf）
 * @param onDownload 「立即更新」/ 下载失败重试 / 「立即安装」共用这一个入口：
 *        已下好的包会被直接复用（镜像不稳，重试不该从 0 再来一遍）
 * @param onIgnore 「忽略此版本」：记下一次版本号，该版本之后不再打扰
 * @param onDismiss 「以后再说」/ 返回键 / 点弹窗外（下载中则取消下载）
 */
@Composable
fun UpdateDialog(
    state: UpdateUi,
    progress: UpdateProgress,
    onDownload: () -> Unit,
    onIgnore: () -> Unit,
    onDismiss: () -> Unit,
) {
    // 只有这四种状态带版本号，也只有它们该被渲染（AppRoot 的 when 里 Idle/Latest/Checking
    // 直接 Unit）。真被误渲染时不画，而不是把入口名「检查更新」冒充成标题。
    val info: UpdateInfo = when (state) {
        is UpdateUi.Available -> state.info
        is UpdateUi.Downloading -> state.info
        is UpdateUi.Ready -> state.info
        is UpdateUi.Failed -> state.info
        UpdateUi.Idle, UpdateUi.Latest, UpdateUi.Checking -> return
    }

    val context = LocalContext.current
    // 只在 Ready 时问一次系统（PackageManager 查询不值得跟着进度重组每秒跑一遍）
    val installAllowed = remember(state) {
        state is UpdateUi.Ready && UpdateInstaller.canInstall(context)
    }

    val statusLine: String? = when (state) {
        is UpdateUi.Downloading ->
            if (progress.percent >= 0) {
                stringResource(R.string.update_downloading, progress.percent)
            } else {
                stringResource(R.string.update_downloading_unknown)
            }
        is UpdateUi.Failed ->
            if (state.error == AppError.Unknown) {
                // 包下好了，失败点在"拉起安装器"。startInstall 用 AppError.Unknown 标记它：
                // UpdateInstaller.install() 返回 false 的唯一成因是没有应用能接这个 MIME
                // （ActivityNotFoundException），不是下载故障。包就在 cache 里，
                // 说「下载失败，可重试」会让用户点一次又一次永远不成功。
                stringResource(R.string.update_failed_install)
            } else {
                stringResource(R.string.update_failed_download)
            }
        // 已下好包但没拿到「安装未知来源」授权：这句话就是下一步该做什么
        is UpdateUi.Ready -> if (!installAllowed) stringResource(R.string.update_install_hint) else null
        else -> null
    }
    val notesText = info.notes.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.update_notes_empty)

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(ModaColors.Surface)
                .padding(horizontal = 20.dp, vertical = 18.dp),
        ) {
            Text(
                stringResource(R.string.update_title, info.versionName),
                style = ModaType.Heading,
                color = ModaColors.Text,
            )

            // 状态行只在有话说时出现；进度条同理。手机上弹窗高度是浮动的，
            // 这里不需要像 TV 那样为"出现/消失时按钮跳动"预留固定槽位。
            if (statusLine != null) {
                Spacer(Modifier.height(8.dp))
                Text(statusLine, style = ModaType.Body, color = ModaColors.TextDim)
            }
            if (state is UpdateUi.Downloading) {
                Spacer(Modifier.height(8.dp))
                val bar = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                if (progress.percent >= 0) {
                    LinearProgressIndicator(
                        progress = { progress.fraction },
                        modifier = bar,
                        color = ModaColors.Accent,
                        trackColor = ModaColors.SurfaceHigh,
                    )
                } else {
                    // 服务端没给 Content-Length：画不确定进度条。
                    // 绝不画一条永远停在 0% 的确定条——用户会以为卡死然后反复按取消重来。
                    LinearProgressIndicator(
                        modifier = bar,
                        color = ModaColors.Accent,
                        trackColor = ModaColors.SurfaceHigh,
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            Text(
                stringResource(R.string.update_notes_header),
                style = ModaType.Caption,
                color = ModaColors.TextDim,
            )
            Spacer(Modifier.height(6.dp))
            // 更新日志长度完全不可控（可能几十行），正文让位、按钮不让：
            // 超过上限自己滚，标题与按钮始终在屏上。
            Text(
                text = notesText,
                style = ModaType.Body,
                color = ModaColors.Text,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .verticalScroll(rememberScrollState()),
            )

            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                when (state) {
                    is UpdateUi.Available -> {
                        TextButton(onClick = onIgnore) {
                            Text(stringResource(R.string.update_btn_ignore), style = ModaType.Body, color = ModaColors.TextDim)
                        }
                        Spacer(Modifier.width(4.dp))
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(R.string.update_btn_later), style = ModaType.Body, color = ModaColors.TextDim)
                        }
                        Spacer(Modifier.width(4.dp))
                        PrimaryButton(stringResource(R.string.update_btn_download), onDownload)
                    }
                    // 下载中只有一个动作：放弃本次下载（= 关弹窗，VM 会 cancel 掉任务）
                    is UpdateUi.Downloading -> TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.update_btn_cancel), style = ModaType.Body, color = ModaColors.TextDim)
                    }
                    // 「立即安装」走 onDownload：VM 见到已下好的包会跳过下载再试一次安装，
                    // 于是这里不需要第二个 VM 入口（重试安装与首次下载共用同一个入口）。
                    is UpdateUi.Ready -> {
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(R.string.update_btn_later), style = ModaType.Body, color = ModaColors.TextDim)
                        }
                        Spacer(Modifier.width(4.dp))
                        PrimaryButton(stringResource(R.string.update_btn_install), onDownload)
                    }
                    is UpdateUi.Failed -> {
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(R.string.update_btn_later), style = ModaType.Body, color = ModaColors.TextDim)
                        }
                        Spacer(Modifier.width(4.dp))
                        PrimaryButton(stringResource(R.string.update_btn_download), onDownload)
                    }
                    // Idle / Latest / Checking 上面已经 return，这里只是给 when 收口
                    else -> TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.update_btn_later), style = ModaType.Body, color = ModaColors.TextDim)
                    }
                }
            }
        }
    }
}

@Composable
private fun PrimaryButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = ModaColors.Accent,
            contentColor = Color.White,
        ),
    ) {
        Text(text)
    }
}
