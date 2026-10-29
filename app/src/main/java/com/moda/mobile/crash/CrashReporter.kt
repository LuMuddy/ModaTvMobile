package com.moda.mobile.crash

import android.content.Context
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 把崩溃日志打成一个 zip，供设置页「上报崩溃」分享出去。
 *
 * 目标位置按需求是 Download/<时间戳>.zip：
 *  1. 公开的 /sdcard/Download —— Android 11+ 需要「所有文件访问」（见 MainActivity 的引导弹窗）；
 *  2. 拿不到就退到应用自己的外部 Download 目录，再不行退内部目录。
 * 和 CrashLog 一个道理：能写哪就写哪，弹窗里显示的是**实际落点**。
 *
 * 顺序是「先打包成功、再删源文件」，反过来做的话一次写盘失败就把用户唯一的日志弄没了。
 */
internal object CrashReporter {

    /** 与崩溃日志文件同名规则：时间戳既是名字也是排序依据 */
    private const val ZIP_TIME_PATTERN = "yyyy-MM-dd_HH-mm-ss"

    class Packed(val zip: File, val count: Int)

    /**
     * 现有崩溃日志。目录不存在 / 读不出来都返回空表——调用方据此决定设置页那一行显不显示，
     * 这里抛异常只会让设置页崩掉，而"没有日志"和"读不到"对用户是同一件事：没什么可上报的。
     */
    fun logFiles(context: Context): List<File> {
        // 没有存储权限 = 这套功能不存在：既不会产生日志，也不该在设置页露头
        if (!CrashLog.permitted(context)) return emptyList()
        return try {
            CrashLog.dir(context)
                .listFiles { file -> file.isFile && file.name.endsWith(".txt") }
                ?.sortedBy { it.name }
                ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** 阻塞 IO：必须在 Dispatchers.IO 上调用 */
    fun pack(context: Context): Packed? {
        val files = logFiles(context)
        if (files.isEmpty()) return null

        val zip = uniqueFile(downloadDir(context), SimpleDateFormat(ZIP_TIME_PATTERN, Locale.US).format(Date()))
        var entries = 0
        try {
            ZipOutputStream(zip.outputStream().buffered()).use { out ->
                files.forEach { file ->
                    out.putNextEntry(ZipEntry(file.name))
                    file.inputStream().buffered().use { input -> input.copyTo(out) }
                    out.closeEntry()
                    entries++
                }
            }
        } catch (_: Exception) {
            // 半截 zip 比没有 zip 更糟：用户会分享出去一个打不开的包
            zip.delete()
            return null
        }

        // 打包成功之后才删：日志在这一刻"搬进"了压缩包，两者只留一份，避免下次上报重复打包
        files.forEach { runCatching { it.delete() } }
        return Packed(zip, entries)
    }

    private fun downloadDir(context: Context): File {
        val publicDownloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (writable(publicDownloads)) return publicDownloads

        val external = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        if (external != null && writable(external)) return external

        val internal = File(context.filesDir, "Download")
        writable(internal)
        return internal
    }

    private fun writable(dir: File): Boolean = try {
        if (!dir.exists()) dir.mkdirs()
        dir.isDirectory && dir.canWrite()
    } catch (_: Exception) {
        false
    }

    /** 同一秒内点两次「上报崩溃」时不要互相覆盖（第二次通常已经没有源文件了，仍做保护） */
    private fun uniqueFile(dir: File, base: String): File {
        val first = File(dir, base + ".zip")
        if (!first.exists()) return first
        var i = 2
        while (i < 100) {
            val next = File(dir, base + "-" + i + ".zip")
            if (!next.exists()) return next
            i++
        }
        return File(dir, base + "-" + System.currentTimeMillis() + ".zip")
    }
}
