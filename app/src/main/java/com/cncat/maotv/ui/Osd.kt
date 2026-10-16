package com.cncat.maotv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.cncat.maotv.AppModel
import com.cncat.maotv.core.Channel
import com.cncat.maotv.core.Theme
import com.cncat.maotv.net.Clock
import kotlinx.coroutines.delay

/** 换台 / 启动时的信息条（频道号、名称、当前与下一档节目） */
@Composable
fun ChannelOSD(channel: Channel, model: AppModel) {
    val tick = model.epg.tick
    val nn = model.epg.nowNext(channel, if (tick > 0) Clock.now() else tick)
    Row(
        Modifier.fillMaxWidth().osdCard(14).padding(horizontal = u(17), vertical = u(11)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(u(12.5f)),
    ) {
        LogoView(channel.logo, 44)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(u(3))) {
            Row(horizontalArrangement = Arrangement.spacedBy(u(8)), verticalAlignment = Alignment.CenterVertically) {
                Txt("${channel.number}", 20, color = Theme.gold, bold = true)
                Txt(channel.name, 17, bold = true, modifier = Modifier.weight(1f, fill = false))
            }
            val now = nn.first
            if (now != null) {
                Txt("${TimeFmt.hm(now.start)} - ${TimeFmt.hm(now.stop)} ${now.title}", 12)
            } else {
                Txt("暂无节目信息", 12, color = Color.White.copy(alpha = 0.6f))
            }
            nn.second?.let { nx ->
                Txt("${TimeFmt.hm(nx.start)} - ${TimeFmt.hm(nx.stop)} ${nx.title}", 12, color = Color.White.copy(alpha = 0.5f))
            }
        }
    }
}

/** 音量调整时的小浮层 */
@Composable
fun VolumeOSD(value: Float) {
    val frac = value.coerceIn(0f, 1f)
    Row(
        Modifier.osdCard(14).padding(horizontal = u(16), vertical = u(12)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(u(12)),
    ) {
        Icon(if (value <= 0.001f) Ico.SPEAKER_OFF else Ico.SPEAKER, u(22), Color.White.copy(alpha = 0.9f))
        Box(Modifier.width(u(140)).height(u(6)).background(Color.White.copy(alpha = 0.18f), RoundedCornerShape(50))) {
            Box(
                Modifier.width(u(140) * frac).height(u(6))
                    .background(Brush.horizontalGradient(listOf(Color(0xFFFF5C8A), Color(0xFFFFB300))), RoundedCornerShape(50))
            )
        }
        Txt("${(frac * 100).toInt()}%", 14, bold = true, modifier = Modifier.width(u(42)), align = TextAlign.End)
    }
}

/** 提示条（收藏同步失败、“您输入的频道不存在”等）：样式和换台信息条一致 */
@Composable
fun ToastOSD(text: String) {
    Txt(
        text, 13, bold = true, weight = FontWeight.SemiBold, maxLines = 3, align = TextAlign.Center,
        modifier = Modifier.osdCard(14).padding(horizontal = u(17), vertical = u(11)),
    )
}

/** 遥控器数字选台：右上角显示已输入的台号（和台号在其他菜单里一样用金色） */
@Composable
fun NumberEntryCard(digits: String) {
    Box(
        Modifier.osdCard(14).padding(horizontal = u(22), vertical = u(10)).width(u(86)),
        contentAlignment = Alignment.Center,
    ) {
        Txt(digits, 32, color = Theme.gold, bold = true, align = TextAlign.Center, letterSpacing = 2.sp)
    }
}

/** 左侧选单展开时右上角的系统时间（HH:mm:ss） */
@Composable
fun ClockBox() {
    var text by remember { mutableStateOf(TimeFmt.hms(Clock.now())) }
    LaunchedEffect(Unit) {
        while (true) {
            val now = Clock.now()
            text = TimeFmt.hms(now)
            delay(1000 - (now % 1000) + 5)
        }
    }
    Box(
        Modifier.glassBox(RoundedCornerShape(u(10))).padding(horizontal = u(10), vertical = u(10)),
        contentAlignment = Alignment.Center,
    ) {
        // 字号、颜色与频道列表里的频道名称一致（白色粗体）
        Txt(text, ListMetrics.NAME_SIZE, bold = true, maxLines = 1, softWrap = false)
    }
}
