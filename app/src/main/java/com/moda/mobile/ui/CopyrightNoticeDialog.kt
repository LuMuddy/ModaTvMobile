package com.moda.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.moda.mobile.BuildConfig
import com.moda.mobile.R
import com.moda.mobile.ui.theme.ModaColors
import com.moda.mobile.ui.theme.ModaType

/**
 * 版权声明正文（四个段落的字符串资源 id，顺序即展示顺序）。
 *
 * 独立成列表而不是一段长文本：分条之后每条只表达一件事，
 * 用户扫一眼就能抓到「免费」「作者不管盗版」这两个要点。
 * 这里存资源 id 而不是字符串：正文要跟系统语言走，启动弹窗与设置页入口共用同一份文案。
 */
private val NoticeParagraphs: List<Int> = listOf(
    R.string.copyright_paragraph_1,
    R.string.copyright_paragraph_2,
    R.string.copyright_paragraph_3,
    R.string.copyright_paragraph_4,
)

/**
 * 版权声明弹窗。
 *
 * 会在每次版本升级后自动出现一次（判定逻辑在 AppRoot，见 CopyrightNoticeStore），
 * 也可以从设置页随时重新打开——同一条声明两种入口，内容完全一致。
 *
 * 触摸端与 TV 端的差别：正文靠手指滚动（不需要正文自己抢焦点、也不需要吃掉方向键），
 * 底部固定一颗「我已知悉」。正文高度上限 320dp —— 手机上四段文案基本装得下，
 * 更小的屏幕上它自己滚，标题与按钮始终可见。
 *
 * @param onDismiss 用户点「我已知悉」或按返回键；调用方负责把它记录成「当前版本已确认」
 */
@Composable
fun CopyrightNoticeDialog(
    onDismiss: () -> Unit,
    versionName: String = BuildConfig.VERSION_NAME,
    versionCode: Int = BuildConfig.VERSION_CODE,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(ModaColors.Surface)
                .padding(horizontal = 20.dp, vertical = 18.dp),
        ) {
            Text(
                stringResource(R.string.copyright_title),
                style = ModaType.Heading,
                color = ModaColors.Text,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                // 版本随构建变化，整句交给资源表，不在代码里拼句子
                stringResource(R.string.copyright_version_line, versionName, versionCode),
                style = ModaType.Caption,
                color = ModaColors.TextDim,
            )
            Spacer(Modifier.height(14.dp))

            // 长文案在小屏上会顶破弹窗高度，所以正文自己滚：
            // 标题与按钮始终可见，用户永远看得见入口和出口。
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                NoticeParagraphs.forEach { paragraphRes ->
                    Text(
                        text = stringResource(paragraphRes),
                        style = ModaType.Body,
                        color = ModaColors.Text,
                    )
                }
            }

            Spacer(Modifier.height(18.dp))
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ModaColors.Accent,
                    contentColor = Color.White,
                ),
            ) {
                Text(stringResource(R.string.copyright_ack))
            }
        }
    }
}
