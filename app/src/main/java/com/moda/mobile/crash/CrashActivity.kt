package com.moda.mobile.crash

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.moda.mobile.R
import com.moda.mobile.ui.CrashScreen
import com.moda.mobile.ui.theme.ModaTheme
import java.io.File

/**
 * 崩溃页宿主。跑在独立进程 :crash（见 Manifest），所以主进程死掉之后它仍然在屏上。
 *
 * 它只做三件事：读日志文件 → 上屏 → 把「复制 / 重新打开 / 关闭」接到平台能力上。
 * 所有文案与布局都在 ui/CrashScreen.kt，本类里没有中文、也不画画。
 */
class CrashActivity : ComponentActivity() {

    companion object {
        const val EXTRA_LOG_PATH = "crash.log_path"
        const val EXTRA_LOG_TIME = "crash.log_time"
        const val EXTRA_LOG_TEXT = "crash.log_text"

        /** 与 CrashLog 的正文上限一致：读整份但不再放大 */
        private const val MAX_READ_CHARS = 512_000
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val path = intent.getStringExtra(EXTRA_LOG_PATH).orEmpty()
        val time = intent.getStringExtra(EXTRA_LOG_TIME).orEmpty()
        val fallback = intent.getStringExtra(EXTRA_LOG_TEXT).orEmpty()
        // 文件读不到（写盘失败 / 目录被清）就退 Intent 里那一份，仍然让用户看到堆栈
        val content = readLog(path) ?: fallback.ifBlank { getString(R.string.m_crash_empty) }

        // 复制成功的反馈只是本页的一次性状态，不必进 ViewModel（这一页没有 VM）
        var copied by mutableStateOf(false)

        setContent {
            ModaTheme {
                CrashScreen(
                    time = time.ifBlank { "-" },
                    path = path.ifBlank { null },
                    content = content,
                    copied = copied,
                    onCopy = {
                        copyToClipboard(content)
                        copied = true
                    },
                    onRestart = { restartApp() },
                    onClose = { closeApp() },
                )
            }
        }
    }

    private fun readLog(path: String): String? {
        if (path.isBlank()) return null
        return try {
            val file = File(path)
            if (!file.isFile) return null
            file.readText(Charsets.UTF_8).take(MAX_READ_CHARS)
        } catch (_: Exception) {
            null
        }
    }

    private fun copyToClipboard(text: String) {
        try {
            val manager = getSystemService(ClipboardManager::class.java) ?: return
            manager.setPrimaryClip(ClipData.newPlainText(getString(R.string.m_crash_clip_label), text))
        } catch (_: Exception) {
            // 复制失败不改变主流程：文件还在，用户可以自己去目录里取
        }
    }

    /**
     * 关闭：先回桌面，再把这张崩溃卡片从任务栈里摘掉。
     *
     * 为什么不是裸的 finishAndRemoveTask：崩溃页自开一条任务（taskAffinity 见 Manifest），
     * 摘掉它之后系统会把下一条任务拉到前面——那正是崩溃时被杀了进程的那个任务，
     * 于是 MainActivity 被重建、应用自己又打开了（实测：点「关闭」→ 应用重新启动，
     * 用户读成「关不掉」）。先启动桌面，前面就有东西可回，应用那条任务不会再被拉起。
     *
     * 启动 HOME 用 try 包住：个别 ROM 的桌面不是标准 CATEGORY_HOME 时也不能把这一页卡住，
     * 退化成 finishAndRemoveTask 依然是"关掉了"。
     */
    private fun closeApp() {
        try {
            val home = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(home)
        } catch (_: Exception) {
            // 没有可回的桌面：下面照样关掉崩溃页
        }
        finishAndRemoveTask()
    }

    /**
     * 重新打开：清掉崩溃那条任务再拉起主界面。
     * 不带 CLEAR_TASK 的话，崩溃时那条任务栈还留在系统里，新起的 Activity 会压在它上面，
     * 用户按返回又会回到那个已经死掉进程的旧页面。
     */
    private fun restartApp() {
        val launch = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        if (launch != null) {
            try {
                startActivity(launch)
            } catch (_: Exception) {
                // 启不起来（极个别 ROM 拦截）就停在崩溃页，用户至少还能复制日志
            }
        }
        finish()
    }
}
