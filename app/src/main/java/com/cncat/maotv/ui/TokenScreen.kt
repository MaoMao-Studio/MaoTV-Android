package com.cncat.maotv.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.height
import com.cncat.maotv.AppModel
import com.cncat.maotv.R
import com.cncat.maotv.core.Theme

/** 首次启动 / Token 失效时的输入页 */
@Composable
fun TokenScreen(model: AppModel) {
    // 和 iOS 一样按“点”（dp）计尺寸，不按屏幕长边缩放，这样手机竖屏 / 横屏看起来和 iOS 一致
    // 按屏幕短边等比缩放（以 430 点宽的 iPhone 为基准），这样任何分辨率下看起来都和 iOS 一样、文字不会被挤成省略号
    val cfg = androidx.compose.ui.platform.LocalConfiguration.current
    val k = (minOf(cfg.screenWidthDp, cfg.screenHeightDp) / 430f).coerceIn(0.6f, 1.8f)
    androidx.compose.runtime.CompositionLocalProvider(LocalUnit provides androidx.compose.ui.platform.LocalDensity.current.density * k) {
        TokenContent(model)
    }
}

@Composable
private fun TokenContent(model: AppModel) {
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val isPad = androidx.compose.ui.platform.LocalConfiguration.current.smallestScreenWidthDp >= 600
    var token by remember { mutableStateOf(model.tokenDraft) }
    val submit = { model.submit(token) }
    val focusRequester = remember { FocusRequester() }
    // 进入页面时焦点自动落在输入框（并弹出键盘）
    LaunchedEffect(Unit) {
        // 等界面布局完成后再请求焦点（太早会失败）
        repeat(20) {
            kotlinx.coroutines.delay(200)
            try { focusRequester.requestFocus(); keyboard?.show(); return@LaunchedEffect } catch (_: Throwable) { }
        }
    }

    BoxWithConstraints(
        Modifier.fillMaxSize()
            .background(Brush.linearGradient(listOf(Color(0xFF1B1233), Color(0xFF0A0A12))))
            .imePadding()
    ) {
        val portrait = maxHeight > maxWidth
        val scroll = rememberScrollState()
        Box(
            Modifier.fillMaxSize().verticalScroll(scroll).padding(u(20)),
            contentAlignment = Alignment.Center,
        ) {
            val logo: @Composable () -> Unit = {
                Image(
                    painterResource(R.drawable.logo), null,
                    modifier = Modifier.width(u(105)).shadow(u(12), RoundedCornerShape(u(28))).clip(RoundedCornerShape(u(28))),
                    contentScale = ContentScale.FillWidth,
                )
            }
            val form: @Composable () -> Unit = {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(u(14))) {
                    Txt("欢迎使用猫TV！", 26, bold = true, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Visible)
                    var focused by remember { mutableStateOf(false) }
                    Box(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(u(10)))
                            .background(Color.White.copy(alpha = 0.1f))
                            .border(u(if (focused) 2 else 1), if (focused) Theme.purpleBorder else Color.White.copy(alpha = 0.2f), RoundedCornerShape(u(10)))
                            .padding(u(12))
                            .onFocusChanged { focused = it.hasFocus }
                    ) {
                        if (token.isEmpty()) Txt("请输入用户Token", 16, color = Color.White.copy(alpha = 0.4f))
                        BasicTextField(
                            value = token,
                            onValueChange = { token = it },
                            singleLine = true,
                            textStyle = TextStyle(color = Color.White, fontSize = us(16)),
                            cursorBrush = SolidColor(Color.White),
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.None, autoCorrect = false,
                                keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done,
                            ),
                            keyboardActions = KeyboardActions(onDone = { submit() }),
                            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                        )
                    }
                    model.message?.let { msg ->
                        Txt(msg, 12, color = Theme.danger, maxLines = 6)
                        if (!msg.startsWith("无法连接") && !msg.startsWith("加载失败") && !msg.startsWith("无法验证")) {
                            Txt("请重新输入正确的Token", 12, color = Color.White.copy(alpha = 0.6f))
                        }
                    }
                    var btnFocused by remember { mutableStateOf(false) }
                    Row(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(u(10)))
                            .background(Brush.horizontalGradient(listOf(Theme.purpleBorder, Theme.purpleA)))
                            .border(u(if (btnFocused) 2 else 0), if (btnFocused) Color.White else Color.Transparent, RoundedCornerShape(u(10)))
                            .onFocusChanged { btnFocused = it.isFocused }
                            .clickable(
                                enabled = !model.isBusy,
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { submit() }
                            .padding(vertical = u(12)),
                        horizontalArrangement = Arrangement.spacedBy(u(8), Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (model.isBusy) Spinner(18)
                        Txt(if (model.isBusy) model.statusText else "确定", 16, weight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                    }
                }
            }
            // 和 iOS 一致：横竖屏都是 左边 Logo、右边输入区
            // 横屏：内容区宽度约为屏幕的 70%，但不小于 u(470)（保证任何文字不出现省略号）、也不超过屏幕可用宽度
            val avail = maxWidth - u(40)
            val contentW = if (portrait) avail else maxOf(maxWidth * 0.7f, minOf(u(470), avail)).coerceAtMost(avail)
            Row(
                Modifier.width(contentW),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(u(36)),
            ) {
                logo(); Box(Modifier.weight(1f)) { form() }
            }
        }
    }
}
