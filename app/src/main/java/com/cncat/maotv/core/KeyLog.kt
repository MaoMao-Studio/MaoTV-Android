package com.cncat.maotv.core

import android.view.KeyEvent

/** 记录最近收到的按键（显示在诊断页），用来排查遥控器按键（例如返回键）没反应的问题 */
object KeyLog {
    private val items = ArrayDeque<String>()
    @Volatile var backCallbacks = 0

    @Synchronized
    fun record(e: KeyEvent) {
        if (e.action != KeyEvent.ACTION_DOWN || e.repeatCount != 0) return
        val dev = e.device?.name ?: "?"
        val line = "${KeyEvent.keyCodeToString(e.keyCode)}（${e.keyCode}） scan=${e.scanCode} 设备=$dev"
        items.addFirst(line)
        while (items.size > 6) items.removeLast()
    }

    @Synchronized
    fun lines(): List<String> = items.toList()

    /** 没被系统映射成 BACK、但实际是“返回”的键 */
    fun isBackLike(e: KeyEvent): Boolean = when (e.keyCode) {
        KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BUTTON_B -> true
        KeyEvent.KEYCODE_UNKNOWN -> e.scanCode == 158 || e.scanCode == 0x224
        else -> false
    }
}
