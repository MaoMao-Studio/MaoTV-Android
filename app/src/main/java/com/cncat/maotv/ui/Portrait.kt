package com.cncat.maotv.ui

import androidx.compose.foundation.background
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import com.cncat.maotv.core.Category
import com.cncat.maotv.core.Channel
import com.cncat.maotv.core.Theme

/** 竖屏时视频下面的整块区域：第一页“猫TV”= 频道分类；点一个分类进入第二页 = 该分类的频道列表 */
@Composable
fun PortraitBrowser(ui: UiState, modifier: Modifier = Modifier) {
    val model = ui.model
    val player = ui.player
    val page = ui.portraitPage
    val catId = ui.selectedCategoryId
    var query by remember { mutableStateOf("") }
    var pageKey by remember { mutableIntStateOf(0) }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val searchFocus = remember { FocusRequester() }
    val headerGap = u(8)
    // 搜索框只有手指点它才会获得焦点；否则遥控器 / 键盘的方向键会把焦点带进去，数字键和上下键就被输入框吃掉
    var editing by remember { mutableStateOf(false) }

    LaunchedEffect(page) {
        query = ""
        editing = false
        focusManager.clearFocus()
        if (page == PortraitPage.CHANNELS) pageKey++ else model.syncFavoriteShown()
    }
    LaunchedEffect(editing) {
        if (editing) {
            kotlinx.coroutines.delay(50)
            try { searchFocus.requestFocus(); keyboard?.show() } catch (_: Throwable) { editing = false }
        }
    }
    DisposableEffect(Unit) { onDispose { ui.textInputActive = false } }

    Column(modifier.background(Color.Black)) {
        // ── 顶栏：标题尽量整栏居中；只有被左边的返回钮 / 右边的按钮挤到时才偏移 ──
        Layout(
            content = {
                Box(Modifier.layoutId("left"), contentAlignment = Alignment.CenterStart) {
                    if (page == PortraitPage.CHANNELS) {
                        Row(
                            Modifier.height(u(40)).tap { ui.portraitPage = PortraitPage.CATEGORIES }.padding(start = u(6), end = u(6)),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(u(3)),
                        ) {
                            Icon(Ico.CHEV_LEFT, u(17), Color.White)
                            Txt("频道分类", 16, softWrap = false)
                        }
                    }
                }
                val title = if (page == PortraitPage.CATEGORIES) "猫TV" else (model.categories.firstOrNull { it.id == catId }?.title ?: "")
                Txt(title, 17, bold = true, align = TextAlign.Center, modifier = Modifier.layoutId("title"))
                Row(Modifier.layoutId("right"), verticalAlignment = Alignment.CenterVertically) {
                    HeaderIcon(if (player.isPaused) Ico.PLAY else Ico.PAUSE) { player.togglePause() }
                    HeaderIcon(Ico.WAVEFORM) { ui.openPopup(PopupKind.AUDIO) }
                    HeaderIcon(Ico.CAPTIONS) { ui.openPopup(PopupKind.SUBTITLE) }
                    HeaderIcon(Ico.GEAR) { ui.openSettings() }
                    HeaderIcon(Ico.INFO) { ui.openDiagnostics() }
                }
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = u(6)).height(u(46)),
        ) { measurables, constraints ->
            val w = constraints.maxWidth
            val h = constraints.maxHeight
            val gap = headerGap.roundToPx()
            val loose = constraints.copy(minWidth = 0, minHeight = 0)
            val left = measurables.first { it.layoutId == "left" }.measure(loose)
            val right = measurables.first { it.layoutId == "right" }.measure(loose)
            val maxTitle = (w - left.width - right.width - 2 * gap).coerceAtLeast(0)
            val title = measurables.first { it.layoutId == "title" }.measure(loose.copy(maxWidth = maxTitle))
            val centered = (w - title.width) / 2
            val minX = left.width + gap
            val maxX = w - right.width - gap - title.width
            val x = centered.coerceIn(minX, maxOf(minX, maxX))
            layout(w, h) {
                left.placeRelative(0, (h - left.height) / 2)
                right.placeRelative(w - right.width, (h - right.height) / 2)
                title.placeRelative(x, (h - title.height) / 2)
            }
        }

        // ── 搜索栏 ──
        Row(
            Modifier.fillMaxWidth().padding(start = u(10), end = u(10), bottom = u(8)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(u(8)),
        ) {
            if (page == PortraitPage.CHANNELS) {
                Box(Modifier.width(u(42)).height(u(40)).tap { ui.portraitEPG = true }, contentAlignment = Alignment.Center) {
                    Icon(Ico.CALENDAR, u(27), Color.White)
                }
            }
            Row(
                Modifier.weight(1f).height(u(38)).clip(RoundedCornerShape(u(10))).background(Color.White.copy(alpha = 0.1f))
                    .padding(horizontal = u(12)),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(u(8)),
            ) {
                Icon(Ico.SEARCH, u(16), Color.White.copy(alpha = 0.5f))
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) Txt("搜索", 16, color = Color.White.copy(alpha = 0.4f))
                    BasicTextField(
                        value = query, onValueChange = { query = it }, singleLine = true,
                        textStyle = TextStyle(color = Color.White, fontSize = us(16)),
                        cursorBrush = SolidColor(Color.White),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                        modifier = Modifier.fillMaxWidth()
                            .focusRequester(searchFocus)
                            .focusProperties { canFocus = editing }
                            .onFocusChanged {
                                ui.textInputActive = it.isFocused
                                if (!it.isFocused && editing) editing = false
                            },
                    )
                    if (!editing) {
                        // 未编辑时盖一层：手指点一下才进入输入状态
                        Box(Modifier.fillMaxSize().tap { editing = true })
                    }
                }
                if (query.isNotEmpty()) {
                    Box(Modifier.tap { query = "" }) { Icon(Ico.CLOSE_CIRCLE, u(18), Color.White.copy(alpha = 0.4f), innerColor = Color(0xFF2A2A33)) }
                }
            }
        }

        // ── 内容：分类页 ⇄ 频道页 左右滑动过渡（点分类：分类页向左滑出；点“频道分类”：频道页向右滑出）──
        val q = query.trim()
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).clipToBounds()) {
            val w = maxWidth
            val progress by animateFloatAsState(if (page == PortraitPage.CHANNELS) 1f else 0f, tween(260), label = "pgslide")
            if (q.isNotEmpty()) {
                val list = remember(q, page, catId, model.channels, model.favoriteShown) {
                    val base = if (page == PortraitPage.CHANNELS) model.channels(catId) else model.channels
                    base.filter { it.name.contains(q, ignoreCase = true) || it.number.toString() == q }
                }
                ChannelList(
                    ui, list, player.current?.id, "search#$q", menuOpen = true, reorderable = false,
                    rowHeight = ROW_H, gold = false, focusIndex = -1, starFocus = false, emptyText = "没有找到相关频道",
                ) { ch -> ui.selectChannelPortrait(ch, list) }
            } else {
                if (progress < 1f) {
                    Box(Modifier.fillMaxSize().offset(x = -w * progress)) { CategoryPage(ui) }
                }
                if (progress > 0f || page == PortraitPage.CHANNELS) {
                    Box(Modifier.fillMaxSize().offset(x = w * (1f - progress))) {
                        val list = remember(catId, model.channels, model.favoriteShown) { model.channels(catId) }
                        val fav = catId == Category.FAVORITES_ID
                        ChannelList(
                            ui, list, player.current?.id, "$catId#${ui.portraitShowKey}#$pageKey", menuOpen = true,
                            reorderable = fav, rowHeight = ROW_H, gold = fav, focusIndex = -1, starFocus = false,
                            emptyText = "这里还没有频道\n在频道右侧点星标即可收藏",
                        ) { ch -> ui.selectChannelPortrait(ch, list) }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeaderIcon(kind: Ico, onClick: () -> Unit) {
    Box(Modifier.width(u(34)).height(u(40)).tap(onClick), contentAlignment = Alignment.Center) {
        Icon(kind, u(19), Color.White)
    }
}

@Composable
private fun CategoryPage(ui: UiState) {
    val model = ui.model
    LazyColumn(
        Modifier.fillMaxSize(), contentPadding = PaddingValues(u(8)),
        verticalArrangement = Arrangement.spacedBy(u(8)),
    ) {
        items(model.categories, key = { it.id }) { c ->
            val selected = c.id == ui.selectedCategoryId
            val fav = c.id == Category.FAVORITES_ID
            val shape = RoundedCornerShape(u(10))
            Row(
                Modifier.fillMaxWidth().height(u(ROW_H)).clip(shape)
                    .background(if (selected) Theme.selectedFill(fav) else SolidColor(Color.White.copy(alpha = 0.04f)), shape)
                    .border(u(1), if (selected) Theme.selectedBorder(fav) else Color.Transparent, shape)
                    .tap {
                        ui.selectCategory(c.id)
                        ui.portraitPage = PortraitPage.CHANNELS
                    }
                    .padding(horizontal = u(16)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Txt(c.title, 18, bold = selected, weight = if (selected) null else androidx.compose.ui.text.font.FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(u(8)))
                Txt("${model.count(c.id)}", 17, color = Color.White.copy(alpha = if (selected) 0.9f else 0.7f))
            }
        }
    }
}

/** 竖屏：整屏节目单（只显示选定频道正在播放及之后的节目） */
@Composable
fun PortraitEPGView(ui: UiState, insets: Insets) {
    val ch: Channel? = ui.player.current
    Column(Modifier.fillMaxSize().background(Color.Black).tap { }) {
        Spacer(Modifier.height(insets.top))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = u(8)).height(u(44)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.height(u(44)).tap { ui.portraitEPG = false }.padding(start = u(6)),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(u(3)),
            ) {
                Icon(Ico.CHEV_LEFT, u(17), Color.White)
                Txt("返回", 16)
            }
            Txt(
                ch?.let { "${it.name} · 节目单" } ?: "节目单", 16, bold = true, align = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(u(70)))
        }
        Box(Modifier.fillMaxWidth().height(u(1)).background(Color.White.copy(alpha = 0.1f)))
        EPGPanel(ui.model, ch, Modifier.weight(1f), showTitle = false, scale = 1.2f)
        Spacer(Modifier.height(insets.bottom))
    }
}

/** 分类行和频道行统一的行高（设计点） */
private const val ROW_H = 54
