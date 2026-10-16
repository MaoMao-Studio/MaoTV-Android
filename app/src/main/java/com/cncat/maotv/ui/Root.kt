package com.cncat.maotv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import com.cncat.maotv.MaoApp
import com.cncat.maotv.Phase
import com.cncat.maotv.R

/** 系统栏 / 刘海占用的边距（Dp） */
data class Insets(val left: Dp, val top: Dp, val right: Dp, val bottom: Dp)

@Composable
fun currentInsets(): Insets {
    val d = LocalDensity.current
    val ld = LocalLayoutDirection.current
    val w = WindowInsets.systemBars.union(WindowInsets.displayCutout)
    return with(d) {
        Insets(
            w.getLeft(d, LayoutDirection.Ltr).toDp(),
            w.getTop(d).toDp(),
            w.getRight(d, LayoutDirection.Ltr).toDp(),
            w.getBottom(d).toDp(),
        )
    }
}

/** 应用的最外层：按认证阶段切换 启动页 / Token 输入页 / 播放界面 */
@Composable
fun RootScreen(app: MaoApp, ui: UiState, applySystemBars: (portrait: Boolean, hidden: Boolean) -> Unit) {
    val unit = rememberUnit()
    CompositionLocalProvider(LocalUnit provides unit) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            when (app.model.phase) {
                Phase.CHECKING -> SplashScreen(app.model.statusText)
                Phase.NEED_TOKEN -> {
                    LaunchedEffect(Unit) { applySystemBars(false, false) }
                    TokenScreen(app.model)
                }
                Phase.READY -> PlayerScreen(app, ui, applySystemBars)
            }
        }
    }
}

@Composable
fun SplashScreen(status: String) {
    Box(Modifier.fillMaxSize().background(Color(0xFF0A0A12)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(u(18))) {
            Image(
                painterResource(R.drawable.logo_text), null,
                modifier = Modifier.width(u(300)), contentScale = ContentScale.FillWidth,
            )
            Spinner(28)
            Txt(status, 12, color = Color.White.copy(alpha = 0.6f))
        }
    }
}
