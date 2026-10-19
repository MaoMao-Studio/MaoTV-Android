package com.cncat.maotv.player

import android.content.Context
import android.view.View
import com.cncat.maotv.core.DiagRow
import com.cncat.maotv.core.DiagSection
import com.cncat.maotv.core.SettingsValues
import com.cncat.maotv.core.TrackItem

/** 打开一个频道时传给内核的参数 */
data class EngineConfig(
    val settings: SettingsValues,
    /** 本次播放是否强制软解：设置里的“强制软解”，或这个频道重试 / 出错后临时改用软解。只对本次有效 */
    val softwareDecode: Boolean,
    /** true = 同一个频道失败后的重试 */
    val isRetry: Boolean,
)

sealed class EngineEvent {
    object Loading : EngineEvent()
    object Playing : EngineEvent()
    object TracksChanged : EngineEvent()
    /** 致命错误；decode = true 表示解码器问题（会改用软解重试） */
    data class Error(val decode: Boolean) : EngineEvent()
    /** 单个轨道 / 解码器出错（Media3），内核自己已经原地恢复；控制器据此统计错误次数 */
    data class TrackError(val decode: Boolean) : EngineEvent()
    object Ended : EngineEvent()
}

data class EngineTracks(val audio: List<TrackItem> = emptyList(), val subtitles: List<TrackItem> = emptyList())

/** 两个播放内核（VLC / Media3）的共同接口。PlayerController 只通过它和播放器打交道。所有方法在主线程调用。 */
interface PlaybackEngine {
    var onEvent: ((EngineEvent) -> Unit)?

    /** 当前画面尺寸（像素），未知时 0×0 */
    val videoWidth: Int
    val videoHeight: Int
    /** 视频帧率（未知时 0） */
    val videoFrameRate: Float
    /** 当前播放位置（毫秒）；用来判断画面有没有在走 */
    val positionMillis: Long
    val isPlaying: Boolean
    val hasVideoOut: Boolean

    /** 创建画面视图（含字幕）。视图被 Compose 的 AndroidView 承载；销毁时调用 [releaseView] */
    fun createView(context: Context): View
    fun releaseView()

    /** 打开频道（先停掉旧的） */
    fun start(url: String, config: EngineConfig)
    fun stop()
    /** 切换内核时彻底释放当前内核 */
    fun release()
    fun setVolume(v: Float)
    /** 4:3 的老频道拉伸为 16:9 */
    fun setStretch(on: Boolean)
    /** 换台 / 重试时清空轨道状态 */
    fun resetTracks()
    fun refreshTracks(applyDefaults: Boolean, userPickedSubtitle: Boolean): EngineTracks
    fun selectAudio(index: Int)
    fun selectSubtitle(index: Int?)
    /** 后台被系统暂停时让声音继续播放 */
    fun resumeIfStopped()
    /** 用户手动暂停 / 恢复 */
    fun pause()
    fun resume()
    /** 出了轨道错误后原地恢复（不换地址） */
    fun recoverInPlace()

    fun diagnosticSections(): List<DiagSection>
    fun statusRows(): List<DiagRow>
}

/** 字幕外观：整套固定样式（按 1080p 标定，按画面高度缩放） */
object SubtitleStyle {
    const val SIZE_PX = 65f                  // 字号（1080 高度下）
    const val REFERENCE_HEIGHT = 1080f
    const val OUTLINE_PX = 5f                // 描边宽度
    const val LETTER_SPACING_PX = 8f         // 字距
    const val BOTTOM_FRACTION = 59f / 1080f  // 距底部：1080 高度下 59px（原 5% = 54px，再上移 5px）
    const val VLC_BOTTOM_FRACTION = 25f / 1080f  // 仅用于 VLC 的 --sub-margin（它在自身字幕行高之外再加这段边距）：实测 47 仍偏高，降到 25
    const val FONT_ASSET = "fonts/AuraTV.ttf"
}
