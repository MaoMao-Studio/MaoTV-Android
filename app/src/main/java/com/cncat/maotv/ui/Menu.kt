package com.cncat.maotv.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.MarqueeSpacing
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.cncat.maotv.AppModel
import com.cncat.maotv.core.Category
import com.cncat.maotv.core.Channel
import com.cncat.maotv.core.Theme
import com.cncat.maotv.net.Clock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Calendar

// ───────────── 小工具 ─────────────

/** 放得下就静止；放不下时显示“…”，active 时停 1 秒后循环滚动 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MarqueeText(text: String, size: Number, color: Color, bold: Boolean, active: Boolean, modifier: Modifier = Modifier) {
    if (active) {
        Txt(
            text, size, color = color, bold = bold, softWrap = false, overflow = TextOverflow.Clip,
            modifier = modifier.basicMarquee(
                iterations = Int.MAX_VALUE, initialDelayMillis = 1000,
                spacing = MarqueeSpacing(u(40)), velocity = u(36),
            ),
        )
    } else {
        Txt(text, size, color = color, bold = bold, modifier = modifier)
    }
}

private suspend fun LazyListState.centerOn(index: Int, viewportPx: Int, rowPx: Int, animate: Boolean) {
    val off = -(viewportPx / 2 - rowPx / 2)
    if (animate) animateScrollToItem(index, off) else scrollToItem(index, off)
}

// ───────────── 左侧分类 ─────────────

/** 分类栏宽度（按最长的分类名算，保证文字完整显示）和文字列宽度 */
@Composable
fun rememberCategoryMetrics(cats: List<Category>): Pair<Dp, Dp> {
    val measurer = rememberTextMeasurer()
    val unit = LocalUnit.current
    val density = LocalDensity.current
    val style = TextStyle(fontSize = us(14), fontWeight = FontWeight.Bold)
    val titles = cats.map { it.title }
    val widestPx = remember(titles, unit, density.density) {
        titles.maxOfOrNull { measurer.measure(AnnotatedString(it), style).size.width } ?: 0
    }
    val widestUnits = widestPx / unit
    val sidebar = u(minOf(maxOf(widestUnits + 44f, 96f), 230f))
    val textW = with(density) { widestPx.toDp() } + u(2)
    return sidebar to textW
}

@Composable
fun CategorySidebar(ui: UiState, cats: List<Category>, textW: Dp, modifier: Modifier = Modifier) {
    LazyColumn(
        modifier.fillMaxHeight(),
        verticalArrangement = Arrangement.spacedBy(u(6)),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(u(8)),
    ) {
        items(cats, key = { it.id }) { c ->
            val selected = c.id == ui.selectedCategoryId
            val fav = c.id == Category.FAVORITES_ID
            val shape = RoundedCornerShape(u(10))
            val remote = false   // 不再单独画“焦点白框”：高亮条就是焦点
            Box(
                Modifier.fillMaxWidth()
                    .clip(shape)
                    .background(if (selected) Theme.selectedFill(fav) else androidx.compose.ui.graphics.SolidColor(Color.Transparent), shape)
                    .border(
                        u(if (remote) 2 else 1),
                        if (remote) Color.White else if (selected) Theme.selectedBorder(fav) else Color.Transparent, shape,
                    )
                    .tap { ui.selectCategory(c.id); ui.onTouchActivity() }
                    .padding(horizontal = u(10), vertical = u(12)),
                contentAlignment = Alignment.Center,
            ) {
                Txt(
                    c.title, 14, bold = selected, softWrap = false, overflow = TextOverflow.Clip,
                    modifier = Modifier.width(textW),
                )
            }
        }
    }
}

// ───────────── 频道行 / 频道列表 ─────────────

@Composable
fun ChannelRow(
    channel: Channel,
    model: AppModel,
    favSet: Set<String>,
    selected: Boolean,
    marquee: Boolean,
    numberWidth: Dp,
    rowHeight: Dp,
    gold: Boolean,
    focused: Boolean,
    starFocused: Boolean,
    modifier: Modifier = Modifier,
    onTap: () -> Unit,
) {
    val fav = channel.tvId.isNotEmpty() && favSet.contains(channel.tvId)
    model.epg.tick   // 每分钟刷新“正在播出”
    val nowTitle = model.epg.nowNext(channel, Clock.now()).first?.title
    val programText = if (!nowTitle.isNullOrEmpty()) nowTitle else "暂无节目信息"
    val shape = RoundedCornerShape(u(10))
    Row(
        modifier.fillMaxWidth().height(rowHeight)
            .clip(shape)
            .background(
                if (selected) Theme.selectedFill(gold) else androidx.compose.ui.graphics.SolidColor(Color.White.copy(alpha = 0.04f)),
                shape,
            )
            .border(
                u(1),
                if (selected) Theme.selectedBorder(gold) else Color.Transparent, shape,
            )
            .tap(onTap)
            .padding(start = u(10), end = u(3.5f)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Txt(
            "${channel.number}", 16, bold = true,
            color = if (selected && gold) Color.White else Theme.gold,
            modifier = Modifier.width(numberWidth), softWrap = false, overflow = TextOverflow.Clip,
        )
        Spacer(Modifier.width(u(10)))
        LogoView(channel.logo, 34)
        Spacer(Modifier.width(u(10)))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(u(2))) {
            MarqueeText(channel.name, 14, Color.White, true, marquee)
            MarqueeText(programText, 11, Color.White.copy(alpha = 0.6f), false, marquee)
        }
        Spacer(Modifier.width(u(3.5f)))
        val starShape = RoundedCornerShape(u(8))
        Box(
            Modifier.size(u(32)).clip(starShape)
                .tap { model.toggleFavorite(channel) },
            contentAlignment = Alignment.Center,
        ) {
            val c = if (fav) (if (selected && gold) Theme.favStar else Theme.gold)
            else Color.White.copy(alpha = if (channel.tvId.isEmpty()) 0.2f else 0.45f)
            Icon(if (fav) Ico.STAR else Ico.STAR_OUTLINE, u(17), c)
        }
    }
}

@Composable
private fun rememberNumberWidth(model: AppModel): Dp {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val digits = maxOf(2, model.channels.size.toString().length)
    val style = TextStyle(fontSize = us(16), fontWeight = FontWeight.Bold)
    val w = remember(digits, style, density.density) { measurer.measure(AnnotatedString("0"), style).size.width }
    return with(density) { (w * digits + 2).toDp() }
}

/**
 * 频道列表（横屏选单中间一栏 / 竖屏频道页共用）。
 * 一直留在界面里（横屏选单用位移滑出），选单每次弹出或换分类时，列表定位到正在播放的频道。
 * “我的收藏”(reorderable)：手指按住一行 1 秒后可拖动调整顺序，松手后提交到云端；失败则还原。
 */
@Composable
fun ChannelList(
    ui: UiState,
    channels: List<Channel>,
    currentId: Int?,
    scrollKey: String,
    menuOpen: Boolean,
    reorderable: Boolean,
    rowHeight: Number,
    gold: Boolean,
    focusIndex: Int,
    starFocus: Boolean,
    emptyText: String,
    modifier: Modifier = Modifier,
    onSelect: (Channel) -> Unit,
) {
    val model = ui.model
    val favSet = remember(model.favoriteIds) { model.favoriteIds.toHashSet() }
    val numberWidth = rememberNumberWidth(model)
    val rowDp = u(rowHeight)
    val gap = u(8)
    val density = LocalDensity.current
    val rowPx = with(density) { rowDp.roundToPx() }
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val touchSlop = LocalViewConfiguration.current.touchSlop

    val ids = channels.map { it.id }     // 用内容（而不是列表对象）作键：重组时拿到新对象也不会打断拖动
    val local = remember(ids) { mutableStateListOf<Channel>().also { it.addAll(channels) } }
    val drag = remember { DragState() }
    val shown: List<Channel> = if (reorderable) local else channels

    BoxWithConstraints(modifier.fillMaxSize()) {
        val viewportPx = constraints.maxHeight

        LaunchedEffect(scrollKey) {
            val idx = shown.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
            state.centerOn(idx + 1, viewportPx, rowPx, animate = false)
        }
        LaunchedEffect(focusIndex) {
            if (focusIndex >= 0 && ui.remoteActive && viewportPx > 0) state.centerOn(focusIndex + 1, viewportPx, rowPx, animate = true)
        }

        if (shown.isEmpty()) {
            Txt(
                emptyText, 12, color = Color.White.copy(alpha = 0.5f), maxLines = 4, align = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = u(40), start = u(12), end = u(12)),
            )
            return@BoxWithConstraints
        }

        fun checkSwap() {
            val k = drag.key ?: return
            val infos = state.layoutInfo.visibleItemsInfo
            val cur = infos.firstOrNull { it.key == k } ?: return
            val center = drag.fingerY - drag.grab
            val curIdx = local.indexOfFirst { it.id == k }
            if (curIdx < 0) return
            val next = infos.firstOrNull { it.index == cur.index + 1 && it.key != BOTTOM_KEY }
            val prev = infos.firstOrNull { it.index == cur.index - 1 && it.key != TOP_KEY }
            if (next != null && center > next.offset + next.size / 2f) {
                local.add(curIdx + 1, local.removeAt(curIdx))
            } else if (prev != null && center < prev.offset + prev.size / 2f) {
                local.add(curIdx - 1, local.removeAt(curIdx))
            }
        }

        if (reorderable) {
            LaunchedEffect(drag.key) {
                if (drag.key == null) return@LaunchedEffect
                while (isActive) {
                    val h = state.layoutInfo.viewportSize.height
                    val edge = rowPx.toFloat()
                    val y = drag.fingerY
                    val dy = when {
                        y < edge -> -(edge - y) / edge * 22f
                        y > h - edge -> (y - (h - edge)) / edge * 22f
                        else -> 0f
                    }
                    if (dy != 0f) state.scrollBy(dy)
                    checkSwap()
                    delay(16)
                }
            }
        }

        // 长按 1 秒后才开始拖动（把系统的长按时间改成 1 秒，用系统自带的“长按后拖动”手势，行为最可靠）
        val gestureModifier = if (!reorderable) Modifier else Modifier.pointerInput(ids) {
            var startIdx = -1
            var dragged: Channel? = null
            detectDragGesturesAfterLongPress(
                onDragStart = { pos ->
                    val hit = state.layoutInfo.visibleItemsInfo.firstOrNull {
                        it.key is Int && pos.y >= it.offset && pos.y <= it.offset + it.size
                    }
                    val ch = hit?.let { h -> local.firstOrNull { it.id == h.key } }
                    if (hit != null && ch != null) {
                        dragged = ch
                        startIdx = local.indexOf(ch)
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        ui.onTouchActivity()
                        drag.grab = pos.y - (hit.offset + hit.size / 2f)
                        drag.fingerY = pos.y
                        drag.key = ch.id
                    } else {
                        dragged = null
                    }
                },
                onDrag = { change, _ ->
                    if (dragged != null) {
                        change.consume()
                        drag.fingerY = change.position.y
                        ui.onTouchActivity()
                        checkSwap()
                    }
                },
                onDragEnd = {
                    val ch = dragged
                    drag.key = null
                    dragged = null
                    if (ch != null) {
                        val endIdx = local.indexOf(ch)
                        if (endIdx >= 0 && endIdx != startIdx) {
                            scope.launch {
                                val ok = ui.model.moveFavorite(ch, endIdx)
                                if (!ok) { local.clear(); local.addAll(channels) }
                            }
                        }
                    }
                },
                onDragCancel = {
                    drag.key = null
                    dragged = null
                    local.clear(); local.addAll(channels)
                },
            )
        }

        val baseVc = LocalViewConfiguration.current
        val vc1s = remember(baseVc) {
            object : androidx.compose.ui.platform.ViewConfiguration by baseVc {
                override val longPressTimeoutMillis: Long get() = 1000L
            }
        }
        androidx.compose.runtime.CompositionLocalProvider(LocalViewConfiguration provides vc1s) {
        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize().then(gestureModifier).padding(horizontal = u(8)),
            verticalArrangement = Arrangement.spacedBy(gap),
        ) {
            item(key = TOP_KEY) { Spacer(Modifier.height(0.dp)) }
            items(shown, key = { it.id }) { ch ->
                val idx = shown.indexOf(ch)
                val dragged = drag.key == ch.id
                val selected = ch.id == currentId
                val animMod = if (dragged) Modifier.animateItem(fadeInSpec = null, placementSpec = null, fadeOutSpec = null)
                else Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null)
                // 遥控器 / 键盘操作时：高亮条跟着焦点走（焦点所在行高亮，正在播放的那行不再单独高亮）；触屏仍高亮正在播放的频道
                val hl = if (ui.remoteActive && focusIndex >= 0) idx == focusIndex else selected
                ChannelRow(
                    channel = ch, model = model, favSet = favSet, selected = hl,
                    marquee = menuOpen && hl, numberWidth = numberWidth, rowHeight = rowDp, gold = gold,
                    focused = ui.remoteActive && idx == focusIndex, starFocused = ui.remoteActive && idx == focusIndex && starFocus,
                    modifier = animMod
                        .zIndex(if (dragged) 1f else 0f)
                        .graphicsLayer {
                            if (dragged) {
                                val cur = state.layoutInfo.visibleItemsInfo.firstOrNull { it.key == ch.id }
                                if (cur != null) {
                                    translationY = (drag.fingerY - drag.grab) - (cur.offset + cur.size / 2f)
                                    scaleX = 1.03f; scaleY = 1.03f
                                    shadowElevation = 12f
                                }
                            }
                        },
                    onTap = { ui.onTouchActivity(); onSelect(ch) },
                )
            }
            item(key = BOTTOM_KEY) { Spacer(Modifier.height(0.dp)) }
        }
        }
    }
}

private const val TOP_KEY = "top"
private const val BOTTOM_KEY = "bottom"

private class DragState {
    var key by mutableStateOf<Int?>(null)
    var fingerY by mutableFloatStateOf(0f)
    var grab by mutableFloatStateOf(0f)
}

// ───────────── “节目单”把手 + 面板 ─────────────

@Composable
fun EPGHandle(ui: UiState, isOpen: Boolean, modifier: Modifier = Modifier) {
    val remote = false   // 遥控器 / 键盘不会再把焦点移到把手列
    Column(
        modifier.fillMaxHeight()
            .background(Theme.sidebar)
            .then(if (remote) Modifier.border(u(2), Color.White) else Modifier)
            .tap { ui.epgChannel = null; ui.epgOpen = !ui.epgOpen; ui.onTouchActivity() },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(u(4), Alignment.CenterVertically),
    ) {
        for (ch in listOf("节", "目", "单")) Txt(ch, 12, color = Color.White.copy(alpha = 0.75f), weight = FontWeight.Medium)
        Spacer(Modifier.height(u(4)))
        Icon(if (isOpen) Ico.CHEV2_LEFT else Ico.CHEV2_RIGHT, u(14), Color.White.copy(alpha = 0.75f))
    }
}

private class EpgRow(val start: Long, val stop: Long, val title: String, val header: String?)

private fun startOfDay(ms: Long): Long {
    val c = Calendar.getInstance()
    c.timeInMillis = ms
    c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
    return c.timeInMillis
}

private fun dayTitle(day: Long, now: Long): String {
    val diff = Math.round((startOfDay(day) - startOfDay(now)) / 86_400_000.0).toInt()
    return when (diff) { 0 -> "今天"; 1 -> "明天"; else -> TimeFmt.day(day) }
}

/** 节目单：只保留“正在播出”及之后的节目，并按天加标题 */
@Composable
fun EPGPanel(
    model: AppModel,
    channel: Channel?,
    modifier: Modifier = Modifier,
    showTitle: Boolean = true,
    scale: Float = 1f,
    scrolls: SharedFlow<Float>? = null,
) {
    val tick = model.epg.tick
    val rows = remember(channel, tick) {
        val now = Clock.now()
        val out = ArrayList<EpgRow>()
        if (channel != null) {
            var lastDay = -1L
            for (p in model.epg.schedule(channel)) {
                if (p.stop <= now) continue
                val day = startOfDay(maxOf(p.start, now))
                out.add(EpgRow(p.start, p.stop, p.title, if (day == lastDay) null else dayTitle(day, now)))
                lastDay = day
            }
        }
        out
    }
    val state = rememberLazyListState()
    LaunchedEffect(channel?.id) { state.scrollToItem(0) }
    if (scrolls != null) {
        LaunchedEffect(scrolls) { scrolls.collect { state.scrollBy(it) } }
    }
    val now = Clock.now()
    Column(modifier.fillMaxSize()) {
        if (showTitle) {
            Txt(
                channel?.let { "${it.name} · 节目单" } ?: "节目单", 15, bold = true,
                modifier = Modifier.padding(horizontal = u(12), vertical = u(12)),
            )
            Box(Modifier.fillMaxWidth().height(u(1)).background(Color.White.copy(alpha = 0.1f)))
        }
        if (rows.isEmpty()) {
            Txt(
                "暂无节目信息", 12, color = Color.White.copy(alpha = 0.5f), align = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = u(30)),
            )
        } else {
            LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
                items(rows.size) { i ->
                    val r = rows[i]
                    val isNow = r.start <= now && now < r.stop
                    Column(Modifier.fillMaxWidth()) {
                        r.header?.let {
                            Txt(
                                it, 12 * scale, color = Color.White.copy(alpha = 0.5f), weight = FontWeight.SemiBold,
                                modifier = Modifier.padding(start = u(12), top = u(12), bottom = u(4)),
                            )
                        }
                        Row(
                            Modifier.fillMaxWidth()
                                .background(if (isNow) Theme.purpleA.copy(alpha = 0.55f) else Color.Transparent)
                                .padding(horizontal = u(12), vertical = u(8)),
                            horizontalArrangement = Arrangement.spacedBy(u(10)),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Txt(
                                TimeFmt.hm(r.start), 13 * scale, weight = FontWeight.SemiBold,
                                color = if (isNow) Theme.gold else Color.White.copy(alpha = 0.7f),
                            )
                            Txt(
                                r.title, 13 * scale, bold = isNow, maxLines = 4,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}
