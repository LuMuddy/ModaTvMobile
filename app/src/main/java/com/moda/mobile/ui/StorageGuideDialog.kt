package com.moda.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.moda.mobile.R
import com.moda.mobile.ui.theme.ModaColors
import com.moda.mobile.ui.theme.ModaType

/**
 * 「所有文件访问」引导弹窗。
 *
 * 为什么需要它：崩溃日志按需求写到 /sdcard/crash，而 Android 11 起应用默认只能写自己的
 * 私有目录。MANAGE_EXTERNAL_STORAGE 不是运行时权限，没有可弹的系统授权框，
 * 唯一路径是跳系统设置页手动开——所以这里必须先讲清楚「为什么」，
 * 否则用户看到的是一个没头没脑的系统设置页，多半直接退出来。
 *
 * 两颗按钮的措辞刻意不对称：「去开启」是动作，「以后再说」是明确的退出，
 * 不写「取消」——用户点了以后日志仍然会落在应用私有目录，不是"什么也没做"。
 */
@Composable
fun StorageGuideDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
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
                stringResource(R.string.m_storage_guide_title),
                style = ModaType.Heading,
                color = ModaColors.Text,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.m_storage_guide_body),
                style = ModaType.Body,
                color = ModaColors.TextDim,
            )
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth()) {
                TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.m_storage_guide_later),
                        color = ModaColors.TextDim,
                        style = ModaType.Body,
                    )
                }
                Button(
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ModaColors.Accent,
                        contentColor = ModaColors.Text,
                    ),
                ) {
                    Text(stringResource(R.string.m_storage_guide_confirm), style = ModaType.Body)
                }
            }
        }
    }
}
