# MOBILE PORT CONTRACT（调度方产物，执行代理必读）

手机端 = TV 端 (`/Users/muddy/AndroidStudioProjects/ModaTv`) 的逻辑与架构移植，**交互全部按触摸重做**。
数据层、错误码、DataStore 存储域、后端接口一律与 TV 端一致；不重写业务规则，只换壳与交互。

## 0. 已定决策（不可在执行中改）

1. 范围：全量对齐——授权门 / 首页 / 搜索 / 详情 / 播放器 / 历史 / 收藏 / 已看完 / 设置(3 分组) / 自更新。
2. 结构：单模块 `:app` + 包内分层（`ui/ data/ model/`），不引 navigation-compose，不引图片加载库。
3. 登录：一键拉起微信确认——展示二维码（供另一台手机扫）**并且**提供「用微信打开授权链接」按钮（Intent 打开
   `https://open.weixin.qq.com/connect/confirm?uuid=<uuid>`）；`wx_qr → wx_poll → activate` 三条逻辑照抄 TV 端。
4. 播放器：横屏全屏 + 手势（单击显隐控制层、双击左/右快退快进、横滑调进度、纵滑左亮度右音量），
   九项能力（内核/解码/清晰度/选集/倍速/弹幕/比例/片头片尾/返回）以底部按钮 + 面板抽屉承载。
5. 自更新：保留链路，asset 名换成 `ModaMobile<version>.apk`，发在本仓库（ModaTvMobile）的 releases，不与 TV 共用 Release 仓库。
6. 验收：`./gradlew assembleDebug` 绿 + 装模拟器跑通关键路径。

## 1. 假设（执行中按此推进，遇到冲突回报而非自行推翻）

- applicationId / namespace = `com.moda.mobile`，源码根 `app/src/main/java/com/moda/mobile/`
- app 名称资源 `app_name` = `Moda`；版本 1.0.0 / versionCode 10000
- compileSdk 35 / targetSdk 34 / minSdk 24 / JDK 17 / AGP 8.9.3 / Kotlin 2.2.20 / Compose BOM 2025.12.01
- 界面语言固定中文、只有 `values/`（无 `values-en`），上屏文案一律走 string 资源，代码里不许中文字面量
- 不出现 `androidx.tv` 依赖；不用 `onFocusChanged / FocusRequester / onKeyEvent` 做交互
- 播放内核保持 Media3 ExoPlayer（默认）+ IJK 双内核，ABI 只留 `arm64-v8a` + `armeabi-v7a`

## 2. 主题契约（T1 产出，所有人只许用这三个名字）

`ui/theme/Theme.kt` 必须提供：

- `object ModaColors`：`Bg Surface SurfaceHigh Accent AccentSoft Text TextDim Danger`（颜色值与 TV 端相同）
- `object ModaType`：`Title Heading Body Caption`（TextStyle，手机字号：22/18/15/12 sp）
- `@Composable fun ModaTheme(content: @Composable () -> Unit)`

## 3. 路由契约（T3 产出）

`ui/Destinations.kt`：`sealed class Dest` 保持 TV 端同名同形——`Home / Search / History / Completed /
Favorites / Settings / SettingsGroup(group) / Detail(cid,title) / Player(cid,vid,title,episodeIndex)`，
以及 `enum class SettingsSectionId(Playback, Network, About)`（字段 `titleRes / descRes` 不变）。

## 4. ViewModel 契约（T3 产出）

`ui/MainViewModel.kt`：TV 端 `TvViewModel` 的移植。**公开成员名一律不改**（`home` `homeTab` `homeFeeds`
`homeLoading` `homeError` `homeFetchedAt` `homeRefreshing` `homeRefreshNotice` `refreshHome()`
`refreshAllHome(reason)` `selectHomeTab()` `detail` `detailLoading` `detailError` `detailTier`
`selectDetailTier` `retryDetail` `detailIsFavorite` `detailResume` `detailCompletedIndexes`
`toggleFavorite` `push` `pop` `current` `player` `danmakuWindow` `onTierChangeFromPlayer` `onProgress`
`onEnded` `retryPlayer` `selectEpisodeFromPlayer` `flushHistory` `historyEntries` `resumeFromHistory`
`removeHistory` `clearHistory` `completedEntries` `resumeCompleted` `removeCompleted` `clearCompleted`
`favoriteEntries` `removeFavorite` `clearFavorites` `clearSearchHistory` `updatePlayerSettings`
`authSession` `updateUi` `downloadUpdate` `ignoreUpdate` `dismissUpdate` `markCompleted`）。
只允许删掉纯遥控器/焦点相关成员，**不许改名、不许改签名**。

## 5. 屏幕与对话框签名（跨任务接口，只有实际拥有该文件的人可改）

规则：**不得删除或改动已有参数**；新增参数必须带默认值，这样并发任务的调用点不会挂。

| 文件拥有者 | 签名（与 TV 端 AppRoot 调用点一致） |
|---|---|
| T4 `HomeScreen.kt` | `HomeScreen(loading, error, onRetry, onOpenDetail, onOpenSearch, onOpenPlayer, onOpenHistory, onOpenFavorites, onOpenSettings, tab, feeds, pageFetchedAt, onSelectTab, onRefresh, refreshing, refreshNotice, history, onResumeHistory, onOpenCompleted)` |
| T4 `SearchScreen.kt` | `SearchScreen(onSearch, onOpenDetail, onBack, loadHotWords, history, onRecordSearch, onClearHistory)` |
| T4 `DetailScreen.kt` | `DetailScreen(detail, loading, error, onPlay, onTierSelect, selectedTier, onBack, description, onRetry, isFavorite, resume, completedIndexes, onToggleFavorite)` |
| T4 `components/ErrorText.kt` | `fun appErrorText(AppError): String`、`fun playerErrorText(PlayerError): String`（唯一错误码翻译点） |
| T5 `PlayerScreen.kt` | `PlayerScreen(url, title, cid, episodeLabel, tiers, selectedTier, danmaku, startPositionMs, onTierChange, onProgress, onEnded, onBack, hlsUrl, episodes, currentEpisodeIndex, onSelectEpisode)` |
| T6 `HistoryScreen.kt` | `HistoryScreen(entries, onResume, onDelete, onClear, onOpenCompleted: () -> Unit = {})` |
| T6 `CompletedScreen.kt` | `CompletedScreen(entries, onResume, onDelete, onClear)` |
| T6 `FavoritesScreen.kt` | `FavoritesScreen(entries, onOpen, onDelete, onClear)` |
| T6 `SettingsScreen.kt` | `SettingsScreen(session, onClearHistory, onClearFavorites, onClearSearchHistory, onOpenGroup, onUpdatePlayerSettings)` |
| T6 `AuthGateScreen.kt` | `AuthGateScreen(...)` 以 TV 端为准，可新增带默认值参数 |
| T6 `UpdateDialog.kt` | `UpdateDialog(state, progress, onDownload, onIgnore, onDismiss)` |
| T6 `CopyrightNoticeDialog.kt` | `CopyrightNoticeDialog(...)` 以 TV 端为准 |

## 6. 触摸交互规范

- 全局：底部导航栏**只有 3 项——首页 / 收藏 / 设置**（`NavigationBar` + `Icons`，由 T3 在 AppRoot 实现）。
  **搜索与历史不在底栏**：两者都是压栈全屏页，入口放在首页顶部工具栏（右侧两个图标按钮：搜索、历史）。
  详情页、播放页、设置子页、历史页、搜索页一律为压栈全屏页（无底栏），返回键（`BackHandler`）先退面板/弹窗，再 pop，最后才退出应用。
- 首页：顶部可横滑的分类 TabRow（19 项），两列网格（手机竖屏 2 列），`LazyVerticalGrid`；下拉刷新替代刷新按钮（同一 `onRefresh`）；
  「继续观看」作为推荐 tab 尾部一段；时间戳仍显示 `上次刷新 … / 现在 …` 两截。
- 搜索：输入框自动聚焦 + 软键盘「搜索」动作，热词与本地历史为可点 Chip；结果两列网格。
- 详情：竖版海报 + 简介可折叠；主按钮「继续播放 mm:ss」/「播放」；选集为 `LazyVerticalGrid`（多行，不再单行横轨）；
  已看完角标保留。
- 播放器：见 §0.4；控制层 3 秒无操作自动隐藏，隐藏时点击整屏显示；进度条用 `Slider` 可拖动快进；
  亮度/音量纵滑带中央指示浮层；面板（选集/倍速/弹幕/清晰度/内核/解码/比例）为底部`ModalBottomSheet`或等价的底出面板。
- 列表/网格项一律 `clickable`（不用 focusable/onFocusChanged）；点击态用 `indication`。

## 7. 文件归属（越界即违规；跨任务文件不存在时按本文档签名建桩不算越界）

- **T1 基建**：根 `settings.gradle.kts` `build.gradle.kts` `gradle.properties` `gradlew` `gradlew.bat`
  `gradle/wrapper/*` `.gitignore` `local.properties`；`app/build.gradle.kts` `app/proguard-rules.pro`；
  `app/src/main/AndroidManifest.xml`；`app/src/main/res/**`（**全部** TV 端 strings_*.xml 原样移植 + `themes.xml`
  `drawable/**` `mipmap/**` `xml/file_paths.xml` `values/strings.xml`）；`ui/theme/Theme.kt`；`ModaApp.kt`
- **T2 数据层**：`data/**`（22 个文件）+ `model/**`（4 个文件）
- **T3 壳**：`MainActivity.kt` `ui/Destinations.kt` `ui/MainViewModel.kt` `ui/AppRoot.kt` `res/values/strings_m_nav.xml`
- **T4 首页/搜索/详情**：`ui/HomeScreen.kt` `ui/SearchScreen.kt` `ui/DetailScreen.kt`
  `ui/components/{Cards,Common,ErrorText,RemoteImage}.kt` `res/values/strings_m_feed.xml`
- **T5 播放器**：`ui/PlayerScreen.kt` `ui/player/**` `res/values/strings_m_player.xml`
- **T6 库页/设置/授权/更新**：`ui/{HistoryScreen,FavoritesScreen,CompletedScreen,SettingsScreen,SettingsSections,AuthGateScreen,UpdateDialog,CopyrightNoticeDialog,ClearConfirmDialog}.kt`
  `ui/components/QrCode.kt` `ui/update/UpdateInstaller.kt` `res/values/strings_m_library.xml`

文案键冲突规则：T1 已把 TV 端所有 string 文件整份移植过来，**已有的键不要重复定义**；
需要新文案就在自己拥有的 `strings_m_*.xml` 里新增 `m_` 前缀的键。

## 8. 交付纪律

- 各自只写自己 §7 里的文件；**不要跑 `./gradlew` 构建**（并发任务的缺失文件会让构建必然失败），构建由调度方统一执行。
- 注释写「为什么」，踩坑与阈值理由保留 TV 端的原意，不要整段删。
- 完成后回报：文件清单、每处偏离 TV 端的理由、遗留风险。