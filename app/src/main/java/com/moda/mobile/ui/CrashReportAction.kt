package com.moda.mobile.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.DisposableEffect
import com.moda.mobile.R
import com.moda.mobile.crash.CrashReporter
import com.moda.mobile.ui.theme.ModaColors
import com.moda.mobile.ui.theme.ModaType
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置页「上报崩溃」的整套交互：打包 → 删源文件 → 系统分享 → 问是否删除压缩包。
 *
 * 关于"分享成功"这个判定，Android 没有可靠的回执：
 *  - chooser 的 IntentSender 回调只说明「用户选了哪个目标应用」，不代表对方收到了；
 *  - 真正的送达、保存成功，第三方应用不会告诉我们。
 * 所以这里用最诚实的折中：**发出分享后等本页重新回到前台**（ON_RESUME）再问一句，
 * 让用户自己确认。这样做还有两个好处：一是没有引入 BroadcastReceiver 与 mutable PendingIntent
 * 那套东西，二是用户在分享面板里点了"取消"也不会把状态搞错——最坏情况是白问一句，
 * 而「压缩包已保存到哪」这句话本身对用户就是有用信息。
 *
 * @param onLogsCleared 源日志已被打进压缩包并删除；调用方据此立刻隐藏设置页那一行
 */
@Composable
internal fun rememberCrashReportAction(onLogsCleared: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    // 已打包的压缩包；非空才可能有"删不删"这一问
    var packed by remember { mutableStateOf<File?>(null) }
    var askDelete by remember { mutableStateOf(false) }
    var errorRes by remember { mutableStateOf<Int?>(null) }
    // 只有"刚发过分享"之后回到前台才弹问句：冷启动、从别的页返回都不该问
    var awaitingShareReturn by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && awaitingShareReturn) {
                awaitingShareReturn = false
                // 压缩包还在（没被系统清理）才问；不在就说明没什么可删的
                if (packed?.exists() == true) askDelete = true
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val start: () -> Unit = {
        scope.launch {
            // 打包是纯 IO：几十 KB 的几次读写，但绝不能在主线程上做
            val result = withContext(Dispatchers.IO) { CrashReporter.pack(context.applicationContext) }
            if (result == null) {
                errorRes = R.string.m_crash_report_failed
            } else {
                packed = result.zip
                // 源文件已经并进压缩包，设置页那一行现在就该消失
                onLogsCleared()
                awaitingShareReturn = true
                if (!share(context, result.zip)) {
                    // 分享面板都拉不起来（没有能处理 zip 的应用）：压缩包留着，也别再问删不删
                    awaitingShareReturn = false
                    errorRes = R.string.m_crash_report_share_failed
                }
            }
        }
    }

    if (askDelete) {
        val zip = packed
        AlertDialog(
            onDismissRequest = { askDelete = false },
            containerColor = ModaColors.Surface,
            title = { Text(stringResource(R.string.m_crash_report_ask_title), style = ModaType.Heading) },
            text = {
                Text(
                    stringResource(R.string.m_crash_report_ask_body, zip?.absolutePath.orEmpty()),
                    style = ModaType.Body,
                    color = ModaColors.TextDim,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    askDelete = false
                    // 删除压缩包只是"清理刚生成的文件"，失败也不值得打扰用户：
                    // 它就在 Download 目录里，用户自己也能删
                    scope.launch {
                        withContext(Dispatchers.IO) { runCatching { zip?.delete() } }
                    }
                    packed = null
                }) {
                    Text(stringResource(R.string.m_crash_report_delete), color = ModaColors.Accent)
                }
            },
            dismissButton = {
                TextButton(onClick = { askDelete = false }) {
                    Text(stringResource(R.string.m_crash_report_keep), color = ModaColors.TextDim)
                }
            },
        )
    }

    val failure = errorRes
    if (failure != null) {
        AlertDialog(
            onDismissRequest = { errorRes = null },
            containerColor = ModaColors.Surface,
            title = { Text(stringResource(R.string.m_crash_report_error_title), style = ModaType.Heading) },
            text = {
                Text(
                    stringResource(failure),
                    style = ModaType.Body,
                    color = ModaColors.TextDim,
                )
            },
            confirmButton = {
                TextButton(onClick = { errorRes = null }) {
                    Text(stringResource(R.string.m_crash_report_error_ok), color = ModaColors.Accent)
                }
            },
        )
    }

    return start
}

/**
 * 拉起系统分享面板。
 *
 * 三处细节都是踩过的坑：
 *  1. 必须用 FileProvider 的 content:// —— 直接把 file:// 交给第三方应用，对方没有读权限，
 *     分享出去的是一张打不开的图/包（Android 7 起系统直接抛 FileUriExposedException）；
 *  2. 同时挂上 clipData：部分国产应用只读 clipData、不读 EXTRA_STREAM；
 *  3. FLAG_GRANT_READ_URI_PERMISSION 加在 ACTION_SEND 上（不是 chooser 上），
 *     否则目标应用拿到的 URI 是没授权的。
 *
 * @return false 表示面板拉不起来（设备上没有能接 zip 的应用），调用方据此提示
 */
private fun share(context: Context, zip: File): Boolean = try {
    val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", zip)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/zip"
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri(null, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(send, context.getString(R.string.m_crash_report_share_title))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(chooser)
    true
} catch (_: Exception) {
    false
}
