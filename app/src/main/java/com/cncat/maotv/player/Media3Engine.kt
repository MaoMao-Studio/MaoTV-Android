@file:androidx.annotation.OptIn(UnstableApi::class)

package com.cncat.maotv.player

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import com.cncat.maotv.core.AsyncQueueMode
import com.cncat.maotv.core.DiagRow
import com.cncat.maotv.core.DiagSection
import com.cncat.maotv.core.SettingsValues
import com.cncat.maotv.core.TrackItem
import com.cncat.maotv.net.Http
import java.util.Locale

/** 字距：给整段字幕加 letterSpacing（单位 em） */
private class LetterSpacingSpan(private val em: Float) : MetricAffectingSpan() {
    override fun updateDrawState(tp: TextPaint) { tp.letterSpacing = em }
    override fun updateMeasureState(tp: TextPaint) { tp.letterSpacing = em }
}

/** Media3（ExoPlayer）内核 */
class Media3Engine(private val appContext: Context) : PlaybackEngine {
    override var onEvent: ((EngineEvent) -> Unit)? = null

    private enum class Kind { HLS, RTSP, PROGRESSIVE }

    private var player: ExoPlayer? = null
    private var trackSelector: DefaultTrackSelector? = null
    private var builtKey = ""

    private var rootView: FrameLayout? = null
    private var playerView: PlayerView? = null
    private var subtitleView: SubtitleView? = null

    private var stretched = false
    private var hasFirstFrame = false
    private var userPaused = false
    private var focusLost = false
    private var vw = 0
    private var vh = 0
    private var cfg: EngineConfig? = null
    private var currentUrl = ""
    private var triedKinds = HashSet<Kind>()
    private var videoDecoderName = ""
    private var audioDecoderName = ""
    private var lastTracks = EngineTracks()

    private data class TrackRef(val group: Tracks.Group, val index: Int)
    private var audioRefs: List<TrackRef> = emptyList()
    private var textRefs: List<TrackRef> = emptyList()

    private val typeface: Typeface? by lazy {
        try { Typeface.createFromAsset(appContext.assets, SubtitleStyle.FONT_ASSET) } catch (e: Throwable) { null }
    }

    // ───────────── 创建播放器 ─────────────

    private fun configKey(c: EngineConfig): String {
        val s = c.settings
        return listOf(c.softwareDecode, s.m3HardwareFirst, s.m3DecoderFallback, s.m3ExceedCaps, s.m3AsyncQueue,
            s.m3FfmpegAudio, s.m3PrioritizeTime, (s.cacheSeconds * 1000).toInt()).joinToString("|")
    }

    private fun buildPlayer(c: EngineConfig): ExoPlayer {
        val s = c.settings
        val hwFirst = s.m3HardwareFirst && !c.softwareDecode
        val selector = MediaCodecSelector { mime, secure, tunneling ->
            val list = MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling)
            // 默认硬解优先；“强制软解 / 本次临时回退”时软解排在前面（硬解仍留作后备）
            if (hwFirst) list.sortedByDescending { it.hardwareAccelerated } else list.sortedBy { it.hardwareAccelerated }
        }
        val rf = DefaultRenderersFactory(appContext)
            .setExtensionRendererMode(
                if (s.m3FfmpegAudio) DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
            )
            .setEnableDecoderFallback(s.m3DecoderFallback)
            .setMediaCodecSelector(selector)
        when (s.m3AsyncQueue) {
            AsyncQueueMode.ON -> rf.forceEnableMediaCodecAsynchronousQueueing()
            AsyncQueueMode.OFF -> rf.forceDisableMediaCodecAsynchronousQueueing()
            AsyncQueueMode.AUTO -> {}
        }

        val cacheMs = Math.round(s.cacheSeconds * 1000).toInt()
        val load = MaoLoadControl(
            minMs = maxOf(cacheMs, 5000),          // 最小缓冲
            maxMs = maxOf(cacheMs * 2, 15000),     // 最大缓冲
            startMs = cacheMs,                     // 起播前缓冲（TS 连续流：缓存满设定值再起播）
            afterRebufferMs = cacheMs.coerceAtLeast(1000),   // 卡顿后恢复前缓冲
            prioritizeTime = s.m3PrioritizeTime,
        )
        loadControl = load

        val ts = DefaultTrackSelector(appContext)
        ts.setParameters(ts.buildUponParameters()
            .setExceedRendererCapabilitiesIfNecessary(s.m3ExceedCaps)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)   // 字幕轨从“第一次选轨”就启用：中途才启用稀疏的字幕轨会让 Media3 重新定位 / 反复缓冲
            .setPreferredTextLanguages(*TEXT_LANGS)
            .setSelectUndeterminedTextLanguage(true)
            .build())
        trackSelector = ts

        val p = ExoPlayer.Builder(appContext, rf)
            .setTrackSelector(ts)
            .setLoadControl(load)
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        p.addListener(listener)
        p.addAnalyticsListener(analytics)
        playerView?.player = p
        return p
    }

    private fun ensurePlayer(c: EngineConfig): ExoPlayer {
        val key = configKey(c)
        val cur = player
        if (cur != null && key == builtKey) return cur
        destroyPlayer()
        builtKey = key
        return buildPlayer(c).also { player = it }
    }

    private fun destroyPlayer() {
        val p = player ?: return
        playerView?.player = null
        try { p.removeListener(listener); p.release() } catch (e: Throwable) { Log.w(TAG, "release", e) }
        player = null
        trackSelector = null
        builtKey = ""
    }

    // ───────────── 视图 ─────────────

    override fun createView(context: Context): View {
        val root = FrameLayout(context)
        root.setBackgroundColor(Color.BLACK)
        val pv = PlayerView(context)
        pv.useController = false
        pv.setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
        pv.setKeepContentOnPlayerReset(false)
        pv.resizeMode = if (stretched) AspectRatioFrameLayout.RESIZE_MODE_FILL else AspectRatioFrameLayout.RESIZE_MODE_FIT
        pv.subtitleView?.visibility = View.GONE            // 自带的字幕层不用，用下面自己的
        root.addView(pv, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val sv = SubtitleView(context)
        sv.setApplyEmbeddedStyles(false)
        sv.setApplyEmbeddedFontSizes(false)
        sv.setStyle(
            CaptionStyleCompat(
                Color.WHITE, Color.TRANSPARENT, Color.TRANSPARENT,
                CaptionStyleCompat.EDGE_TYPE_OUTLINE, Color.BLACK, typeface,
            )
        )
        sv.setFractionalTextSize(SubtitleStyle.SIZE_PX / SubtitleStyle.REFERENCE_HEIGHT)
        sv.setBottomPaddingFraction(SubtitleStyle.BOTTOM_FRACTION)
        root.addView(sv, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        rootView = root
        playerView = pv
        subtitleView = sv
        pv.player = player
        return root
    }

    override fun releaseView() {
        playerView?.player = null
        subtitleView?.setCues(emptyList())
        rootView = null
        playerView = null
        subtitleView = null
    }

    // ───────────── 基本状态 ─────────────

    override val videoWidth: Int get() = vw
    override val videoHeight: Int get() = vh
    override val videoFrameRate: Float
        get() {
            val f = player?.videoFormat?.frameRate ?: return 0f
            return if (f > 0f) f else 0f
        }
    override val positionMillis: Long get() = player?.currentPosition ?: 0L
    override val isPlaying: Boolean get() = player?.isPlaying ?: false
    override val hasVideoOut: Boolean get() = hasFirstFrame

    // ───────────── 控制 ─────────────

    private fun guessKind(url: String): Kind {
        val lower = url.lowercase()
        return when {
            lower.startsWith("rtsp://") || lower.startsWith("rtsps://") -> Kind.RTSP
            lower.substringBefore('?').endsWith(".m3u8") || lower.contains(".m3u8") -> Kind.HLS
            else -> Kind.PROGRESSIVE
        }
    }

    override fun start(url: String, config: EngineConfig) {
        cfg = config
        currentUrl = url
        userPaused = false
        focusLost = false
        hasFirstFrame = false
        vw = 0; vh = 0
        videoDecoderName = ""; audioDecoderName = ""
        triedKinds = HashSet()
        val p = try {
            ensurePlayer(config)
        } catch (e: Throwable) {
            Log.e(TAG, "创建播放器失败", e)
            onEvent?.invoke(EngineEvent.Error(decode = false))
            return
        }
        resetSelection()
        // m3u8 能拿到“未来数据”：约 1 秒就起播，之后继续缓存；TS 等连续流：缓存到设定值再起播
        loadControl?.fastStart = guessKind(url) == Kind.HLS
        lastErrorText = null
        t0 = android.os.SystemClock.elapsedRealtime(); synchronized(evlog) { evlog.clear() }
        PgsStats.reset(); cueCallbacks = 0; lastCueCount = 0; lastCueBitmap = false
        hints = M3u8Hints.EMPTY
        val seq = hintSeq.incrementAndGet()
        M3u8Hints.fetch(url) { h ->
            if (seq == hintSeq.get()) android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (seq == hintSeq.get()) { hints = h; onEvent?.invoke(EngineEvent.TracksChanged) }
            }
        }
        open(p, guessKind(url))
    }

    @Volatile private var hints: M3u8Hints = M3u8Hints.EMPTY
    private val hintSeq = java.util.concurrent.atomic.AtomicInteger(0)
    private var loadControl: MaoLoadControl? = null
    @Volatile private var lastErrorText: String? = null

    private fun open(p: ExoPlayer, kind: Kind) {
        triedKinds.add(kind)
        val c = cfg ?: return
        try {
            p.stop()
            p.setMediaSource(buildSource(currentUrl, kind, c.settings))
            p.prepare()
            p.playWhenReady = true
        } catch (e: Throwable) {
            Log.e(TAG, "打开失败", e)
            onEvent?.invoke(EngineEvent.Error(decode = false))
        }
    }

    private fun buildSource(url: String, kind: Kind, s: SettingsValues): MediaSource {
        val item = MediaItem.Builder().setUri(url).also {
            if (s.m3NoCatchUp) it.setLiveMinPlaybackSpeed(1f).setLiveMaxPlaybackSpeed(1f)
        }.build()
        val http = OkHttpDataSource.Factory(Http.callFactory).setUserAgent("MaoTV/4.0 (Android)")
        val ds = DefaultDataSource.Factory(appContext, http)
        // 读取超时 20 秒（OkHttp 客户端里设置）；加载失败自动重试最多 10 次
        val policy = DefaultLoadErrorHandlingPolicy(10)
        var tsFlags = 0
        if (s.m3TsNonIdr) tsFlags = tsFlags or DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES
        if (s.m3TsDetectAu) tsFlags = tsFlags or DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
        return when (kind) {
            Kind.HLS -> HlsMediaSource.Factory(ds)
                .setExtractorFactory(MaoHlsExtractorFactory(tsFlags))
                .setLoadErrorHandlingPolicy(policy)
                .setAllowChunklessPreparation(false)
                // WebVTT / PGS 等字幕在提取阶段就解析成 cues（1.5 的渲染器默认不再解析原始字幕，否则抛异常导致不断重试）
                .experimentalParseSubtitlesDuringExtraction(true)
                .createMediaSource(item)
            Kind.RTSP -> RtspMediaSource.Factory().createMediaSource(item)
            Kind.PROGRESSIVE -> ProgressiveMediaSource.Factory(
                ds, MaoExtractorsFactory(tsFlags)
            ).setLoadErrorHandlingPolicy(policy).createMediaSource(item)
        }
    }

    override fun stop() {
        userPaused = false
        try { player?.stop() } catch (e: Throwable) { }
    }

    override fun release() {
        userPaused = false
        destroyPlayer()
    }

    override fun setVolume(v: Float) {
        try { player?.volume = v.coerceIn(0f, 1f) } catch (e: Throwable) { }
    }

    override fun setStretch(on: Boolean) {
        stretched = on
        playerView?.resizeMode = if (on) AspectRatioFrameLayout.RESIZE_MODE_FILL else AspectRatioFrameLayout.RESIZE_MODE_FIT
    }

    override fun resetTracks() {
        lastTracks = EngineTracks()
        audioRefs = emptyList(); textRefs = emptyList()
        stretched = false
        playerView?.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        subtitleView?.setCues(emptyList())
        resetSelection()
    }

    private val TEXT_LANGS = arrayOf("zh", "zho", "chi", "cmn", "yue", "en", "eng", "ja", "jpn", "ko", "kor")

    private fun resetSelection() {
        subtitleHidden = false
        lastLoadError = null
        val ts = trackSelector ?: return
        ts.setParameters(ts.buildUponParameters()
            .clearOverrides()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setPreferredTextLanguages(*TEXT_LANGS)
            .setSelectUndeterminedTextLanguage(true)
            .build())
    }

    override fun pause() {
        userPaused = true
        player?.playWhenReady = false
    }

    override fun resume() {
        userPaused = false
        focusLost = false
        player?.playWhenReady = true
    }

    override fun resumeIfStopped() {
        // 被别的应用永久抢走音频焦点后不再自动抢回来
        if (userPaused || focusLost) return
        val p = player ?: return
        if (!p.playWhenReady) p.playWhenReady = true
    }

    override fun recoverInPlace() {
        val p = player ?: return
        try {
            p.prepare()
            p.playWhenReady = !userPaused
        } catch (e: Throwable) {
            Log.w(TAG, "recoverInPlace", e)
        }
    }

    // ───────────── 音轨 / 字幕轨 ─────────────

    private fun trackName(f: Format, fallback: String): String {
        val parts = ArrayList<String>()
        f.label?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
        f.language?.takeIf { it.isNotBlank() && it != "und" }?.let {
            val n = try { Locale.forLanguageTag(it).getDisplayLanguage(Locale.CHINA) } catch (e: Exception) { "" }
            parts.add(n.ifEmpty { it })
        }
        if (parts.isEmpty()) return fallback
        return parts.joinToString(" ")
    }

    override fun refreshTracks(applyDefaults: Boolean, userPickedSubtitle: Boolean): EngineTracks {
        val p = player ?: return EngineTracks()
        val ts = trackSelector ?: return EngineTracks()
        val groups = p.currentTracks.groups
        val aRefs = ArrayList<TrackRef>()
        val tRefs = ArrayList<TrackRef>()
        for (g in groups) {
            for (i in 0 until g.length) {
                if (!g.isTrackSupported(i)) continue
                if (g.type == C.TRACK_TYPE_AUDIO) aRefs.add(TrackRef(g, i))
                else if (g.type == C.TRACK_TYPE_TEXT) tRefs.add(TrackRef(g, i))
            }
        }
        // 按 m3u8 的 EXT-X-MEDIA 顺序重排并统一命名（和 VLC 一致）；没有 m3u8 信息时保持播放器给出的顺序
        fun cand(r: TrackRef): TrackCand {
            val f = r.group.getTrackFormat(r.index)
            return TrackCand(f.label.orEmpty(), f.language?.takeIf { it != "und" }.orEmpty())
        }
        val aOrd = TrackArranger.arrange(aRefs.map(::cand), hints.audio)
        val tOrd = TrackArranger.arrange(tRefs.map(::cand), hints.subtitles)
        val aSorted = aOrd.map { aRefs[it.first] }
        val tSorted = tOrd.map { tRefs[it.first] }
        audioRefs = aSorted
        textRefs = tSorted

        // 字幕轨一出现就选中第一条（用户自己选过 / 关闭过就不再自动选）
        val textDisabled = ts.parameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
        val anyTextSelected = !textDisabled && tSorted.any { it.group.isTrackSelected(it.index) }
        if (applyDefaults && !userPickedSubtitle && !anyTextSelected && tSorted.isNotEmpty()) {
            val first = tSorted.first()
            ts.setParameters(ts.buildUponParameters()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(TrackSelectionOverride(first.group.mediaTrackGroup, first.index))
                .build())
        }
        val textOn = !ts.parameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT) && !subtitleHidden

        lastTracks = EngineTracks(
            audio = aOrd.mapIndexed { i, (idx, n) ->
                val r = aRefs[idx]
                TrackItem("a$i", n ?: trackName(r.group.getTrackFormat(r.index), "音轨 ${i + 1}"), r.group.isTrackSelected(r.index))
            },
            subtitles = tOrd.mapIndexed { i, (idx, n) ->
                val r = tRefs[idx]
                TrackItem("s$i", n ?: trackName(r.group.getTrackFormat(r.index), "字幕 ${i + 1}"), textOn && r.group.isTrackSelected(r.index))
            },
        )
        return lastTracks
    }

    override fun selectAudio(index: Int) {
        val r = audioRefs.getOrNull(index) ?: return
        val ts = trackSelector ?: return
        ts.setParameters(ts.buildUponParameters()
            .setOverrideForType(TrackSelectionOverride(r.group.mediaTrackGroup, r.index))
            .build())
    }

    override fun selectSubtitle(index: Int?) {
        val ts = trackSelector ?: return
        val r = if (index != null) textRefs.getOrNull(index) else null
        ev("选字幕 ${index ?: "关闭"}")
        if (r == null) {
            // 关闭 = 只隐藏；轨道保持选中，之后再打开不会触发重新拉流
            subtitleHidden = true
            subtitleView?.setCues(emptyList())
            return
        }
        subtitleHidden = false
        val textEnabled = !ts.parameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
        if (textEnabled && r.group.isTrackSelected(r.index)) return   // 已经选中：直接显示即可
        ts.setParameters(run {
            ts.buildUponParameters()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(TrackSelectionOverride(r.group.mediaTrackGroup, r.index))
                .build()
        })
    }

    // ───────────── 监听 ─────────────

    private val listener = object : Player.Listener {
        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            ev("位置跳变 原因$reason ${oldPosition.positionMs / 1000}→${newPosition.positionMs / 1000}秒")
        }

        override fun onPlaybackStateChanged(state: Int) {
            ev("状态 " + when (state) { Player.STATE_BUFFERING -> "缓冲"; Player.STATE_READY -> "就绪"; Player.STATE_ENDED -> "结束"; else -> "空闲" })
            when (state) {
                Player.STATE_BUFFERING -> onEvent?.invoke(EngineEvent.Loading)
                Player.STATE_READY -> onEvent?.invoke(EngineEvent.Playing)
                Player.STATE_ENDED -> onEvent?.invoke(EngineEvent.Ended)
                else -> {}
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS) focusLost = true
            if (playWhenReady) focusLost = false
        }

        override fun onTracksChanged(tracks: Tracks) {
            ev("轨道变化 音${tracks.groups.count { it.type == C.TRACK_TYPE_AUDIO }} 字${tracks.groups.count { it.type == C.TRACK_TYPE_TEXT }} 字幕选中=${tracks.groups.any { it.type == C.TRACK_TYPE_TEXT && it.isSelected }}")
            onEvent?.invoke(EngineEvent.TracksChanged)
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            vw = videoSize.width
            vh = videoSize.height
        }

        override fun onRenderedFirstFrame() {
            hasFirstFrame = true
            onEvent?.invoke(EngineEvent.Playing)
        }

        override fun onCues(cueGroup: CueGroup) {
            cueCallbacks++
            lastCueCount = cueGroup.cues.size
            lastCueBitmap = cueGroup.cues.any { it.bitmap != null }
            val sv = subtitleView ?: return
            if (subtitleHidden) { sv.setCues(emptyList()); return }
            val em = SubtitleStyle.LETTER_SPACING_PX / SubtitleStyle.SIZE_PX
            val out = cueGroup.cues.map { cue ->
                val t = cue.text
                if (t == null || t.isEmpty()) cue    // 位图字幕（PGS）保持自带的位置
                else {
                    val sb = SpannableStringBuilder(t)
                    sb.setSpan(LetterSpacingSpan(em), 0, sb.length, SpannableStringBuilder.SPAN_INCLUSIVE_INCLUSIVE)
                    // 文字字幕（WebVTT 等）常自带“贴底”的行位置，会让“距底部 5%”不生效：清掉，统一由字幕层按固定高度显示
                    cue.buildUpon().setText(sb)
                        .setLine(Cue.DIMEN_UNSET, Cue.TYPE_UNSET).setLineAnchor(Cue.TYPE_UNSET)
                        .setPosition(Cue.DIMEN_UNSET).setPositionAnchor(Cue.TYPE_UNSET)
                        .setTextAlignment(android.text.Layout.Alignment.ALIGN_CENTER)
                        .build()
                }
            }
            sv.setCues(out)
        }

        override fun onPlayerError(error: PlaybackException) {
            val p = player ?: return
            Log.w(TAG, "播放错误 ${error.errorCodeName}", error)
            // 记下最近一次错误，显示在诊断页（排查 PGS 等字幕问题用）
            lastErrorText = buildString {
                append(error.errorCodeName)
                (error as? ExoPlaybackException)?.let { e ->
                    append(" / type=").append(e.type)
                    e.rendererFormat?.sampleMimeType?.let { append(" / ").append(it) }
                }
                var c: Throwable? = error
                var n = 0
                while (c != null && n < 3) {
                    append(" | ").append(c.javaClass.simpleName).append(": ").append((c.message ?: "").take(80))
                    c = c.cause; n++
                }
                // 最深一层异常的抛出位置（区分“首次解析失败”还是“重连后失败”）
                var d: Throwable = error
                while (d.cause != null && d.cause !== d) d = d.cause!!
                d.stackTrace.take(3).forEach { append(" @").append(it.className.substringAfterLast('.')).append('.').append(it.methodName) }
            }
            val code = error.errorCode
            ev("错误 ${error.errorCodeName}")

            // 直播窗口落后（缓冲耗尽后的追赶）：原地重新准备
            if (code == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                p.seekToDefaultPosition()
                p.prepare()
                return
            }
            // 地址格式猜错：换另一种方式再试一次（不算一次“重试”）
            if (code == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED && !triedKinds.contains(Kind.HLS)) {
                open(p, Kind.HLS); return
            }
            if (code == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED && !triedKinds.contains(Kind.PROGRESSIVE)) {
                open(p, Kind.PROGRESSIVE); return
            }

            val ex = error as? ExoPlaybackException
            // 字幕渲染出错：只关掉字幕继续播，绝不当成解码失败（否则会不停重试 / 切软解）
            if (ex != null && ex.type == ExoPlaybackException.TYPE_RENDERER &&
                ex.rendererFormat?.sampleMimeType?.let { MimeTypes.isText(it) || it == MimeTypes.APPLICATION_MEDIA3_CUES } == true
            ) {
                trackSelector?.let { ts ->
                    ts.setParameters(ts.buildUponParameters()
                        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build())
                }
                p.prepare()
                return
            }
            if (ex != null && ex.type == ExoPlaybackException.TYPE_RENDERER) {
                // 单个轨道 / 解码器出错：带伤继续播，原地恢复，由控制器统计次数
                if (cfg?.settings?.m3KeepPlaying == true) {
                    onEvent?.invoke(EngineEvent.TrackError(decode = true))
                    recoverInPlace()
                } else {
                    onEvent?.invoke(EngineEvent.Error(decode = true))
                }
            } else {
                onEvent?.invoke(EngineEvent.Error(decode = false))
            }
        }
    }

    private val analytics = object : AnalyticsListener {
        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long,
        ) { videoDecoderName = decoderName }

        override fun onAudioDecoderInitialized(
            eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long,
        ) { audioDecoderName = decoderName }

        override fun onLoadError(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: androidx.media3.exoplayer.source.LoadEventInfo,
            mediaLoadData: androidx.media3.exoplayer.source.MediaLoadData,
            error: java.io.IOException,
            wasCanceled: Boolean,
        ) {
            lastLoadError = "${error.javaClass.simpleName}: ${(error.message ?: "").take(60)} | 已读 ${loadEventInfo.bytesLoaded} 字节 | ${loadEventInfo.uri.host}"
        }
    }
    @Volatile private var lastLoadError: String? = null
    private val evlog = java.util.ArrayDeque<String>()
    @Volatile private var t0 = 0L
    private fun ev(s: String) {
        synchronized(evlog) {
            evlog.addLast("%.1fs %s".format((android.os.SystemClock.elapsedRealtime() - t0) / 1000.0, s))
            while (evlog.size > 14) evlog.removeFirst()
        }
    }
    @Volatile private var cueCallbacks = 0
    @Volatile private var lastCueCount = 0
    @Volatile private var lastCueBitmap = false
    /** 字幕“关闭”只隐藏、不取消选中轨道：新启用从未读取过的稀疏轨（PGS）会让 Media3 重新拉流 */
    @Volatile private var subtitleHidden = false

    // ───────────── 诊断 ─────────────

    private fun isSoftwareDecoder(name: String): Boolean {
        val n = name.lowercase()
        return n.startsWith("omx.google.") || n.startsWith("c2.android.") || n.contains("ffmpeg") || n.contains("sw")
    }

    override fun diagnosticSections(): List<DiagSection> {
        val p = player
        val out = ArrayList<DiagSection>()
        val cacheMs = cfg?.settings?.cacheSeconds ?: 0.0

        val video = ArrayList<DiagRow>()
        video.add(DiagRow("播放内核", "Media3（ExoPlayer）"))
        video.add(DiagRow("解码方式",
            if (videoDecoderName.isEmpty()) "（等待解码器）"
            else (if (isSoftwareDecoder(videoDecoderName)) "软解：" else "硬解：") + videoDecoderName))
        video.add(DiagRow("缓存时间", String.format(Locale.US, "%.1f 秒", cacheMs)))
        val vf = p?.videoFormat
        if (vf != null) {
            video.add(DiagRow("视频编码", friendly(vf.sampleMimeType)))
            if (vf.width > 0) video.add(DiagRow("视频分辨率", "${vf.width}×${vf.height}"))
            if (vf.frameRate > 0) video.add(DiagRow("视频帧率", String.format(Locale.US, "%.2f fps", vf.frameRate)))
            if (vf.bitrate > 0) video.add(DiagRow("视频码率", String.format(Locale.US, "%.2f Mbps", vf.bitrate / 1_000_000.0)))
        }
        out.add(DiagSection("视频", video))

        val af = p?.audioFormat
        if (af != null) {
            val audio = arrayListOf(DiagRow("音频编码", friendly(af.sampleMimeType)))
            if (audioDecoderName.isNotEmpty())
                audio.add(DiagRow("音频解码器", (if (isSoftwareDecoder(audioDecoderName)) "软解：" else "硬解：") + audioDecoderName))
            if (af.channelCount > 0) audio.add(DiagRow("声道", "${af.channelCount}"))
            if (af.sampleRate > 0) audio.add(DiagRow("采样率", "${af.sampleRate} Hz"))
            if (af.bitrate > 0) audio.add(DiagRow("音频码率", "${af.bitrate / 1000} kbps"))
            audio.add(DiagRow("音轨数", "${audioRefs.size}"))
            out.add(DiagSection("音频", audio))
        } else {
            out.add(DiagSection("音频", listOf(DiagRow("音频", if (audioRefs.isEmpty()) "无" else "${audioRefs.size} 条音轨"))))
        }

        val sub = arrayListOf(DiagRow("字幕轨数", "${textRefs.size}"))
        sub.add(DiagRow("当前字幕", lastTracks.subtitles.firstOrNull { it.isOn }?.name ?: "关闭"))
        // 列出播放器看到的所有字幕轨（含不支持的），方便排查
        player?.currentTracks?.groups?.filter { it.type == C.TRACK_TYPE_TEXT }?.forEachIndexed { gi, g ->
            for (i in 0 until g.length) {
                val f = g.getTrackFormat(i)
                sub.add(DiagRow("字幕${gi + 1}详情", "id=${f.id} / 容器=${f.containerMimeType} / 编码=${f.codecs} / 语言=${f.language} / 名称=${f.label}"))
                sub.add(DiagRow("字幕${gi + 1}格式", "${f.sampleMimeType ?: "?"}${if (g.isTrackSupported(i)) "" else "（不支持）"}"))
            }
        }
        out.add(DiagSection("字幕", sub))
        return out
    }

    override fun statusRows(): List<DiagRow> {
        val p = player
        val st = when (p?.playbackState) {
            Player.STATE_IDLE -> "空闲"
            Player.STATE_BUFFERING -> "缓冲中"
            Player.STATE_READY -> if (p.playWhenReady) "播放中" else "已暂停"
            Player.STATE_ENDED -> "已结束"
            else -> "未知"
        }
        val rows = arrayListOf(DiagRow("状态", st))
        val dc = p?.videoDecoderCounters
        if (dc != null) {
            dc.ensureUpdated()
            rows.add(DiagRow("已渲染帧数", "${dc.renderedOutputBufferCount}"))
            rows.add(DiagRow("丢帧", "${dc.droppedBufferCount}"))
        }
        if (p != null) rows.add(DiagRow("缓冲余量", String.format(Locale.US, "%.1f 秒", p.totalBufferedDuration / 1000.0)))
        lastErrorText?.let { rows.add(DiagRow("最近播放器错误", it)) }
        lastLoadError?.let { rows.add(DiagRow("最近加载错误", it)) }
        rows.add(DiagRow("TS流类型", "${PgsStats.path} / ${PgsStats.streams().ifEmpty { "无" }}"))
        rows.add(DiagRow("DVB字幕包", "收到 ${PgsStats.dvbPackets} 个，其中无对齐标志 ${PgsStats.dvbUnaligned} 个"))
        fun sec(us: Long) = if (us < 0) "无" else "%.1f".format(us / 1e6)
        rows.add(DiagRow("字幕样本时间", "共 ${PgsStats.textCount} 个 / 首个 ${sec(PgsStats.textFirstUs)} / 最新 ${sec(PgsStats.textLastUs)} 秒 | 视频最新 ${sec(PgsStats.videoLastUs)} | 音频最新 ${sec(PgsStats.audioLastUs)} | 播放位置 ${"%.1f".format((player?.currentPosition ?: 0) / 1000.0)}"))
        synchronized(evlog) { evlog.forEachIndexed { i, e -> rows.add(DiagRow("事件${i + 1}", e)) } }
        rows.add(DiagRow("PGS读取器", "轨道 ${PgsStats.tracks} / 收到 ${PgsStats.bytes} 字节 / 完整显示集 ${PgsStats.sets} / 丢弃 ${PgsStats.dropped} / 最近 ${if (PgsStats.lastSetUs < 0) "无" else "%.1f 秒".format(PgsStats.lastSetUs / 1e6)}"))
        rows.add(DiagRow("字幕输出", "回调 $cueCallbacks 次 / 当前 $lastCueCount 条${if (lastCueBitmap) "（位图）" else ""} / 隐藏 ${if (subtitleHidden) "是" else "否"} / 播放位置 ${"%.1f".format((player?.currentPosition ?: 0) / 1000.0)} 秒"))
        try {
            val pl = player
            if (pl != null) for (i in 0 until pl.rendererCount) {
                val r = pl.getRenderer(i)
                val kind = when (pl.getRendererType(i)) { C.TRACK_TYPE_VIDEO -> "视频"; C.TRACK_TYPE_AUDIO -> "音频"; C.TRACK_TYPE_TEXT -> "字幕"; else -> "其他" }
                if (r.state != 0) rows.add(DiagRow("渲染器·$kind", "${if (r.state == 2) "运行" else "已启用"} / 就绪 ${if (r.isReady) "是" else "否"} / 结束 ${if (r.isEnded) "是" else "否"}"))
            }
        } catch (_: Throwable) { }
        return rows
    }

    private fun friendly(mime: String?): String = when (mime) {
        "video/avc" -> "H.264 / AVC"
        "video/hevc" -> "H.265 / HEVC"
        "video/mpeg2" -> "MPEG-2"
        "video/mp4v-es" -> "MPEG-4"
        "audio/mp4a-latm" -> "AAC"
        "audio/mpeg-L2" -> "MP2"
        "audio/mpeg" -> "MP3"
        "audio/ac3" -> "AC-3（杜比数字）"
        "audio/eac3" -> "E-AC-3（杜比数字+）"
        "audio/vnd.dts" -> "DTS"
        null -> "未知"
        else -> mime
    }

    companion object {
        private const val TAG = "Media3Engine"
    }
}

/** 起播策略：m3u8（fastStart）缓存约 1 秒就起播，起播后继续缓存到设定值；其余按设定的缓存时间起播 */
@androidx.annotation.OptIn(UnstableApi::class)
class MaoLoadControl(
    minMs: Int, maxMs: Int, startMs: Int, afterRebufferMs: Int, prioritizeTime: Boolean,
) : DefaultLoadControl(
    androidx.media3.exoplayer.upstream.DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE),
    minMs, maxMs, startMs, afterRebufferMs, C.LENGTH_UNSET, prioritizeTime, 0, false,
) {
    @Volatile var fastStart = false

    override fun shouldStartPlayback(parameters: androidx.media3.exoplayer.LoadControl.Parameters): Boolean {
        if (fastStart && !parameters.rebuffering) {
            val bufferedUs = (parameters.bufferedDurationUs / parameters.playbackSpeed.coerceAtLeast(0.1f)).toLong()
            if (bufferedUs >= 1_000_000L) return true
        }
        return super.shouldStartPlayback(parameters)
    }
}

