package com.cncat.maotv.player

import android.content.Context
import android.net.Uri
import android.util.Log
import android.view.View
import com.cncat.maotv.core.DiagRow
import com.cncat.maotv.core.DiagSection
import com.cncat.maotv.core.TrackItem
import com.cncat.maotv.net.Http
import org.videolan.libvlc.Dialog
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout
import java.io.File
import java.util.Locale

/** libVLC 内核 */
class VlcEngine(private val appContext: Context) : PlaybackEngine {
    override var onEvent: ((EngineEvent) -> Unit)? = null

    private var libVlc: LibVLC? = null
    private var player: MediaPlayer? = null
    private var layout: VLCVideoLayout? = null
    private var createFailed = false

    private var stretched = false
    private var hasVout = false
    private var userPaused = false
    private var softwareDecode = false
    private var cacheMillis = 0
    private var lastTracks = EngineTracks()

    // 诊断：每秒显示帧数的采样
    private var fpsTime = 0L
    private var fpsShown = 0
    private var measuredFps = 0.0

    // ───────────── 创建 ─────────────

    /** 把字库从 assets 复制到应用目录（libVLC 只能读真实文件路径） */
    private fun fontPath(): String? {
        return try {
            val out = File(appContext.filesDir, "aura.ttf")
            val len = appContext.assets.openFd(SubtitleStyle.FONT_ASSET).use { it.length }
            if (!out.exists() || out.length() != len) {
                val tmp = File(appContext.filesDir, "aura.ttf.tmp")
                appContext.assets.open(SubtitleStyle.FONT_ASSET).use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                out.delete()
                if (!tmp.renameTo(out)) return null
            }
            out.absolutePath
        } catch (e: Exception) {
            Log.w(TAG, "字库复制失败", e)
            null
        }
    }

    private fun ensurePlayer(): MediaPlayer? {
        player?.let { return it }
        if (createFailed) return null
        val full = ArrayList<String>()
        full.add("--http-reconnect")
        // 字幕外观：字号 = 画面高度 / 17（1080 高度下约 65px），白字黑描边，字库用 Aura
        fontPath()?.let { full.add("--freetype-font=$it") }
        full.add("--freetype-rel-fontsize=${Math.round(SubtitleStyle.REFERENCE_HEIGHT / SubtitleStyle.SIZE_PX)}")
        full.add("--freetype-outline-thickness=${outlineThickness(SubtitleStyle.OUTLINE_PX)}")
        full.add("--freetype-outline-color=0")
        full.add("--freetype-color=16777215")
        full.add("--sub-margin=${Math.round(SubtitleStyle.REFERENCE_HEIGHT * SubtitleStyle.BOTTOM_FRACTION)}")

        var vlc: LibVLC? = try {
            LibVLC(appContext, full)
        } catch (e: Throwable) {
            Log.w(TAG, "LibVLC 创建失败，改用精简参数", e)
            null
        }
        if (vlc == null) {
            vlc = try {
                LibVLC(appContext, arrayListOf("--http-reconnect"))
            } catch (e: Throwable) {
                Log.e(TAG, "LibVLC 创建失败", e)
                null
            }
        }
        if (vlc == null) {
            createFailed = true
            return null
        }
        libVlc = vlc

        // 遇到不受信任的证书：打开了“忽略SSL证书”就放行（只在本次运行内有效），否则拒绝
        try {
            Dialog.setCallbacks(vlc, object : Dialog.Callbacks {
                override fun onDisplay(dialog: Dialog.ErrorMessage) {}
                override fun onDisplay(dialog: Dialog.LoginDialog) { dialog.dismiss() }
                override fun onDisplay(dialog: Dialog.QuestionDialog) {
                    if (Http.ignoreSsl) dialog.postAction(1) else dialog.dismiss()
                }
                override fun onDisplay(dialog: Dialog.ProgressDialog) {}
                override fun onCanceled(dialog: Dialog?) {}
                override fun onProgressUpdate(dialog: Dialog.ProgressDialog) {}
            })
        } catch (e: Throwable) {
            Log.w(TAG, "设置对话框回调失败", e)
        }

        val mp = MediaPlayer(vlc)
        mp.setEventListener { ev -> handle(ev) }
        player = mp
        return mp
    }

    /** 描边宽度（像素）→ VLC 只认 0 / 2 / 4 / 6 */
    private fun outlineThickness(px: Float): Int = when {
        px <= 0f -> 0
        px <= 3f -> 2
        px <= 6f -> 4
        else -> 6
    }

    // ───────────── 视图 ─────────────

    override fun createView(context: Context): View {
        val l = VLCVideoLayout(context)
        layout = l
        ensurePlayer()?.attachViews(l, null, true, false)
        applyStretch()
        return l
    }

    override fun releaseView() {
        try {
            player?.detachViews()
        } catch (e: Throwable) {
            Log.w(TAG, "detachViews", e)
        }
        layout = null
    }

    // ───────────── 基本状态 ─────────────

    private val videoTrack: IMedia.VideoTrack?
        get() = try { player?.currentVideoTrack } catch (e: Throwable) { null }

    override val videoWidth: Int get() = videoTrack?.width ?: 0
    override val videoHeight: Int get() = videoTrack?.height ?: 0
    override val videoFrameRate: Float
        get() {
            val t = videoTrack ?: return 0f
            return if (t.frameRateDen > 0) t.frameRateNum.toFloat() / t.frameRateDen else 0f
        }
    override val positionMillis: Long get() = player?.time ?: 0L
    override val isPlaying: Boolean get() = player?.isPlaying ?: false
    override val hasVideoOut: Boolean get() = hasVout

    // ───────────── 控制 ─────────────

    override fun start(url: String, config: EngineConfig) {
        val mp = ensurePlayer()
        if (mp == null) {
            onEvent?.invoke(EngineEvent.Error(decode = false))
            return
        }
        userPaused = false
        hasVout = false
        softwareDecode = config.softwareDecode
        cacheMillis = Math.round(config.settings.cacheSeconds * 1000).toInt()
        try {
            mp.stop()
            val media = Media(libVlc!!, Uri.parse(url))
            media.addOption(":network-caching=$cacheMillis")
            media.addOption(":live-caching=$cacheMillis")
            // 直播源丢包 / 时钟跳变时尽量“带伤继续播”（顶多短暂花屏），而不是停下来等关键帧或重新同步时钟
            media.addOption(":no-ts-cc-check")
            media.addOption(":clock-jitter=0")
            media.addOption(":clock-synchro=0")
            // 能硬解就硬解；只有“强制软解”（设置里或本次临时回退）才改用软解
            media.setHWDecoderEnabled(!softwareDecode, false)
            if (softwareDecode) {
                media.addOption(":codec=avcodec,all")
                media.addOption(":avcodec-hw=none")
            }
            mp.media = media
            media.release()
            mp.play()
        } catch (e: Throwable) {
            Log.e(TAG, "start 失败", e)
            onEvent?.invoke(EngineEvent.Error(decode = false))
        }
    }

    override fun stop() {
        userPaused = false
        try { player?.stop() } catch (e: Throwable) { Log.w(TAG, "stop", e) }
    }

    override fun release() {
        userPaused = false
        try { player?.stop() } catch (e: Throwable) { }
        try { player?.detachViews() } catch (e: Throwable) { }
        try { player?.release() } catch (e: Throwable) { }
        try { libVlc?.release() } catch (e: Throwable) { }
        player = null
        libVlc = null
        layout = null
    }

    override fun setVolume(v: Float) {
        try { player?.volume = Math.round(v.coerceIn(0f, 1f) * 100) } catch (e: Throwable) { }
    }

    override fun setStretch(on: Boolean) {
        if (on == stretched) return
        stretched = on
        applyStretch()
    }

    private fun applyStretch() {
        try {
            player?.videoScale = if (stretched) MediaPlayer.ScaleType.SURFACE_16_9 else MediaPlayer.ScaleType.SURFACE_BEST_FIT
        } catch (e: Throwable) { }
    }

    override fun resetTracks() {
        lastTracks = EngineTracks()
        if (stretched) {
            stretched = false
            applyStretch()
        }
    }

    override fun pause() {
        userPaused = true
        try { if (player?.isPlaying == true) player?.pause() } catch (e: Throwable) { }
    }

    override fun resume() {
        userPaused = false
        try { player?.play() } catch (e: Throwable) { }
    }

    override fun resumeIfStopped() {
        if (userPaused) return
        val mp = player ?: return
        try { if (!mp.isPlaying) mp.play() } catch (e: Throwable) { }
    }

    override fun recoverInPlace() { /* VLC 没有“轨道错误”，这里不需要 */ }

    // ───────────── 音轨 / 字幕轨（兼容不同版本的返回类型，用反射读 id / name） ─────────────

    private class Entry(val id: String, val name: String)

    private fun entries(raw: Any?): List<Entry> {
        val items: List<Any?> = when (raw) {
            is Array<*> -> raw.toList()
            is List<*> -> raw
            else -> return emptyList()
        }
        val out = ArrayList<Entry>()
        for (o in items) {
            if (o == null) continue
            val id = readProp(o, "id") ?: continue
            val name = readProp(o, "name") ?: ""
            out.add(Entry(id, name))
        }
        return out
    }

    private fun readProp(o: Any, name: String): String? {
        try {
            val cls = o.javaClass
            val getter = cls.methods.firstOrNull {
                it.parameterTypes.isEmpty() && (it.name.equals("get$name", true) || it.name.equals(name, true))
            }
            if (getter != null) return getter.invoke(o)?.toString()
            return cls.getField(name).get(o)?.toString()
        } catch (e: Throwable) {
            return null
        }
    }

    /** 调用 setAudioTrack / setSpuTrack：参数类型可能是 int 也可能是 String */
    private fun setTrack(method: String, id: String): Boolean {
        val mp = player ?: return false
        return try {
            val m = mp.javaClass.methods.firstOrNull { it.name == method && it.parameterTypes.size == 1 } ?: return false
            val arg: Any = when (m.parameterTypes[0]) {
                Int::class.javaPrimitiveType, Int::class.javaObjectType -> id.toInt()
                else -> id
            }
            m.invoke(mp, arg)
            true
        } catch (e: Throwable) {
            Log.w(TAG, "$method 失败", e)
            false
        }
    }

    private fun currentId(getter: String): String? {
        val mp = player ?: return null
        return try { mp.javaClass.getMethod(getter).invoke(mp)?.toString() } catch (e: Throwable) { null }
    }

    private var audioEntries: List<Entry> = emptyList()
    private var subtitleEntries: List<Entry> = emptyList()

    private fun isDisabled(id: String) = id == "-1"

    override fun refreshTracks(applyDefaults: Boolean, userPickedSubtitle: Boolean): EngineTracks {
        val mp = player ?: return EngineTracks()
        try {
            // 音轨（列表里 id = -1 的是“禁用”，不显示）
            val audios = entries(mp.audioTracks).filter { !isDisabled(it.id) }
            audioEntries = audios
            if (applyDefaults && currentId("getAudioTrack").let { it == null || isDisabled(it) }) {
                audios.firstOrNull()?.let { setTrack("setAudioTrack", it.id) }
            }
            val selA = currentId("getAudioTrack")

            // 字幕轨：一出现就选中第一条（用户自己选过 / 关闭过就不再自动选）
            val subs = entries(mp.spuTracks).filter { !isDisabled(it.id) }
            subtitleEntries = subs
            if (applyDefaults && !userPickedSubtitle && currentId("getSpuTrack").let { it == null || isDisabled(it) }) {
                subs.firstOrNull()?.let { setTrack("setSpuTrack", it.id) }
            }
            val selS = currentId("getSpuTrack")

            lastTracks = EngineTracks(
                audio = audios.mapIndexed { i, e -> TrackItem("a$i", clean(e.name, "音轨 ${i + 1}"), e.id == selA) },
                subtitles = subs.mapIndexed { i, e -> TrackItem("s$i", clean(e.name, "字幕 ${i + 1}"), e.id == selS) },
            )
        } catch (e: Throwable) {
            Log.w(TAG, "refreshTracks", e)
        }
        return lastTracks
    }

    private fun clean(raw: String, fallback: String): String = raw.trim().ifEmpty { fallback }

    override fun selectAudio(index: Int) {
        audioEntries.getOrNull(index)?.let { setTrack("setAudioTrack", it.id) }
    }

    override fun selectSubtitle(index: Int?) {
        if (index != null && subtitleEntries.indices.contains(index)) setTrack("setSpuTrack", subtitleEntries[index].id)
        else setTrack("setSpuTrack", "-1")
    }

    // ───────────── 事件 ─────────────

    private fun handle(ev: MediaPlayer.Event) {
        when (ev.type) {
            MediaPlayer.Event.Opening, MediaPlayer.Event.Buffering -> onEvent?.invoke(EngineEvent.Loading)
            MediaPlayer.Event.Playing -> onEvent?.invoke(EngineEvent.Playing)
            MediaPlayer.Event.ESAdded -> onEvent?.invoke(EngineEvent.TracksChanged)
            MediaPlayer.Event.Vout -> hasVout = ev.voutCount > 0
            MediaPlayer.Event.EncounteredError -> onEvent?.invoke(EngineEvent.Error(decode = false))
            MediaPlayer.Event.EndReached, MediaPlayer.Event.Stopped -> onEvent?.invoke(EngineEvent.Ended)
            MediaPlayer.Event.Paused -> {
                // 用户自己暂停的不动；被系统暂停后自动恢复
                if (!userPaused) try { player?.play() } catch (e: Throwable) { }
            }
        }
    }

    // ───────────── 诊断 ─────────────

    private fun tracks(): List<IMedia.Track> {
        val media = player?.media ?: return emptyList()
        return try {
            (0 until media.trackCount).mapNotNull { media.getTrack(it) }
        } catch (e: Throwable) {
            emptyList()
        }
    }

    override fun diagnosticSections(): List<DiagSection> {
        val out = ArrayList<DiagSection>()
        val infos = tracks()
        val v = infos.firstOrNull { it.type == IMedia.Track.Type.Video } as? IMedia.VideoTrack

        val video = ArrayList<DiagRow>()
        video.add(DiagRow("播放内核", "VLC（libVLC）"))
        video.add(DiagRow("解码方式", if (softwareDecode) "软解（avcodec）" else "硬解优先（MediaCodec），失败自动软解"))
        video.add(DiagRow("缓存时间", String.format(Locale.US, "%.1f 秒", cacheMillis / 1000.0)))
        if (v != null) {
            video.add(DiagRow("视频编码", friendly(v.codec)))
            if (v.width > 0 && v.height > 0) video.add(DiagRow("视频分辨率", "${v.width}×${v.height}"))
            if (v.frameRateDen > 0 && v.frameRateNum > 0)
                video.add(DiagRow("视频帧率", String.format(Locale.US, "%.2f fps", v.frameRateNum.toDouble() / v.frameRateDen)))
            if (v.bitrate > 0) video.add(DiagRow("视频码率", String.format(Locale.US, "%.2f Mbps", v.bitrate / 1_000_000.0)))
        }
        out.add(DiagSection("视频", video))

        val a = infos.firstOrNull { it.type == IMedia.Track.Type.Audio } as? IMedia.AudioTrack
        if (a != null) {
            val audio = arrayListOf(DiagRow("音频编码", friendly(a.codec)))
            if (a.channels > 0) audio.add(DiagRow("声道", "${a.channels}"))
            if (a.rate > 0) audio.add(DiagRow("采样率", "${a.rate} Hz"))
            if (a.bitrate > 0) audio.add(DiagRow("音频码率", "${a.bitrate / 1000} kbps"))
            audio.add(DiagRow("音轨数", "${audioEntries.size}"))
            out.add(DiagSection("音频", audio))
        } else {
            out.add(DiagSection("音频", listOf(DiagRow("音频", if (audioEntries.isEmpty()) "无" else "${audioEntries.size} 条音轨"))))
        }

        val sub = arrayListOf(DiagRow("字幕轨数", "${subtitleEntries.size}"))
        val on = lastTracks.subtitles.firstOrNull { it.isOn }
        sub.add(DiagRow("当前字幕", on?.name ?: "关闭"))
        out.add(DiagSection("字幕", sub))
        return out
    }

    override fun statusRows(): List<DiagRow> {
        val rows = arrayListOf(DiagRow("状态", if (isPlaying) "播放中" else "未在播放"))
        try {
            val st = player?.media?.stats
            if (st != null) {
                val shown = st.displayedPictures
                val now = System.currentTimeMillis()
                if (fpsTime != 0L && now - fpsTime >= 1000) {
                    measuredFps = (shown - fpsShown) * 1000.0 / (now - fpsTime)
                    fpsTime = now; fpsShown = shown
                } else if (fpsTime == 0L) {
                    fpsTime = now; fpsShown = shown
                }
                if (measuredFps > 0) rows.add(DiagRow("显示帧率", String.format(Locale.US, "%.1f fps", measuredFps)))
                rows.add(DiagRow("已显示帧数", "$shown"))
                rows.add(DiagRow("丢帧", "${st.lostPictures}"))
            }
        } catch (e: Throwable) { }
        return rows
    }

    private fun friendly(raw: String?): String = when ((raw ?: "").lowercase()) {
        "h264", "avc1" -> "H.264 / AVC"
        "hevc", "hvc1", "hev1" -> "H.265 / HEVC"
        "mpgv", "mp2v", "mpeg2video" -> "MPEG-2"
        "mp4v" -> "MPEG-4"
        "mp4a" -> "AAC"
        "mpga" -> "MPEG 音频（MP2/MP3）"
        "mp3" -> "MP3"
        "a52", "ac-3", "ac3" -> "AC-3（杜比数字）"
        "eac3", "ec-3" -> "E-AC-3（杜比数字+）"
        "dts" -> "DTS"
        "bdpg", "pgs" -> "PGS（蓝光位图字幕）"
        "dvbs" -> "DVB 字幕"
        "subt", "srt" -> "SRT"
        "wvtt" -> "WebVTT"
        "" -> "未知"
        else -> raw!!.uppercase()
    }

    companion object {
        private const val TAG = "VlcEngine"
    }
}
