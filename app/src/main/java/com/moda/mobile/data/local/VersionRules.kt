package com.moda.mobile.data.local

/**
 * 「要不要提示更新」的唯一判据：**远端 versionCode 严格大于本机 versionCode**。
 *
 * 为什么不再比版本名：手机端的发版号已经改成单调递增的整数序号（v1.0.0 → 1、v1.0.1 → 2，
 * 由 .github/workflows/android-release.yml 按 tag 序号复算），版本名只剩给人看的作用，
 * 而两个版本名谁新本来就得靠「按 . - + 切段再比数字」的规则去猜——
 * "1.0.10" 与 "1.1.0" 猜错一次就是「某些版本永远收不到更新」，
 * 这种故障只在用户侧、只在某些版本上暴露。versionCode 是单调整数，
 * Android 自己判覆盖安装也是按它，拿它判「有没有新版」没有歧义。
 *
 * 为什么不再保留按名字回落：回落只在远端票据**没有** versionCode 时才生效，
 * 而那种票据本来就没法可靠地判新旧，回落的结果是把「不知道」当成「有一个新版」推给用户。
 * 宁可当作「没有更新」，也不推一个下载不了的包。现在 version-mobile.json 由 CI 生成，
 * versionCode 一定在里头。
 *
 * [remoteCode] 为 null（票据里没有这个字段）或 <= 0（数字坏了）一律判「没有更新」：
 * 一个不可信的数字比没有数字更危险。
 *
 * 无 Context / DataStore / 协程：可以脱离 Android 直接验证边界。
 */
object VersionRules {

    fun isNewer(remoteCode: Int?, localCode: Int): Boolean =
        remoteCode != null && remoteCode > 0 && remoteCode > localCode
}
