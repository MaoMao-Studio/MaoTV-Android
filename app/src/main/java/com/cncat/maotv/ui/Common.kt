package com.cncat.maotv.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import coil.compose.SubcomposeAsyncImage
import com.cncat.maotv.core.Theme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 点击（没有水波纹，不占用焦点） */
fun Modifier.tap(onClick: () -> Unit): Modifier = composed {
    val cb = rememberUpdatedState(onClick)
    pointerInput(Unit) { detectTapGestures { cb.value() } }
}

@Composable
fun Txt(
    text: String,
    size: Number,
    modifier: Modifier = Modifier,
    color: Color = Color.White,
    bold: Boolean = false,
    weight: FontWeight? = null,
    maxLines: Int = 1,
    overflow: TextOverflow = TextOverflow.Ellipsis,
    align: TextAlign = TextAlign.Unspecified,
    softWrap: Boolean = true,
    letterSpacing: TextUnit = TextUnit.Unspecified,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = TextStyle(
            color = color,
            fontSize = us(size),
            fontWeight = weight ?: if (bold) FontWeight.Bold else FontWeight.Normal,
            textAlign = align,
            letterSpacing = letterSpacing,
        ),
        overflow = overflow,
        softWrap = softWrap,
        maxLines = maxLines,
    )
}

/** 台标：圆角浅色底，加载失败 / 没有地址时显示电视图标 */
@Composable
fun LogoView(url: String?, size: Number, modifier: Modifier = Modifier) {
    val s = u(size)
    val shape = RoundedCornerShape(s * 0.22f)
    Box(modifier.size(s).clip(shape).background(Color.White.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
        val placeholder: @Composable () -> Unit = {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(Ico.TV, s * 0.5f, Color.White.copy(alpha = 0.35f))
            }
        }
        if (url.isNullOrEmpty()) placeholder() else {
            SubcomposeAsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(s * 0.1f),
                loading = { placeholder() },
                error = { placeholder() },
            )
        }
    }
}

/** 转圈的加载指示 */
@Composable
fun Spinner(size: Number, color: Color = Color.White) {
    val t = rememberInfiniteTransition(label = "spin")
    val angle = t.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "a")
    val s = u(size)
    Canvas(Modifier.size(s)) {
        val w = this.size.minDimension * 0.12f
        drawArc(
            color.copy(alpha = 0.25f), 0f, 360f, false, Offset(w / 2, w / 2),
            Size(this.size.width - w, this.size.height - w), style = Stroke(w),
        )
        drawArc(
            color, angle.value, 90f, false, Offset(w / 2, w / 2),
            Size(this.size.width - w, this.size.height - w), style = Stroke(w, cap = StrokeCap.Round),
        )
    }
}

/** 换台信息条 / 提示条 / 音量条 / 台号输入框共用的卡片样式 */
@Composable
fun Modifier.osdCard(radius: Number): Modifier {
    val shape = RoundedCornerShape(u(radius))
    return this
        .background(Brush.linearGradient(listOf(Theme.osdA, Theme.osdB)), shape)
        .border(u(1), Color.White.copy(alpha = 0.08f), shape)
}

/** 底部按钮 / 时钟框共用的“半透明黑底 + 白色细边”样式 */
@Composable
fun Modifier.glassBox(shape: androidx.compose.ui.graphics.Shape): Modifier =
    this.background(Color.Black.copy(alpha = 0.6f), shape).border(u(1), Color.White.copy(alpha = 0.25f), shape)

object TimeFmt {
    private val hm = SimpleDateFormat("HH:mm", Locale.US)
    private val hms = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val day = SimpleDateFormat("M月d日 EEEE", Locale.CHINA)
    fun hm(ms: Long): String = hm.format(Date(ms))
    fun hms(ms: Long): String = hms.format(Date(ms))
    fun day(ms: Long): String = day.format(Date(ms))
}

/** 选单里频道列表的版式常数（时钟框要和“第一行高亮条”对齐，所以集中放在这里） */
object ListMetrics {
    /** 列表一行的高度（设计点） */
    const val ROW_HEIGHT = 48f
    /** 列表四周的内边距 */
    const val LIST_PADDING = 8f
    /** 行与行的间距 */
    const val ROW_GAP = 8f
    /** 第一行高亮条的垂直中心（距选单顶端） */
    const val FIRST_ROW_CENTER = LIST_PADDING + ROW_HEIGHT / 2
    /** 频道名字号：时钟的字号 / 颜色和它一致 */
    const val NAME_SIZE = 14f
}

/** 底部按钮 / 时钟框与屏幕右边缘的距离（两者必须相同，才能保证右侧对齐） */
object ControlRow {
    const val END_PADDING = 18f
    const val BOTTOM_PADDING = 14f
    const val BUTTON_SIZE = 44f
    const val BUTTON_GAP = 12f
}
