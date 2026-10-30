package com.moda.mobile.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.moda.mobile.BuildConfig
import com.moda.mobile.R
import com.moda.mobile.crash.CrashReporter
import com.moda.mobile.data.Repo
import com.moda.mobile.data.local.CopyrightNoticeStoreImpl
import com.moda.mobile.data.local.SearchHistoryStoreImpl
import com.moda.mobile.model.LibraryEntry
import com.moda.mobile.ui.components.appErrorText
import com.moda.mobile.ui.player.PlaybackSession
import com.moda.mobile.ui.theme.ModaColors
import com.moda.mobile.ui.theme.ModaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 全应用唯一入口：先过授权门，再按栈顶渲染。
 *
 * 三条分支是有意排成「先问授权态读没读到、再问授没授权」的，顺序不能换：
 * 盘上的会话读出来之前 session 也是 null，先问授权就会在已授权的机器上先闪一屏授权框。
 */
@Composable
fun AppRoot() {
    val context = LocalContext.current
    // ViewModel 必须建在这一层：门与内容互相切换时它不能跟着重建，
    // 否则切一次分支就等于清空路由栈、重读一遍盘上的会话。
    val vm: MainViewModel = viewModel { MainViewModel(context.applicationContext) }
    val session by vm.authSession.collectAsStateWithLifecycle()
    val exitApp: () -> Unit = { context.exitApp() }

    // —— 下面三样是「更新检查」的全部运行时依赖，故意挂在授权门之外 ——
    //
    // 它们原来长在 AuthorizedContent 里，只有已授权、界面真的上屏了才跑。
    // 可授权过期的那批设备恰恰是最需要新版的人：他们卡在授权门上，进不去
    //「设置 → 关于」，也就没有第二条手动检查的路，永远等不到升级。
    // 所以检查、进度、安装回流观察器都提到这一层：授权态一读出来就位，
    // 门开着还是关着都一样。
    //
    // 一次冷启动只能打一次 GitHub：下面这一份是全应用唯一的启动检查，
    // AuthorizedContent 里不再有第二份。留两份 = 冷启动打两次 API，
    // 两次都可能改 updateUi、互相覆盖。
    LaunchedEffect(Unit) { vm.checkUpdate(manual = false) }

    // 下载进度是 StateFlow（回调在 IO 线程，见 MainViewModel.updateProgress），
    // 组合里 collectAsState 读，天然回到主线程。
    val updateProgress by vm.updateProgress.collectAsStateWithLifecycle()

    // 从系统「安装未知来源 / 安装确认」页回来要续装：那是一个独立的 Activity，
    // 回来时组合不会重建，只有 ON_RESUME 能告诉我们"用户已经回来了"。
    // 挂在门这一层是为了让未授权时弹出的更新提示也能走完安装回流。
    // onDispose 必须反注册：不摘的话每次重组都往 Activity 上多挂一个 observer，
    // 一路累加（表现为一次回到前台弹出好几个安装器）。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.onReturnedFromForeground()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    when {
        // a) 还不知授权态：只铺底色，什么都不画——不画门，也不画内容。
        // 这一段是冷启动的第一两帧，画门等于给已授权的机器看一眼「需要授权」。
        !vm.authReady -> {
            // 授权态还没定的这两帧里，返回键必须吞掉：不吞就走系统默认的 finish。
            // 已授权的机器冷启动按返回，用户看到的是「应用闪退」而不是他熟悉的「确认退出」，
            // 而这种只发生在两帧内的闪退事后极难对上号。窗口极短，再按一次就落到正常界面。
            BackHandler(enabled = true) { /* 授权态未定，先吞掉 */ }
            Box(Modifier.fillMaxSize().background(ModaColors.Bg))
        }

        // b) 确定未授权：整屏只有门。Dest 的分支一个都不进组合——
        // 不是「画出来再用遮罩盖住」：盖住的内容照样在组合里、照样会发请求。
        session == null -> AuthGateScreen(
            // 扫码是主路径：进屏自动取码、每 1.5s 轮询一次；授权码只在 need_code / 取码失败时补位
            onRequestQr = vm::startQr,
            onPollQr = vm::pollQr,
            onActivate = vm::activate,
            // 退出走 AppRoot 里已有的那一份拿法，不另造退出机制
            onExit = exitApp,
        )

        // c) 已授权：正常界面。授权成功时 session 变非空，分支自己切到这一条；
        // 首页由 MainViewModel.applyAuthorized() 里既有的 loadHome() 装载，这里不做任何跳转。
        else -> AuthorizedContent(vm)
    }

    // 授权门外的挂载点：a) 授权态未定、b) 确定未授权——这两条分支本来就不显示
    // 版权弹窗，所以 suppressed 恒为 false。已授权那条由 AuthorizedContent 自己挂
    // （它要按版权弹窗让位），两边条件互斥，同屏只会有一个 UpdateDialogHost。
    if (!vm.authReady || session == null) {
        UpdateDialogHost(vm = vm, progress = updateProgress, suppressed = false)
    }
}

/**
 * 退出应用：结束这一屏，并从最近任务里摘掉本任务。
 *
 * 为什么不是裸的 Activity.finish()：
 *  1. LocalContext 不保证就是 Activity（可能是包装它的 ContextThemeWrapper），
 *     直接强转失败时 finish() 根本不执行，表现是「点了退出，屏幕上还是那个授权页」；
 *  2. 只 finish 的话任务还留在最近任务列表里，从卡片点回来又是同一个授权页，
 *     用户同样会读成「退出了它还有个授权页」。finishAndRemoveTask 才是"退出"这一个动作。
 */
private fun Context.exitApp() {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) {
            c.finishAndRemoveTask()
            return
        }
        c = c.baseContext
    }
    (this as? Activity)?.finishAndRemoveTask()
}

/** 「再按一次退出」的判定窗口；超过它再按返回只会重新弹提示 */
private const val EXIT_BACK_WINDOW_MS = 2_000L

/**
 * 底栏「首页」的双击窗口。
 *
 * 400ms 是 Android 上双击的惯用上限（ViewConfiguration.getDoubleTapTimeout() 是 300ms，
 * 这里放宽到 400ms：底栏是屏幕最底部、最容易点偏的一块区域，按 300ms 卡会让"想刷新"
 * 的用户反复失败）。窗口内第二次点击才算双击，窗口外一律按单击处理（只导航）。
 */
private const val HOME_DOUBLE_TAP_MS = 400L

/**
 * 底栏占位高度里「内容」那一截。
 *
 * material3 1.4 的 NavigationBar 高度是写死的 NavigationBarKt.NavigationBarHeight
 * （= NavigationBarTokens.TallContainerHeight = 80dp），公开 API 只暴露了 windowInsets，
 * 所以这一截只能跟着写。占位必须与真实底栏**同高**：多 1dp 会在内容底下空一条缝，
 * 少 1dp 会把最后一行内容压在底栏下面。
 */
private val NAV_BAR_CONTENT_HEIGHT = 80.dp

/**
 * 底栏显隐的动画时长（高度收放与滑出/淡出共用这一个值）。
 *
 * 两处必须同源：它们是同一件事的两个侧面，长短不一就会出现「空间已经收完、底栏还悬在半空」，
 * 看上去像被一刀裁掉。
 */
private const val NAV_BAR_ANIM_MS = 220

/**
 * 已授权后的正常界面：路由栈在 MainViewModel 里，这里只按栈顶渲染。
 *
 * 手机端与 TV 端最大的差别在**壳**：底部三格导航承载一级页（首页 / 收藏 / 设置），
 * 搜索、历史、详情、播放、设置子页、已看完一律是压栈的全屏页（不显示底栏）。
 * 搜索与历史的入口在首页顶部工具栏（契约 §6）：它们是"从首页点进去的页"，
 * 不是并列的一级页，所以没有自己常驻的 tab，也不占底栏宽度。
 * 路由仍沿用 TV 端的 push/pop/current，不引 navigation-compose——见契约 §0.2。
 */
@Composable
private fun AuthorizedContent(vm: MainViewModel) {
    val context = LocalContext.current

    // 一级页按返回不直接退出应用：手机上没有"确认退出"这个系统约定，但有「连按两次返回退出」，
    // 它是三个键位里唯一一个既不打断连续操作、又不会误杀的方案：
    //  - 弹确认框：每次想退出都要点两下（关弹窗 + 真退），而用户按返回多半是手滑；
    //  - 直接 finish：误触就闪退，用户会以为应用崩了。
    // 所以这里是「提示 + 2 秒窗口」；TV 端那套 ExitConfirmDialog（遥控器没有提示条、
    // 焦点必须先落在「取消」上）在触摸端没有存在理由。
    //
    // 提示用 Snackbar 而不是 Toast：Android 12+ 的系统 Toast 会强制带应用图标、白底胶囊、
    // 固定弹在屏幕底部——深色沉浸界面里又突兀又压在底栏上，用户第一反应是"这是谁弹的"。
    // Snackbar 挂在 Scaffold 上（底栏之上、同一套主题色），样式与界面一致，也不需要系统权限。
    var lastBackAt by remember { mutableLongStateOf(0L) }
    val exitHint = stringResource(R.string.m_nav_exit_hint)
    val snackbarHostState = remember { SnackbarHostState() }
    // showSnackbar 是挂起函数：必须在协程里起，不能在 BackHandler 里同步等它（会阻塞返回键回调）
    val snackbarScope = rememberCoroutineScope()
    BackHandler(enabled = true) {
        if (vm.stack.size > 1) {
            // 从播放页退出必须先补一次进度落盘，再出栈。
            //
            // 顺序不能反：pop() 会把 player 重置成 Loading，而 flushHistory() 是从当前
            // player 快照里取 cid/vid 的——先 pop 再 flush 等于什么都没写。
            // 手机端返回手势与三键导航都走这条全局通路，所以这一笔必须在这里补：
            // 否则最后一段进度（5 秒节流窗口之后的那些）根本没落盘，
            // 首页「继续观看」看到的还是上一次写的旧位置。
            //
            // 三条退出路径现在都覆盖到了：
            //  1. 系统返回手势/返回键：这里；2. 播放页内返回按钮：它自己的 onBack 已 flush；
            //  3. 播完/切集：onEnded、selectEpisodeFromPlayer 在换掉快照之前就 writeHistory 了。
            // 重复 flush 是安全的：它内部有 updatedAt 与 completedVids 两道闸门，
            // 同一个位置写第二次只是把 updatedAt 刷新一下，不会产生重复记录。
            if (vm.current is Dest.Player) vm.flushHistory()
            vm.pop()
        } else {
            val now = SystemClock.elapsedRealtime()
            if (now - lastBackAt <= EXIT_BACK_WINDOW_MS) {
                context.exitApp()
            } else {
                lastBackAt = now
                // 先撤掉上一条再弹新的：连按返回时不该看到两条排队（第二次返回是退出，
                // 用户根本没机会看完第二条）；currentSnackbarData 为空时 dismiss 是安全的 no-op
                snackbarHostState.currentSnackbarData?.dismiss()
                snackbarScope.launch {
                    snackbarHostState.showSnackbar(exitHint, duration = SnackbarDuration.Short)
                }
            }
        }
    }

    // 版权声明：每次版本升级后（含首次安装）启动时自动弹一次。
    // 判据是「上次确认过的 versionCode」，不是布尔开关——升级后版本号天然不同，
    // 不需要任何「记得重置标记」的额外逻辑。
    // 初始值 false：不能默认 true，否则首次安装的用户在读完 DataStore 之前就看到了弹窗闪烁；
    // 也不能默认 true 后再关，那会让弹窗一闪而过。宁可晚一帧出现。
    val noticeStore = remember(context) {
        CopyrightNoticeStoreImpl.create(context.applicationContext)
    }
    var showCopyright by remember { mutableStateOf(false) }
    // 设置页手动打开的那一次不受版本判定约束，两条来源共用同一个弹窗
    var manualCopyright by remember { mutableStateOf(false) }
    val noticeScope = rememberCoroutineScope()
    LaunchedEffect(noticeStore) {
        // 读失败（极少数 IO 异常）就当没确认过，宁可多弹一次也不漏掉声明
        val acked = runCatching { noticeStore.acknowledgedVersion() }.getOrDefault(0)
        showCopyright = acked != BuildConfig.VERSION_CODE
    }

    // 启动检查与安装回流观察器已经提到 AppRoot（授权门外），这里不再有第二份。
    // 下载进度还得自己收一份：AuthorizedContent 的签名保持 (vm) 不变，
    // 版权状态留在本层 remember，不为了传一个进度值把既有逻辑牵上去。
    // StateFlow 多一个收集者只是各读各的当前值，不会重放历史、也不会互相覆盖。
    val updateProgress by vm.updateProgress.collectAsStateWithLifecycle()

    val dest = vm.current
    // 底栏显隐的唯一判据：栈顶是不是导航栏里的三项之一。
    // 用 [RootTab.of] 而不是在渲染处散写 when：底栏有没有、哪一项高亮、返回键退到哪，
    // 三件事必须同时成立，分散判断早晚有一处对不上。
    // 搜索 / 历史刻意不在这三项里：它们由首页工具栏压栈进来，返回键走 pop() 那条既有分支。
    val rootTab = RootTab.of(dest)

    // 播放引擎的释放时机只跟「现在在哪个页面」有关，跟时限无关。
    // 详情 ↔ 播放是同一次观看的两端：引擎必须活到这两页都走完，所以只要还停在其中一页就不释放；
    // 一旦到了首页/收藏/设置/搜索等页面，这次观看就真的结束了，立刻释放
    // （声音在 detach 那一刻就已经停了，这里回收的是内存与解码器）。
    // 不能靠时限：播放页要等 VM 拉完地址（episodeInfo → pickBestTier → playLink）才 acquire，
    // 这个网络往返可能比任何固定宽限期都长。
    LaunchedEffect(dest) {
        if (dest !is Dest.Player && dest !is Dest.Detail) PlaybackSession.releaseIfDetached()
    }

    // 底栏显隐只由首页上报（其它两个一级页没有可滚动的网格，没人会报）。
    // 状态必须放在 Scaffold 这一层：底栏是它的 bottomBar，动画改的是它的位置。
    var navBarVisible by remember { mutableStateOf(true) }
    // 换一级页时归位。首页滚到深处把底栏藏了之后跑去收藏/设置，那两页没有"上滑"这条路
    // 可以把底栏叫回来，不归位就会停在一条永远不再出现的底栏上。
    LaunchedEffect(rootTab) { navBarVisible = true }

    // 底栏两截高度：
    // - navBarInset：它自己吃的系统栏 inset（NavigationBar 内部是
    //   windowInsetsPadding(windowInsets).height(80dp)，所以总高恰为「inset + 80dp」）。
    //   从同一处 NavigationBarDefaults.windowInsets 现算，手势条与三键导航共用一套式子；
    //   MainActivity 开了 edge-to-edge，这份 inset 非零，不能当成 0。
    // - navBarHeight：可见时的总高。
    val navBarInset = NavigationBarDefaults.windowInsets.asPaddingValues().calculateBottomPadding()
    val navBarHeight = NAV_BAR_CONTENT_HEIGHT + navBarInset

    // 占位高度跟着可见性**平滑收放**：既不是恒定，也不是跳变。
    // Scaffold 的 innerPadding 由 bottomBar 的实际高度驱动，所以内容会跟着这一截一起内扩/回缩——
    // 底栏滑出后不会在底下留一条 80dp 的黑带，最后一行的下方只剩安全区。
    // 收回的终点取 navBarInset 而不是 0.dp：inset 那一截是手势条/三键导航的安全区，
    // 收成 0 会把最后一行内容压在系统手势区底下（那不是"空"，但是点不到/被手势抢走）。
    val reserve by animateDpAsState(
        targetValue = if (navBarVisible) navBarHeight else navBarInset,
        animationSpec = tween(NAV_BAR_ANIM_MS),
        label = "nav-bar-reserve",
    )

    Box(Modifier.fillMaxSize().background(ModaColors.Bg)) {
        if (rootTab != null) {
            Scaffold(
                containerColor = ModaColors.Bg,
                // 「再按一次退出」的提示宿主。位置取 Scaffold 默认（内容区底部、底栏之上），
                // 所以它不会盖住底栏的三项；颜色显式给主题色——Snackbar 默认的 inverseSurface
                // 是一块浅灰，在深色界面上同样突兀（只是比系统 Toast 强一点）。
                snackbarHost = {
                    SnackbarHost(hostState = snackbarHostState) { data ->
                        Snackbar(
                            snackbarData = data,
                            containerColor = ModaColors.SurfaceHigh,
                            contentColor = ModaColors.Text,
                        )
                    }
                },
                // 只有一级页有底栏；Scaffold 的 innerPadding 顺便把状态栏/导航栏让开
                bottomBar = {
                    // 占位盒高度 = reserve（可见 = 80dp + inset，隐藏 = 只剩 inset，中间是 220ms 的收放）。
                    // Scaffold 的 innerPadding 由这个盒子的实际高度驱动：内容随收放平滑内扩，
                    // 底栏滑出后不留黑带；恢复时内容平滑回到原位。全程不需要手动加 padding。
                    Box(Modifier.fillMaxWidth().height(reserve)) {
                        AnimatedVisibility(
                            visible = navBarVisible,
                            modifier = Modifier.align(Alignment.BottomCenter),
                            // 与高度收放**同一时长（220ms）+ 淡入淡出**：空间收到底的那一刻，
                            // 底栏也必须正好滑走并淡掉；只做位移而不同步时长就会变成
                            // 「底栏还悬在半空、空间已经归零」——看起来像被一刀裁掉。
                            // 方向一律 +it：默认是从上方掉下来 / 往上收起（抽屉方向），
                            // 底栏该走屏幕下缘。
                            enter = slideInVertically(
                                animationSpec = tween(NAV_BAR_ANIM_MS),
                                initialOffsetY = { it },
                            ) + fadeIn(animationSpec = tween(NAV_BAR_ANIM_MS)),
                            exit = slideOutVertically(
                                animationSpec = tween(NAV_BAR_ANIM_MS),
                                targetOffsetY = { it },
                            ) + fadeOut(animationSpec = tween(NAV_BAR_ANIM_MS)),
                        ) {
                            // 收藏 / 设置：单击即切页。首页：单击只回首页，双击才整页刷新
                            // （refreshHome 与工具栏刷新按钮同一条通路，19 个 tab 一起拉）。
                            // 隐藏后这棵子树不在组合里，「双击隐藏区域」根本无从点起——
                            // 没有底栏可点，也就不该有刷新；恢复只靠页面上滑。
                            ModaBottomBar(
                                selected = rootTab,
                                onSelect = { tab -> vm.openRoot(tab) },
                                onRefreshHome = { vm.refreshHome() },
                            )
                        }
                    }
                },
            ) { inner ->
                Box(Modifier.fillMaxSize().padding(inner)) {
                    Route(
                        vm = vm,
                        dest = dest,
                        onOpenCopyright = { manualCopyright = true },
                        onNavBarVisibleChange = { navBarVisible = it },
                    )
                }
            }
        } else {
            // 压栈页：没有底栏。搜索 / 历史 / 详情 / 播放 / 设置子页 / 已看完都落在这一支。
            // 播放页整屏交给播放器自己（它进入横向全屏、控制层浮在画面上），
            // 其余压栈页让开状态栏与手势区，否则标题会被状态栏压住、返回按钮落在手势区里。
            val pageModifier = when {
                dest is Dest.Player -> Modifier.fillMaxSize()
                // 详情页**自己**让状态栏：它顶部是一块贴顶的视频（播放器节点不参与列排版），
                // 让外层统一 padding 与让本页 padding 是同一件事，但只有后者能保证
                // "内容列与播放器节点拿到同一个数"——两处各写一次 inset 的算法，
                // 迟早一处生效一处不生效（实测就是画面盖住了状态栏）。见 DetailScreen 的 statusBarTop。
                dest is Dest.Detail -> Modifier.fillMaxSize()
                else -> Modifier.fillMaxSize().safeDrawingPadding()
            }
            Box(pageModifier) {
                Route(vm, dest, onOpenCopyright = { manualCopyright = true })
            }
        }
    }

    if (showCopyright || manualCopyright) {
        CopyrightNoticeDialog(
            onDismiss = {
                val wasUpgradePrompt = showCopyright && !manualCopyright
                showCopyright = false
                manualCopyright = false
                // 只有启动自动弹出的那次才记「已确认当前版本」；
                // 从设置页主动重看的，不该影响下次升级的提示。
                if (wasUpgradePrompt) {
                    noticeScope.launch {
                        runCatching { noticeStore.acknowledge(BuildConfig.VERSION_CODE) }
                    }
                }
            },
        )
    }

    // 渲染规则与授权门外那一份完全共用（见 UpdateDialogHost），
    // 唯一区别是这里要按版权弹窗让位。
    UpdateDialogHost(
        vm = vm,
        progress = updateProgress,
        suppressed = showCopyright || manualCopyright,
    )
}

/**
 * 更新弹窗的唯一渲染出口：授权门内外共用这一份。
 *
 * 为什么要有这个函数：门内门外都要弹，但门内还多一条「版权弹窗优先」的护栏。
 * 把渲染规则收在一处，两条挂载点就不会一边改了另一边忘了——「没检测到就没有」
 * 这条臂漏改一次，Latest 就会被 else 接住，凭空弹一个只剩「以后再说」的空弹窗。
 *
 * Idle / Latest / Checking 不渲染：没检测到就没有，也不造一个"已是最新"的弹窗
 * 来打断正在看剧的人。
 *
 * suppressed 承载的是「版权弹窗在屏时更新弹窗让位」：两个 Dialog 是两个窗口，
 * 同时上屏就是两层遮罩互相盖、返回键被谁吃掉说不清（启动首帧恰好"升级提示"与
 * "发现新版本"撞在一起）。版权弹窗关掉后 updateUi 还是 Available，
 * 那一帧自然就会补上，不需要任何排队逻辑。
 *
 * 「立即安装」也走 onDownload：VM 见到已下好的包会跳过下载直接再试一次安装，
 * 这样"重试安装"与"首次下载"共用同一个入口，UI 不必再记一个 VM 方法名。
 */
@Composable
private fun UpdateDialogHost(vm: MainViewModel, progress: UpdateProgress, suppressed: Boolean) {
    when (val update = vm.updateUi) {
        UpdateUi.Idle, UpdateUi.Latest, UpdateUi.Checking -> Unit
        else -> if (!suppressed) {
            UpdateDialog(
                state = update,
                progress = progress,
                onDownload = vm::downloadUpdate,
                onIgnore = vm::ignoreUpdate,
                onDismiss = vm::dismissUpdate,
            )
        }
    }
}

/**
 * 栈顶 → 渲染哪一屏。只做分发，任何状态都不在这里存。
 *
 * [onOpenCopyright] 从 AuthorizedContent 透传下来：版权弹窗只有一个实例、
 * 挂在 AuthorizedContent 上（它与启动自动弹出的那一次共用），子页只负责"点了"。
 */
@Composable
private fun Route(
    vm: MainViewModel,
    dest: Dest,
    onOpenCopyright: () -> Unit,
    /** 首页据此上报底栏显隐；压栈页没有底栏，默认空实现即可 */
    onNavBarVisibleChange: (Boolean) -> Unit = {},
) {
    when (dest) {
        is Dest.Home -> HomeRoute(vm, onNavBarVisibleChange)
        is Dest.Search -> SearchRoute(vm)
        is Dest.History -> HistoryRoute(vm)
        is Dest.Completed -> CompletedRoute(vm)
        is Dest.Favorites -> FavoritesRoute(vm)
        is Dest.Settings -> SettingsRoute(vm)
        // 版权声明的常驻入口在「关于」子页的最后一行，所以只有子页路由需要这个回调
        is Dest.SettingsGroup -> SettingsGroupRoute(
            vm = vm,
            group = dest.group,
            onOpenCopyright = onOpenCopyright,
        )
        is Dest.Detail -> DetailRoute(vm, dest)
        is Dest.Player -> PlayerRoute(vm)
    }
}

/**
 * 底部导航栏：三项一级页——首页 / 收藏 / 设置。
 *
 * 为什么只留三项（契约 §6）：搜索与历史的入口在首页顶部工具栏，它们是"从首页点进去"的页，
 * 不是并列的一级导航；五格平铺还会把底栏撑到两列网格的宽度上，且回来时手指容易落在"搜索"上。
 *
 * 用 Material3 的 NavigationBar 而不是自绘：它自带水波纹、选中指示条、
 * 以及「图标 + 文案」的触达尺寸（48dp），是触摸端唯一不用自己量尺寸的写法。
 * 三个图标都取自 material-icons-core（Home / Favorite / Settings），不依赖 icons-extended。
 */
@Composable
private fun ModaBottomBar(
    selected: RootTab,
    onSelect: (Dest) -> Unit,
    /**
     * 首页被**双击**时触发：整页刷新（与工具栏刷新按钮同一个 vm.refreshHome 通路）。
     * 带默认值是刻意的：底栏是这个函数唯一的调用点，默认值让"只想导航"的场景不必写空 lambda。
     */
    onRefreshHome: () -> Unit = {},
) {
    // 上一次点击「首页」的时刻（elapsedRealtime：不受系统时间被改动影响）。
    // 状态必须活在这一层：放进 onClick 的局部变量等于每次重组就归零，双击永远判不出来。
    var lastHomeTapAt by remember { mutableLongStateOf(0L) }
    NavigationBar(containerColor = ModaColors.Surface, contentColor = ModaColors.Text) {
        RootTab.entries.forEach { tab ->
            NavigationBarItem(
                selected = tab == selected,
                // 收藏 / 设置：单击即切页，走 vm.openRoot（先把栈收回栈底再压入目标页，
                // 于是"收藏 → 设置 → 返回"回到的是首页，而不是收藏页）。
                //
                // 首页：单击**只导航、绝不刷新**——用户反馈"每点一次首页就刷新"，
                // 而点底栏首页的常见意图是"回到首页看看"，不是"重新拉 19 个 tab"。
                // 想刷新的用户用双击（或工具栏那颗刷新按钮），语义更明确：
                //  - 窗口内第一次点击：照常 openRoot(Dest.Home)（回首页这条不能被吞掉，
                //    否则从收藏页双击首页时第一下什么都不发生）；
                //  - 窗口内第二次点击：只调 onRefreshHome，不再重复 openRoot；
                //    随后把时刻清零，避免第三次点击又被判成一次新的双击（连点变成连刷）。
                //  - 点其它 tab 时也清零：否则"首页 → 收藏 → 首页"里最后那一下会被
                //    前一次首页点击的时间戳误判成双击。
                onClick = {
                    if (tab == RootTab.Home) {
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastHomeTapAt <= HOME_DOUBLE_TAP_MS) {
                            lastHomeTapAt = 0L
                            onRefreshHome()
                        } else {
                            lastHomeTapAt = now
                            onSelect(tab.dest)
                        }
                    } else {
                        lastHomeTapAt = 0L
                        onSelect(tab.dest)
                    }
                },
                icon = { Icon(tab.icon, contentDescription = null) },
                label = { Text(stringResource(tab.labelRes), style = ModaType.Caption) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = ModaColors.Accent,
                    selectedTextColor = ModaColors.Accent,
                    unselectedIconColor = ModaColors.TextDim,
                    unselectedTextColor = ModaColors.TextDim,
                    indicatorColor = ModaColors.SurfaceHigh,
                ),
            )
        }
    }
}

/**
 * 底部导航的三项：首页 / 收藏 / 设置。
 *
 * 单独一个枚举而不是散在渲染处：顺序（首页/收藏/设置）就是需求定的顺序，
 * 高亮判定与「哪些 Dest 算一级页」共用同一份定义，不会出现"点了没高亮"的错位。
 *
 * 搜索与历史**故意不在这里**（契约 §6 改的信息架构）：它们从首页顶部工具栏压栈进入，
 * 于是 [of] 对它们返回 null，走无底栏的压栈分支，返回键走 pop()。
 * 它们也就不再需要底栏文案，m_nav_search / m_nav_history 两个键一并删掉。
 */
private enum class RootTab(
    val dest: Dest,
    @param:StringRes val labelRes: Int,
    val icon: ImageVector,
) {
    Home(Dest.Home, R.string.m_nav_home, Icons.Filled.Home),
    Favorites(Dest.Favorites, R.string.m_nav_favorites, Icons.Filled.Favorite),
    Settings(Dest.Settings, R.string.m_nav_settings, Icons.Filled.Settings),
    ;

    companion object {
        /**
         * 栈顶不是这三项之一就返回 null == 不显示底栏。
         * 覆盖：搜索 / 历史 / 详情 / 播放 / 设置子页 / 已看完（全部是压栈全屏页）。
         */
        fun of(dest: Dest): RootTab? = entries.firstOrNull { it.dest == dest }
    }
}

@Composable
private fun HomeRoute(vm: MainViewModel, onNavBarVisibleChange: (Boolean) -> Unit) {
    // 回到首页**不自动重拉**：本屏每次重新进组合（从详情 / 播放 / 收藏返回）都只渲染现有缓存，
    // 要不要新数据交给用户决定——手动刷新走「底栏双击首页」「工具栏刷新按钮」两条（无下拉手势），
    // 自动刷新只在冷启动判一次（MainViewModel.ensureHomeLoaded 里的 30 分钟门禁）。
    //
    // 这里原先挂着一个「回首页判超时」的 LaunchedEffect(Unit)：从二级页回来会让本屏重新进组合，
    // 那句就重跑一次，一过 30 分钟窗口就整页重拉 19 个频道并弹提示——用户看到的正是
    // 「返回首页却自己刷新了」。那个入口整个下线，判定逻辑也一并从 ViewModel 删除（不留死代码）。

    // 继续观看行直接读历史流：它有新记录就立刻出现在首页，
    // 不需要等下一次首页刷新，也不需要在这里加一个「装载」任务
    val history by vm.historyEntries.collectAsStateWithLifecycle()
    HomeScreen(
        loading = vm.homeLoading,
        // 首页屏幕收的是成品文案（它自己不认错误码），翻译这一步只在这里做一次
        error = vm.homeError?.let { appErrorText(it) },
        // 错误态重试 = 强制整页刷新：留着旧内容时用户点重试，等的就是真数据，
        // 而不是又一次被 30 分钟窗口挡回来的缓存
        // 理由串只进日志（MainViewModel 里 Log.w 打出），不上屏，所以不走 string 资源
        onRetry = { vm.refreshAllHome("error-retry") },
        onOpenDetail = { cid, title -> vm.push(Dest.Detail(cid, title)) },
        onOpenSearch = { vm.push(Dest.Search) },
        onOpenPlayer = { cid, vid, title, index ->
            vm.push(Dest.Player(cid, vid, title, index))
        },
        onOpenHistory = { vm.push(Dest.History) },
        onOpenFavorites = { vm.push(Dest.Favorites) },
        onOpenSettings = { vm.push(Dest.Settings) },
        // ---- tab 化接线 ----
        tab = vm.homeTab,
        feeds = vm.homeFeeds,
        // 工具栏时间取整页统一的那一个：它与 30 分钟门禁判的是同一个值，
        // 不随切换 tab 跳动。按 tab 各算各的 fetchedAt 只会有两个结果对不上
        pageFetchedAt = vm.homeFetchedAt,
        onSelectTab = vm::selectHomeTab,
        // 工具栏刷新按钮：19 个 tab 整页重拉（手机端没有下拉刷新，见 AGENTS.md：
        // 滚到顶再往下拽与"滚到顶"是同一条手势，误触一次就是整轮 19 个频道重拉）。与错误态重试走同一条通路，
        // 区别只在 homeError 的清理时机（refreshHome 会先把它清掉）
        onRefresh = { vm.refreshHome() },
        // 刷新在跑的 3~4 秒里内容可能与刚才完全一样（同一分钟内时间戳都不跳），
        // 刷新指示是用户唯一能看到的「这一下按对了」
        refreshing = vm.homeRefreshing,
        // 刷新结果（成功/失败）浮一条提示：整页刷新成功时内容可能一模一样，
        // 没有这句话用户判断不出这一下刷新到底有没有生效
        refreshNotice = vm.homeRefreshNotice,
        history = history,
        // 点「继续观看」直接进播放器，不经过详情页
        onResumeHistory = vm::resumeFromHistory,
        onOpenCompleted = { vm.push(Dest.Completed) },
        // ---- 用户自定义的分类顺序 ----
        // tab 行、pager 页数、initialPage、页 → tab 的映射全部以它为准
        // （推荐固定第一位由 VM 保证，首页落地页不会变）。
        tabOrder = vm.homeTabOrder,
        // 首页网格下滑 → 报 false（底栏隐藏），上滑 / 回顶 → 报 true（恢复）。
        // 其它页不上报，所以底栏在它们那里恒为可见（换一级页时还会归位一次）。
        onNavBarVisibleChange = onNavBarVisibleChange,
    )
}

/** 历史页：条目流来自 viewModelScope 里的 ViewModel，屏幕本身无状态 */
@Composable
private fun HistoryRoute(vm: MainViewModel) {
    val entries by vm.historyEntries.collectAsStateWithLifecycle()
    HistoryScreen(
        entries = entries,
        onResume = vm::resumeFromHistory,
        onDelete = vm::removeHistory,
        onClear = vm::clearHistory,
        // 「已看完」页与历史并列，入口就挂在历史页上（它不在底栏三项里）
        onOpenCompleted = { vm.push(Dest.Completed) },
        // 二级页必须有显式返回。这里与硬件返回键走同一个 vm.pop()：两条路殊途同归，
        // 不存在"按钮退一层、返回键退两层"的分叉；也不要在这里多调一次 pop（会一次退两页）。
        onBack = { vm.pop() },
    )
}

/**
 * 已看完页：条目流来自 viewModelScope 里的 ViewModel，屏幕本身无状态。
 *
 * 删除只删「这一集」而不是整部剧：这一页是按集记录的（一部剧可以看好几集），
 * 传整个 entry 而不是 cid，屏幕就不必知道这个区别。
 */
@Composable
private fun CompletedRoute(vm: MainViewModel) {
    val entries by vm.completedEntries.collectAsStateWithLifecycle()
    CompletedScreen(
        entries = entries,
        onResume = vm::resumeCompleted,
        onDelete = { entry -> vm.removeCompleted(entry.cid, entry.episodeIndex) },
        onClear = vm::clearCompleted,
        // 与历史页同一条规则：二级页左上角返回 == 硬件返回键 == vm.pop()（不重复 pop）
        onBack = { vm.pop() },
    )
}

// === 收藏模块（勿删）===
/**
 * 收藏页：与本文件其它 Route 一样只做「流 → 屏幕」的搬运。
 * 点条目进详情而不是直接播放——收藏是追剧语义，没有"该看哪一集"的答案。
 */
@Composable
private fun FavoritesRoute(vm: MainViewModel) {
    val entries by vm.favoriteEntries.collectAsStateWithLifecycle()
    FavoritesScreen(
        entries = entries,
        onOpen = { entry -> vm.push(Dest.Detail(entry.cid, entry.title)) },
        onDelete = vm::removeFavorite,
        onClear = vm::clearFavorites,
    )
}

/**
 * 设置页：授权态来自 [MainViewModel.authSession] 这条 Flow，兑换后立刻回灌到屏幕，
 * 所以不需要重启应用——屏幕上显示的授权状态就是正在生效的那一份。
 */
@Composable
private fun SettingsRoute(vm: MainViewModel) {
    val session by vm.authSession.collectAsStateWithLifecycle()
    SettingsScreen(
        session = session,
        onClearHistory = vm::clearHistory,
        onClearFavorites = vm::clearFavorites,
        onClearSearchHistory = vm::clearSearchHistory,
        onOpenGroup = { group -> vm.push(Dest.SettingsGroup(group)) },
        // 播放设置改走 VM 的写入口：写操作跑在 viewModelScope 上，
        // 用户改完立刻按返回也不会把这次写连同组合一起取消掉
        onUpdatePlayerSettings = vm::updatePlayerSettings,
    )
}

/**
 * 设置子页：只按 group 选渲染哪一段，三段共享同一份授权态与播放设置。
 *
 * 每页进入时各自从 store 读一次当前值（见 rememberPlayerSettingsHolder），
 * 所以播放子页改完返回菜单，摘要拿到的就是刚写进去的新值。
 * 前提是那次写不能被组合销毁打断——这正是下面把写接到 vm.updatePlayerSettings 的原因。
 */
@Composable
private fun SettingsGroupRoute(
    vm: MainViewModel,
    group: SettingsSectionId,
    /** 「关于」页那一行的点按；弹窗本身由 AuthorizedContent 持有，这里只做转发 */
    onOpenCopyright: () -> Unit,
) {
    val session by vm.authSession.collectAsStateWithLifecycle()
    when (group) {
        SettingsSectionId.Playback -> PlaybackSettingsSection(
            holder = rememberPlayerSettingsHolder(vm::updatePlayerSettings),
        )
        SettingsSectionId.Network -> NetworkSettingsSection(
            session = session,
            onClearHistory = vm::clearHistory,
            onClearFavorites = vm::clearFavorites,
            onClearSearchHistory = vm::clearSearchHistory,
            onClearCompleted = vm::clearCompleted,
            onClearAuth = vm::signOut,
        )
        SettingsSectionId.About -> {
            // 崩溃日志条数每次进这一页现数一次：上报成功后要**立刻**消失，
            // 等下一次冷启动才隐藏等于没做（这一行的显示条件就是"有日志"）
            val appContext = LocalContext.current.applicationContext
            var crashLogCount by remember { mutableStateOf(0) }
            LaunchedEffect(Unit) {
                crashLogCount = withContext(Dispatchers.IO) { CrashReporter.logFiles(appContext).size }
            }
            val reportCrash = rememberCrashReportAction(onLogsCleared = { crashLogCount = 0 })

            AboutSettingsSection(
                onOpenCopyright = onOpenCopyright,
                updateUi = vm.updateUi,
                onCheckUpdate = { vm.checkUpdate(manual = true) },
                crashLogCount = crashLogCount,
                onReportCrash = reportCrash,
            )
        }
        // 分类排序：顺序本身住在 VM（homeTabOrder 只读 + moveHomeTab/resetHomeTabOrder），
        // 这一页只读它、只发指令。
        //
        // onLeave 只 pop，**不刷新**：分类顺序不需要重拉数据。
        // 每页的内容是按 HomeTab 存进 feeds 的（与下标无关），顺序一变只是换了渲染次序，
        // 内容本身一条都没变；而 refreshHome 会整页重拉 19 个频道，代价与收益完全不成比例。
        SettingsSectionId.HomeTabs -> HomeTabsSection(
            order = vm.homeTabOrder,
            onMove = vm::moveHomeTab,
            onReset = vm::resetHomeTabOrder,
            onLeave = { vm.pop() },
        )
    }
}

@Composable
private fun SearchRoute(vm: MainViewModel) {
    // 搜索历史是搜索页私有的状态，不进 ViewModel：只有这一个屏幕读写，
    // 放进 VM 只会让 VM 多背一份与它其它职责无关的流。
    val context = LocalContext.current
    val historyStore = remember(context) { SearchHistoryStoreImpl.create(context.applicationContext) }
    val history by historyStore.entries.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()

    SearchScreen(
        onSearch = { query -> Repo.search(query) },
        onOpenDetail = { cid, title -> vm.push(Dest.Detail(cid, title)) },
        onBack = { vm.pop() },
        loadHotWords = { Repo.hotWords() },
        history = history,
        onRecordSearch = { word -> scope.launch { historyStore.add(word) } },
        onClearHistory = { scope.launch { historyStore.clear() } },
    )
}

@Composable
private fun DetailRoute(vm: MainViewModel, dest: Dest.Detail) {
    // detailCid 已在 push 时切到 dest.cid，所以从收藏页点进来的第一帧拿到的就是这部剧的收藏态
    val isFavorite by vm.detailIsFavorite.collectAsStateWithLifecycle()
    // 历史落点：看过这剧就把主按钮写成「继续播放 xx:xx」，并把选集滚到看过的那一集
    val resume by vm.detailResume.collectAsStateWithLifecycle()
    // 已看完的集号：选集网格右上角的「看完」角标靠它。跟着 detailCid 切流，
    // 从收藏页点进 A、返回、再点进 B 时会自动换成 B 的完成态，不会把 A 的角标带过去
    val completedIndexes by vm.detailCompletedIndexes.collectAsStateWithLifecycle()
    // 流量播放提醒：与设置 → 播放设置里那颗 Switch 是**同一条流**（同一个 DataStore 键），
    // 所以详情页里勾了「不再提醒」之后，设置页那颗开关的状态也跟着一起变
    val playerSettings by vm.playerSettings.collectAsStateWithLifecycle()
    DetailScreen(
        detail = vm.detail,
        loading = vm.detailLoading,
        error = vm.detailError,
        onTierSelect = vm::selectDetailTier,
        selectedTier = vm.detailTier,
        onBack = { vm.pop() },
        // 用接口返回的真实剧情简介；取不到就留空让详情页隐藏该行。
        // 绝不能再拿剧名顶替——那会和标题完全重复，一眼就看出来不对
        description = vm.detail?.description.orEmpty(),
        onRetry = vm::retryDetail,
        isFavorite = isFavorite,
        resume = resume,
        completedIndexes = completedIndexes,
        onToggleFavorite = {
            val series = vm.detail ?: return@DetailScreen
            vm.toggleFavorite(
                LibraryEntry(
                    cid = series.cid,
                    vid = series.episodes.firstOrNull()?.vid.orEmpty(),
                    title = series.title,
                    // 详情页宣传图（eps.meta.pic 原图）就是收藏卡最该用的图
                    cover = series.cover,
                    episodeIndex = 0,
                    positionMs = 0,
                    durationMs = 0,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        },
        // ---- 顶部小屏播放器（详情页自带的那块「真的能播」的画面）----
        // 这五个实参是它的**唯一数据入口**：不传的话 preview 恒为 null，
        // 小屏就是一格黑屏、连 loading 与错误文案都没有（真机上表现为"详情页顶部死的"）。
        // 地址由 VM 装：进页面装一次续播集（onLoadPreview 的入参是集下标，不是 cid），
        // 离开页面清掉（引擎 release 与停投在 DetailPreviewPlayer 的 onDispose 里）。
        preview = vm.detailPreview,
        previewLoading = vm.detailPreviewLoading,
        previewError = vm.detailPreviewError,
        onLoadPreview = vm::loadDetailPreview,
        onClearPreview = vm::clearDetailPreview,
        // 全屏「清晰度」面板的数据：档位表与当前档都来自 episodeInfo，
        // 与正在播的地址同一来源；切档只换地址，播放器按新 url 换源
        previewTiers = vm.detailPreviewTiers,
        previewTier = vm.detailPreviewTier,
        onSelectPreviewTier = vm::selectDetailPreviewTier,
        // 小屏弹幕：窗口只服务详情页那一格（与播放页的 danmakuWindow 分开，
        // 两者 vid 不同，混用会互相清空）。位置由播放器自己的轮询回调。
        danmaku = vm.previewDanmakuWindow,
        onEnsureDanmaku = vm::ensurePreviewDanmaku,
        // ---- 流量播放提醒（只有蜂窝才谈，判定见 data/NetKind）----
        // 网络类型由 VM 探测后暴露成只读状态；onProbeNet 会把新鲜结论**返回**给屏幕，
        // 自动起播的判定必须在同一步拿到它（等状态回流就晚了一次装地址）
        netKind = vm.netKind,
        onProbeNet = vm::probeNetKind,
        cellularPrompt = playerSettings.cellularPrompt,
        onSetCellularPrompt = vm::setCellularPrompt,
    )
}

/**
 * 播放页的三种装配结果。
 *
 * Loading / Error 由本层自己画，不用 components/ 里的 LoadingState/ErrorState：
 * 契约 §5 只钉了 appErrorText / playerErrorText 两个组件的签名，
 * 其余组件的形态归 T4 所有，这里少引一个就少一处跨任务假设。
 * Ready 之后一律交给 PlayerScreen，它自己负责横屏全屏与控制层。
 */
@Composable
private fun PlayerRoute(vm: MainViewModel) {
    when (val state = vm.player) {
        is PlayerUi.Loading -> Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator(color = ModaColors.Accent)
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.core_player_preparing), color = ModaColors.TextDim, style = ModaType.Body)
        }
        // 错误码到文案的唯一出口是 appErrorText：这里不许再出现任何自己拼的提示
        is PlayerUi.Error -> Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(appErrorText(state.error), color = ModaColors.Danger, style = ModaType.Body)
            Spacer(Modifier.height(20.dp))
            Button(onClick = vm::retryPlayer) { Text(stringResource(R.string.core_retry)) }
        }
        is PlayerUi.Ready -> PlayerScreen(
            url = state.url,
            title = state.title,
            cid = state.cid,
            episodeLabel = state.episodeLabel,
            tiers = state.tiers,
            selectedTier = state.selectedTier,
            // 弹幕只有 MainViewModel 一个来源：窗口是 Compose 状态，换到新一窗就自动重组
            danmaku = vm.danmakuWindow,
            startPositionMs = state.startPositionMs,
            onTierChange = vm::onTierChangeFromPlayer,
            onProgress = vm::onProgress,
            onEnded = vm::onEnded,
            onBack = {
                vm.flushHistory()
                vm.pop()
            },
            hlsUrl = state.hlsUrl,
            episodes = state.episodes,
            currentEpisodeIndex = state.episodeIndex,
            onSelectEpisode = { episode -> vm.selectEpisodeFromPlayer(state.cid, episode) },
            // 换集期间这一页不卸载：只在画面上叠一个"切换中"（见 MainViewModel.startPlayer）
            switching = state.switching,
        )
    }
}
