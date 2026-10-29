# AGENTS.md

给在本仓库里写代码的人 / AI 编码代理看的**强制约定**。产品介绍与截图看 [README.md](README.md)。

本项目是 **ModaTv 手机端**：逻辑与网络层移植自 TV 端（`../ModaTv`），**交互层全部按触摸重写**。
拿不准某处该怎么实现时，先去 TV 端看同一个功能怎么做的——但**不要**把遥控器那套（焦点、D-Pad、按键分发）搬过来。

---

## 分层与依赖方向

```
ui/*Screen.kt ──▶ MainViewModel ──▶ Repo ──▶ Api / CarouselSource / ChannelSource
                                  └─────▶ data/local/*Store  (DataStore)
```

- **屏幕层不碰 `Api`、不碰 `OkHttp`、不碰 DataStore**。要数据只调 `Repo` 或 ViewModel 的既有方法。
- **异常在 ViewModel 内落成 UI 状态**（Loading / Ready / Empty / Error），错误码到文案的翻译**只有一个出口**：
  `ui/components/ErrorText.kt` 的 `appErrorText` / `playerErrorText`。数据层只抛 `AppError` / `PlayerError`，
  不许在数据层拼中文，也不许在界面里再写一份错误码映射。
- **路由是手写的字符串对象**（`ui/Destinations.kt` 的 `Dest`），不引 navigation-compose。
  新增页面 = 加一个 `Dest` 子类型 + 在 `AppRoot` 的 `when` 里加一个分支。
- **`data/local` 里的 Store 只管读写，不含业务判断**。带阈值的判断要么是纯函数（如 `IntroOutroRules`），
  要么在 `Repo` / ViewModel 里。
- **`ui/player/PlayerEngine` 只认 Boolean / String / 原生类型**，不 import `data.local` 的 `PlayerCore` / `DecoderMode`。

## 触摸纪律（与 TV 端的根本区别）

- **不存在焦点体系**：不许出现 `onFocusChanged` / `FocusRequester` / `focusable` / `onKeyEvent` / `androidx.tv` / `Key.`。
  点击一律 `Modifier.clickable`，不要自绘焦点框。
- 触控区 ≥ 44dp；列表/网格用 `LazyColumn` / `LazyVerticalGrid`，不要为「避免焦点飞出」加任何 pad。
- 界面语言固定中文，只有 `values/`，**不要加 `values-en`**。

### 从 TV 端「遥控器纪律」翻译过来的三条（本质都是「交互只有一个来源」）

- **返回键分层退出**：先关面板 → 再关控制层 → 再退出播放页 → 再退出页面 → 最后退出应用。
  任何一层新增的弹窗/面板都要插进这条链，不能直接 `finish()`。
- **面板展开后它就是最上层**：触摸归它，不穿透到底下控制层；面板内的横滑/纵滑按各自的轴分派，
  不要用「所有面板一律忽略某个手势」糊过去——用户看到一排按钮却拖不动。
- **同一层不许放两套争抢的手势来源**（TV 端是「两套焦点必然抢键」，手机端是「两个 pointerInput 抢同一片区域」）：
  播放页的 tap / doubleTap / drag 写在同一段 `pointerInput` 语义里并由 `consume()` 明确归属，
  不要在同一块画面上叠两个互不知情的 `pointerInput`。

## 导航规则（改动前先读）

- **底栏只有三项**：首页 / 收藏 / 设置。搜索与历史**不在底栏**，入口是首页顶部工具栏右侧的两个图标。
- **所有二级页（搜索 / 历史 / 已看完 / 详情 / 播放 / 设置子页）必须有左上角返回**，
  返回**不得**出现在右侧或底部。一级页（首页 / 收藏 / 设置）是底栏根页面，不加返回箭头。
- 返回按钮与系统返回键必须走同一条 `vm.pop()`，否则会出现「按钮退一层、返回键退两层」。
- 首页内容区是 `HorizontalPager`（一页一分类），与顶部 tab **双向同步**：
  - collector 的 `LaunchedEffect` **key 里不能有 `tab`**（否则点 tab 会先重启 collector、用旧页压新 tab → 回弹）；
  - 程序化滚动期间用**计数器**（不是布尔）压掉上报，`try/finally` 复位；
  - 未被加载过的页必须显示 loading，不要显示「暂无内容」空态。

## 刷新语义（被用户反复纠正过，别改回去）

- **回首页不自动刷新**。自动刷新只在**冷启动**判一次 30 分钟门禁（`ensureHomeLoaded`）。
- **唯一的刷新入口是「双击底栏首页」**。工具栏的刷新图标已经删掉（常驻图标只会被误点，一次误触就是 19 个频道整页重拉），
  分类排序页离开时也不再补刷（顺序变了不需要重拉数据，每页 feed 是按 `HomeTab` 存的）。
- **没有下拉刷新**：手机上「滚到顶再往下拽」与「滚到顶」是同一条手势，误触一次等于整页 19 个频道重拉。

## 播放器

- **大屏（全屏播放页）**控制层分三处：
  - 顶栏 = 返回（最左）+ 标题·集号 + **投屏 + 锁定**（最右）；
  - 底栏第一行 = 播放/暂停 · 下一集 · 时间 · 进度条 · **弹幕快捷开关（行尾）**；
  - 底栏第二行（右下角簇，右对齐）= **倍速 / 码率(=清晰度) / 选集 / 更多**；「更多」里收 弹幕完整面板 · 比例 · 播放内核 · 解码方式 · 片头片尾。
  **原来的整排八项文字功能行已删除**，功能只走上表这几个入口，底部四角不许出现孤悬图标。
- **小屏（详情页内嵌播放器）**：
  - **只有返回常驻**；标题在页面里（播放器下方、简介上方，且**不带集号**——选集里已有集数），不在覆盖层；
  - 点击画面才显示控制层：右上投屏+倍速、右下全屏、底部一行（播放/暂停 · 当前时间 · 进度条 · 总时长）；
    3 秒无操作自动隐藏，面板打开期间不收；
  - **单击画面 = 显隐控制层，不再兼做播放/暂停**（播放/暂停是控制层里那颗按钮）；
  - 手势只有纵滑（左半屏亮度 / 右半屏音量，取手势**起点 x**），**纵滑优先于单击**（越过 slop 就不算点击）；
  - 小屏不做选集/清晰度/内核/解码/比例面板，也**不上报历史进度、不判完成**；
  - 选集格子是 **3 列缩略图**（`Episode.cover` + 集号），角标沿用预告/VIP/已看完，当前集用描边高亮而不是填色。
- 手势：单击显隐（3 秒无操作自动隐藏，任何交互重置计时）、双击左/右半屏 ∓10s、横滑预览后松手 seek、
  纵滑**左半屏亮度 / 右半屏音量**。纵滑的左右判定必须用手势**起点 x**，不能用手实时 x。
- 亮度写的是 `window.attributes.screenBrightness`，**离开播放页必须恢复 `BRIGHTNESS_OVERRIDE_NONE``(-1f)`**，
  否则整个 App 都会被留在调过的亮度上。
- 锁定时整屏只允许出现「解锁」这一个入口，绝不弹整个控制层。
- 七个底部面板统一走 `PlayerSheet` 封装（同宽、同标题栏、同 padding、同 chip 尺寸），不要各写一套。

## 降级与兜底（TV 端踩过的坑，手机端同样适用）

- **附属功能失败不阻断主流程**：弹幕、封面、分集缩略图、热词、演职员信息的失败一律静默降级
  （空列表 / 占位色块 / 不显示），绝不阻断起播或首页。
- **首页只有一个数据源**：`v.qq.com` 的频道页与首页 HTML，抓不到就是这一屏为空，由 `HomeCacheStore` 的持久化缓存顶上。
  **不要为了「让首页看起来有内容」把搜索结果塞进首页**——宁可空，也不拿别处的数据冒充。
- **档位失败进 TTL 黑名单（10 分钟）**，不要改成立即永久隐藏：绝大多数失败是网络抖动，
  永久拉黑会让一个档位在整个进程生命周期内消失。
- **拉黑、缓存、重试都要带时间**：任何「永久」状态都要能回答「它什么时候恢复？」。

## 注释

- 注释写**为什么**，不写**是什么**。`i++` 旁边不需要注释；阈值、时序、手势分层这类「看代码看不出理由」的地方必须有。
- **踩过的坑要留在注释里**（例如「用布尔 key 的 LaunchedEffect 会导致焦点永久留在工具栏」，
  「class Collector 的 key 里带 tab 会让点 tab 回弹」），否则下一次重构会原样踩回去。

## 数据与存储

- 全部 DataStore Preferences，**一个存储域一个文件**：`moda_history` / `moda_favorites` / `moda_completed` /
  `moda_home_cache` / `moda_intro_outro` / `moda_player_settings` / `moda_auth_session` / `moda_search_history` /
  `moda_copyright_notice` / `moda_update_check` / `moda_home_tabs`。新增域 = 新增文件，不要往别人的文件里塞键。
- 一个键缺失 = 「用户从未改过」，读不到时**静默退回默认值**，不抛异常、不写「首次运行迁移」。
- 枚举落盘用 `enum.name`，不存中文 label。「恢复默认」= **删键**，不是写一个和默认值相同的值。
- 首页分类顺序存在 `moda_home_tabs`，**「推荐」固定第一**（首页落地页），排序 UI 不许把它挪走。

## 播放阈值

- 片头片尾判定全部是 `IntroOutroRules` 里的**纯函数**，阈值改动前先读每条常量上的说明（1% 误触线、10% 上限线、60s 最小间隔）。
- 完成线是片长 95%（`COMPLETE_PERCENT`），只有 `markCompleted` 一个入口，粒度是 `(cid, episodeIndex)` 不是 cid。

## 依赖与自造轮子

- **不加不必要的依赖**：图片加载用 `ui/components/RemoteImage.kt` 手搓、路由手写、不引 navigation-compose / coil / hilt / appcompat。
- 依赖全部显式写在 `app/build.gradle.kts`，**不用 version catalog**。
- 播放内核：Media3 ExoPlayer（默认）+ IJK；ABI 只留 `arm64-v8a` + `armeabi-v7a`，加 ABI 前先算 APK 体积代价。

## 自更新

- 手机端**只认自己的发布资产**：`version-mobile.json` + `ModaMobile<版本>.apk`，发在**本仓库**（ModaTvMobile）的 Release 上——与 TV 端的 ModaTv 仓库整仓隔离，不共用一个 Release。
- **绝不回退去读 TV 的 `version.json` / `releases.atom`**——那会把 TV 的版本说明弹到手机上（踩过）。
- 资产不存在 → 静默「已是最新」；只有**网络异常**才允许报错。

## 文案

- 所有上屏文案走 string 资源；代码里不许出现中文字面量（日志与内部异常除外）。
- 一个页面/模块一个 `strings_*.xml`；新键带所属前缀（手机端新增用 `m_`），**不许重复定义 TV 移植过来的键**。
- 枚举里要显示给用户的名字用 `@StringRes val xxxRes: Int`，**不要存中文字符串**。
- 带参数的文案用占位符（`%1$d` / `%1$s`），不要把数字拼进句子再上屏。

## 已知没有数据的东西（不要再去找）

- **评分**：后端 `eps.meta` 没有 score/rating；`v.qq.com` 封面页 HTML 里也没有；
  `float_vinfo2` 虽然留着 `douban` 字段，但实测恒为 `0`/`null`。首页卡片显示的是**分类 + 集数**角标，不是评分。

## 提交与验证

- 提交信息遵循 Conventional Commits：`<type>(<scope>): <中文描述>`。
  scope 取值：`home` `search` `detail` `player` `settings` `auth` `data` `model` `ui` `docs` `build` `ci`。
- 一次提交只做一件事；格式化与逻辑改动不混在同一个提交里。
- 改动后至少跑通 `./gradlew assembleDebug`，再用 `adb install` 装到设备上**手动走查涉及的那条触摸路径**。
  本项目没有单元测试，手动走查就是功能验收的唯一手段。
- **不要提交** `local.properties`、`.idea/`、`build/`、任何真实 token / 授权码 / 指纹。
- **截图属于代码**：仓库公开，截图前确认画面里没有二维码、授权码、账号会话令牌。
