package com.moda.mobile.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.moda.mobile.R
import com.moda.mobile.ui.theme.ModaColors
import com.moda.mobile.ui.theme.ModaType
import kotlin.math.absoluteValue

/** 封面兜底配色：同一标题恒定取到同一色，避免重组时闪烁 */
private val CoverPalette = listOf(
    Color(0xFF2B3A67), Color(0xFF3D2B4F), Color(0xFF1F4B4B), Color(0xFF4A2F2A),
    Color(0xFF2E4552), Color(0xFF3F3B2E), Color(0xFF232C4A), Color(0xFF4A3446),
)

fun coverColorOf(title: String): Color =
    CoverPalette[title.hashCode().absoluteValue % CoverPalette.size]

/**
 * 封面占位块：无图 / 加载中 / 加载失败都靠它撑住版面，绝不出现白洞。
 *
 * 手机端仍保留首字兜底：RemoteImage 的三级回退最后一级就是它，
 * 少了这一级，图床抽风时整屏卡片会变成一个空框。
 */
@Composable
fun CoverBlock(
    title: String,
    modifier: Modifier = Modifier,
    corner: Dp = 12.dp,
) {
    val base = coverColorOf(title)
    Box(
        modifier
            .clip(RoundedCornerShape(corner))
            .background(Brush.verticalGradient(listOf(base, base.copy(alpha = 0.55f)))),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = title.trim().take(1).ifBlank { stringResource(R.string.core_cover_fallback) },
            style = ModaType.Title,
            color = Color.White.copy(alpha = 0.8f),
        )
    }
}

/** 角标（VIP / 预告 / 接口返回的 badge）。字号走 Caption，压在海报角落时不抢主体 */
@Composable
fun Badge(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = ModaColors.Accent,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(text, style = ModaType.Caption, color = Color.White)
    }
}

/**
 * 通用按钮。触摸端不需要焦点态，用 material3 的 Button 拿到水波纹与最小触达尺寸（48dp）。
 *
 * 签名刻意与 TV 端 components/Common.kt 的 ActionButton 保持一致（可用的子集）：
 * T3/T6 的弹窗与库页是照着 TV 端调用点写的，少一个参数就会挂。
 * 焦点相关的参数（focusedScale）在手机端没有存在理由，没有移植；
 * [labelSize] 保留是因为授权门那几颗按钮按它放大字号（"一屏只有两个按钮"的场景）。
 */
@Composable
fun ActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
    /** 标签字号：null 表示跟随 ModaType.Body 的手机正文刻度 */
    labelSize: TextUnit? = null,
) {
    val labelStyle = if (labelSize != null) ModaType.Body.copy(fontSize = labelSize) else ModaType.Body
    if (primary) {
        Button(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = ModaColors.Accent,
                contentColor = Color.White,
                disabledContainerColor = ModaColors.SurfaceHigh,
                disabledContentColor = ModaColors.TextDim,
            ),
        ) {
            Text(text, style = labelStyle, color = LocalContentColor.current, maxLines = 1)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, ModaColors.SurfaceHigh),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = ModaColors.Text,
                disabledContentColor = ModaColors.TextDim,
            ),
        ) {
            Text(text, style = labelStyle, color = LocalContentColor.current, maxLines = 1)
        }
    }
}

@Composable
fun LoadingState(
    // 默认值必须走资源：写死的中文在英文系统上会原样蹦出来
    text: String = stringResource(R.string.core_loading),
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(color = ModaColors.Accent, modifier = Modifier.size(36.dp))
        Spacer(Modifier.height(14.dp))
        Text(text, style = ModaType.Body, color = ModaColors.TextDim, textAlign = TextAlign.Center)
    }
}

@Composable
fun ErrorState(
    message: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    Column(
        modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.core_load_failed), style = ModaType.Heading, color = ModaColors.Danger)
        Spacer(Modifier.height(8.dp))
        Text(
            text = message,
            style = ModaType.Body,
            color = ModaColors.TextDim,
            textAlign = TextAlign.Center,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
        if (onRetry != null) {
            Spacer(Modifier.height(20.dp))
            ActionButton(text = stringResource(R.string.core_retry), onClick = onRetry, primary = true)
        }
    }
}

@Composable
fun EmptyState(
    message: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    Column(
        modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = message,
            style = ModaType.Body,
            color = ModaColors.TextDim,
            textAlign = TextAlign.Center,
        )
        if (onRetry != null) {
            Spacer(Modifier.height(20.dp))
            ActionButton(text = stringResource(R.string.core_reload), onClick = onRetry, primary = true)
        }
    }
}

/** 小标题 + 内容的分隔文字，行标题与详情里的区块标题共用 */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = ModaType.Heading, color = ModaColors.Text, modifier = modifier)
}
