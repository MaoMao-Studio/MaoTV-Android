package com.cncat.maotv.core

import android.content.Context
import android.content.SharedPreferences

enum class EngineKind(val key: String, val title: String) {
    MEDIA3("media3", "Media3"), VLC("vlc", "VLC");

    companion object {
        fun from(key: String?) = values().firstOrNull { it.key == key } ?: MEDIA3
    }
}

/** Media3 异步解码队列：自动 / 强制开 / 强制关 */
enum class AsyncQueueMode(val key: String, val title: String) {
    AUTO("auto", "自动"), ON("on", "开"), OFF("off", "关");

    companion object {
        fun from(key: String?) = values().firstOrNull { it.key == key } ?: AUTO
    }
}

/** 设置面板里编辑的一组值（不可变；面板里改的是草稿，保存时才应用） */
data class SettingsValues(
    // —— 通用 ——
    val reverseChannel: Boolean = false,      // 换台反转
    val frameRateMatch: Boolean = true,       // 按视频帧率设置屏幕刷新率
    val ignoreSsl: Boolean = false,           // 忽略 SSL 证书
    // —— 播放器 ——
    val engine: EngineKind = EngineKind.MEDIA3,
    val cacheSeconds: Double = DEFAULT_CACHE,
    // —— VLC ——
    val vlcSoftware: Boolean = false,         // VLC 强制软解（不推荐）
    // —— Media3：解码 ——
    val m3HardwareFirst: Boolean = true,      // 优先硬件解码
    val m3DecoderFallback: Boolean = true,    // 解码器自动降级
    val m3ExceedCaps: Boolean = true,         // 超出设备能力时仍尝试播放
    val m3AsyncQueue: AsyncQueueMode = AsyncQueueMode.AUTO,
    val m3FfmpegAudio: Boolean = false,       // 强制用 FFmpeg 软解音频（排查用）
    // —— Media3：带伤继续播 ——
    val m3TsNonIdr: Boolean = true,           // TS 允许从非关键帧开始
    val m3TsDetectAu: Boolean = true,         // TS 检测访问单元
    val m3KeepPlaying: Boolean = true,        // 单个轨道出错时不停播（自动重新拉流）
    val m3NoCatchUp: Boolean = true,          // 不为追赶直播而变速
    val m3PrioritizeTime: Boolean = true,     // 优先按时间而不是内存大小控制缓冲
) {
    companion object {
        const val DEFAULT_CACHE = 2.5
        const val MIN_CACHE = 0.5
        const val MAX_CACHE = 15.0
    }
}

/** 用户设置，保存在 SharedPreferences，下次启动仍然生效 */
class SettingsStore(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("maotv_settings", Context.MODE_PRIVATE)

    var values: SettingsValues = load()
        private set

    private fun load(): SettingsValues {
        val d = SettingsValues()
        val cache = sp.getFloat("cache", d.cacheSeconds.toFloat()).toDouble()
        return SettingsValues(
            reverseChannel = sp.getBoolean("reverseChannel", d.reverseChannel),
            frameRateMatch = sp.getBoolean("frameRateMatch", d.frameRateMatch),
            ignoreSsl = sp.getBoolean("ignoreSsl", d.ignoreSsl),
            engine = EngineKind.from(sp.getString("engine", null)),
            cacheSeconds = cache.coerceIn(SettingsValues.MIN_CACHE, SettingsValues.MAX_CACHE),
            vlcSoftware = sp.getBoolean("vlcSoftware", d.vlcSoftware),
            m3HardwareFirst = sp.getBoolean("m3HardwareFirst", d.m3HardwareFirst),
            m3DecoderFallback = sp.getBoolean("m3DecoderFallback", d.m3DecoderFallback),
            m3ExceedCaps = sp.getBoolean("m3ExceedCaps", d.m3ExceedCaps),
            m3AsyncQueue = AsyncQueueMode.from(sp.getString("m3AsyncQueue", null)),
            m3FfmpegAudio = sp.getBoolean("m3FfmpegAudio", d.m3FfmpegAudio),
            m3TsNonIdr = sp.getBoolean("m3TsNonIdr", d.m3TsNonIdr),
            m3TsDetectAu = sp.getBoolean("m3TsDetectAu", d.m3TsDetectAu),
            m3KeepPlaying = sp.getBoolean("m3KeepPlaying", d.m3KeepPlaying),
            m3NoCatchUp = sp.getBoolean("m3NoCatchUp", d.m3NoCatchUp),
            m3PrioritizeTime = sp.getBoolean("m3PrioritizeTime", d.m3PrioritizeTime),
        )
    }

    fun apply(v: SettingsValues) {
        val nv = v.copy(cacheSeconds = v.cacheSeconds.coerceIn(SettingsValues.MIN_CACHE, SettingsValues.MAX_CACHE))
        values = nv
        sp.edit()
            .putBoolean("reverseChannel", nv.reverseChannel)
            .putBoolean("frameRateMatch", nv.frameRateMatch)
            .putBoolean("ignoreSsl", nv.ignoreSsl)
            .putString("engine", nv.engine.key)
            .putFloat("cache", nv.cacheSeconds.toFloat())
            .putBoolean("vlcSoftware", nv.vlcSoftware)
            .putBoolean("m3HardwareFirst", nv.m3HardwareFirst)
            .putBoolean("m3DecoderFallback", nv.m3DecoderFallback)
            .putBoolean("m3ExceedCaps", nv.m3ExceedCaps)
            .putString("m3AsyncQueue", nv.m3AsyncQueue.key)
            .putBoolean("m3FfmpegAudio", nv.m3FfmpegAudio)
            .putBoolean("m3TsNonIdr", nv.m3TsNonIdr)
            .putBoolean("m3TsDetectAu", nv.m3TsDetectAu)
            .putBoolean("m3KeepPlaying", nv.m3KeepPlaying)
            .putBoolean("m3NoCatchUp", nv.m3NoCatchUp)
            .putBoolean("m3PrioritizeTime", nv.m3PrioritizeTime)
            .apply()
    }

    // —— 其他小状态 ——
    var token: String
        get() = sp.getString("userToken", "") ?: ""
        set(v) { sp.edit().putString("userToken", v).apply() }

    var lastChannelUrl: String?
        get() = sp.getString("lastChannelURL", null)
        set(v) { sp.edit().putString("lastChannelURL", v).apply() }

    var lastCategoryId: String?
        get() = sp.getString("lastCategoryID", null)
        set(v) { sp.edit().putString("lastCategoryID", v).apply() }

    var volume: Float
        get() = sp.getFloat("playerVolume", 1f)
        set(v) { sp.edit().putFloat("playerVolume", v).apply() }

    var epgLastUpdate: Long
        get() = sp.getLong("epgLastUpdate", 0L)
        set(v) { sp.edit().putLong("epgLastUpdate", v).apply() }
}
