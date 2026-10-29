package com.moda.mobile

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.moda.mobile.crash.CrashHandler
import com.moda.mobile.crash.CrashLog
import com.moda.mobile.data.local.StoragePromptStore
import com.moda.mobile.data.local.StoragePromptStoreImpl
import com.moda.mobile.ui.AppRoot
import com.moda.mobile.ui.StorageGuideDialog
import com.moda.mobile.ui.theme.ModaColors
import com.moda.mobile.ui.theme.ModaTheme
import kotlinx.coroutines.launch

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

    /**
     * 存储权限：崩溃日志要写到 /sdcard/crash，那是一个别的应用也看得到的目录。
     *
     * 回调里不做任何事——不弹 Toast、不写状态位。日志能不能落盘不取决于这次申请记没记住，
     * 而取决于写入那一刻 CrashLog 判出的实际可写目录（见 crash/CrashLog.kt 的 dir()）：
     * 授了就落 /sdcard/crash，没授就落应用自己的外部目录，两条路都有日志可看。
     */
    private val storagePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* 结果不改变任何界面状态，见上 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureWindow()
        requestStoragePermissionIfPossible()

        setContent {
            ModaTheme {
                // 先铺底色，避免首帧组合前出现白闪
                Box(Modifier.fillMaxSize().background(ModaColors.Bg)) {
                    AppRoot()
                }
                // 挂在 AppRoot 之外：授权门挡在前面时它也得能提示
                //（未授权的机器同样会崩，日志一样要落盘）
                StorageGuideHost()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 用户刚从设置页打开「所有文件访问」回来时，本进程启动时那次判定还是"没权限"，
        // 处理器因此没装（ModaApp.onCreate 只在有权限时装）。这里补一次：
        // 不补的话，授权之后到下次冷启动之间的崩溃一条日志都不会留，而用户恰恰是
        // 为了"能上报崩溃"才去授权的，这段空窗正好把他最想复现的那次崩掉。
        // install 内部有 CAS，重复调用是安全的。
        if (CrashLog.permitted(this)) CrashHandler.install(this)
    }

    /**
     * 启动时的存储引导：Android 11+ 走「所有文件访问」的说明书式弹窗。
     *
     * 判定三件事：系统确实需要这个权限（11+）、现在还没给、这个版本还没提示过。
     * 三者任一不成立组合里就不出现——尤其第三条：这是跳系统设置页的重活，每次冷启动都弹
     * 会变成骚扰，同一个 versionCode 只问一次（判定存在 moda_storage_prompt）。
     */
    @Composable
    private fun StorageGuideHost() {
        val context = LocalContext.current
        val store: StoragePromptStore = remember(context) {
            StoragePromptStoreImpl.create(context.applicationContext)
        }
        val scope = rememberCoroutineScope()
        var visible by remember { mutableStateOf(false) }

        LaunchedEffect(store) {
            if (!needsAllFilesAccess()) return@LaunchedEffect
            // 读不出来（IO 异常）就当没提示过：宁可多问一次，也不要让用户永远看不到这条路
            val acked = runCatching { store.acknowledgedVersion() }.getOrDefault(0)
            visible = acked != BuildConfig.VERSION_CODE
        }

        if (!visible) return

        // 记住「这个版本问过了」只有一种写法：读完就写。点哪颗按钮都写——
        // 用户点「以后再说」也是给了答案，下一次冷启动再弹就是在无视他的选择
        val remember = {
            visible = false
            scope.launch { runCatching { store.acknowledge(BuildConfig.VERSION_CODE) } }
            Unit
        }

        StorageGuideDialog(
            onConfirm = {
                remember()
                openAllFilesAccessSettings(this)
            },
            onDismiss = remember,
        )
    }

    /** Android 11+ 且尚未获得「所有文件访问」才需要引导；低版本走 WRITE_EXTERNAL_STORAGE 运行时权限 */
    private fun needsAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()

    /**
     * 跳「所有文件访问」设置页。
     *
     * 先试带包的 ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION（直接落在本应用的开关上），
     * 个别 ROM 不认这个 action，再退到整个应用的列表页——退一步用户还得自己找一下，
     * 但总好过点了没反应。
     */
    private fun openAllFilesAccessSettings(context: Context) {
        val direct = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.fromParts("package", context.packageName, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(direct)
            return
        } catch (_: Exception) {
            // 下面再试列表页
        }
        try {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (_: Exception) {
            // 两个都打不开（极个别裁剪过的 ROM）：弹窗已经记住了「问过」，日志走应用私有目录
        }
    }

    /**
     * 启动就申请一次存储权限（用户要求「打开的时候申请一下」）。
     *
     * 只在一拿到就真能用的版本上发起：WRITE_EXTERNAL_STORAGE 从 Android 11 起对
     * targetSdk >= 30 的应用恒为拒绝，弹了也只是闪一下或者干脆不弹，
     * 用户会以为申请失败——那种"申请了但什么也没发生"比不申请更让人困惑。
     * 高版本走 CrashLog 的降级目录（/sdcard/Android/data/<包名>/files/crash），不需要权限。
     */
    private fun requestStoragePermissionIfPossible() {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.Q) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
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
