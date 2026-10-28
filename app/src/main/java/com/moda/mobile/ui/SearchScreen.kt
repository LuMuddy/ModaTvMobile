package com.moda.mobile.ui

import android.os.SystemClock
import android.view.View
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.moda.mobile.R
import com.moda.mobile.model.AppError
import com.moda.mobile.model.VideoItem
import com.moda.mobile.ui.components.EmptyState
import com.moda.mobile.ui.components.ErrorState
import com.moda.mobile.ui.components.LoadingState
import com.moda.mobile.ui.components.SectionTitle
import com.moda.mobile.ui.components.VideoCard
import com.moda.mobile.ui.components.WordChip
import com.moda.mobile.ui.components.appErrorText
import com.moda.mobile.ui.theme.ModaColors
import com.moda.mobile.ui.theme.ModaType
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 手机竖屏 2 列，与首页同一档：搜索结果也是海报墙，卡宽一致才不会有"两套网格"的观感 */
private const val RESULT_COLUMNS = 2

/** 占位提示换词节奏：与参考实现一致，3.5 秒一条 */
private const val HINT_INTERVAL_MS = 3_500L

/** 换词的淡入淡出时长：换词是"文字变了"，硬跳会让人以为是自己误触 */
private const val HINT_FADE_MS = 420

/** 热词条自动前进节奏：4 秒一格，走到末尾回第 0 个 */
private const val RAIL_INTERVAL_MS = 4_000L

/** 手指刚碰过热词条时的暂停时长：刚按过就自己滑走等于把目标挪掉 */
private const val RAIL_PAUSE_MS = 8_000L

/**
 * 搜索页：输入框 + 热词/历史 + 结果网格。
 *
 * 热词与历史都通过参数注入，屏幕自身不碰网络也不碰 DataStore：
 * 热词慢或挂掉只表现为「少一块」，不会把搜索页拖进错误态。
 *
 * 进屏自动聚焦输入框并拉起软键盘：手机上没有实体键盘，不自动聚焦的话
 * 用户要点两下（点框 + 等 IME）才能开始打字，而"进来就是来搜东西的"是本页唯一的用途。
 * 实现刻意不用任何焦点机具（契约 §1：手机端不出现 TV 端那套焦点 API），
 * 而是让 ComposeView 自己按 FOCUS_FORWARD 找第一个可聚焦子节点：
 * 那个节点就是下面这个输入框——本文件里既没有焦点框，也没有焦点相关 API。
 */
@Composable
fun SearchScreen(
    onSearch: suspend (String) -> List<VideoItem>,
    onOpenDetail: (String, String) -> Unit,
    onBack: () -> Unit,
    loadHotWords: suspend () -> List<String> = { emptyList() },
    history: List<String> = emptyList(),
    onRecordSearch: (String) -> Unit = {},
    onClearHistory: () -> Unit = {},
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<VideoItem>?>(null) }
    var searching by remember { mutableStateOf(false) }
    /** 搜索失败只存错误码：文案在渲染处过 appErrorText，切系统语言才能跟着变 */
    var error by remember { mutableStateOf<AppError?>(null) }
    var lastQuery by remember { mutableStateOf("") }
    var hotWords by remember { mutableStateOf<List<String>>(emptyList()) }
    /** 输入框占位里正在轮播的热词：输入为空时按「搜索」就用它当查询词 */
    var hintWord by remember { mutableStateOf("") }

    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val rootView = LocalView.current

    fun submit(word: String) {
        val q = word.trim()
        if (q.isEmpty() || searching) return
        // 搜索后键盘必须收起来：不收的话结果被 IME 顶掉一半，用户还以为没搜到
        keyboard?.hide()
        focusManager.clearFocus()
        lastQuery = q
        query = q
        searching = true
        error = null
        scope.launch {
            try {
                val found = onSearch(q)
                results = found
                // 只有真的搜成功才进历史：失败/取消不该污染记录
                onRecordSearch(q)
            } catch (e: CancellationException) {
                // 页面被销毁时的取消不该显示成「搜索失败」
                throw e
            } catch (e: Exception) {
                // 屏幕层不碰 data/Api：只按 JDK 的 IOException 粗分「网络 / 未知」，
                // 更细的错误码（HTTP 状态等）在 Repo 侧已经进了日志
                error = if (e.isNetworkFailure()) AppError.Network else AppError.Unknown
                results = null
            } finally {
                searching = false
            }
        }
    }

    /**
     * 输入为空时的提交动作：用输入框里正轮播的那个热词。
     * 用户看到的提示就是即将搜的词——看得见却搜不了，那颗搜索键才像是坏的。
     */
    fun submitFromField() {
        submit(query.ifBlank { hintWord })
    }

    // 首帧还没完成布局时请求焦点会失败，等两帧再要，之后立刻显式拉起输入法
    LaunchedEffect(Unit) {
        withFrameNanos { }
        withFrameNanos { }
        runCatching { rootView.requestFocus(View.FOCUS_FORWARD) }
        keyboard?.show()
    }

    // 热词异步加载：不挂在首帧上，接口慢或挂了输入框照常可用，来了再显示
    LaunchedEffect(Unit) {
        hotWords = try {
            loadHotWords()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Repo 侧已兜底，这里只防注入的实现抛异常；失败整块不显示
            emptyList()
        }
    }

    Box(Modifier.fillMaxSize().background(ModaColors.Bg).imePadding()) {
        Column(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
            // 返回画在左上角的覆盖层里，而不是 Row 的第一个格子：
            // 进屏自动聚焦靠 Compose 按组合顺序找第一个可聚焦节点（见上面的注释），
            // 返回若排在输入框前面就会把焦点抢走，进屏后得再点一下才能打字。
            // 覆盖层后组合，命中测试先于输入框，所以左侧 48dp 那块点下去是返回。
            Box(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 给覆盖层的返回键留出正好一个 IconButton 的宽度（M3 最小触控区 48dp ≥ 44dp）
                    Spacer(Modifier.width(48.dp))
                    OutlinedTextField(
                        value = query,
                        onValueChange = { next ->
                            query = next
                            // 清空输入就回到「未输入」：热词与历史重新出现，否则用户没有退路
                            if (next.isBlank()) {
                                results = null
                                error = null
                                lastQuery = ""
                            }
                        },
                        modifier = Modifier.weight(1f),
                        // 空框里轮播热词：一进屏就告诉用户"现在大家都搜什么"。
                        // 用户一开始打字 placeholder 就不再生效，正常提示也就回来了
                        placeholder = {
                            HotWordHint(words = hotWords, onCurrentChange = { hintWord = it })
                        },
                        // 搜索并入框尾：顶行只剩「返回 + 输入框」两个元素。
                        // 有输入时先给清除键，搜索键固定在最后，位置不随输入跳动
                        trailingIcon = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (query.isNotEmpty()) {
                                    IconButton(onClick = { query = ""; results = null; error = null; lastQuery = "" }) {
                                        Icon(
                                            imageVector = Icons.Filled.Close,
                                            contentDescription = stringResource(R.string.common_clear),
                                            tint = ModaColors.TextDim,
                                        )
                                    }
                                }
                                IconButton(onClick = { submitFromField() }) {
                                    Icon(
                                        imageVector = Icons.Filled.Search,
                                        contentDescription = stringResource(R.string.search_submit),
                                        tint = ModaColors.Text,
                                    )
                                }
                            }
                        },
                        // IME 右下角那颗键就是「搜索」：触摸端最自然的提交动作在这里，
                        // 框尾那颗放大镜是它的备用入口，两者走同一个提交动作
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { submitFromField() }),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = ModaColors.Surface,
                            unfocusedContainerColor = ModaColors.Surface,
                            focusedBorderColor = ModaColors.AccentSoft,
                            unfocusedBorderColor = ModaColors.SurfaceHigh,
                            focusedTextColor = ModaColors.Text,
                            unfocusedTextColor = ModaColors.Text,
                            cursorColor = ModaColors.AccentSoft,
                            focusedPlaceholderColor = ModaColors.TextDim,
                            unfocusedPlaceholderColor = ModaColors.TextDim,
                        ),
                    )
                }
                // 返回钉在整行最左：手机端二级页的返回一律在左上角，右侧不再出现
                IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.search_back),
                        tint = ModaColors.Text,
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            val shown = results
            // 先落成 val 再用：error 是委托属性，when 分支里智能转换不到非空 AppError
            val shownError = error
            val hint = when {
                searching -> null
                shownError != null -> null
                shown == null -> stringResource(R.string.search_idle_hint)
                shown.isEmpty() -> stringResource(R.string.search_no_result, lastQuery)
                else -> stringResource(R.string.search_result_count, shown.size)
            }
            if (hint != null) {
                Text(hint, style = ModaType.Caption, color = ModaColors.TextDim)
                Spacer(Modifier.height(8.dp))
            }

            // weight(1f)：Column 里 fillMaxSize 拿到的是整列高度，网格视口会比屏幕长出一截
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    searching -> LoadingState(stringResource(R.string.search_loading, lastQuery))
                    shownError != null -> ErrorState(
                        message = appErrorText(shownError),
                        onRetry = { submit(query) },
                    )
                    shown == null -> IdlePanel(
                        hotWords = hotWords,
                        history = history,
                        onPick = { word -> submit(word) },
                        onClearHistory = onClearHistory,
                    )
                    // 空结果也给一个「重搜」出口：网络抖动导致的空结果真能救回来
                    shown.isEmpty() -> EmptyState(
                        message = stringResource(R.string.search_empty_retry_hint),
                        onRetry = { submit(query) },
                    )
                    else -> LazyVerticalGrid(
                        columns = GridCells.Fixed(RESULT_COLUMNS),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        itemsIndexed(
                            items = shown,
                            key = { i, item -> "${item.cid}-${item.vid}-$i" },
                        ) { _, item ->
                            VideoCard(
                                item = item,
                                onClick = { onOpenDetail(item.cid, item.title) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 未输入时的两块内容，顺序是需求定的：热词在上、历史在下。
 * 两块都为空（热词没拉到 + 没有历史）时退回一句空态文案。
 *
 * 这个面板只在「还没有结果」时被组合（结果非空/搜索中走的是网格与加载态），
 * 所以"有结果不轮播"不需要额外判断：那两个轮播协程此时根本不存在。
 */
@Composable
private fun IdlePanel(
    hotWords: List<String>,
    history: List<String>,
    onPick: (String) -> Unit,
    onClearHistory: () -> Unit,
) {
    if (hotWords.isEmpty() && history.isEmpty()) {
        EmptyState(message = stringResource(R.string.search_empty_idle))
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        if (hotWords.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle(stringResource(R.string.search_hotword_title))
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.search_hotword_tap_hint),
                    style = ModaType.Caption,
                    color = ModaColors.TextDim,
                )
            }
            HotWordRail(words = hotWords, onPick = onPick)
        }
        if (history.isNotEmpty()) {
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                SectionTitle(stringResource(R.string.search_history_title))
                Spacer(Modifier.width(10.dp))
                TextButton(onClick = onClearHistory) {
                    Text(
                        text = stringResource(R.string.search_clear_history),
                        style = ModaType.Caption,
                        color = ModaColors.TextDim,
                    )
                }
            }
            ChipRail(words = history, keyPrefix = "h", onPick = onPick)
        }
    }
}

/**
 * 历史词条轨道。热词那条要多做"自动前进 + 触摸暂停"，见 [HotWordRail]，
 * 所以两条不能合并；尺寸/圆角/间距仍是同一份取值，改一处要照着改另一处。
 * 横滑交给 LazyRow，行内没有竖向手势。
 */
@Composable
private fun ChipRail(
    words: List<String>,
    keyPrefix: String,
    onPick: (String) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(count = words.size, key = { index -> "$keyPrefix-${words[index]}" }) { index ->
            WordChip(text = words[index], onClick = { onPick(words[index]) })
        }
    }
}

/**
 * 热词条轨道：每 4 秒自动前进一格，走到末尾回第 0 个。
 *
 * 触摸端没有焦点机具（TV 端那套"焦点进块才轮播"在手机上不成立），暂停只能看手指：
 * 用户滑动或点过词条后暂停 8 秒再恢复——刚按下去列表就自己滑走，等于把目标从手底下挪开。
 * 手势靠 [pointerInput] 在 Initial 阶段"只看不消费"地记下时刻：LazyRow 没有 interactionSource 参数，
 * 而自动前进与用户滑动共用同一个 LazyListState，用 isScrollInProgress 分不出是谁在滚。
 * 热词 ≤1 个时协程直接返回，不留一个只会空转的 while。
 */
@Composable
private fun HotWordRail(
    words: List<String>,
    onPick: (String) -> Unit,
) {
    val state = rememberLazyListState()
    /** 最后一次被手指碰到的时刻（elapsedRealtime 口径，不受系统时间跳变影响） */
    var lastTouchAt by remember { mutableStateOf(0L) }

    LaunchedEffect(words) {
        if (words.size <= 1) return@LaunchedEffect
        while (true) {
            val quiet = SystemClock.elapsedRealtime() - lastTouchAt
            if (quiet < RAIL_PAUSE_MS) {
                // 刚被碰过：等足暂停余量后再重新判断，别在这里就前进
                delay(RAIL_PAUSE_MS - quiet)
                continue
            }
            delay(RAIL_INTERVAL_MS)
            // 睡着的这 4 秒里可能又被碰了，落地前再看一眼
            if (SystemClock.elapsedRealtime() - lastTouchAt < RAIL_PAUSE_MS) continue
            // 起点每轮从 state 现取：用户手动滑到哪，下一格就从哪接着走
            val next = (state.firstVisibleItemIndex + 1) % words.size
            // 用户的手势会抢占滚动权，animate 会抛 CancellationException；
            // 那不是本协程被取消（真被取消时下一次 delay 会立刻再抛出），吞掉继续
            runCatching { state.animateScrollToItem(next) }
        }
    }

    LazyRow(
        state = state,
        modifier = Modifier
            .fillMaxWidth()
            // 只看不消费：滚动仍然归 LazyRow，这里只把"用户刚碰过"记下来
            .pointerInput(words) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.any { it.pressed }) {
                            lastTouchAt = SystemClock.elapsedRealtime()
                        }
                    }
                }
            },
        contentPadding = PaddingValues(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(count = words.size, key = { index -> "w-${words[index]}" }) { index ->
            WordChip(text = words[index], onClick = { onPick(words[index]) })
        }
    }
}

/**
 * 输入框占位提示里的热词轮播：3.5 秒换一条，淡入淡出 420ms。
 *
 * 当前词通过 [onCurrentChange] 实时交回父级：输入为空时按搜索就用它，
 * 否则用户看得见提示却搜不了，那颗键像是坏的。
 * 两个 LaunchedEffect 的 key 都是热词列表本身（不是 Unit）：列表刷新后
 * 旧循环必须随组合一起被取消，不能接着在旧列表上跑。
 */
@Composable
private fun HotWordHint(
    words: List<String>,
    onCurrentChange: (String) -> Unit,
) {
    var index by remember(words) { mutableStateOf(0) }

    LaunchedEffect(words) {
        // 一条热词没有"轮播"可言：不启动协程，免得空转
        if (words.size <= 1) return@LaunchedEffect
        while (true) {
            delay(HINT_INTERVAL_MS)
            index = (index + 1) % words.size
        }
    }

    val word = words.getOrNull(index)
    LaunchedEffect(word) { onCurrentChange(word.orEmpty()) }

    Crossfade(
        targetState = word,
        animationSpec = tween(HINT_FADE_MS),
        label = "hotword-hint",
    ) { shown ->
        Text(
            text = if (shown.isNullOrBlank()) {
                stringResource(R.string.search_hint_input)
            } else {
                stringResource(R.string.search_hint_hotword, shown)
            },
            style = ModaType.Body,
            color = ModaColors.TextDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 只按异常类型判「是不是网络掉线」，不看任何 message：
 * Repo 抛出的 ApiException 把底层 IOException 挂在 cause 上（见 Repo.asAppError）。
 * 屏幕层因此不必 import data/Api，也就不会把数据层拖进 UI。
 */
private fun Throwable.isNetworkFailure(): Boolean {
    // 不用 generateSequence 追 cause：异常链成环（A.cause = B、B.cause = A）时它会死循环
    var current: Throwable? = this
    var hops = 0
    while (current != null && hops < 8) {
        if (current is IOException) return true
        current = current.cause
        hops++
    }
    return false
}