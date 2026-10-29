package com.moda.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moda.mobile.R
import com.moda.mobile.ui.theme.ModaColors
import com.moda.mobile.ui.theme.ModaType

/**
 * 崩溃页。跑在 :crash 进程里（见 crash/CrashActivity.kt），主进程此刻已经死了。
 *
 * 三条设计约束都来自「它出现在用户刚被意外打断的那一刻」：
 *  1. 第一屏就能看完：标题 + 时间 + 日志路径 + 可滚动的正文，不需要先点开任何东西；
 *  2. 主操作是「复制日志」而不是「重新打开」——复制的成功率是 100%，
 *     重新打开在启动即崩的情况下会把用户送进第二次崩溃；
 *  3. 正文可选可滚动（SelectionContainer + verticalScroll），手指拖着走不会被当成点击。
 *
 * 本页不复用 ui/components 里的 LoadingState / ErrorState：那两个是给业务页用的
 * （重试走 ViewModel），崩溃页没有 VM、也没有「重试请求」这个概念。
 */
@Composable
fun CrashScreen(
    time: String,
    path: String?,
    content: String,
    copied: Boolean,
    onCopy: () -> Unit,
    onRestart: () -> Unit,
    onClose: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(ModaColors.Bg)
            .safeDrawingPadding()
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Text(
            text = stringResource(R.string.m_crash_title),
            color = ModaColors.Danger,
            style = ModaType.Title,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.m_crash_desc),
            color = ModaColors.TextDim,
            style = ModaType.Body,
        )

        Spacer(Modifier.height(14.dp))
        InfoRow(stringResource(R.string.m_crash_time), time)
        Spacer(Modifier.height(4.dp))
        // 路径按实际落点显示（Android 11+ 不是 /sdcard/crash），过长时省略中间不现实，
        // 这里直接尾部省略：用户真正要找的是最后一级目录
        InfoRow(
            label = stringResource(R.string.m_crash_path),
            value = path ?: stringResource(R.string.m_crash_path_unavailable),
            monospace = true,
        )

        Spacer(Modifier.height(14.dp))
        Text(
            text = stringResource(R.string.m_crash_detail),
            color = ModaColors.TextDim,
            style = ModaType.Caption,
        )
        Spacer(Modifier.height(6.dp))
        SelectionContainer(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(ModaColors.Surface),
        ) {
            Text(
                text = content,
                color = ModaColors.Text,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 15.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
            )
        }

        Spacer(Modifier.height(14.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(
                onClick = onCopy,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ModaColors.Accent,
                    contentColor = ModaColors.Text,
                ),
            ) {
                Text(
                    text = stringResource(if (copied) R.string.m_crash_copied else R.string.m_crash_copy),
                    style = ModaType.Body,
                )
            }
            Button(
                onClick = onRestart,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ModaColors.SurfaceHigh,
                    contentColor = ModaColors.Text,
                ),
            ) {
                Text(stringResource(R.string.m_crash_restart), style = ModaType.Body)
            }
            // 关闭用 TextButton：它是「离开这一屏」，不该和上面两个实心按钮抢同一档视觉权重
            TextButton(onClick = onClose, modifier = Modifier.weight(0.8f)) {
                Text(stringResource(R.string.m_crash_close), color = ModaColors.TextDim, style = ModaType.Body)
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, monospace: Boolean = false) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            text = label,
            color = ModaColors.TextDim,
            style = ModaType.Caption,
            modifier = Modifier.padding(end = 10.dp),
        )
        Text(
            text = value,
            color = ModaColors.Text,
            style = if (monospace) ModaType.Caption else ModaType.Body,
            fontFamily = if (monospace) FontFamily.Monospace else null,
            // 路径给到 3 行：目录一长，2 行只够显示目录、文件名（也就是崩溃时间）会被省略号吃掉，
            // 而那正是用户要去文件管理器里对照的东西
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
