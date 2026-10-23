package com.cncat.maotv.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.Image
import com.cncat.maotv.MaoApp
import com.cncat.maotv.R
import com.cncat.maotv.core.Category
import com.cncat.maotv.core.Theme
import com.cncat.maotv.player.PlayerController
import kotlin.math.abs

private const val HANDLE_W = 24f
private const val LIST_W = 270f
private const val EPG_W = 240f
private val SLIDE = tween<androidx.compose.ui.unit.Dp>(250)

/**
 * 播放主界面。
 * 横屏：画面（最大的 16:9 区域居中）+ 左侧选单 + 右下角按钮 +（选单展开时）右上角时钟 + 底部 OSD。
 * 竖屏：顶部让出系统状态栏（不与画面重叠）→ 16:9 画面 → 分类 / 频道浏览页；不显示“台号/频道名/节目”信息条。
 */
@Composable
fun PlayerScreen(app: MaoApp, ui: UiState, applySystemBars: (portrait: Boolean, hidden: Boolean) -> Unit) {
    val model = app.model
    val player = app.player
    val sysInsets = currentInsets()
    val cutInsets = cutoutInsets()

    LaunchedEffect(Unit) { ui.startPlayback() }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val portrait = maxHeight > maxWidth
        // 竖屏：系统状态栏占位；横屏：沉浸式全屏，只避让刘海
        val insets = if (portrait) sysInsets else cutInsets
        SideEffect {
            ui.portrait = portrait
            applySystemBars(portrait, !portrait)       // 竖屏：显示状态栏；横屏：沉浸式全屏
        }
        val pip = ui.inPip

        // ───── 画面：横竖屏共用同一个 AndroidView（不随版式切换重建）─────
        val videoModifier = when {
            pip -> Modifier.fillMaxSize()
            portrait -> Modifier.align(Alignment.TopCenter).padding(top = insets.top).fillMaxWidth().aspectRatio(16f / 9f)
            else -> Modifier.align(Alignment.Center).aspectRatio(16f / 9f)
        }
        Box(videoModifier.background(Color.Black).clip(androidx.compose.ui.graphics.RectangleShape)) {
            if (player.session != null) {
                key(player.engineKind) {
                    val kind = player.engineKind
                    AndroidView(
                        factory = { ctx -> player.engineInstance(kind).createView(ctx) },
                        modifier = Modifier.fillMaxSize(),
                        onRelease = { player.engineInstance(kind).releaseView() },
                    )
                }
            }
            if (portrait && !pip) {
                // 竖屏视频区：点一下 = 暂停 / 恢复；上下滑换台、左右滑调音量
                Box(Modifier.fillMaxSize().gestures(ui, tapToggleMenu = false))
            }
        }

        if (pip) {
            StatusOverlay(player, 200.0)
            return@BoxWithConstraints
        }

        if (portrait) {
            PortraitLayers(ui, insets, maxWidth)
        } else {
            LandscapeLayers(ui, insets)
        }

        // ───── 诊断 / 设置 / 确认框（横竖屏共用）─────
        if (ui.diagVisible) DiagnosticsPanel(ui, portrait)
        if (ui.settingsVisible) SettingsPanel(ui, portrait)
        ui.popup?.let { TrackPopup(ui, it, portrait, insets) }
        if (ui.sslConfirm) SslConfirmDialog(ui)
    }
}

// ───────────────────────── 横屏 ─────────────────────────

@Composable
private fun androidx.compose.foundation.layout.BoxScope.LandscapeLayers(ui: UiState, insets: Insets) {
    val model = ui.model
    val player = ui.player

    // 手势层：点空白处弹出 / 收起选单；上下滑换台；左右滑调音量
    Box(Modifier.fillMaxSize().gestures(ui, tapToggleMenu = true))

    StatusOverlay(player, 280.0, hold = ui.pauseIconShown, retryFocused = ui.remoteActive && !ui.menuVisible)
    PauseIndicator(ui, clickable = true, modifier = Modifier.align(Alignment.Center))

    // 底部 OSD 区：换台信息条 → 提示条 → 音量条（自下而上）
    BottomOsdStack(ui, widthFraction = 0.72f, maxWidthDp = 900, bottomPad = insets.bottom + u(18), showChannelInfo = true)

    // 左侧选单
    MenuPanel(ui, insets)

    // 右下角按钮 + 右上角时钟：同时出现、同时消失
    val menuOn = ui.menuVisible
    AnimatedVisibility(
        visible = menuOn, enter = fadeIn(tween(250)), exit = fadeOut(tween(250)),
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(Modifier.fillMaxSize()) {
            if (ui.buttonsShown) ControlButtons(ui, insets, Modifier.align(Alignment.BottomEnd))
            // 时钟：右边缘与底部按钮行的最右侧按钮对齐（用同一个 END_PADDING），
            // 垂直中心对齐频道列表第一行高亮条中心。
            Box(
                Modifier.align(Alignment.TopEnd)
                    .padding(top = insets.top + u(ListMetrics.LIST_PADDING), end = insets.right + u(ControlRow.END_PADDING))
                    .height(u(ListMetrics.ROW_HEIGHT)),
                contentAlignment = Alignment.CenterEnd,
            ) { ClockBox() }
        }
    }

    // 数字选台：右上角（此时选单已被收回，不会和时钟同时出现）
    if (ui.digits.isNotEmpty()) {
        Box(
            Modifier.align(Alignment.TopEnd)
                .padding(top = insets.top + u(14), end = insets.right + u(ControlRow.END_PADDING)),
        ) { NumberEntryCard(ui.digits) }
    }
}

@Composable
private fun MenuPanel(ui: UiState, insets: Insets) {
    val model = ui.model
    val player = ui.player
    val cats = model.categories
    val (sidebarW, textW) = rememberCategoryMetrics(cats)
    val handleW = u(HANDLE_W)
    val listW = u(LIST_W)
    val epgW = u(EPG_W)
    val hidden = -(insets.left + sidebarW + listW + handleW + epgW + 40.dp)
    val offsetX by animateDpAsState(if (ui.menuVisible && !ui.buttonsOnly) 0.dp else hidden, SLIDE, label = "menu")
    val epgWidth by animateDpAsState(if (ui.epgOpen) epgW else 0.dp, SLIDE, label = "epg")
    val favSel = ui.selectedCategoryId == Category.FAVORITES_ID

    Row(
        Modifier.fillMaxHeight()
            .offset(x = offsetX)
            .shadow(12.dp)
            .background(Theme.sidebar.copy(alpha = 0.97f))
            .padding(start = insets.left, top = insets.top, bottom = insets.bottom)
            .touchMonitor { ui.onTouchActivity() }
            .tap { ui.onTouchActivity() },          // 吃掉选单上的空白点击，不让它穿透到画面手势层
    ) {
        CategorySidebar(ui, cats, textW, Modifier.width(sidebarW).background(Theme.sidebar))
        Box(Modifier.width(listW).fillMaxHeight().background(Theme.panel)) {
            val list = remember(ui.selectedCategoryId, model.channels, model.favoriteShown) {
                model.channels(ui.selectedCategoryId)
            }
            ChannelList(
                ui, list, player.current?.id, "${ui.selectedCategoryId}#${ui.menuShowCount}",
                menuOpen = ui.menuVisible && !ui.buttonsOnly, reorderable = favSel, rowHeight = ListMetrics.ROW_HEIGHT, gold = favSel,
                focusIndex = if (ui.remoteActive && ui.menuVisible && (ui.zone == Zone.CHANNEL || ui.zone == Zone.EPG)) ui.chFocus else -1,
                starFocus = ui.remoteActive && ui.zone == Zone.STAR,
                emptyText = "这里还没有频道\n在频道右侧点星标即可收藏",
            ) { ch -> ui.zone = Zone.CHANNEL; ui.selectChannel(ch) }
        }
        EPGHandle(ui, ui.epgOpen, Modifier.width(handleW))
        Box(Modifier.width(epgWidth).fillMaxHeight().clip(androidx.compose.ui.graphics.RectangleShape).background(Theme.panel)) {
            if (epgWidth > 0.dp) {
                // 内部固定宽度，外层裁剪 → 展开 / 收回时是“伸缩”效果
                Box(Modifier.width(epgW).fillMaxHeight()) {
                    EPGPanel(model, ui.epgChannel ?: player.current, Modifier.fillMaxSize(), scrolls = ui.epgScrolls)
                }
            }
        }
    }
}

@Composable
private fun ControlButtons(ui: UiState, insets: Insets, modifier: Modifier) {
    val player = ui.player
    Row(
        modifier.padding(end = insets.right + u(ControlRow.END_PADDING), bottom = insets.bottom + u(ControlRow.BOTTOM_PADDING)),
        horizontalArrangement = Arrangement.spacedBy(u(ControlRow.BUTTON_GAP)),
    ) {
        val icons = listOf(if (player.isPaused) Ico.PLAY else Ico.PAUSE, Ico.WAVEFORM, Ico.CAPTIONS, Ico.GEAR, Ico.INFO)
        icons.forEachIndexed { i, ico ->
            val focus = ui.remoteActive && ui.zone == Zone.BUTTON && ui.btnFocus == i && ui.popup == null
            Box(
                Modifier.size(u(ControlRow.BUTTON_SIZE))
                    .glassBox(CircleShape)
                    .then(if (focus) Modifier.border(u(2), Color.White, CircleShape) else Modifier)
                    .tap { ui.pressButton(i) },
                contentAlignment = Alignment.Center,
            ) { Icon(ico, u(20), Color.White) }
        }
    }
}

// ───────────────────────── 竖屏 ─────────────────────────

@Composable
private fun PortraitLayers(ui: UiState, insets: Insets, width: androidx.compose.ui.unit.Dp) {
    val player = ui.player
    val videoH = width * 9f / 16f
    val top = insets.top + videoH

    // 视频区内的状态 / OSD：只放音量条、提示条（竖屏不显示台号 + 频道名 + EPG 信息条）
    Box(Modifier.padding(top = insets.top).fillMaxWidth().height(videoH)) {
        StatusOverlay(player, 200.0, compact = true, hold = ui.pauseIconShown, retryFocused = ui.remoteActive && !ui.menuVisible)
        PauseIndicator(ui, clickable = false, modifier = Modifier.align(Alignment.Center))
        BottomOsdStack(ui, widthFraction = 1f, maxWidthDp = 2000, bottomPad = u(8), showChannelInfo = false, sidePad = u(8))
        if (ui.digits.isNotEmpty()) {
            Box(Modifier.align(Alignment.TopEnd).padding(top = u(10), end = u(10))) { NumberEntryCard(ui.digits) }
        }
    }

    // 下半部分：分类 / 频道浏览
    PortraitBrowser(ui, Modifier.fillMaxSize().padding(top = top, bottom = insets.bottom))

    if (ui.portraitEPG) PortraitEPGView(ui, insets)
}

// ───────────────────────── 公共部件 ─────────────────────────

/** 底部 OSD 区：自下而上 = 换台信息条（仅横屏）→ 提示条 → 退出提示 → 音量条 */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.BottomOsdStack(
    ui: UiState,
    widthFraction: Float,
    maxWidthDp: Int,
    bottomPad: androidx.compose.ui.unit.Dp,
    showChannelInfo: Boolean,
    sidePad: androidx.compose.ui.unit.Dp = 0.dp,
) {
    val model = ui.model
    val enter = fadeIn(tween(200)) + slideInVertically(tween(250)) { it / 2 }
    val exit = fadeOut(tween(200)) + slideOutVertically(tween(250)) { it / 2 }
    Column(
        Modifier.align(Alignment.BottomCenter)
            .fillMaxWidth(widthFraction).widthIn(max = u(maxWidthDp))
            .padding(start = sidePad, end = sidePad, bottom = bottomPad),
        verticalArrangement = Arrangement.spacedBy(u(10), Alignment.Bottom),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val vol = ui.volumeOsd
        AnimatedVisibility(vol != null, enter = enter, exit = exit) { VolumeOSD(vol ?: 0f) }

        val toast = model.toast
        var lastToast by remember { mutableStateOf("") }
        if (toast != null) lastToast = toast
        AnimatedVisibility(toast != null, enter = enter, exit = exit) { ToastOSD(lastToast) }

        AnimatedVisibility(ui.exitHint, enter = enter, exit = exit) { ToastOSD("再按一次退出猫TV") }

        if (showChannelInfo) {
            val ch = ui.player.current
            AnimatedVisibility(ui.osdVisible && ch != null, enter = enter, exit = exit) {
                if (ch != null) ChannelOSD(ch, model)
            }
        }
    }
}

/** 缓冲 / 重试 / 失败提示 */
@Composable
private fun StatusOverlay(player: PlayerController, logoWidth: Double, compact: Boolean = false, hold: Boolean = false, retryFocused: Boolean = false) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (player.failed) {
            Column(
                Modifier.background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(u(16))).padding(u(22)),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(u(if (compact) 6 else 12)),
            ) {
                Icon(Ico.WARNING, u(30), Theme.gold)
                Txt("播放失败", 16, weight = FontWeight.SemiBold)
                Txt("已重试 ${PlayerController.MAX_RETRIES} 次仍无法播放", 12, color = Color.White.copy(alpha = 0.6f))
                Box(
                    Modifier.clip(RoundedCornerShape(50)).background(Theme.purpleA)
                        .then(if (retryFocused) Modifier.border(u(2), Color.White, RoundedCornerShape(50)) else Modifier)
                        .tap { player.reload() }.padding(horizontal = u(26), vertical = u(8)),
                ) { Txt("重试", 15, weight = FontWeight.SemiBold) }
            }
        } else if (player.isBuffering && !hold && !player.isPaused) {
            Box(
                Modifier.fillMaxSize().then(if (!player.hasPicture) Modifier.background(Color.Black) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(u(if (compact) 8 else 14))) {
                    if (!player.hasPicture && !player.resuming) {
                        Image(
                            painterResource(R.drawable.logo_text), null,
                            modifier = Modifier.width(u(logoWidth)), contentScale = ContentScale.FillWidth,
                        )
                    }
                    Spinner(if (compact) 22 else 30)
                    Txt(
                        "${if (player.resuming) "正在恢复播放" else if (player.hasPicture) "缓冲中" else "频道加载中"}（${player.speedText}）…", 13,
                        color = Color.White.copy(alpha = 0.85f),
                    )
                    player.retryText?.let {
                        Txt(
                            it, 12, color = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(50))
                                .padding(horizontal = u(12), vertical = u(4)),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 暂停图标：暂停时由大变小淡入（1.8 倍 → 1 倍），恢复时由小变大淡出（1 倍 → 1.8 倍）。
 * 动画没结束前 ui.pauseIconShown 一直为 true，加载界面（“正在恢复播放…”）等它播完才出现。
 * 横屏下点图标 = 恢复播放（竖屏由整个画面区域的点击处理）。
 */
@Composable
private fun PauseIndicator(ui: UiState, clickable: Boolean, modifier: Modifier = Modifier) {
    val paused = ui.player.isPaused
    val prog = remember { Animatable(0f) }
    LaunchedEffect(paused) {
        if (paused) {
            ui.pauseIconShown = true
            prog.animateTo(1f, tween(240))
        } else {
            prog.animateTo(0f, tween(240))
            ui.pauseIconShown = false
        }
    }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { ui.pauseIconShown = false } }
    val p = prog.value
    if (p > 0f) {
        Box(
            modifier.size(u(64))
                .graphicsLayer {
                    val sc = 1f + 0.8f * (1f - p)
                    scaleX = sc; scaleY = sc; alpha = p
                }
                .clip(CircleShape).background(Color.Black.copy(alpha = 0.55f))
                .then(if (clickable && paused) Modifier.tap { ui.player.togglePause() } else Modifier),
            contentAlignment = Alignment.Center,
        ) { Icon(Ico.PAUSE, u(26), Color.White) }
    }
}

/**
 * 画面手势：点击（横屏切换选单）；上下滑换台（滑动 > 60px）；左右滑调音量（横扫半个宽度 = 100%）。
 */
private fun Modifier.gestures(ui: UiState, tapToggleMenu: Boolean): Modifier = this
    .pointerInput(tapToggleMenu) {
        detectTapGestures {
            ui.remoteActive = false
            if (tapToggleMenu) ui.toggleMenuByTap() else ui.player.togglePause()
        }
    }
    .pointerInput(Unit) {
        var total = Offset.Zero
        var horizontal = false
        var startVolume = 1f
        var decided = false
        detectDragGestures(
            onDragStart = {
                total = Offset.Zero; decided = false
                startVolume = ui.player.volume
            },
            onDrag = { change, drag ->
                change.consume()
                total += drag
                if (!decided) {
                    horizontal = abs(total.x) > abs(total.y)
                    decided = true
                }
                if (horizontal) {
                    val delta = total.x / (size.width * 0.5f).coerceAtLeast(1f)
                    ui.setVolumeAndFlash(startVolume + delta)
                }
            },
            onDragEnd = {
                if (decided && !horizontal && abs(total.y) > 60f) {
                    // 下滑 = 下一个台，上滑 = 上一个台（与 iOS 版相同；“换台反转”打开时相反）
                    ui.stepByDirection(towardsNext = total.y > 0)
                }
                decided = false
            },
            onDragCancel = { decided = false },
        )
    }

/** 监听选单上的任何触摸（不消费事件）：选单里有操作就让“8 秒自动收起”重新计时 */
private fun Modifier.touchMonitor(onTouch: () -> Unit): Modifier = this.pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        onTouch()
        do {
            val e = awaitPointerEvent(PointerEventPass.Initial)
            onTouch()
        } while (e.changes.any { it.pressed })
    }
}
