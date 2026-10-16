package com.cncat.maotv.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.cncat.maotv.core.DiagRow
import com.cncat.maotv.core.Theme
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.roundToInt

// ───────────── 通用外壳 ─────────────

/** 半透明遮罩 + 居中的卡片；点遮罩 = onDismiss，点卡片本身不会穿透 */
@Composable
private fun ModalCard(
    portrait: Boolean,
    landscapeMaxW: Number,
    landscapeMaxH: Number,
    portraitMaxH: Number,
    onDismiss: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)).tap(onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        val w = if (portrait) maxWidth * 0.92f else minOf(maxWidth * 0.78f, u(landscapeMaxW))
        val h = if (portrait) minOf(maxHeight * 0.72f, u(portraitMaxH)) else minOf(maxHeight * 0.86f, u(landscapeMaxH))
        val shape = RoundedCornerShape(u(16))
        Column(
            Modifier.width(w).height(h).clip(shape).background(Theme.dialog)
                .border(u(1), Color.White.copy(alpha = 0.12f), shape).tap { },
        ) { content() }
    }
}

// ───────────── 设置面板 ─────────────

@Composable
fun SettingsPanel(ui: UiState, portrait: Boolean) {
    val rows = settingsRows(ui)
    // 遥控器焦点序号：0 = 保存设置，1.. = 非标题行依次编号
    val focusOf = IntArray(rows.size) { -1 }
    var n = 0
    for (i in rows.indices) if (rows[i] !is SRow.Header) { n++; focusOf[i] = n }
    val state = rememberLazyListState()
    LaunchedEffect(ui.settingsFocus) {
        if (!ui.remoteActive) return@LaunchedEffect
        if (ui.settingsFocus == 0) { state.animateScrollToItem(0); return@LaunchedEffect }
        val idx = focusOf.indexOf(ui.settingsFocus)
        if (idx < 0) return@LaunchedEffect
        val vis = state.layoutInfo.visibleItemsInfo
        if (vis.isEmpty() || idx <= vis.first().index || idx >= vis.last().index) {
            state.animateScrollToItem((idx - 1).coerceAtLeast(0))
        }
    }

    ModalCard(portrait, 520, 420, 560, onDismiss = { ui.closeSettings(false) }) {
        val saveFocus = ui.remoteActive && ui.settingsFocus == 0
        Row(
            Modifier.fillMaxWidth().padding(horizontal = u(16), vertical = u(12)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Txt("播放设置", 16, bold = true, modifier = Modifier.weight(1f))
            val s = RoundedCornerShape(u(8))
            Txt(
                "保存设置", 15, color = Theme.gold, weight = FontWeight.SemiBold,
                modifier = Modifier.clip(s)
                    .border(u(if (saveFocus) 2 else 0), if (saveFocus) Theme.gold else Color.Transparent, s)
                    .tap { ui.closeSettings(true) }
                    .padding(horizontal = u(8), vertical = u(4)),
            )
        }
        Box(Modifier.fillMaxWidth().height(u(1)).background(Color.White.copy(alpha = 0.15f)))
        LazyColumn(
            state = state, modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(u(16)),
            verticalArrangement = Arrangement.spacedBy(u(12)),
        ) {
            itemsIndexed(rows) { i, row ->
                val focused = ui.remoteActive && focusOf[i] == ui.settingsFocus && focusOf[i] > 0
                SettingsRowView(row, focused)
            }
        }
    }
}

@Composable
private fun SettingsRowView(row: SRow, focused: Boolean) {
    val shape = RoundedCornerShape(u(8))
    val ring = Modifier.border(u(if (focused) 2 else 0), if (focused) Theme.purpleBorder else Color.Transparent, shape)
    when (row) {
        is SRow.Header -> Txt(row.title, 13, color = Theme.gold, bold = true, modifier = Modifier.padding(top = u(6)))
        is SRow.Toggle -> Row(
            Modifier.fillMaxWidth().clip(shape).then(ring).tap { row.set(!row.on) }.padding(u(4)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(u(12)),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(u(4))) {
                Txt(row.label, 13, bold = true, maxLines = 3)
                if (row.hint != null) Txt(row.hint, 12, color = Color.White.copy(alpha = 0.6f), maxLines = 4)
            }
            ToggleSwitch(row.on)
        }
        is SRow.Segment -> Column(
            Modifier.fillMaxWidth().clip(shape).then(ring).padding(u(4)),
            verticalArrangement = Arrangement.spacedBy(u(8)),
        ) {
            if (row.label != null) Txt(row.label, 13, bold = true)
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(u(9))).background(Color.White.copy(alpha = 0.1f)).padding(u(3)),
                horizontalArrangement = Arrangement.spacedBy(u(4)),
            ) {
                row.options.forEachIndexed { i, title ->
                    val on = i == row.index
                    Box(
                        Modifier.weight(1f).clip(RoundedCornerShape(u(7)))
                            .background(if (on) Theme.purpleA else Color.Transparent)
                            .tap { row.set(i) }.padding(vertical = u(7)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Txt(title, 14, weight = FontWeight.SemiBold, color = Color.White.copy(alpha = if (on) 1f else 0.7f))
                    }
                }
            }
        }
        is SRow.Slider -> Column(
            Modifier.fillMaxWidth().clip(shape).then(ring).padding(u(4)),
            verticalArrangement = Arrangement.spacedBy(u(6)),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(u(10))) {
                Txt(row.label, 13, color = Theme.gold, bold = true, modifier = Modifier.weight(1f))
                Txt(String.format(Locale.US, "%.1f 秒", row.value), 14, weight = FontWeight.SemiBold)
                Txt("恢复默认", 12, color = Color.White.copy(alpha = 0.7f), modifier = Modifier.tap { row.reset() }.padding(u(4)))
            }
            SliderBar(row.value, row.min, row.max, row.step, row.set)
            if (row.hint != null) Txt(row.hint, 12, color = Color.White.copy(alpha = 0.6f), maxLines = 4)
        }
    }
}

@Composable
private fun ToggleSwitch(on: Boolean) {
    Box(
        Modifier.width(u(40)).height(u(24)).clip(RoundedCornerShape(50))
            .background(if (on) Theme.purpleA else Color.White.copy(alpha = 0.25f)),
    ) {
        Box(
            Modifier.align(if (on) Alignment.CenterEnd else Alignment.CenterStart)
                .padding(u(2)).size(u(20)).background(Color.White, CircleShape)
        )
    }
}

@Composable
private fun SliderBar(value: Double, min: Double, max: Double, step: Double, onChange: (Double) -> Unit) {
    val frac = ((value - min) / (max - min)).toFloat().coerceIn(0f, 1f)
    var width by remember { mutableIntStateOf(1) }
    val thumbPx = upx(20)
    fun fromX(x: Float) {
        val f = (x / width).coerceIn(0f, 1f)
        val raw = min + f * (max - min)
        val snapped = Math.round(raw / step) * step
        onChange(snapped.coerceIn(min, max))
    }
    Box(
        Modifier.fillMaxWidth().height(u(28)).onSizeChanged { width = maxOf(1, it.width) }
            .pointerInput(Unit) { detectTapGestures { fromX(it.x) } }
            .pointerInput(Unit) { detectHorizontalDragGestures { change, _ -> fromX(change.position.x); change.consume() } },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxWidth().height(u(4)).background(Color.White.copy(alpha = 0.18f), RoundedCornerShape(50)))
        Box(
            Modifier.fillMaxWidth(frac).height(u(4))
                .background(Brush.horizontalGradient(listOf(Theme.purpleBorder, Theme.purpleA)), RoundedCornerShape(50))
        )
        Box(
            Modifier.offset { IntOffset((frac * (width - thumbPx)).roundToInt(), 0) }
                .size(u(20)).background(Color.White, CircleShape)
        )
    }
}

/** 打开“忽略SSL证书”前的确认框 */
@Composable
fun SslConfirmDialog(ui: UiState) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).tap { ui.sslConfirm = false },
        contentAlignment = Alignment.Center,
    ) {
        val shape = RoundedCornerShape(u(16))
        Column(
            Modifier.widthIn(max = u(340)).padding(horizontal = u(16)).clip(shape).background(Theme.dialog)
                .border(u(1), Color.White.copy(alpha = 0.12f), shape).tap { }.padding(u(18)),
            verticalArrangement = Arrangement.spacedBy(u(16)),
        ) {
            Txt("安全提示", 16, bold = true, color = Theme.gold)
            Txt("打开该选项有可能会导致用户Token被窃取，请谨慎操作！", 14, maxLines = 5)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(u(12))) {
                DialogButton("取消", ui.remoteActive && ui.sslFocus == 0, false, Modifier.weight(1f)) { ui.sslConfirm = false }
                DialogButton("仍然打开", ui.remoteActive && ui.sslFocus == 1, true, Modifier.weight(1f)) {
                    ui.settingsDraft = ui.settingsDraft.copy(ignoreSsl = true)
                    ui.sslConfirm = false
                }
            }
        }
    }
}

@Composable
private fun DialogButton(text: String, focused: Boolean, primary: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val s = RoundedCornerShape(u(10))
    Box(
        modifier.clip(s)
            .background(if (primary) Theme.purpleA else Color.White.copy(alpha = 0.1f))
            .border(u(if (focused) 2 else 0), if (focused) Color.White else Color.Transparent, s)
            .tap(onClick).padding(vertical = u(10)),
        contentAlignment = Alignment.Center,
    ) { Txt(text, 14, weight = FontWeight.SemiBold) }
}

// ───────────── 诊断面板 ─────────────

@Composable
fun DiagnosticsPanel(ui: UiState, portrait: Boolean) {
    val view = LocalView.current
    val scroll = rememberScrollState()
    var second by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1000); second++ } }
    LaunchedEffect(Unit) { ui.diagScrolls.collect { scroll.scrollBy(it) } }

    val unit = LocalUnit.current
    val sections = run {
        second.hashCode()    // 每秒刷新一次
        val d = view.display
        val screen = listOf(
            DiagRow("屏幕", "${view.width}×${view.height} px"),
            DiagRow("版式", if (portrait) "竖屏" else "横屏"),
            DiagRow("刷新率", if (d != null) String.format(Locale.US, "%.1f Hz", d.refreshRate) else "未知"),
            DiagRow("界面缩放", String.format(Locale.US, "1 设计点 = %.2f px", unit)),
            DiagRow("设备", "${Build.MANUFACTURER} ${Build.MODEL}"),
            DiagRow("系统", "Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）"),
            DiagRow("CPU 架构", Build.SUPPORTED_ABIS.firstOrNull() ?: "未知"),
        )
        ui.player.diagnostics(screen)
    }

    ModalCard(portrait, 600, 420, 640, onDismiss = { ui.diagVisible = false }) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = u(16), vertical = u(12)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Txt("播放诊断", 16, bold = true, modifier = Modifier.weight(1f))
            Box(Modifier.tap { ui.diagVisible = false }) { Icon(Ico.CLOSE_CIRCLE, u(24), Color.White.copy(alpha = 0.7f), innerColor = Theme.dialog) }
        }
        Box(Modifier.fillMaxWidth().height(u(1)).background(Color.White.copy(alpha = 0.15f)))
        Column(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(scroll).padding(u(16)),
            verticalArrangement = Arrangement.spacedBy(u(14)),
        ) {
            for (sec in sections) {
                Column(verticalArrangement = Arrangement.spacedBy(u(6))) {
                    Txt(sec.title, 12, color = Theme.gold, bold = true)
                    for (r in sec.rows) {
                        Row(horizontalArrangement = Arrangement.spacedBy(u(10)), verticalAlignment = Alignment.Top) {
                            Txt(r.label, 13, color = Color.White.copy(alpha = 0.55f), modifier = Modifier.width(u(88)), maxLines = 2)
                            Txt(r.value, 13, weight = FontWeight.Medium, maxLines = 8, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

// ───────────── 音轨 / 字幕弹出菜单 ─────────────

@Composable
fun TrackPopup(ui: UiState, kind: PopupKind, portrait: Boolean, insets: Insets) {
    val player = ui.player
    val entries: List<Pair<String, Boolean>> = when (kind) {
        PopupKind.AUDIO ->
            if (player.audioItems.isEmpty()) listOf("无可用音轨" to false)
            else player.audioItems.map { it.name to it.isOn }
        PopupKind.SUBTITLE ->
            listOf("关闭字幕" to player.subtitleItems.none { it.isOn }) + player.subtitleItems.map { it.name to it.isOn }
    }
    val title = if (kind == PopupKind.AUDIO) "音轨" else "字幕"
    val state = rememberLazyListState()
    LaunchedEffect(ui.popupFocus) {
        if (ui.remoteActive) state.animateScrollToItem((ui.popupFocus - 2).coerceAtLeast(0))
    }
    val card: @Composable () -> Unit = {
        val shape = RoundedCornerShape(u(12))
        Column(
            Modifier.widthIn(min = u(180), max = u(320)).clip(shape).background(Theme.dialog)
                .border(u(1), Color.White.copy(alpha = 0.15f), shape).tap { },
        ) {
            Txt(title, 12, color = Theme.gold, bold = true, modifier = Modifier.padding(horizontal = u(14), vertical = u(8)))
            LazyColumn(Modifier.heightIn(max = u(260)), state = state) {
                itemsIndexed(entries) { i, (name, on) ->
                    val focus = ui.remoteActive && ui.popupFocus == i
                    Row(
                        Modifier.fillMaxWidth().height(u(36))
                            .background(if (focus) Theme.purpleA else Color.Transparent)
                            .tap { ui.choosePopup(kind, i) }
                            .padding(horizontal = u(14)),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(u(8)),
                    ) {
                        Box(Modifier.size(u(16))) { if (on) Icon(Ico.CHECK, u(16), Theme.gold) }
                        Txt(name, 14, bold = on, modifier = Modifier.weight(1f, fill = false))
                    }
                }
            }
            Spacer(Modifier.height(u(4)))
        }
    }
    Box(Modifier.fillMaxSize().tap { ui.closePopup() }) {
        if (portrait) {
            Box(Modifier.align(Alignment.Center)) { card() }
        } else {
            // 弹出在对应按钮的正上方，右边缘与按钮右边缘对齐
            val btnIndex = if (kind == PopupKind.AUDIO) 1 else 2
            val fromRight = (UiState.BUTTON_COUNT - 1 - btnIndex)
            Box(
                Modifier.align(Alignment.BottomEnd).padding(
                    end = insets.right + u(ControlRow.END_PADDING + fromRight * (ControlRow.BUTTON_SIZE + ControlRow.BUTTON_GAP)),
                    bottom = insets.bottom + u(ControlRow.BOTTOM_PADDING + ControlRow.BUTTON_SIZE + 8),
                )
            ) { card() }
        }
    }
}
