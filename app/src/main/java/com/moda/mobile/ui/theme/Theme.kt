package com.moda.mobile.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp

/**
 * 颜色值与 TV 端逐字一致：同一套配色在两个端上是同一份设计，
 * 改一处就必须两边一起改，否则「同一个应用」在两块屏幕上会是两种蓝。
 * Accent/AccentSoft 的对比度是按白字 4.5:1 这条线挑的，别凭感觉调亮。
 */
object ModaColors {
    val Bg = Color(0xFF0B0B0F)
    val Surface = Color(0xFF15161C)
    val SurfaceHigh = Color(0xFF1F2129)
    /** 基础蓝：白字压上去 5.67:1 合格；原先 #3D7BFF 只有 3.84:1，白字看不清 */
    val Accent = Color(0xFF2A5FD6)
    /** 按下/选中时略亮的蓝：白字 5.02:1 仍合格，保留一点按下反馈 */
    val AccentSoft = Color(0xFF3568DE)
    val Text = Color(0xFFF2F3F7)
    val TextDim = Color(0xFF9AA0AE)
    /** TV 端同名颜色用于焦点框；手机端没有焦点态，留给文字/图标的强调白 */
    val Focus = Color(0xFFFFFFFF)
    val Danger = Color(0xFFFF6B6B)
}

private val scheme = darkColorScheme(
    primary = ModaColors.Accent,
    onPrimary = Color.White,
    background = ModaColors.Bg,
    onBackground = ModaColors.Text,
    surface = ModaColors.Surface,
    onSurface = ModaColors.Text,
)

/**
 * 字号按手机重算：TV 端 44/28/20/16sp 是按三米外观看定的，
 * 搬到 6 寸屏上会大到一屏放不下两行正文。22/18/15/12 是手机竖屏阅读的常见刻度，
 * 与 TV 端的关系不是等比缩放（等比会得到约 20/13/9/7，Caption 会掉到不可读）。
 */
object ModaType {
    val Title = TextStyle(fontSize = 22.sp, color = ModaColors.Text)
    val Heading = TextStyle(fontSize = 18.sp, color = ModaColors.Text)
    val Body = TextStyle(fontSize = 15.sp, color = ModaColors.Text)
    val Caption = TextStyle(fontSize = 12.sp, color = ModaColors.TextDim)
}

@Composable
fun ModaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
