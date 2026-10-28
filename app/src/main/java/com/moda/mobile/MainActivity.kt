package com.moda.mobile

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.moda.mobile.ui.AppRoot
import com.moda.mobile.ui.theme.ModaColors
import com.moda.mobile.ui.theme.ModaTheme

/**
 * 手机端唯一的 Activity。
 *
 * 与 TV 端的三处差别（都是「能拉下通知栏」这个要求的直接结果）：
 *  1. 不做常驻整屏沉浸：TV 端在 onCreate 与每次 onWindowFocusChanged 里都 hide 一遍系统栏，
 *     手机上那样做的代价是下拉通知栏失效、手势条被吞、切后台回来还得再 hide 一次。
 *     这里只开 edge-to-edge（内容画到系统栏底下），系统栏本身照常显示、照常可拉。
 *  2. 系统栏底色跟随主题深色底：状态栏 / 导航栏设成与页面同一个底色（ModaColors.Bg），
 *     图标用浅色。不这么做的话，深色页面上方会横一条浅色系统栏，第一眼像没适配。
 *  3. 播放页才进全屏：那是播放器自己的事（它进入横屏全屏并隐藏系统栏，退出时恢复），
 *     Activity 层不预先锁死任何沉浸状态，否则退出播放页回不到带状态栏的正常界面。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureWindow()

        setContent {
            ModaTheme {
                // 先铺底色，避免首帧组合前出现白闪
                Box(Modifier.fillMaxSize().background(ModaColors.Bg)) {
                    AppRoot()
                }
            }
        }
    }

    /**
     * 窗口配置只做一次，且不做任何「隐藏系统栏」的动作。
     *
     * setDecorFitsSystemWindows(false) 之后由各屏自己让位系统栏：
     * 一级页靠 Scaffold 的 innerPadding，压栈页靠 safeDrawingPadding，播放页整屏不避让。
     * 这样内容可以画到栏底下（首页海报、播放页画面），而需要交互的按钮不会落在手势区里。
     */
    // statusBarColor / navigationBarColor 在 compileSdk 35 上已标记废弃，
    // 但 targetSdk 34 的设备上它们仍是唯一能让系统栏跟随主题底色的手段：
    // 只用透明 + 内容垫底的话，API 24~29 的导航栏会留一条系统默认灰。
    @Suppress("DEPRECATION")
    private fun configureWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // 系统栏底色 = 主题底色。edge-to-edge 下这两行只在 targetSdk < 35 有意义，
        // 35 起系统强制透明并由内容自己垫底；保留它们是为了 API 24~34 的设备观感一致。
        val barColor = ModaColors.Bg.toArgb()
        window.statusBarColor = barColor
        window.navigationBarColor = barColor

        // 深色主题 → 系统栏图标必须是浅色，否则黑底黑图标等于看不见
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        // 搜索页要软键盘把内容顶上去（而不是盖住列表）：adjustResize 是 API < 30 上
        // 唯一能让窗口真正让位的方式；API 30+ 走 WindowInsets.ime，由搜索页用 imePadding 收。
        // 放在代码里而不是只写在 Manifest：本 Activity 是唯一入口，写在这里不会漏配。
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }
}
