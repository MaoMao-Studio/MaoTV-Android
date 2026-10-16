package com.cncat.maotv.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

enum class Ico {
    PAUSE, PLAY, WAVEFORM, CAPTIONS, GEAR, INFO, STAR, STAR_OUTLINE, CALENDAR, SEARCH,
    CHEV_LEFT, CLOSE_CIRCLE, CHECK, TV, CHEV2_LEFT, CHEV2_RIGHT, WARNING, SPEAKER, SPEAKER_OFF,
}

/** 全部图标都用 Canvas 画（不依赖图标库）；坐标系 24×24 */
@Composable
fun Icon(kind: Ico, size: Dp, color: Color, modifier: Modifier = Modifier, innerColor: Color = Color.Black) {
    Canvas(modifier.size(size)) {
        val k = this.size.minDimension / 24f
        drawIco(kind, k, color, innerColor)
    }
}

private fun DrawScope.drawIco(kind: Ico, k: Float, c: Color, inner: Color) {
    fun p(x: Float, y: Float) = Offset(x * k, y * k)
    val sw = 1.9f * k
    val line = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
    when (kind) {
        Ico.PAUSE -> {
            drawRoundRect(c, p(6f, 5f), Size(4f * k, 14f * k), CornerRadius(1.2f * k))
            drawRoundRect(c, p(14f, 5f), Size(4f * k, 14f * k), CornerRadius(1.2f * k))
        }
        Ico.PLAY -> {
            val path = Path().apply {
                moveTo(7.5f * k, 4.8f * k); lineTo(19.5f * k, 12f * k); lineTo(7.5f * k, 19.2f * k); close()
            }
            drawPath(path, c, style = Fill)
            drawPath(path, c, style = Stroke(width = 1.6f * k, join = StrokeJoin.Round))
        }
        Ico.WAVEFORM -> {
            val hs = floatArrayOf(3f, 7f, 10f, 6f, 3f)
            for (i in hs.indices) {
                val x = 4f + i * 4f
                drawLine(c, p(x, 12f - hs[i]), p(x, 12f + hs[i]), strokeWidth = sw, cap = StrokeCap.Round)
            }
        }
        Ico.CAPTIONS -> {
            val path = Path().apply {
                addRoundRect(androidx.compose.ui.geometry.RoundRect(3f * k, 4.5f * k, 21f * k, 17f * k, 3f * k, 3f * k))
            }
            drawPath(path, c, style = line)
            val tail = Path().apply { moveTo(8f * k, 17f * k); lineTo(8f * k, 21f * k); lineTo(12.5f * k, 17f * k) }
            drawPath(tail, c, style = line)
            drawLine(c, p(7f, 9.5f), p(11f, 9.5f), strokeWidth = sw, cap = StrokeCap.Round)
            drawLine(c, p(13f, 9.5f), p(17f, 9.5f), strokeWidth = sw, cap = StrokeCap.Round)
            drawLine(c, p(7f, 13f), p(14f, 13f), strokeWidth = sw, cap = StrokeCap.Round)
        }
        Ico.GEAR -> {
            drawCircle(c, 6.2f * k, p(12f, 12f), style = line)
            drawCircle(c, 2.4f * k, p(12f, 12f), style = line)
            for (i in 0 until 8) {
                val a = i * PI / 4
                val x1 = 12f + 6.2f * cos(a).toFloat(); val y1 = 12f + 6.2f * sin(a).toFloat()
                val x2 = 12f + 9.4f * cos(a).toFloat(); val y2 = 12f + 9.4f * sin(a).toFloat()
                drawLine(c, p(x1, y1), p(x2, y2), strokeWidth = 2.6f * k, cap = StrokeCap.Round)
            }
        }
        Ico.INFO -> {
            drawCircle(c, 9f * k, p(12f, 12f), style = line)
            drawCircle(c, 1.2f * k, p(12f, 7.7f), style = Fill)
            drawLine(c, p(12f, 11f), p(12f, 16.8f), strokeWidth = sw, cap = StrokeCap.Round)
        }
        Ico.STAR, Ico.STAR_OUTLINE -> {
            val path = Path()
            for (i in 0 until 10) {
                val r = if (i % 2 == 0) 10f else 4.3f
                val a = -PI / 2 + i * PI / 5
                val x = (12f + r * cos(a).toFloat()) * k
                val y = (12.6f + r * sin(a).toFloat()) * k
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            if (kind == Ico.STAR) {
                drawPath(path, c, style = Fill)
                drawPath(path, c, style = Stroke(width = 1.4f * k, join = StrokeJoin.Round))
            } else {
                drawPath(path, c, style = Stroke(width = 1.6f * k, join = StrokeJoin.Round))
            }
        }
        Ico.CALENDAR -> {
            val path = Path().apply {
                addRoundRect(androidx.compose.ui.geometry.RoundRect(3.5f * k, 5f * k, 20.5f * k, 20.5f * k, 2.5f * k, 2.5f * k))
            }
            drawPath(path, c, style = line)
            drawLine(c, p(3.5f, 9.8f), p(20.5f, 9.8f), strokeWidth = sw, cap = StrokeCap.Round)
            drawLine(c, p(8f, 3f), p(8f, 6.8f), strokeWidth = sw, cap = StrokeCap.Round)
            drawLine(c, p(16f, 3f), p(16f, 6.8f), strokeWidth = sw, cap = StrokeCap.Round)
            for (row in 0..1) for (col in 0..2) {
                drawCircle(c, 1f * k, p(8f + col * 4f, 13.6f + row * 3.6f), style = Fill)
            }
        }
        Ico.SEARCH -> {
            drawCircle(c, 6.2f * k, p(10.5f, 10.5f), style = line)
            drawLine(c, p(15.2f, 15.2f), p(20.5f, 20.5f), strokeWidth = sw, cap = StrokeCap.Round)
        }
        Ico.CHEV_LEFT -> {
            val path = Path().apply { moveTo(15f * k, 4.5f * k); lineTo(7.5f * k, 12f * k); lineTo(15f * k, 19.5f * k) }
            drawPath(path, c, style = Stroke(width = 2.4f * k, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        Ico.CLOSE_CIRCLE -> {
            drawCircle(c, 10.5f * k, p(12f, 12f), style = Fill)
            drawLine(inner, p(8.3f, 8.3f), p(15.7f, 15.7f), strokeWidth = 2f * k, cap = StrokeCap.Round)
            drawLine(inner, p(15.7f, 8.3f), p(8.3f, 15.7f), strokeWidth = 2f * k, cap = StrokeCap.Round)
        }
        Ico.CHECK -> {
            val path = Path().apply { moveTo(5f * k, 12.5f * k); lineTo(10f * k, 17.5f * k); lineTo(19f * k, 7f * k) }
            drawPath(path, c, style = Stroke(width = 2.4f * k, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        Ico.TV -> {
            val path = Path().apply {
                addRoundRect(androidx.compose.ui.geometry.RoundRect(3f * k, 5f * k, 21f * k, 17f * k, 2f * k, 2f * k))
            }
            drawPath(path, c, style = line)
            drawLine(c, p(8f, 20.5f), p(16f, 20.5f), strokeWidth = sw, cap = StrokeCap.Round)
        }
        Ico.CHEV2_LEFT, Ico.CHEV2_RIGHT -> {
            val dir = if (kind == Ico.CHEV2_LEFT) -1f else 1f
            val st = Stroke(width = 2.6f * k, cap = StrokeCap.Round, join = StrokeJoin.Round)
            for (off in floatArrayOf(-3.6f, 3.6f)) {
                val cx = 12f + off * 1f
                val path = Path().apply {
                    moveTo((cx - dir * 3f) * k, 5.5f * k)
                    lineTo((cx + dir * 3f) * k, 12f * k)
                    lineTo((cx - dir * 3f) * k, 18.5f * k)
                }
                drawPath(path, c, style = st)
            }
        }
        Ico.SPEAKER, Ico.SPEAKER_OFF -> {
            val body = Path().apply {
                moveTo(3.5f * k, 9.5f * k); lineTo(7.5f * k, 9.5f * k); lineTo(12.5f * k, 5f * k)
                lineTo(12.5f * k, 19f * k); lineTo(7.5f * k, 14.5f * k); lineTo(3.5f * k, 14.5f * k); close()
            }
            drawPath(body, c, style = Fill)
            if (kind == Ico.SPEAKER) {
                drawArc(c, -45f, 90f, false, p(9f, 7.5f), Size(9f * k, 9f * k), style = Stroke(width = 1.8f * k, cap = StrokeCap.Round))
                drawArc(c, -50f, 100f, false, p(6.5f, 4.5f), Size(15f * k, 15f * k), style = Stroke(width = 1.8f * k, cap = StrokeCap.Round))
            } else {
                drawLine(c, p(16f, 9.5f), p(21f, 14.5f), strokeWidth = 1.9f * k, cap = StrokeCap.Round)
                drawLine(c, p(21f, 9.5f), p(16f, 14.5f), strokeWidth = 1.9f * k, cap = StrokeCap.Round)
            }
        }
        Ico.WARNING -> {
            val path = Path().apply { moveTo(12f * k, 2.5f * k); lineTo(22.5f * k, 20.5f * k); lineTo(1.5f * k, 20.5f * k); close() }
            drawPath(path, c, style = Fill)
            drawPath(path, c, style = Stroke(width = 2f * k, join = StrokeJoin.Round))
            drawLine(inner, p(12f, 9f), p(12f, 14.5f), strokeWidth = 2.2f * k, cap = StrokeCap.Round)
            drawCircle(inner, 1.2f * k, p(12f, 17.6f), style = Fill)
        }
    }
}
