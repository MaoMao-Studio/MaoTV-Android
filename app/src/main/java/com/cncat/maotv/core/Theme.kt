package com.cncat.maotv.core

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

object Theme {
    val gold = Color(0xFFFFB800)
    val sidebar = Color(0xFF0C0C15)
    val panel = Color(0xFF13131E)
    val dialog = Color(0xFF15151F)
    val purpleA = Color(0xFF5B2D8A)
    val purpleB = Color(0xFF3A2162)
    val purpleBorder = Color(0xFF9B5DE5)
    /** “我的收藏”的选中高亮：深一点的金色，星标（亮金）压在上面仍然清楚 */
    val favA = Color(0xFFA87400)
    val favB = Color(0xFF7A5400)
    val favBorder = Color(0xFFFFC933)
    val favStar = Color(0xFFFFE066)
    val osdA = Color(0xFF1F1735)
    val osdB = Color(0xFF130F22)
    val danger = Color(0xFFFF6B6B)

    fun selectedFill(fav: Boolean): Brush =
        Brush.horizontalGradient(if (fav) listOf(favA, favB) else listOf(purpleA, purpleB))

    fun selectedBorder(fav: Boolean): Color = if (fav) favBorder else purpleBorder
}
