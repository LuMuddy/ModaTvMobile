import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 签名材料只从 local.properties 读（该文件被 .gitignore 排除），CI 在 runner 上临时写一份。
// 四个键：moda.keystoreFile / moda.keystorePassword / moda.keyAlias / moda.keyPassword。
// 前缀用 moda. 而不是 TV 端的 modatv.，是有意的：两端共用同一台机器的 local.properties 时
// （本地开发常把两个工程放在一起），键名撞了就会拿 TV 的密钥去签手机包。
val localSecrets = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}

fun secret(key: String): String = localSecrets.getProperty(key).orEmpty()

// 发版时由 CI 从 git tag 推导，用 -Pmoda.versionName / -Pmoda.versionCode 覆盖。
// 先算成变量：产物文件名（ModaMobile<版本>.apk）也要拿版本名，只写在 defaultConfig 里就取不到了。
//
// 本地不带 -P 时就是当前发版常量 2 / 1.0.1，与 v1.0.1 那次 CI 按 tag 序号算出的号一致；
// 不做 TV 端那种「从 tag 推导 +1」：手机端的发版号一律由 CI 按 tag 序号生成
// （v1.0.0 → 1、v1.0.1 → 2），本地包只是开发自测，让它去猜线上号既猜不准，也会和
// CI 算出两套值，对不上时就是一次装不上的版本。
// 每发一版这两个默认值都要跟着挪：漏挪的话本地包版本号比线上旧，覆盖安装会被系统拒掉。
val appVersionName = (project.findProperty("moda.versionName") as String?) ?: "1.0.1"
val appVersionCode = (project.findProperty("moda.versionCode") as String?)?.toIntOrNull() ?: 2

android {
    namespace = "com.moda.mobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.moda.mobile"
        // 手机端最低到 Android 7.0：media3 的下限就是 21，但 datastore/compose 这一套
        // 在 24 以下要么多打 multidex 补丁要么掉 API，24 是这套依赖能干净编译的最低点。
        minSdk = 24
        targetSdk = 34
        // 当前的 2 / 1.0.1，与 TV 端的 major*10000+minor*100+patch 编码**不同**：
        // 手机端的号由 .github/workflows/android-release.yml 按 tag 在版本序里的位置复算
        // （v1.0.0 → 1、v1.0.1 → 2），改这里的常量不会影响线上包，改 CI 的推导才影响。
        // versionCode 必须随每次发版严格递增，否则装着线上包的设备会拒绝安装（降级）。
        versionCode = appVersionCode
        versionName = appVersionName

        /**
         * 只保留手机真正常见的两个 ABI。
         * 原因是 ijk 的 native 包同时打了 arm64-v8a / armeabi-v7a / armeabi / x86_64，
         * 每个 ABI 的 libijkffmpeg.so 都在 5~12MB，全量收进来会让 APK 直接多出几十 MB，
         * 而手机端不存在 x86 真机（模拟器是 x86_64，调试用 ExoPlayer 内核即可），
         * 所以按 ABI 过滤是性价比最高的一刀。
         */
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    /**
     * 签名材料只从 local.properties 读，那文件永远不进仓库。
     * 没写 moda.keystoreFile 时不建 signingConfig，assembleRelease 会出未签名包——
     * 装不上但编得过，本地只想看看 release 产物时不必先造密钥。
     * CI 上必须签名：workflow 用 apksigner 校验产物，签没签由构建结果说话，不靠约定。
     */
    val releaseKeystore = secret("moda.keystoreFile")
    signingConfigs {
        if (releaseKeystore.isNotBlank()) {
            create("release") {
                storeFile = rootProject.file(releaseKeystore)
                storePassword = secret("moda.keystorePassword")
                keyAlias = secret("moda.keyAlias")
                keyPassword = secret("moda.keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        // 设置页与版权弹窗要在运行时显示版本号与构建号，必须生成 BuildConfig
        buildConfig = true
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

/**
 * 产物文件名固定成 ModaMobile<版本>.apk。
 *
 * 这个名字是三处契约之一：Release 上挂的 asset 名、客户端拼的直链
 * （data/UpdateSource.kt 的 apkUrl：/releases/download/v<版本>/ModaMobile<版本>.apk）、
 * 以及这里。客户端不扫 asset 列表，拿版本号直接拼 URL，差一个字符就是一个 404，
 * 而现场表现只是「下载失败」，极难定位。改名字要三处一起改，且已装的老客户端会用旧名字拼地址。
 *
 * outputFileName 只存在于 VariantOutputImpl（AGP 公开 API 没有这个口子），
 * 升级 AGP 后这里编译不过就是它变了；真取不到时 CI 会以「找不到 ModaMobile<版本>.apk」报错，
 * 不会静默发出去一个叫 app-release.apk 的资产。
 */
androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        variant.outputs.forEach { output ->
            (output as? com.android.build.api.variant.impl.VariantOutputImpl)
                ?.outputFileName
                ?.set("ModaMobile${appVersionName}.apk")
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    /**
     * 与 TV 端唯一的依赖差异：没有电视端的 tv-material 坐标。
     * 电视端引它是为了 Card/TabRow 的焦点放大与遥控器按键语义，
     * 手机端全部改用 material3 的 clickable + indication，多引一份等于多带一套
     * 永不使用的焦点样式和一个额外的 material3 版本约束。
     */
    implementation("androidx.core:core-ktx:1.13.1")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.4.1")

    /**
     * IJK 内核：只用一个自包含的 AAR，它同时带 tv.danmaku.ijk.media.player.IjkMediaPlayer 的
     * Java 类和 arm64-v8a / armeabi-v7a 的 libijkplayer/libijkffmpeg/libijksdl.so。
     *
     * 为什么用这个坐标而不是官方 tv.danmaku.ijk.media:ijkplayer-java:0.8.8：
     * 官方 0.8.8 只发布在 jcenter 上，jcenter 已停服；实测 Maven Central 与 jitpack 上都没有
     * tv.danmaku.ijk.media 这个 group（POM 全部 404），而本项目 settings.gradle.kts 里
     * repositoriesMode 是 FAIL_ON_PROJECT_REPOS，app 模块不能自己加仓库，所以 0.8.8 拿不到。
     * 这个包是 IJK 0.8.8 的社区重新打包版，Java 包名与 JNI 符号都和官方一致
     * （已用 strings 校验过 .so 里的 tv/danmaku/ijk/media/player/IjkMediaPlayer 符号），
     * 且从 mavenCentral() 直接可解析，不需要额外仓库。
     */
    implementation("xyz.doikki.android.dkplayer:player-ijk:3.3.7")

    /**
     * 微信登录二维码在本地画：服务端 wx_qr 回的是一张图片 URL，但扫的必须是屏幕上的像素，
     * 所以只引 core（纯 JVM 的编码器，QRCodeWriter 足够），不引 android-core / javase 那一套：
     * 那两个包带的是摄像头解码与 GUI，我们一条都不用。
     */
    implementation("com.google.zxing:core:3.5.3")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // 请求日志用官方拦截器（BODY 级、tag = okhttp.OkHttpClient），只挂 debug 包，见 data/ApiLog.kt
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.datastore:datastore-preferences:1.1.7")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.2")
}
