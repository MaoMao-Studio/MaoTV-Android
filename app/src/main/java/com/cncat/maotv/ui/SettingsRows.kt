package com.cncat.maotv.ui

import com.cncat.maotv.core.AsyncQueueMode
import com.cncat.maotv.core.EngineKind
import com.cncat.maotv.core.LateDropMode
import com.cncat.maotv.core.SettingsValues

private const val HW_HINT = "优先使用硬解，当播放画面异常可尝试使用软解。"

/** 设置面板里的一行（界面和遥控器共用同一份描述，保证两边行为一致） */
sealed class SRow {
    class Header(val title: String) : SRow()
    class Toggle(val label: String, val hint: String?, val on: Boolean, val set: (Boolean) -> Unit) : SRow()
    class Segment(val label: String?, val options: List<String>, val index: Int, val set: (Int) -> Unit) : SRow()
    class Slider(
        val label: String, val hint: String?, val value: Double, val min: Double, val max: Double,
        val step: Double, val set: (Double) -> Unit, val reset: () -> Unit,
    ) : SRow()
}

/**
 * 设置面板内容。顺序：通用设置 → 播放器选择（含缓存时间）→ 所选播放器自己的设置。
 * 以后要加设置项，只在这里加一行。
 */
fun settingsRows(ui: UiState): List<SRow> {
    val v = ui.settingsDraft
    fun edit(f: (SettingsValues) -> SettingsValues) { ui.settingsDraft = f(ui.settingsDraft) }
    val rows = ArrayList<SRow>()

    rows += SRow.Header("通用")
    rows += SRow.Toggle("换台反转", "打开后：遥控器上键 / 下键、手指上滑 / 下滑的换台方向对调。", v.reverseChannel) { on -> edit { it.copy(reverseChannel = on) } }
    rows += SRow.Toggle("按视频帧率设置屏幕刷新率", "让屏幕刷新率和片源帧率匹配，画面更顺滑；设备不支持时自动忽略。", v.frameRateMatch) { on -> edit { it.copy(frameRateMatch = on) } }
    rows += SRow.Toggle("忽略SSL证书", "访问 https 时不检查证书有效性（老盒子系统时间 / 根证书不对时用）。", v.ignoreSsl) { on ->
        if (on && !ui.settingsDraft.ignoreSsl) {
            ui.sslFocus = 0
            ui.sslConfirm = true          // 打开前先弹出确认
        } else edit { it.copy(ignoreSsl = on) }
    }

    rows += SRow.Header("播放器")
    rows += SRow.Segment(null, EngineKind.values().map { it.title }, v.engine.ordinal) { i -> edit { it.copy(engine = EngineKind.values()[i]) } }
    rows += SRow.Slider(
        "缓存时间", "缓存上述时长再开始播放，数值越大越能抵御网络波动，但加载频道时间越长。",
        v.cacheSeconds, SettingsValues.MIN_CACHE, SettingsValues.MAX_CACHE, 0.5,
        { x -> edit { it.copy(cacheSeconds = x) } },
        { edit { it.copy(cacheSeconds = SettingsValues.DEFAULT_CACHE) } },
    )

    if (v.engine == EngineKind.VLC) {
        rows += SRow.Header("VLC 选项")
        rows += SRow.Toggle("硬件解码", HW_HINT, !v.vlcSoftware) { on -> edit { it.copy(vlcSoftware = !on) } }
    } else {
        rows += SRow.Header("Media3 · 解码")
        rows += SRow.Toggle("硬件解码", HW_HINT, v.m3HardwareFirst) { on -> edit { it.copy(m3HardwareFirst = on) } }
        rows += SRow.Toggle("解码器自动降级", "首选解码器失败时自动换用下一个可用的解码器（含软解）。", v.m3DecoderFallback) { on -> edit { it.copy(m3DecoderFallback = on) } }
        rows += SRow.Toggle("超出设备能力仍尝试播放", "分辨率/码率超出解码器标称能力时仍然继续播放。", v.m3ExceedCaps) { on -> edit { it.copy(m3ExceedCaps = on) } }
        rows += SRow.Segment("异步解码队列", AsyncQueueMode.values().map { it.title }, v.m3AsyncQueue.ordinal) { i -> edit { it.copy(m3AsyncQueue = AsyncQueueMode.values()[i]) } }
        rows += SRow.Segment("视频晚到丢帧", LateDropMode.values().map { it.title }, v.m3LateDrop.ordinal) { i -> edit { it.copy(m3LateDrop = LateDropMode.values()[i]) } }
        rows += SRow.Toggle("不设置解码器操作速率", "高帧率（50 / 60fps）频道丢帧严重、而网络没问题时可以打开试试；个别旧解码器会被“操作速率”限速。", v.m3NoOperatingRate) { on -> edit { it.copy(m3NoOperatingRate = on) } }
        rows += SRow.Toggle("强制 FFmpeg 软解音频（排查用）", "MP2 / AC3 等音频本来就会自动用 FFmpeg 解码，一般不用打开。", v.m3FfmpegAudio) { on -> edit { it.copy(m3FfmpegAudio = on) } }

        rows += SRow.Header("Media3兼容性选项")
        rows += SRow.Toggle("TS 允许从非关键帧开始", "换台时不必等到关键帧，更快出画。", v.m3TsNonIdr) { on -> edit { it.copy(m3TsNonIdr = on) } }
        rows += SRow.Toggle("TS 检测访问单元", "没有关键帧标记的流也能识别出画面起点。", v.m3TsDetectAu) { on -> edit { it.copy(m3TsDetectAu = on) } }
        rows += SRow.Toggle("单个轨道出错时不停播", "某个音轨 / 视频轨出错时原地恢复，必要时自动重新拉流，不中断播放。", v.m3KeepPlaying) { on -> edit { it.copy(m3KeepPlaying = on) } }
        rows += SRow.Toggle("不为追赶直播而变速", "不通过加速 / 减速播放来追赶直播进度，避免声音变调。", v.m3NoCatchUp) { on -> edit { it.copy(m3NoCatchUp = on) } }
        rows += SRow.Toggle("优先按时间控制缓冲", "缓冲按缓存时间而不是按内存大小来控制，高码率频道更稳定。", v.m3PrioritizeTime) { on -> edit { it.copy(m3PrioritizeTime = on) } }
    }
    return rows
}
