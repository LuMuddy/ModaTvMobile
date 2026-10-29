package com.moda.mobile.crash

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.moda.mobile.BuildConfig
import com.moda.mobile.R
import com.moda.mobile.data.local.DeviceFingerprint
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 崩溃日志的落盘与内容拼装。
 *
 * 目录选择是「先按用户要求，再按系统能力」的顺序：
 *  1. /sdcard/crash —— 用户指定的位置。Android 10 及以下（targetSdk 34 下靠 requestLegacyExternalStorage）
 *     只要用户给了 WRITE_EXTERNAL_STORAGE 就写得进去；
 *  2. <外部私有目录>/crash = /sdcard/Android/data/<包名>/files/crash —— Android 11 起分区存储
 *     彻底禁止应用直接写 /sdcard 根目录下的任意目录，这一步是系统限制下最接近要求的落点
 *     （文件管理器里仍然看得到，卸载应用时才一起删掉）；
 *  3. <内部私有目录>/crash —— 连外部存储都挂不上时兜底。
 *
 * 崩溃页显示的是**实际落点**而不是写死的第一优先级路径：写死会让 Android 11+ 的用户
 * 按提示去 /sdcard/crash 找一个根本不存在的文件。
 */
internal object CrashLog {

    const val DIR_NAME = "crash"

    /** 文件名就是崩溃时间：同一天多次崩溃不会互相覆盖，按名字排序就是时间顺序 */
    private const val FILE_TIME_PATTERN = "yyyy-MM-dd_HH-mm-ss"

    private const val ROW_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss.SSS Z"

    /** 多级 cause 的堆栈可能非常大，各留一道上限，避免一次崩溃写出一个几十 MB 的文本 */
    private const val MAX_STACK_CHARS = 400_000
    private const val MAX_REPORT_CHARS = 512_000

    /** 保存结果：file 为 null 表示一个字节都没写出去，[report] 仍然可以上崩溃页显示 */
    class Saved(val file: File?, val report: String)

    /**
     * 崩溃这套功能的总开关：没有存储权限就整个不启用。
     *
     * 产品上的取舍（用户定的）：拿不到权限的机器既不采集崩溃、也不弹崩溃页、设置页也不出现
     * 「上报崩溃」——那就等于这个应用没有这套功能，崩溃时交给系统默认行为。
     * 而不是"半套"：日志写进应用私有目录、用户找不到、也没法分享出去。
     *
     * Android 11+ 判的是「所有文件访问」那个 appop（manifest 的 MANAGE_EXTERNAL_STORAGE），
     * 它是唯一能让 /sdcard/crash 可写的东西；10 及以下判运行时 WRITE_EXTERNAL_STORAGE。
     */
    fun permitted(context: Context): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED
        }
    } catch (_: Exception) {
        // 判不出来就当没有：宁可退回系统默认行为，也不要写到一个用户拿不到的地方
        false
    }

    /**
     * 解析崩溃日志目录。先判能不能写，不能写就退下一档——
     * 只在写入那一刻失败的话，崩溃页与文件内容会各说一套路径。
     */
    fun dir(context: Context): File {
        val primary = File(Environment.getExternalStorageDirectory(), DIR_NAME)
        if (writable(primary)) return primary

        val external = context.getExternalFilesDir(null)
        if (external != null) {
            val fallback = File(external, DIR_NAME)
            if (writable(fallback)) return fallback
        }

        val internal = File(context.filesDir, DIR_NAME)
        writable(internal)
        return internal
    }

    private fun writable(dir: File): Boolean = try {
        if (!dir.exists()) dir.mkdirs()
        dir.isDirectory && dir.canWrite()
    } catch (_: Exception) {
        false
    }

    /** 崩溃时间既是文件名也是正文第一行，两处取同一个 Date */
    fun save(context: Context, thread: Thread?, error: Throwable): Saved {
        val now = Date()
        val report = buildReport(context, thread, error, now)
        val dir = dir(context)
        val file = try {
            val target = uniqueFile(dir, formatTime(now, FILE_TIME_PATTERN))
            target.writeText(report, Charsets.UTF_8)
            target
        } catch (_: Exception) {
            // 写失败不影响崩溃页：报告内容已经拼好了，直接上屏
            null
        }
        return Saved(file, report)
    }

    /** 同一秒内崩两次（主线程崩完、监听线程跟着崩）时不要把后一条覆盖掉前一条 */
    private fun uniqueFile(dir: File, base: String): File {
        val first = File(dir, base + ".txt")
        if (!first.exists()) return first
        var i = 2
        while (i < 100) {
            val next = File(dir, base + "-" + i + ".txt")
            if (!next.exists()) return next
            i++
        }
        return File(dir, base + "-" + System.currentTimeMillis() + ".txt")
    }

    /**
     * 报告正文。字段顺序是给人工比对用的：先应用（版本/进程/线程），再设备，最后堆栈。
     * 崩溃页与文件用的是同一份文本，不存在「文件里有、屏幕上没有」的第二套拼装。
     */
    fun buildReport(context: Context, thread: Thread?, error: Throwable, now: Date = Date()): String {
        val dir = dir(context)
        val sb = StringBuilder(4096)

        sb.append("=== ")
            .append(runCatching { context.getString(R.string.app_name) }.getOrDefault("ModaTv"))
            .append(" 崩溃报告 ===\n")
        row(sb, "崩溃时间", formatTime(now, ROW_TIME_PATTERN))
        row(sb, "应用版本", BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")")
        row(sb, "包名", context.packageName)
        row(sb, "进程", processName(context))
        row(sb, "线程", thread?.name ?: "-")
        row(sb, "崩溃类型", error.javaClass.name)
        error.message?.let { row(sb, "崩溃信息", it) }

        sb.append("\n--- 设备 ---\n")
        row(sb, "厂商", Build.MANUFACTURER)
        row(sb, "品牌", Build.BRAND)
        row(sb, "型号", Build.MODEL)
        row(sb, "设备", Build.DEVICE)
        row(sb, "产品", Build.PRODUCT)
        row(sb, "主板/硬件", Build.BOARD + " / " + Build.HARDWARE)
        row(sb, "Android", Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")")
        row(sb, "系统构建", Build.DISPLAY)
        row(sb, "构建指纹", Build.FINGERPRINT)
        row(sb, "ABI", Build.SUPPORTED_ABIS.joinToString(","))
        row(sb, "屏幕", screenLine(context))
        row(sb, "语言/时区", Locale.getDefault().toString() + " / " + TimeZone.getDefault().id)
        row(sb, "运行时长", uptimeLine())
        row(sb, "内存", memoryLine())
        row(sb, "崩溃目录", dir.absolutePath)
        row(sb, "可用空间", storageLine(dir))
        row(sb, "写存储权限", storagePermissionLine(context))
        row(sb, "设备指纹", runCatching { DeviceFingerprint.of(context) }.getOrDefault("-"))

        sb.append("\n--- 堆栈 ---\n")
        sb.append(stackTrace(error).take(MAX_STACK_CHARS))

        return if (sb.length > MAX_REPORT_CHARS) sb.substring(0, MAX_REPORT_CHARS) else sb.toString()
    }

    /**
     * 必须显式构造 SimpleDateFormat。
     * 写成 `PATTERN.format(date)` 拿到的是 kotlin.text 的 String.format(日期)：
     * 它把 PATTERN 当格式串、把日期当参数，没有 % 占位符时原样返回，
     * 文件名于是变成字面的 "yyyy-MM-dd_HH-mm-ss"（真机踩过一次，已改）。
     */
    private fun formatTime(date: Date, pattern: String): String =
        SimpleDateFormat(pattern, Locale.US).format(date)

    private fun row(sb: StringBuilder, key: String, value: String) {
        sb.append(key).append(": ").append(value).append('\n')
    }

    private fun stackTrace(error: Throwable): String = try {
        val sw = StringWriter()
        PrintWriter(sw).use { error.printStackTrace(it) }
        sw.toString()
    } catch (_: Exception) {
        error.javaClass.name + ": " + error.message + "\n"
    }

    /**
     * 进程名。崩溃日志里它比包名有用得多：:crash 进程自己的崩溃与主进程的崩溃
     * 表现完全不同（前者是崩溃页崩了），没有这一行就分不清。
     */
    fun processName(context: Context): String = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName() ?: context.packageName
        } else {
            File("/proc/self/cmdline").readText().trim('\u0000', '\n').ifBlank { context.packageName }
        }
    } catch (_: Exception) {
        context.packageName
    }

    private fun screenLine(context: Context): String = try {
        val dm = context.resources.displayMetrics
        dm.widthPixels.toString() + "x" + dm.heightPixels + "px, " + dm.densityDpi + "dpi, " +
            String.format(Locale.US, "%.2f", dm.density) + "x"
    } catch (_: Exception) {
        "-"
    }

    /** elapsedRealtime 是「开机以来的时长」，不是本进程活了多久——它用来判「是不是刚开机就崩」 */
    private fun uptimeLine(): String = try {
        val ms = SystemClock.elapsedRealtime()
        val h = ms / 3_600_000
        val m = (ms % 3_600_000) / 60_000
        val s = (ms % 60_000) / 1000
        h.toString() + "h" + m + "m" + s + "s (自开机)"
    } catch (_: Exception) {
        "-"
    }

    private fun memoryLine(): String = try {
        val rt = Runtime.getRuntime()
        val mb = 1024L * 1024L
        "堆上限 " + rt.maxMemory() / mb + "MB / 已用 " + (rt.totalMemory() - rt.freeMemory()) / mb + "MB"
    } catch (_: Exception) {
        "-"
    }

    private fun storageLine(dir: File): String = try {
        val stat = StatFs(dir.absolutePath)
        val total = stat.blockCountLong * stat.blockSizeLong
        val avail = stat.availableBlocksLong * stat.blockSizeLong
        human(avail) + " 可用 / " + human(total) + " 共"
    } catch (_: Exception) {
        "-"
    }

    private fun human(bytes: Long): String = when {
        bytes >= (1L shl 30) -> String.format(Locale.US, "%.1fGB", bytes.toDouble() / (1L shl 30))
        bytes >= (1L shl 20) -> String.format(Locale.US, "%.1fMB", bytes.toDouble() / (1L shl 20))
        else -> (bytes / 1024) .toString() + "KB"
    }

    /**
     * 写存储权限的状态要单独记：崩溃日志「为什么没落在 /sdcard/crash」的答案
     * 八成就在这一行（没给权限 / 系统版本太高），省掉一轮来回问。
     */
    private fun storagePermissionLine(context: Context): String = try {
        val legacy = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) == PackageManager.PERMISSION_GRANTED
        // Android 11+ 真正起作用的不是上面的运行时权限，而是「所有文件访问」这个 appop
        // （manifest 里的 MANAGE_EXTERNAL_STORAGE）。不判它的话，日志明明已经写进 /sdcard/crash，
        // 报告里却还写着"系统不允许分区存储"，排查的人第一眼就被带偏
        val allFiles = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            Environment.isExternalStorageManager()
        when {
            allFiles && legacy -> "已授予 (所有文件访问 + WRITE_EXTERNAL_STORAGE)"
            allFiles -> "已授予 (所有文件访问)"
            legacy -> "已授予 (WRITE_EXTERNAL_STORAGE)"
            Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q -> "未授予"
            else -> "未授予 (SDK " + Build.VERSION.SDK_INT + " 分区存储)，已退到应用私有目录"
        }
    } catch (_: Exception) {
        "-"
    }
}
