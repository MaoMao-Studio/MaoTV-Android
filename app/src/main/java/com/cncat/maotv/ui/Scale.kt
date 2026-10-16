package com.cncat.maotv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * 界面尺寸按屏幕大小等比缩放：所有数值都是按 iPhone 横屏（长边 932 点）设计的“设计点”，
 * 屏幕长边是多少像素，1 个设计点就是多少 / 932 像素。这样手机、平板、1080p / 720p 电视盒子上
 * 各个面板占屏幕的比例都一样。
 */
val LocalUnit = compositionLocalOf { 1f }

/** 1 个设计点 = 多少像素 */
@Composable
fun rememberUnit(): Float {
    val cfg = LocalConfiguration.current
    val density = LocalDensity.current.density
    val longPx = maxOf(cfg.screenWidthDp, cfg.screenHeightDp) * density
    return (longPx / 932f).coerceAtLeast(0.5f)
}

@Composable
@ReadOnlyComposable
fun u(x: Number): Dp {
    val d = LocalDensity.current
    return Dp(x.toFloat() * LocalUnit.current / d.density)
}

/** 字号：不跟随系统“字体大小”设置（界面是按屏幕等比缩放的） */
@Composable
@ReadOnlyComposable
fun us(x: Number): TextUnit {
    val d = LocalDensity.current
    return (x.toFloat() * LocalUnit.current / d.density / d.fontScale).sp
}

/** 设计点 → 像素（画 Canvas 用） */
@Composable
@ReadOnlyComposable
fun upx(x: Number): Float = x.toFloat() * LocalUnit.current
