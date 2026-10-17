package com.cncat.maotv.ui

import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cncat.maotv.MaoApp
import com.cncat.maotv.Phase
import com.cncat.maotv.core.Category
import com.cncat.maotv.core.Channel
import com.cncat.maotv.core.SettingsValues
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 左侧选单里遥控器光标所在的区域 */
enum class Zone { CATEGORY, CHANNEL, STAR, HANDLE, EPG, BUTTON }

enum class PopupKind { AUDIO, SUBTITLE }

enum class PortraitPage { CATEGORIES, CHANNELS }

/**
 * 播放界面的全部界面状态和操作逻辑：选单、OSD、数字选台、设置面板、遥控器光标。
 * 触摸操作由 Compose 手势直接调用这里的函数；遥控器按键由 MainActivity 转交 [onKey]（不依赖 Compose 焦点）。
 */
class UiState(private val app: MaoApp, private val onExit: () -> Unit) {
    val model = app.model
    val player get() = app.player
    private val scope = model.scope
    private val store = model.settings

    // ───────────── 公开状态 ─────────────

    /** true = 竖屏版式（由界面布局回填） */
    var portrait by mutableStateOf(false)
    var inPip by mutableStateOf(false)
    /** 竖屏搜索框获得焦点时为 true：此时按键（含数字键）交给输入框，不当作遥控器操作 */
    /** 暂停图标正在显示（含淡入 / 淡出动画期间） */
    var pauseIconShown by mutableStateOf(false)
    var textInputActive by mutableStateOf(false)

    var menuVisible by mutableStateOf(false)
        private set
    var epgOpen by mutableStateOf(false)
    var selectedCategoryId by mutableStateOf(Category.ALL_ID)
        private set
    var menuShowCount by mutableIntStateOf(0)
        private set

    var osdVisible by mutableStateOf(false)
        private set
    var volumeOsd by mutableStateOf<Float?>(null)
        private set
    var exitHint by mutableStateOf(false)
        private set

    /** 数字选台：已输入的数字 */
    var digits by mutableStateOf("")
        private set

    var diagVisible by mutableStateOf(false)
    var settingsVisible by mutableStateOf(false)
        private set
    var settingsDraft by mutableStateOf(SettingsValues())
    var settingsFocus by mutableIntStateOf(1)
    var settingsRev by mutableIntStateOf(0)
        private set
    var sslConfirm by mutableStateOf(false)
    var sslFocus by mutableIntStateOf(0)

    var portraitPage by mutableStateOf(PortraitPage.CATEGORIES)
    var portraitEPG by mutableStateOf(false)
    var portraitShowKey by mutableIntStateOf(0)
        private set

    // 遥控器光标
    var remoteActive by mutableStateOf(false)
    var zone by mutableStateOf(Zone.CHANNEL)
        private set
    var chFocus by mutableIntStateOf(0)
        private set
    var btnFocus by mutableIntStateOf(1)
        private set
    var popup by mutableStateOf<PopupKind?>(null)
        private set
    var popupFocus by mutableIntStateOf(0)
        private set

    val epgScrolls = MutableSharedFlow<Float>(extraBufferCapacity = 16)
    val diagScrolls = MutableSharedFlow<Float>(extraBufferCapacity = 16)

    // ───────────── 内部 ─────────────

    private var transitionJob: Job? = null
    private var osdJob: Job? = null
    private var volumeJob: Job? = null
    private var digitsJob: Job? = null
    private var exitJob: Job? = null
    private var hideWatcher: Job? = null
    private var hideDeadline = 0L
    private var osdClosing = false
    private var menuClosing = false

    private var okDown = false
    private var okLong = false

    val categories get() = model.categories
    fun channelsInSelected(): List<Channel> = model.channels(selectedCategoryId)

    // ───────────── 启动 ─────────────

    fun startPlayback() {
        if (player.session != null) return
        val all = model.channels
        val last = store.lastChannelUrl
        val found = all.firstOrNull { it.url == last }
        val ch = found ?: all.firstOrNull() ?: return
        val saved = store.lastCategoryId
        var cat = ch.group
        var list = model.channels(cat)
        if (found != null && saved != null && model.categories.any { it.id == saved }) {
            val savedList = model.channels(saved)
            if (savedList.any { it.id == ch.id }) { cat = saved; list = savedList }
        }
        selectedCategoryId = cat
        portraitPage = PortraitPage.CHANNELS
        portraitShowKey++
        player.play(ch, list)
        showOSD()
    }

    fun selectCategory(id: String) {
        if (id == selectedCategoryId) return
        selectedCategoryId = id
        store.lastCategoryId = id
        model.syncFavoriteShown()   // 取消收藏后留着的频道，切换分类时才真正消失
    }

    // ───────────── 换台 ─────────────

    /** 在左侧选单里选了频道：立刻收起选单并换台；OSD 等选单收完再显示 */
    fun selectChannel(ch: Channel) {
        val list = channelsInSelected()
        player.playlist = list
        hideMenu()
        if (ch.id == player.current?.id) return
        player.play(ch, list)
        showOSD()
    }

    /** 竖屏里点了某个频道：直接换台（列表一直在屏幕上） */
    fun selectChannelPortrait(ch: Channel, list: List<Channel>) {
        player.playlist = list
        if (ch.id != player.current?.id) player.play(ch, list)
        showOSD()
    }

    fun step(delta: Int) {
        player.step(delta)
        showOSD()
    }

    /** 触摸上下滑 / 遥控器上下键：“换台反转”打开时方向相反 */
    fun stepByDirection(towardsNext: Boolean) {
        val rev = store.values.reverseChannel
        step(if (towardsNext != rev) 1 else -1)
    }

    // ───────────── 选单 ─────────────

    fun toggleMenuByTap() {
        remoteActive = false
        if (menuVisible) hideMenu() else showMenu()
    }

    /** 呼出选单。如果 OSD 正在显示（或正在收起），先让 OSD 收起，再展开选单。 */
    fun showMenu(focusButtons: Boolean = false) {
        if (portrait) return
        transitionJob?.cancel()
        val mustWait = osdVisible || osdClosing
        if (osdVisible) hideOSD()
        transitionJob = scope.launch {
            if (mustWait) {
                delay(SLIDE_WAIT)
            }
            presentMenu(focusButtons)
        }
    }

    private fun presentMenu(focusButtons: Boolean) {
        menuShowCount++
        menuClosing = false
        menuVisible = true
        popup = null
        val list = channelsInSelected()
        chFocus = list.indexOfFirst { it.id == player.current?.id }.coerceAtLeast(0)
        if (focusButtons) { zone = Zone.BUTTON; btnFocus = 1 } else zone = Zone.CHANNEL
        player.refreshTracks()
        bumpHideTimer()
    }

    fun hideMenu() {
        hideDeadline = Long.MAX_VALUE
        hideWatcher?.cancel(); hideWatcher = null
        popup = null
        if (!menuVisible) return
        menuVisible = false
        menuClosing = true
        scope.launch {
            delay(SLIDE_WAIT)
            if (menuVisible) return@launch
            menuClosing = false
            epgOpen = false      // 收完以后再把节目单复位（此时已在屏幕外）
        }
    }

    /** 选单里有任何操作（触摸 / 按键 / 滚动）就把“8 秒后收起”的截止时间往后推 */
    fun bumpHideTimer() {
        if (!menuVisible) return
        hideDeadline = SystemClock.elapsedRealtime() + MENU_AUTO_HIDE_MS
        if (hideWatcher != null) return
        hideWatcher = scope.launch {
            while (isActive) {
                delay(300)
                if (!menuVisible) break
                if (SystemClock.elapsedRealtime() >= hideDeadline) {
                    hideWatcher = null
                    hideMenu()
                    return@launch
                }
            }
            hideWatcher = null
        }
    }

    fun onTouchActivity() {
        remoteActive = false
        bumpHideTimer()
    }

    // ───────────── OSD ─────────────

    /** 需要显示 OSD。如果选单正在显示（或正在收起），先收起选单，再显示 OSD。 */
    fun showOSD() {
        transitionJob?.cancel()
        val mustWait = menuVisible || menuClosing
        if (menuVisible) hideMenu()
        transitionJob = scope.launch {
            if (mustWait) delay(SLIDE_WAIT)
            presentOSD()
        }
    }

    private fun presentOSD() {
        osdJob?.cancel()
        osdClosing = false
        osdVisible = true
        osdJob = scope.launch {
            // 画面还没出来就一直显示；画面出来（或播放失败）后再显示 3 秒。最多等 2 分钟兜底。
            var waited = 0
            while (!(player.hasPicture || player.failed) && waited < 600) {
                delay(200)
                waited++
            }
            delay(OSD_SECONDS * 1000)
            hideOSD()
        }
    }

    private fun hideOSD() {
        osdJob?.cancel()
        if (!osdVisible) return
        osdClosing = true
        osdVisible = false
        scope.launch {
            delay(SLIDE_WAIT)
            osdClosing = false
        }
    }

    // ───────────── 音量 ─────────────

    fun setVolumeAndFlash(v: Float) {
        player.changeVolume(v)
        flashVolume()
    }

    fun adjustVolume(delta: Float) = setVolumeAndFlash(player.volume + delta)

    private fun flashVolume() {
        volumeJob?.cancel()
        volumeOsd = player.volume
        volumeJob = scope.launch {
            delay(1600)
            volumeOsd = null
        }
    }

    // ───────────── 数字选台 ─────────────

    private fun maxDigits(): Int = maxOf(3, model.channels.size.toString().length)

    private fun enterDigit(d: Int) {
        if (digits.isEmpty()) {
            // 开始输入：先把左侧选单收回去
            if (menuVisible) hideMenu()
            popup = null
        }
        if (digits.length >= maxDigits()) { restartDigitsTimer(); return }
        digits += d.toString()
        restartDigitsTimer()
    }

    private fun restartDigitsTimer() {
        digitsJob?.cancel()
        digitsJob = scope.launch {
            delay(DIGITS_WAIT_MS)
            commitDigits()
        }
    }

    /** 3 秒没有新按键，或按了确定键：按台号换台（前导 0 忽略：012 → 12） */
    private fun commitDigits() {
        digitsJob?.cancel(); digitsJob = null
        val s = digits
        digits = ""
        if (s.isEmpty()) return
        val n = s.toIntOrNull() ?: 0
        val ch = model.channelByNumber(n)
        if (ch == null) {
            model.showToast("您输入的频道不存在", 3000)
            return
        }
        playByNumber(ch)
    }

    fun cancelDigits() {
        digitsJob?.cancel(); digitsJob = null
        digits = ""
    }

    private fun playByNumber(ch: Channel) {
        // 频道在当前分类里就沿用当前分类；否则改到“全部频道”，这样上下台、再次呼出选单都对得上
        var list = channelsInSelected()
        if (list.none { it.id == ch.id }) {
            selectCategory(Category.ALL_ID)
            list = channelsInSelected()
        }
        player.playlist = list
        if (ch.id != player.current?.id) player.play(ch, list)
        showOSD()
    }

    // ───────────── 设置 / 诊断 ─────────────

    fun openSettings() {
        hideMenu()
        popup = null
        settingsDraft = store.values
        settingsFocus = 1
        settingsVisible = true
    }

    fun closeSettings(save: Boolean) {
        sslConfirm = false
        settingsVisible = false
        if (save) {
            player.applySettings(settingsDraft)
            settingsRev++
        }
    }

    fun openDiagnostics() {
        hideMenu()
        popup = null
        diagVisible = true
    }

    // ───────────── 音轨 / 字幕弹出菜单 ─────────────

    fun openPopup(kind: PopupKind) {
        popup = kind
        popupFocus = popupOnIndex(kind)
        bumpHideTimer()
    }

    fun closePopup() { popup = null }

    fun popupCount(kind: PopupKind): Int = when (kind) {
        PopupKind.AUDIO -> maxOf(1, player.audioItems.size)
        PopupKind.SUBTITLE -> player.subtitleItems.size + 1
    }

    private fun popupOnIndex(kind: PopupKind): Int = when (kind) {
        PopupKind.AUDIO -> player.audioItems.indexOfFirst { it.isOn }.coerceAtLeast(0)
        PopupKind.SUBTITLE -> {
            val i = player.subtitleItems.indexOfFirst { it.isOn }
            if (i < 0) 0 else i + 1
        }
    }

    fun choosePopup(kind: PopupKind, index: Int) {
        when (kind) {
            PopupKind.AUDIO -> if (player.audioItems.isNotEmpty()) player.selectAudio(index)
            PopupKind.SUBTITLE -> player.selectSubtitle(if (index == 0) null else index - 1)
        }
        popup = null
        bumpHideTimer()
    }

    // ───────────── 返回键 ─────────────

    fun onBack() {
        remoteActive = true
        if (digits.isNotEmpty()) { cancelDigits(); return }      // 输入过程中返回：取消换台
        if (sslConfirm) { sslConfirm = false; return }
        if (settingsVisible) { closeSettings(false); return }     // 不保存
        if (diagVisible) { diagVisible = false; return }
        if (popup != null) { popup = null; return }
        if (portrait) {
            if (portraitEPG) { portraitEPG = false; return }
            if (portraitPage == PortraitPage.CHANNELS) { portraitPage = PortraitPage.CATEGORIES; return }
        } else if (menuVisible) {
            hideMenu(); return
        }
        if (exitHint) {
            cancelExitHint()
            onExit()
        } else {
            exitHint = true
            exitJob?.cancel()
            exitJob = scope.launch {
                delay(3000)
                exitHint = false
            }
        }
    }

    private fun cancelExitHint() {
        exitJob?.cancel(); exitJob = null
        exitHint = false
    }

    // ───────────── 遥控器 ─────────────

    private fun isOk(code: Int) = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER ||
        code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_BUTTON_A

    private fun digitOf(code: Int): Int = when (code) {
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> code - KeyEvent.KEYCODE_0
        in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> code - KeyEvent.KEYCODE_NUMPAD_0
        else -> -1
    }

    private fun isHandled(code: Int): Boolean = isOk(code) || digitOf(code) >= 0 || when (code) {
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN,
        KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_INFO -> true
        else -> false
    }

    /** 返回 true = 已处理（Activity 不再往下传）。返回键不走这里（由 OnBackPressedCallback 统一处理）。 */
    fun onKey(e: KeyEvent): Boolean {
        if (model.phase != Phase.READY) return false
        if (inPip || textInputActive) return false
        val code = e.keyCode
        if (!isHandled(code)) return false

        if (e.action == KeyEvent.ACTION_UP) {
            if (isOk(code)) {
                if (okDown && !okLong) onOk()
                okDown = false; okLong = false
            }
            return true
        }
        if (e.action != KeyEvent.ACTION_DOWN) return false

        if (exitHint) cancelExitHint()

        if (isOk(code)) {
            if (e.repeatCount == 0) {
                okDown = true; okLong = false
                remoteActive = true
            } else if (okDown && !okLong && e.repeatCount == 1) {
                okLong = true
                onLongOk()
            }
            return true
        }
        val d = digitOf(code)
        if (d >= 0) {
            remoteActive = true
            if (e.repeatCount == 0 && !settingsVisible && !diagVisible && !sslConfirm) enterDigit(d)
            return true
        }
        remoteActive = true
        onDirectional(code, e.repeatCount)
        return true
    }

    private fun onDirectional(code: Int, repeat: Int) {
        // 正在输入台号：方向键 / 其他键不动作（返回取消、确定立即换台）
        if (digits.isNotEmpty()) {
            if (code == KeyEvent.KEYCODE_MENU) cancelDigits()
            return
        }
        when {
            sslConfirm -> sslKeys(code)
            settingsVisible -> settingsKeys(code)
            diagVisible -> diagKeys(code)
            popup != null -> popupKeys(code)
            portrait -> videoKeys(code, repeat)
            menuVisible -> menuKeys(code, repeat)
            else -> videoKeys(code, repeat)
        }
    }

    private fun onOk() {
        if (digits.isNotEmpty()) { commitDigits(); return }
        when {
            sslConfirm -> {
                if (sslFocus == 1) settingsDraft = settingsDraft.copy(ignoreSsl = true)
                sslConfirm = false
            }
            settingsVisible -> settingsOk()
            diagVisible -> diagVisible = false
            popup != null -> popup?.let { choosePopup(it, popupFocus) }
            portrait -> {}
            menuVisible -> menuOk()
            else -> showMenu()
        }
    }

    private fun onLongOk() {
        if (digits.isNotEmpty() || sslConfirm || settingsVisible || diagVisible || popup != null || portrait) return
        if (menuVisible) { zone = Zone.BUTTON; btnFocus = 1; bumpHideTimer() } else showMenu(focusButtons = true)
    }

    // —— 只有画面时 ——

    private fun videoKeys(code: Int, repeat: Int) {
        when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> if (repeat == 0) stepByDirection(towardsNext = false)
            KeyEvent.KEYCODE_DPAD_DOWN -> if (repeat == 0) stepByDirection(towardsNext = true)
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_DOWN -> if (repeat == 0) step(1)
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_UP -> if (repeat == 0) step(-1)
            KeyEvent.KEYCODE_DPAD_LEFT -> adjustVolume(-0.05f)
            KeyEvent.KEYCODE_DPAD_RIGHT -> adjustVolume(0.05f)
            KeyEvent.KEYCODE_MENU -> if (!portrait) showMenu(focusButtons = true)
            KeyEvent.KEYCODE_INFO -> showOSD()
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> if (repeat == 0) player.togglePause()
            KeyEvent.KEYCODE_MEDIA_PLAY -> if (player.isPaused) player.togglePause()
            KeyEvent.KEYCODE_MEDIA_PAUSE -> if (!player.isPaused) player.togglePause()
        }
    }

    // —— 左侧选单 ——

    private fun menuKeys(code: Int, repeat: Int) {
        bumpHideTimer()
        val cats = categories
        when (code) {
            KeyEvent.KEYCODE_MENU -> {
                if (zone == Zone.BUTTON) zone = Zone.CHANNEL else { zone = Zone.BUTTON; btnFocus = 1 }
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> if (repeat == 0) player.togglePause()
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN,
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                val dir = when (code) {
                    KeyEvent.KEYCODE_DPAD_UP -> -1
                    KeyEvent.KEYCODE_DPAD_DOWN -> 1
                    KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_CHANNEL_DOWN -> -8
                    else -> 8
                }
                when (zone) {
                    Zone.CATEGORY -> {
                        val i = cats.indexOfFirst { it.id == selectedCategoryId }.coerceAtLeast(0)
                        val n = (i + dir.coerceIn(-1, 1)).coerceIn(0, (cats.size - 1).coerceAtLeast(0))
                        if (n != i && cats.isNotEmpty()) {
                            selectCategory(cats[n].id)
                            val list = channelsInSelected()
                            chFocus = list.indexOfFirst { it.id == player.current?.id }.coerceAtLeast(0)
                        }
                    }
                    Zone.CHANNEL, Zone.STAR -> {
                        val size = channelsInSelected().size
                        if (size > 0) chFocus = (chFocus + dir).coerceIn(0, size - 1)
                    }
                    Zone.EPG -> epgScrolls.tryEmit(dir * 48f)
                    Zone.BUTTON -> if (dir < 0) zone = Zone.CHANNEL
                    Zone.HANDLE -> {}
                }
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> when (zone) {
                Zone.CATEGORY -> {}
                Zone.CHANNEL -> zone = Zone.CATEGORY
                Zone.STAR -> zone = Zone.CHANNEL
                Zone.HANDLE -> zone = Zone.STAR
                Zone.EPG -> zone = Zone.HANDLE
                Zone.BUTTON -> btnFocus = (btnFocus - 1).coerceAtLeast(0)
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> when (zone) {
                Zone.CATEGORY -> zone = Zone.CHANNEL
                Zone.CHANNEL -> zone = Zone.STAR
                Zone.STAR -> zone = Zone.HANDLE
                Zone.HANDLE -> { epgOpen = true; zone = Zone.EPG }
                Zone.EPG -> {}
                Zone.BUTTON -> btnFocus = (btnFocus + 1).coerceAtMost(BUTTON_COUNT - 1)
            }
        }
    }

    private fun menuOk() {
        bumpHideTimer()
        when (zone) {
            Zone.CATEGORY -> zone = Zone.CHANNEL
            Zone.CHANNEL -> channelsInSelected().getOrNull(chFocus)?.let { selectChannel(it) }
            Zone.STAR -> channelsInSelected().getOrNull(chFocus)?.let { model.toggleFavorite(it) }
            Zone.HANDLE -> epgOpen = !epgOpen
            Zone.EPG -> {}
            Zone.BUTTON -> pressButton(btnFocus)
        }
    }

    /** 底部按钮：0 暂停/播放 1 音轨 2 字幕 3 设置 4 诊断 */
    fun pressButton(index: Int) {
        bumpHideTimer()
        when (index) {
            0 -> player.togglePause()
            1 -> openPopup(PopupKind.AUDIO)
            2 -> openPopup(PopupKind.SUBTITLE)
            3 -> openSettings()
            4 -> openDiagnostics()
        }
    }

    // —— 音轨 / 字幕弹出菜单 ——

    private fun popupKeys(code: Int) {
        val kind = popup ?: return
        bumpHideTimer()
        val n = popupCount(kind)
        when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> popupFocus = (popupFocus - 1).coerceAtLeast(0)
            KeyEvent.KEYCODE_DPAD_DOWN -> popupFocus = (popupFocus + 1).coerceAtMost(n - 1)
            KeyEvent.KEYCODE_MENU -> popup = null
        }
    }

    // —— 诊断 ——

    private fun diagKeys(code: Int) {
        when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> diagScrolls.tryEmit(-120f)
            KeyEvent.KEYCODE_DPAD_DOWN -> diagScrolls.tryEmit(120f)
            KeyEvent.KEYCODE_PAGE_UP -> diagScrolls.tryEmit(-400f)
            KeyEvent.KEYCODE_PAGE_DOWN -> diagScrolls.tryEmit(400f)
            KeyEvent.KEYCODE_MENU -> diagVisible = false
        }
    }

    // —— 设置面板 ——

    private fun settingsKeys(code: Int) {
        val rows = settingsRows(this).filter { it !is SRow.Header }
        val last = rows.size       // 0 = 保存设置，1..n = 各项
        when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> settingsFocus = (settingsFocus - 1).coerceAtLeast(0)
            KeyEvent.KEYCODE_DPAD_DOWN -> settingsFocus = (settingsFocus + 1).coerceAtMost(last)
            KeyEvent.KEYCODE_MENU -> closeSettings(true)
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val row = rows.getOrNull(settingsFocus - 1) ?: return
                val dir = if (code == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1
                when (row) {
                    is SRow.Toggle -> row.set(dir > 0)
                    is SRow.Segment -> row.set((row.index + dir).coerceIn(0, row.options.size - 1))
                    is SRow.Slider -> row.set((row.value + dir * row.step).coerceIn(row.min, row.max))
                    is SRow.Header -> {}
                }
            }
        }
    }

    private fun settingsOk() {
        if (settingsFocus == 0) { closeSettings(true); return }
        val rows = settingsRows(this).filter { it !is SRow.Header }
        when (val row = rows.getOrNull(settingsFocus - 1) ?: return) {
            is SRow.Toggle -> row.set(!row.on)
            is SRow.Segment -> row.set((row.index + 1) % row.options.size)
            is SRow.Slider -> row.reset()
            is SRow.Header -> {}
        }
    }

    private fun sslKeys(code: Int) {
        when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP -> sslFocus = 0
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN -> sslFocus = 1
        }
    }

    // ───────────── 其他 ─────────────

    companion object {
        const val MENU_AUTO_HIDE_MS = 8000L
        const val OSD_SECONDS = 3L
        const val SLIDE_WAIT = 300L
        const val DIGITS_WAIT_MS = 3000L
        const val BUTTON_COUNT = 5
    }
}
