package com.cncat.maotv.player

import android.content.Context
import android.content.Intent
import android.net.TrafficStats
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.cncat.maotv.AppModel
import com.cncat.maotv.core.Channel
import com.cncat.maotv.core.DiagRow
import com.cncat.maotv.core.DiagSection
import com.cncat.maotv.core.EngineKind
import com.cncat.maotv.core.SettingsValues
import com.cncat.maotv.core.TrackItem
import com.cncat.maotv.net.Http
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

/** 一次播放失败的性质 */
private enum class FailureKind { NETWORK, TIMEOUT, DECODE, ENDED }

/**
 * 播放总控：换台、超时检测、自动重试、音量、后台播放、诊断。
 * 具体的解码和画面由两个内核实现（VlcEngine / Media3Engine），通过 PlaybackEngine 接口使用。
 */
class PlayerController(private val model: AppModel) {
    private val appContext: Context = model.appContext
    private val scope = model.scope
    private val store = model.settings

    // ───────────── 对外状态 ─────────────

    var engineKind by mutableStateOf(store.values.engine)
        private set
    /** 正在播放（或即将播放）的频道，界面据此高亮 */
    var session by mutableStateOf<Channel?>(null)
        private set
    var isBuffering by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set
    var retryText by mutableStateOf<String?>(null)
        private set
    var audioItems by mutableStateOf<List<TrackItem>>(emptyList())
        private set
    var subtitleItems by mutableStateOf<List<TrackItem>>(emptyList())
        private set
    var volume by mutableFloatStateOf(store.volume.coerceIn(0f, 1f))
        private set
    /** 当前频道的画面是否已经出来（OSD 据此在出画后 3 秒消失；加载界面据此显示 Logo） */
    var hasPicture by mutableStateOf(false)
        private set
    /** 当前下载速度（加载界面显示） */
    var speedText by mutableStateOf("0KB/s")
        private set
    var videoWidth by mutableIntStateOf(0)
        private set
    var videoHeight by mutableIntStateOf(0)
        private set
    var videoFps by mutableFloatStateOf(0f)
        private set
    /** 4:3 的老频道：拉伸成 16:9 显示 */
    var stretchToFill by mutableStateOf(false)
        private set
    var isPaused by mutableStateOf(false)
        private set

    /** 上下换台使用的频道列表（当前所在分类） */
    var playlist: List<Channel> = emptyList()

    private var target: Channel? = null
    val current: Channel? get() = target ?: session

    /** 服务（通知栏）监听状态变化 */
    var onStateChanged: (() -> Unit)? = null

    // ───────────── 内核 ─────────────

    private val engines = HashMap<EngineKind, PlaybackEngine>()

    fun engineInstance(kind: EngineKind): PlaybackEngine {
        engines[kind]?.let { return it }
        val e: PlaybackEngine = if (kind == EngineKind.VLC) VlcEngine(appContext) else Media3Engine(appContext)
        e.onEvent = { ev -> if (engineKind == kind) handle(ev) }   // 不是当前内核的回调，忽略
        engines[kind] = e
        return e
    }

    val engine: PlaybackEngine get() = engineInstance(engineKind)

    // ───────────── 内部状态 ─────────────

    private var userPickedSubtitle = false
    private var generation = 0
    var retryCount = 0
        private set
    private var attemptStartedAt = 0L
    private var loadingSince = 0L          // 0 = 没有在等待
    private var playingSince = 0L
    private var lastMillis = -1L
    private var lastProgressAt = 0L
    private var healthySince = 0L

    /** 本次播放（这个频道这一轮）是否临时改用软解。换台就清除，绝不永久标记频道 */
    private var sessionSoftware = false

    private var watchdog: Job? = null
    private var retryJob: Job? = null
    private var speedJob: Job? = null
    private var backgroundJob: Job? = null
    private var inBackground = false
    private var pausedAt = 0L

    // 轨道错误（“带伤继续播”）统计
    private val softErrors = ArrayDeque<Long>()
    private var repulledAt = 0L
    private var lastUnstableToast = 0L

    init {
        startSpeedMonitor()
    }

    // ───────────── 播放控制 ─────────────

    fun play(ch: Channel, list: List<Channel>? = null) {
        if (list != null) playlist = list
        cancelTasks()
        generation++
        retryCount = 0
        failed = false
        retryText = null
        isBuffering = true
        isPaused = false
        pausedAt = 0
        sessionSoftware = false
        softErrors.clear()
        repulledAt = 0
        target = ch
        resetAttemptClock()
        resetTrackState()
        store.lastChannelUrl = ch.url
        session = ch
        startAttempt(isRetry = false)
        startWatchdog()
        ensureService()
        notifyState()
    }

    /** delta = +1 下一个台，-1 上一个台 */
    fun step(delta: Int) {
        val cur = current ?: return
        if (playlist.isEmpty()) return
        val idx = playlist.indexOfFirst { it.id == cur.id }.let { if (it < 0) 0 else it }
        val n = ((idx + delta) % playlist.size + playlist.size) % playlist.size
        play(playlist[n])
    }

    /** 手动重试（播放失败页面的按钮）：重新开始计数 */
    fun reload() {
        if (current == null) return
        isPaused = false
        pausedAt = 0
        cancelTasks()
        generation++
        retryCount = 0
        failed = false
        retryText = null
        sessionSoftware = false
        restartPlayback(isRetry = false)
        startWatchdog()
        notifyState()
    }

    /**
     * 暂停 / 恢复。直播流没有“继续”的概念：暂停久了缓冲区早就空了（或者服务器已经断开），
     * 所以暂停超过缓存时长再恢复时，直接重新拉流；暂停很短的话原地继续。
     */
    fun togglePause() {
        if (current == null || session == null || failed) return
        if (!isPaused) {
            isPaused = true
            pausedAt = SystemClock.elapsedRealtime()
            isBuffering = false
            retryText = null
            retryJob?.cancel(); retryJob = null
            engine.pause()
            notifyState()
            return
        }
        val pausedMs = SystemClock.elapsedRealtime() - pausedAt
        isPaused = false
        pausedAt = 0
        if (pausedMs > maxOf(3000.0, store.values.cacheSeconds * 1000) || !hasPicture) {
            reload()
        } else {
            lastProgressAt = SystemClock.elapsedRealtime()
            lastMillis = -1
            engine.resume()
            notifyState()
        }
    }

    /** 设置面板“保存”时调用：保存设置；有改动就重新打开当前频道 */
    fun applySettings(new: SettingsValues) {
        val old = store.values
        val fixed = new.copy(cacheSeconds = new.cacheSeconds.coerceIn(SettingsValues.MIN_CACHE, SettingsValues.MAX_CACHE))
        if (fixed == old) return
        store.apply(fixed)
        model.onIgnoreSslChanged()

        val engineChanged = fixed.engine != old.engine
        // 只有“换台反转 / 帧率匹配 / 忽略SSL”变了，不需要重新拉流
        val playbackSame = fixed.copy(reverseChannel = old.reverseChannel, frameRateMatch = old.frameRateMatch,
            ignoreSsl = old.ignoreSsl) == old
        if (engineChanged) {
            val oldKind = old.engine
            engines.remove(oldKind)?.let { e ->
                e.onEvent = null
                if (current != null) e.stop()
                e.release()
            }
            engineKind = fixed.engine
        }
        if (playbackSame || current == null) return
        reload()
    }

    /** 用 M3U 里的原始地址打开当前频道（先停掉旧的） */
    private fun startAttempt(isRetry: Boolean) {
        val ch = current ?: return
        attemptStartedAt = SystemClock.elapsedRealtime()
        val s = store.values
        val e = engine
        val soft = sessionSoftware || (engineKind == EngineKind.VLC && s.vlcSoftware)
        e.start(ch.url, EngineConfig(settings = s, softwareDecode = soft, isRetry = isRetry))
        e.setVolume(volume)
    }

    private fun restartPlayback(isRetry: Boolean) {
        if (current == null) return
        resetAttemptClock()
        resetTrackState()
        isBuffering = true
        startAttempt(isRetry)
    }

    private fun resetAttemptClock() {
        hasPicture = false
        val now = SystemClock.elapsedRealtime()
        loadingSince = now
        playingSince = 0
        lastMillis = -1
        lastProgressAt = now
        healthySince = 0
    }

    private fun resetTrackState() {
        userPickedSubtitle = false
        audioItems = emptyList()
        subtitleItems = emptyList()
        videoWidth = 0; videoHeight = 0; videoFps = 0f
        stretchToFill = false
        engine.resetTracks()
    }

    private fun cancelTasks() {
        watchdog?.cancel(); watchdog = null
        retryJob?.cancel(); retryJob = null
    }

    /** 退出应用时调用：停止播放并释放 */
    fun shutdown() {
        cancelTasks()
        generation++
        session = null
        target = null
        isBuffering = false
        for (e in engines.values) { e.onEvent = null; try { e.stop() } catch (_: Throwable) { } }
        notifyState()
        appContext.stopService(Intent(appContext, PlaybackService::class.java))
    }

    // ───────────── 音量（应用内音量，0…1） ─────────────

    fun changeVolume(v: Float) {
        val c = v.coerceIn(0f, 1f)
        volume = c
        engine.setVolume(c)
        store.volume = c
    }

    // ───────────── 内核回调 ─────────────

    private fun handle(ev: EngineEvent) {
        if (session == null || failed || isPaused) return
        when (ev) {
            is EngineEvent.Loading -> if (!hasPicture) isBuffering = true
            is EngineEvent.Playing, is EngineEvent.TracksChanged -> {
                engine.setVolume(volume)
                refreshTracks(applyDefaults = true)
                updateVideoGeometry()
            }
            is EngineEvent.Error -> handleFailure(if (ev.decode) FailureKind.DECODE else FailureKind.NETWORK)
            is EngineEvent.TrackError -> onTrackError(ev.decode)
            is EngineEvent.Ended -> {
                // 自己停掉旧频道时也会收到结束；那时还没有画面，或者刚刚才开始，直接忽略
                if (hasPicture && SystemClock.elapsedRealtime() - attemptStartedAt > 1000) handleFailure(FailureKind.ENDED)
            }
        }
    }

    /**
     * 单个轨道 / 解码器出错：内核已经原地恢复，不停播。
     * 10 秒内累计 3 次 → 第一步重新拉流；重新拉流后 60 秒内再次累计 3 次 → 提示“网络或直播源不稳定”（3 秒，最多每 30 秒一次），
     * 并且如果还在用硬解，本次改用软解。始终不停播。
     */
    private fun onTrackError(decode: Boolean) {
        val now = SystemClock.elapsedRealtime()
        softErrors.addLast(now)
        while (softErrors.isNotEmpty() && now - softErrors.first() > 10_000) softErrors.removeFirst()
        if (softErrors.size < 3) return
        softErrors.clear()

        val afterRepull = repulledAt != 0L && now - repulledAt < 60_000
        if (!afterRepull) {
            repulledAt = now
            restartPlayback(isRetry = true)
            return
        }
        if (now - lastUnstableToast > 30_000) {
            lastUnstableToast = now
            model.showToast("网络或直播源不稳定", 3000)
        }
        if (decode && !sessionSoftware && engineKind == EngineKind.MEDIA3) {
            sessionSoftware = true          // 只对本次有效：换台后恢复
            repulledAt = now
            restartPlayback(isRetry = true)
        }
    }

    // ───────────── 进展检测、超时与重试 ─────────────

    private fun timeoutMs(): Long = (maxOf(20.0, store.values.cacheSeconds + 15) * 1000).toLong()

    private fun startWatchdog() {
        watchdog?.cancel()
        val g = generation
        watchdog = scope.launch {
            while (isActive) {
                delay(500)
                if (g != generation) return@launch
                tick()
            }
        }
    }

    private fun tick() {
        if (session == null || failed || isPaused || retryJob != null) return
        val now = SystemClock.elapsedRealtime()
        val e = engine

        val ms = e.positionMillis
        if (ms != lastMillis) {
            lastMillis = ms
            lastProgressAt = now
        }
        val progressing = ms > 0 && now - lastProgressAt < 1500 && e.isPlaying

        if (progressing) {
            if (healthySince == 0L) healthySince = now
            // 有视频输出就算出画；纯音频频道则连续走时 2 秒后算就绪
            if (e.hasVideoOut || now - healthySince > 2000) {
                if (!hasPicture) {
                    hasPicture = true
                    refreshTracks(applyDefaults = true)
                    updateVideoGeometry()
                    notifyState()
                }
                if (isBuffering) isBuffering = false
                loadingSince = 0
                retryText = null
                if (playingSince == 0L) playingSince = now
            }
        } else {
            healthySince = 0
            if (!isBuffering) isBuffering = true
            if (loadingSince == 0L) loadingSince = if (hasPicture) lastProgressAt else now
        }

        if (loadingSince != 0L && now - loadingSince >= timeoutMs()) {
            handleFailure(FailureKind.TIMEOUT)
            return
        }

        if (playingSince != 0L && retryCount > 0 && now - playingSince >= STABLE_MS) {
            retryCount = 0
            retryText = null
        }
    }

    private fun handleFailure(kind: FailureKind) {
        if (retryJob != null || failed || session == null) return
        loadingSince = 0
        playingSince = 0

        retryCount++
        if (retryCount > MAX_RETRIES) {
            failed = true
            isBuffering = false
            retryText = null
            engine.stop()
            notifyState()
            return
        }

        // 解码失败：本次改用软解重试（只对本次有效，换台后恢复硬解优先）
        if (kind == FailureKind.DECODE) sessionSoftware = true

        isBuffering = true
        hasPicture = false
        val notice = if (kind == FailureKind.DECODE) "硬解失败，改用软解重试" else "连接异常，正在重试"
        retryText = "$notice ($retryCount/$MAX_RETRIES)"

        val g = generation
        val delayMs = minOf(retryCount, 3) * 1000L   // 1s、2s、3s、3s…
        retryJob = scope.launch {
            delay(delayMs)
            if (g != generation) return@launch
            retryJob = null
            restartPlayback(isRetry = true)
        }
    }

    // ───────────── 后台播放 ─────────────

    fun onEnterBackground() {
        inBackground = true
        backgroundJob?.cancel()
        backgroundJob = scope.launch {
            while (isActive) {
                delay(1000)
                if (!inBackground) return@launch
                keepAliveInBackground()
            }
        }
    }

    fun onEnterForeground() {
        inBackground = false
        backgroundJob?.cancel(); backgroundJob = null
        updateVideoGeometry()
    }

    /** 在后台时每秒检查一次：被系统暂停的话让声音继续播放 */
    private fun keepAliveInBackground() {
        if (session == null || failed || isPaused || retryJob != null || !hasPicture) return
        engine.resumeIfStopped()
    }

    private fun ensureService() {
        try {
            ContextCompat.startForegroundService(appContext, Intent(appContext, PlaybackService::class.java))
        } catch (e: Throwable) {
            // 后台启动前台服务被系统拒绝时忽略：播放本身不受影响
        }
    }

    private fun notifyState() {
        onStateChanged?.invoke()
    }

    // ───────────── 画面比例 ─────────────

    /** 记录视频尺寸；4:3 的老频道拉伸成 16:9，其余按原比例显示 */
    private fun updateVideoGeometry() {
        val e = engine
        val w = e.videoWidth
        val h = e.videoHeight
        if (w <= 0 || h <= 0) return
        if (w != videoWidth) videoWidth = w
        if (h != videoHeight) videoHeight = h
        val fps = e.videoFrameRate
        if (fps != videoFps) videoFps = fps
        val ratio = w.toDouble() / h
        val stretch = ratio > 1.2 && ratio < 1.5
        if (stretch != stretchToFill) stretchToFill = stretch
        e.setStretch(stretch)
    }

    // ───────────── 下载速度 ─────────────

    private fun startSpeedMonitor() {
        speedJob = scope.launch {
            var last = TrafficStats.getTotalRxBytes()
            var lastTime = SystemClock.elapsedRealtime()
            while (isActive) {
                delay(1000)
                val now = TrafficStats.getTotalRxBytes()
                val t = SystemClock.elapsedRealtime()
                val dt = (t - lastTime) / 1000.0
                if (now >= 0 && last >= 0 && now >= last && dt > 0 && isBuffering) {
                    val text = formatSpeed((now - last) / dt)
                    if (text != speedText) speedText = text
                }
                last = now
                lastTime = t
            }
        }
    }

    private fun formatSpeed(bytesPerSecond: Double): String =
        if (bytesPerSecond >= 1024 * 1024) String.format(Locale.US, "%.1fMB/s", bytesPerSecond / 1024 / 1024)
        else "${(bytesPerSecond / 1024).toInt()}KB/s"

    // ───────────── 音轨 / 字幕轨 ─────────────

    fun refreshTracks(applyDefaults: Boolean = false) {
        val t = engine.refreshTracks(applyDefaults, userPickedSubtitle)
        if (t.audio != audioItems) audioItems = t.audio
        if (t.subtitles != subtitleItems) subtitleItems = t.subtitles
    }

    fun selectAudio(index: Int) {
        engine.selectAudio(index)
        scope.launch {
            delay(300)
            refreshTracks()
        }
    }

    /** index 为 null 表示关闭字幕 */
    fun selectSubtitle(index: Int?) {
        userPickedSubtitle = true
        engine.selectSubtitle(index)
        scope.launch {
            delay(150)
            refreshTracks()
        }
    }

    // ───────────── 诊断 ─────────────

    fun diagnostics(screenRows: List<DiagRow>): List<DiagSection> {
        val out = ArrayList<DiagSection>()
        out.add(DiagSection("屏幕", screenRows))
        val sections = engine.diagnosticSections().toMutableList()
        val vi = sections.indexOfFirst { it.title == "视频" }
        if (vi >= 0) {
            val rows = sections[vi].rows.toMutableList()
            rows.add(DiagRow("画面比例", if (stretchToFill) "4:3，已拉伸为 16:9" else "原比例（16:9 区域内适配）"))
            sections[vi] = DiagSection("视频", rows)
        }
        out.addAll(sections)
        val play = arrayListOf(
            DiagRow("重试次数", "$retryCount/$MAX_RETRIES"),
            DiagRow("本次软解回退", if (sessionSoftware) "是（仅本次）" else "否"),
            DiagRow("忽略SSL证书", if (Http.ignoreSsl) "开" else "关"),
        )
        play.addAll(engine.statusRows())
        out.add(DiagSection("播放状态", play))
        return out
    }

    companion object {
        /** 单个频道最多连续重试次数 */
        const val MAX_RETRIES = 10
        /** 连续稳定播放这么久之后，重试计数清零 */
        const val STABLE_MS = 15_000L
    }
}
