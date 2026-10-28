plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.moda.mobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.moda.mobile"
        // 手机端最低到 Android 7.0：media3 的下限就是 21，但 datastore/compose 这一套
        // 在 24 以下要么多打 multidex 补丁要么掉 API，24 是这套依赖能干净编译的最低点。
        minSdk = 24
        targetSdk = 34
        // versionCode 的编码与 TV 端同一条公式：major*10000 + minor*100 + patch（1.0.0 → 10000）。
        // 手机端首版没有 CI 发版流水线，所以不像 TV 端那样留 -P 覆盖口子——
        // 等真的需要从 tag 推导时再加，现在多一个入口只会多一处对不上的地方。
        // 每个版本段必须小于 100，否则会串段（1.0.100 与 1.1.0 撞号）。
        versionCode = 10000
        versionName = "1.0.0"

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
     * 手机端首版不发签名包，所以这里不建 signingConfig、也不改产物文件名：
     * TV 端那两处（local.properties 里读 keystore、onVariants 把产物改名成 ModaTv<版本>.apk）
     * 都是为「Release 上挂的 asset 名 = 客户端拼的直链」这条契约服务的，
     * 手机端的 asset 名契约（ModaMobile<版本>.apk）由 T6 的 UpdateSource 决定，
     * 而现在既没有签名材料也没有写死的文件名，release 产物就是默认的 app-release-unsigned.apk。
     */
    buildTypes {
        release {
            isMinifyEnabled = false
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
