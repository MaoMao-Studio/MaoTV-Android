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
    var token by remember { mutableStateOf(model.tokenDraft) }
    val submit = { model.submit(token) }

    BoxWithConstraints(
        Modifier.fillMaxSize()
            .background(Brush.linearGradient(listOf(Color(0xFF1B1233), Color(0xFF0A0A12))))
            .imePadding()
    ) {
        val portrait = maxHeight > maxWidth
        val scroll = rememberScrollState()
        Box(
            Modifier.fillMaxSize().verticalScroll(scroll).padding(u(24)),
            contentAlignment = Alignment.Center,
        ) {
            val logo: @Composable () -> Unit = {
                Image(
                    painterResource(R.drawable.logo), null,
                    modifier = Modifier.width(u(120)).clip(RoundedCornerShape(u(28))),
                    contentScale = ContentScale.FillWidth,
                )
            }
            val form: @Composable () -> Unit = {
                Column(Modifier.fillMaxWidth().widthIn(max = u(420)), verticalArrangement = Arrangement.spacedBy(u(14))) {
                    Txt("欢迎使用猫TV！", 28, bold = true)
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
                            modifier = Modifier.fillMaxWidth(),
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
            if (portrait) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(u(24))) {
                    logo(); form()
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(u(36))) {
                    logo(); form()
                }
            }
        }
    }
}
