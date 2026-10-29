package com.moda.mobile.crash

import android.content.Context
import android.content.Intent
import android.os.Process
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 全局未捕获异常采集。
 *
 * 为什么崩溃页放在独立进程（:crash）：主线程崩溃时，本进程的 Looper 已经死了，
 * 同一个进程里再启动任何 Activity 都不会有界面——用户看到的仍然是系统那句「已停止运行」。
 * 崩溃页在 :crash 进程里跑，主进程随后退出，界面还在。
 *
 * 交棒顺序是硬约束：
 *  1. 先写日志（此刻进程还活着，IO 一定成功）；
 *  2. 再把启动崩溃页的请求递给 ActivityManager（startActivity 是跨进程调用，递出去之后
 *     即使本进程立刻死，系统照样会把那个进程拉起来）；
 *  3. 喘 300ms 让系统把请求排上队；
 *  4. **自己收尾**（Log.e + killProcess），不交给系统默认处理器。
 *
 * 第 4 步是有代价的取舍：交给默认处理器意味着系统还会叠一个「应用屡次停止运行」对话框，
 * 那个对话框盖在我们崩溃页上面，用户要先点掉它才能看到我们的页面，点「关闭应用」还会直接把
 * 崩溃页一起关掉（实测：按钮全都点不到）。所以这里接管到底，同时把堆栈补一条 Log.e，
 * 让 logcat 里仍然留得下同样的证据。
 */
internal object CrashHandler {

    private const val TAG = "ModaCrash"

    /** 交给系统处理 startActivity 的时间；显式 sleep 的是崩溃线程本身，不阻塞任何界面 */
    private const val HANDOFF_DELAY_MS = 300L

    /** 崩溃页的兜底文本上限：Intent 有 ~1MB 的传输上限，堆栈长了会被静默截断甚至抛异常 */
    private const val MAX_EXTRA_CHARS = 20_000

    private const val EXIT_CODE = 10

    /** 与 Manifest 里 CrashActivity 的 android:process 必须一致 */
    const val CRASH_PROCESS_SUFFIX = ":crash"

    private val installed = AtomicBoolean(false)

    fun install(context: Context) {
        // 应用有多个进程（:crash），每个进程都会走 Application.onCreate；重复安装会把
        // 处理器链套成多层，一次崩溃写出好几份日志、拉起好几个崩溃页
        if (!installed.compareAndSet(false, true)) return

        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            val tookOver = try {
                handle(app, thread, error)
            } catch (_: Throwable) {
                // 采集自己炸了：退回系统默认行为，至少还能看到系统的崩溃对话框
                false
            }
            if (!tookOver) {
                try {
                    previous?.uncaughtException(thread, error)
                } catch (_: Throwable) {
                    Process.killProcess(Process.myPid())
                }
            }
        }
    }

    /**
     * @return true 表示已经接管收尾（日志已写、崩溃页已拉起），调用方不要再交给默认处理器
     */
    private fun handle(app: Context, thread: Thread, error: Throwable): Boolean {
        val saved = try {
            CrashLog.save(app, thread, error)
        } catch (_: Throwable) {
            CrashLog.Saved(null, "")
        }

        // logcat 里留一份同样的证据：我们不再走系统默认处理器，系统那条 FATAL EXCEPTION 不会出现
        try {
            Log.e(TAG, "未捕获异常，日志=${saved.file?.absolutePath ?: "未写入"}", error)
        } catch (_: Throwable) {
            // 日志系统本身不可用也不该影响收尾
        }

        // 崩溃页自己也崩了：不再拉起第二个崩溃页（否则无限套娃），直接走系统默认处理
        if (isCrashProcess(app)) return false

        val launched = launchCrashPage(app, saved)
        sleepHandoff()
        // 崩溃页没拉起来（后台启动被拦 / 系统已在收尾）就交回默认处理器：
        // 用户看不到我们的页面时，系统那个对话框是唯一能告诉他"应用崩了"的东西
        if (!launched) return false

        try {
            Process.killProcess(Process.myPid())
        } catch (_: Throwable) {
            // 落到下面的 System.exit 收尾
        }
        System.exit(EXIT_CODE)
        return true
    }

    private fun isCrashProcess(context: Context): Boolean =
        CrashLog.processName(context).endsWith(CRASH_PROCESS_SUFFIX)

    private fun launchCrashPage(app: Context, saved: CrashLog.Saved): Boolean {
        val intent = Intent(app, CrashActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            putExtra(CrashActivity.EXTRA_LOG_PATH, saved.file?.absolutePath.orEmpty())
            // 文件名就是崩溃时间，直接当标题行用，屏幕上显示的时间与文件对得上
            putExtra(CrashActivity.EXTRA_LOG_TIME, saved.file?.name?.removeSuffix(".txt").orEmpty())
            putExtra(CrashActivity.EXTRA_LOG_TEXT, saved.report.take(MAX_EXTRA_CHARS))
        }
        return try {
            app.startActivity(intent)
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun sleepHandoff() {
        try {
            Thread.sleep(HANDOFF_DELAY_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
