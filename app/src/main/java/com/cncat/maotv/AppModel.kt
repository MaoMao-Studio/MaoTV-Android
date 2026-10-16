package com.cncat.maotv

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cncat.maotv.core.ApiError
import com.cncat.maotv.core.Category
import com.cncat.maotv.core.Channel
import com.cncat.maotv.core.FavError
import com.cncat.maotv.core.M3UParser
import com.cncat.maotv.core.SettingsStore
import com.cncat.maotv.net.ApiClient
import com.cncat.maotv.net.Clock
import com.cncat.maotv.net.EpgStore
import com.cncat.maotv.net.FavoriteApi
import com.cncat.maotv.net.Http
import com.cncat.maotv.net.ServerProbe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class Phase { CHECKING, NEED_TOKEN, READY }

/** 全应用共用的状态：认证、频道列表、分类、收藏、提示条。 */
class AppModel(context: Context) {
    val appContext: Context = context.applicationContext
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val settings = SettingsStore(appContext)
    val epg = EpgStore(appContext, settings, scope)

    var phase by mutableStateOf(Phase.CHECKING)
        private set
    /** 服务器 / 网络错误信息，显示在 Token 输入页 */
    var message by mutableStateOf<String?>(null)
    var isBusy by mutableStateOf(false)
        private set
    /** 网络类错误时，保留用户刚输入的 Token 方便重试 */
    var tokenDraft by mutableStateOf("")
    var statusText by mutableStateOf("正在认证…")
        private set

    var channels by mutableStateOf<List<Channel>>(emptyList())
        private set
    var categories by mutableStateOf<List<Category>>(emptyList())
        private set

    /** 云端收藏：频道 ID（tv-id），按云端顺序 */
    var favoriteIds by mutableStateOf<List<String>>(emptyList())
        private set
    private var favoriteSet: Set<String> = emptySet()
    /** “我的收藏”列表里实际显示的顺序：取消收藏的频道先留在原位（星标变空），切换分类再回来才真正消失 */
    var favoriteShown by mutableStateOf<List<String>>(emptyList())
        private set
    private var favoriteLoaded = false
    private val favLock = Mutex()
    private var tvMap: Map<String, Channel> = emptyMap()
    private var byGroup: Map<String, List<Channel>> = emptyMap()
    private var byNumber: Map<Int, Channel> = emptyMap()

    /** 一条短提示（收藏同步失败等），几秒后自动消失 */
    var toast by mutableStateOf<String?>(null)
        private set
    private var toastJob: Job? = null

    // —— 服务器验证 ——
    private var probeJob: Job? = null
    @Volatile private var probeVerified = false
    private var probeReason: String = ""

    val savedToken: String get() = settings.token

    init {
        Http.init(appContext)
        Http.ignoreSsl = settings.values.ignoreSsl
    }

    // ───────────── 启动 / 认证 ─────────────

    fun start() {
        if (phase != Phase.CHECKING || probeJob != null) return
        startProbe()
        val token = savedToken
        if (token.isEmpty()) {
            phase = Phase.NEED_TOKEN
            return
        }
        scope.launch { bootstrap(token) }
    }

    /** 设置里切换“忽略SSL证书”后调用 */
    fun onIgnoreSslChanged() {
        Http.ignoreSsl = settings.values.ignoreSsl
    }

    private fun startProbe() {
        probeJob = scope.launch {
            probeVerified = false
            var attempt = 0
            while (true) {
                val r = withContext(Dispatchers.IO) { probeOnce() }
                when (r) {
                    is ServerProbe.Result.Verified -> {
                        probeVerified = true
                        val diff = r.serverTimeMs - System.currentTimeMillis()
                        Clock.offsetMs = if (kotlin.math.abs(diff) > 3000) diff else 0L
                        return@launch
                    }
                    is ServerProbe.Result.SslError -> { probeReason = "证书错误：${r.reason}"; return@launch }
                    is ServerProbe.Result.Failed -> {
                        probeReason = r.reason
                        if (r.reason == "签名不正确" || r.reason == "随机数不一致") return@launch   // 假冒的服务器：不重试
                        attempt++
                        if (attempt >= 3) return@launch
                        delay(2000)
                    }
                }
            }
        }
    }

    /** 先按当前设置探测；证书出错就忽略证书再试一次，成功则自动打开“忽略SSL证书” */
    private fun probeOnce(): ServerProbe.Result {
        val first = ServerProbe.probe(Http.client)
        if (first is ServerProbe.Result.SslError && !Http.ignoreSsl) {
            val second = ServerProbe.probe(Http.trustAll)
            if (second is ServerProbe.Result.Verified) {
                settings.apply(settings.values.copy(ignoreSsl = true))
                Http.ignoreSsl = true
                return second
            }
            return first
        }
        return first
    }

    /** 等探测结果（探测没跑完就等；没通过则再探一次）。通过返回 true */
    private suspend fun ensureVerified(): Boolean {
        probeJob?.join()
        if (probeVerified) return true
        startProbe()
        probeJob?.join()
        return probeVerified
    }

    /** 用户在输入页提交 Token */
    fun submit(raw: String) {
        val token = raw.trim()
        if (token.isEmpty()) {
            message = "请输入用户Token"
            return
        }
        if (isBusy) return
        scope.launch { bootstrap(token) }
    }

    private suspend fun bootstrap(token: String) {
        isBusy = true
        try {
            // Token 只有在服务器身份验证通过之后才会发出
            if (!probeVerified) {
                statusText = "等待验证…"
                if (!ensureVerified()) {
                    message = "无法验证服务器身份（$probeReason），Token 未发送。请检查网络后重试"
                    tokenDraft = token
                    phase = Phase.NEED_TOKEN
                    return
                }
            }
            var attempt = 0
            while (true) {
                statusText = if (attempt == 0) "正在认证…" else "网络异常，正在重试 ($attempt/$MAX_NETWORK_RETRIES)"
                try {
                    val cfg = withContext(Dispatchers.IO) { ApiClient.validate(token) }
                    // 先读云端收藏，再下载频道列表；收藏读取失败不影响进入，进入后用提示条告知
                    statusText = "认证成功，正在获取收藏列表…"
                    favoriteIds = emptyList(); favoriteSet = emptySet(); favoriteShown = emptyList()
                    favoriteLoaded = false
                    val favError = loadFavorites(token, retries = 1, quiet = true)
                    statusText = "开始加载频道…"
                    val text = withContext(Dispatchers.IO) { ApiClient.fetchText(cfg.m3u) }
                    val list = withContext(Dispatchers.Default) { M3UParser.parse(text) }
                    if (list.isEmpty()) throw ApiError.BadConfig("频道列表获取失败")

                    settings.token = token
                    apply(list)
                    epg.configure(cfg.epg)
                    message = null
                    tokenDraft = ""
                    phase = Phase.READY
                    if (favError != null) showToast("同步收藏失败：$favError")
                    return
                } catch (e: ApiError) {
                    // 只有“网络错误 / 超时”才重试；服务器明确拒绝 Token 的不重试
                    if (e is ApiError.Network && attempt < MAX_NETWORK_RETRIES) {
                        attempt++; delay(2000); continue
                    }
                    message = e.message
                    tokenDraft = if (e.isRejection) "" else token
                    phase = Phase.NEED_TOKEN
                    return
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (attempt < MAX_NETWORK_RETRIES) { attempt++; delay(2000); continue }
                    message = "加载失败：${e.message}"
                    tokenDraft = token
                    phase = Phase.NEED_TOKEN
                    return
                }
            }
        } finally {
            isBusy = false
        }
    }

    // ───────────── 频道 / 分类 ─────────────

    private fun apply(raw: List<Channel>) {
        // 先按分类出现的顺序分组，再给全部频道依次编唯一台号（1、2、3……）
        val order = ArrayList<String>()
        val grouped = HashMap<String, MutableList<Channel>>()
        for (ch in raw) {
            if (!grouped.containsKey(ch.group)) order.add(ch.group)
            grouped.getOrPut(ch.group) { ArrayList() }.add(ch)
        }
        val list = ArrayList<Channel>(raw.size)
        val dict = HashMap<String, MutableList<Channel>>()
        var n = 1
        for (g in order) for (ch in grouped[g].orEmpty()) {
            val c = ch.copy(number = n++)
            list.add(c)
            dict.getOrPut(g) { ArrayList() }.add(c)
        }
        val map = HashMap<String, Channel>()
        for (c in list) if (c.tvId.isNotEmpty() && !map.containsKey(c.tvId)) map[c.tvId] = c
        tvMap = map
        byGroup = dict
        byNumber = list.associateBy { it.number }
        channels = list
        categories = listOf(
            Category(Category.FAVORITES_ID, "我的收藏", "⭐️"),
            Category(Category.ALL_ID, "全部频道", "📺"),
        ) + order.map { Category(it, it, iconFor(it)) }
    }

    fun channels(inCategory: String): List<Channel> = when (inCategory) {
        // 云端顺序；云端有、但当前列表里已经没有的频道直接忽略
        Category.FAVORITES_ID -> favoriteShown.mapNotNull { tvMap[it] }
        Category.ALL_ID -> channels
        else -> byGroup[inCategory] ?: emptyList()
    }

    fun count(categoryId: String): Int =
        if (categoryId == Category.FAVORITES_ID) favoriteIds.count { tvMap.containsKey(it) }
        else channels(categoryId).size

    /** 按台号找频道（数字键选台用） */
    fun channelByNumber(n: Int): Channel? = byNumber[n]

    private fun iconFor(title: String): String = when {
        title.contains("内地") || title.contains("大陆") -> "🇨🇳"
        title.contains("港") || title.contains("澳") -> "🇭🇰"
        title.contains("台") -> "🇹🇼"
        title.contains("海外") || title.contains("国际") -> "🌏"
        title.contains("休闲") || title.contains("娱乐") -> "☕️"
        title.contains("体育") -> "⚽️"
        title.contains("电影") -> "🎬"
        title.contains("新闻") -> "📰"
        title.contains("少儿") || title.contains("卡通") -> "🧸"
        else -> "📡"
    }

    // ───────────── 提示条 ─────────────

    fun showToast(text: String, millis: Long = 3500) {
        toast = text
        toastJob?.cancel()
        toastJob = scope.launch {
            delay(millis)
            toast = null
        }
    }

    // ───────────── 收藏 ─────────────

    fun syncFavoriteShown() {
        if (favoriteShown != favoriteIds) favoriteShown = favoriteIds
    }

    fun isFavorite(ch: Channel): Boolean = ch.tvId.isNotEmpty() && favoriteSet.contains(ch.tvId)

    private fun setFavorites(ids: List<String>) {
        favoriteIds = ids
        favoriteSet = ids.toHashSet()
    }

    /** 读取云端收藏列表。成功返回 null；失败返回原因。quiet = true 时不弹提示条 */
    suspend fun loadFavorites(token: String = savedToken, retries: Int = 3, quiet: Boolean = false): String? {
        if (token.isEmpty()) return "没有 Token"
        var attempt = 0
        while (true) {
            try {
                val ids = withContext(Dispatchers.IO) { FavoriteApi.list(token) }
                setFavorites(ids.distinct())
                favoriteShown = favoriteIds
                favoriteLoaded = true
                return null
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                val fe = e as? FavError
                if ((fe?.isNetwork ?: true) && attempt < retries) {
                    attempt++; delay(2000); continue
                }
                val reason = fe?.msg ?: (e.message ?: "未知错误")
                if (!quiet) showToast("同步收藏失败：$reason")
                return reason
            }
        }
    }

    /** 点星标：先问云端，成功了才更新界面；失败就提示原因、界面保持不变 */
    fun toggleFavorite(ch: Channel) {
        if (ch.tvId.isEmpty()) { showToast("收藏失败：该频道没有 tv-id"); return }
        if (!favoriteLoaded) {
            showToast("同步收藏失败：收藏列表还没有加载成功，请稍候…")
            scope.launch { loadFavorites() }
            return
        }
        val id = ch.tvId
        scope.launch {
            favLock.withLock {
                val adding = !favoriteSet.contains(id)
                try {
                    withContext(Dispatchers.IO) {
                        FavoriteApi.call(savedToken, if (adding) "add" else "delete", id)
                    }
                    if (adding) {
                        if (!favoriteSet.contains(id)) setFavorites(favoriteIds + id)
                        if (!favoriteShown.contains(id)) favoriteShown = favoriteShown + id
                    } else {
                        setFavorites(favoriteIds.filter { it != id })
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val m = (e as? FavError)?.msg ?: (e.message ?: "未知错误")
                    showToast((if (adding) "收藏失败：" else "取消收藏失败：") + m)
                }
            }
        }
    }

    /**
     * 在“我的收藏”里把频道拖到列表里的第 index 个位置（从 0 数起，界面上看到的位置）。
     * 成功返回 true；失败提示原因并返回 false（界面会还原）。
     */
    suspend fun moveFavorite(ch: Channel, index: Int): Boolean {
        if (!favoriteLoaded || ch.tvId.isEmpty()) return false
        val id = ch.tvId
        return favLock.withLock {
            val arr = favoriteIds.toMutableList()
            val from = arr.indexOf(id)
            if (from < 0) return@withLock false
            arr.removeAt(from)
            // 界面上只显示当前频道列表里还存在的收藏；把“界面位置”换算成云端列表里的位置
            val visible = arr.filter { tvMap.containsKey(it) }
            // 界面列表里可能还留着刚取消收藏的频道：换算时不把它们算进去
            val ui = favoriteShown.toMutableList()
            ui.remove(id)
            val kk = index.coerceIn(0, ui.size)
            val k = minOf(ui.subList(0, kk).count { favoriteSet.contains(it) && tvMap.containsKey(it) }, visible.size)
            val insertAt = when {
                k < visible.size && arr.indexOf(visible[k]) >= 0 -> arr.indexOf(visible[k])
                visible.isNotEmpty() && arr.indexOf(visible.last()) >= 0 -> arr.indexOf(visible.last()) + 1
                else -> arr.size
            }
            arr.add(insertAt, id)
            if (arr == favoriteIds) return@withLock true
            try {
                withContext(Dispatchers.IO) {
                    FavoriteApi.call(savedToken, "modify", id, sortOrder = insertAt + 1)
                }
                setFavorites(arr)
                ui.add(kk, id)
                favoriteShown = ui
                true
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                showToast("同步收藏失败：" + ((e as? FavError)?.msg ?: (e.message ?: "未知错误")))
                false
            }
        }
    }

    companion object {
        /** 认证 Token、下载频道列表时，网络错误 / 超时（20 秒）最多自动重试的次数 */
        const val MAX_NETWORK_RETRIES = 10
    }
}
