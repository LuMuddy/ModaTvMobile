package com.moda.mobile.ui.player

import android.view.View
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * 让**面板自己那个窗口**也进入沉浸式。
 *
 * 沉浸式是按窗口生效的：播放页 hide(systemBars()) 作用在 Activity 的主窗口上，
 * 而七个底部面板（ModalBottomSheet = 一个 Dialog 窗口）与选集转盘（Dialog）各自是独立窗口。
 * 那个窗口一拿到焦点，系统就按它自己的默认设置把状态栏与导航栏显示出来——
 * 横屏时导航栏还是一条贴右边的竖栏，正好压在面板右边缘上。
 * 现象就是"点开画面比例，顶上冒出时间电量、右边冒出一条三键栏，还盖住面板右下角"。
 *
 * 所以面板内容里要再 hide 一次，作用在它所属的 dialog 窗口上。
 * 拿不到 dialog 窗口（不是 Dialog 形态）时什么都不做：这层是锦上添花，不能因为它没生效就崩。
 */
@Composable
fun HideSystemBarsInDialogWindow() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = view.findDialogWindow()
        if (window == null) {
            onDispose { }
        } else {
            WindowInsetsControllerCompat(window, window.decorView)
                .hide(WindowInsetsCompat.Type.systemBars())
            onDispose { }
        }
    }
}

/**
 * 往上找实现 [DialogWindowProvider] 的那一层（Dialog 的 DecorView），取它的 Window。
 * Compose 的内容不在窗口根节点上，所以要顺着 parent 链爬，不能只看直接父级。
 */
private fun View.findDialogWindow(): Window? {
    var node: View? = this
    while (node != null) {
        if (node is DialogWindowProvider) return node.window
        node = node.parent as? View
    }
    return null
}
