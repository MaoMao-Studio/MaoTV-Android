package com.cncat.maotv.net

import android.content.Context
import android.util.Xml
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import com.cncat.maotv.core.Channel
import com.cncat.maotv.core.Programme
import com.cncat.maotv.core.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.util.zip.GZIPInputStream

/** 统一的频道 id / 名称归一化（小写，去空格、连字符、下划线） */
fun epgNorm(s: String): String = buildString {
    for (c in s.lowercase()) if (!c.isWhitespace() && c != '-' && c != '_') append(c)
}

/** 校准后的“现在”：盒子时钟不准时，用服务器返回的时间算出偏差 */
object Clock {
    @Volatile var offsetMs: Long = 0L
    fun now(): Long = System.currentTimeMillis() + offsetMs
}

/** EPG（XMLTV，可 gzip）：下载、解析、每 3 小时刷新、按频道查询 */
class EpgStore(private val context: Context, private val store: SettingsStore, private val scope: CoroutineScope) {
    /** 每分钟更新一次，用来驱动“当前节目”刷新；EPG 载入完成时也会更新 */
    var tick by mutableLongStateOf(Clock.now())
        private set

    @Volatile private var programmes: Map<String, List<Programme>> = emptyMap()
    @Volatile private var nameToId: Map<String, String> = emptyMap()
    private val keyCache = HashMap<Int, String>()

    private var url: String? = null
    private var heartbeatJob: Job? = null
    @Volatile private var loading = false

    private val maxAge = 3 * 3600_000L
    private val cacheFile: File get() = File(context.cacheDir, "epg.xml.gz")
    private val isStale: Boolean get() = Clock.now() - store.epgLastUpdate >= maxAge

    fun configure(url: String?) {
        this.url = url
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            launch { refresh(force = false) }
            while (isActive) {
                delay(60_000)
                tick = Clock.now()
                if (isStale) launch { refresh(force = true) }
            }
        }
    }

    private suspend fun refresh(force: Boolean) {
        val u = url ?: return
        if (loading) return
        loading = true
        try {
            val cache = cacheFile
            val hasCache = cache.exists()
            if (!force && hasCache && programmes.isEmpty()) {
                withContext(Dispatchers.IO) { parse(cache) }?.let { apply(it) }
            }
            if (!(force || isStale || !hasCache)) return
            withContext(Dispatchers.IO) {
                try {
                    val req = Request.Builder().url(u).build()
                    val client = Http.client.newBuilder().readTimeout(180, java.util.concurrent.TimeUnit.SECONDS).build()
                    client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return@withContext
                        val tmp = File(context.cacheDir, "epg.tmp")
                        resp.body?.byteStream()?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                        cache.delete()
                        if (!tmp.renameTo(cache)) return@withContext
                    }
                    parse(cache)?.let { r ->
                        withContext(Dispatchers.Main) { apply(r) }
                        store.epgLastUpdate = Clock.now()
                    }
                } catch (e: Exception) {
                    // 下载失败：保留旧数据，下个心跳再试
                }
            }
        } finally {
            loading = false
        }
    }

    private fun apply(r: Parsed) {
        programmes = r.programmes
        nameToId = r.nameToId
        synchronized(keyCache) { keyCache.clear() }
        tick = Clock.now()
    }

    // —— 查询 ——

    private fun keyFor(ch: Channel): String? {
        synchronized(keyCache) {
            keyCache[ch.id]?.let { return it.ifEmpty { null } }
        }
        val cands = listOf(ch.tvgId, ch.tvgName, ch.name).map(::epgNorm).filter { it.isNotEmpty() }
        val progs = programmes
        var found: String? = null
        for (c in cands) if (progs.containsKey(c)) { found = c; break }
        if (found == null) {
            for (c in cands) {
                val id = nameToId[c]
                if (id != null && progs.containsKey(id)) { found = id; break }
            }
        }
        synchronized(keyCache) { keyCache[ch.id] = found ?: "" }
        return found
    }

    fun schedule(ch: Channel): List<Programme> {
        val k = keyFor(ch) ?: return emptyList()
        return programmes[k] ?: emptyList()
    }

    fun nowNext(ch: Channel, at: Long = Clock.now()): Pair<Programme?, Programme?> {
        val list = schedule(ch)
        val idx = list.indexOfFirst { it.start <= at && at < it.stop }
        if (idx >= 0) return list[idx] to list.getOrNull(idx + 1)
        return null to list.firstOrNull { it.start > at }
    }

    // —— 解析 ——

    class Parsed(val programmes: Map<String, List<Programme>>, val nameToId: Map<String, String>)

    private fun parse(file: File): Parsed? {
        return try {
            file.inputStream().buffered().use { raw ->
                raw.mark(4)
                val b0 = raw.read(); val b1 = raw.read()
                raw.reset()
                val input: InputStream = if (b0 == 0x1F && b1 == 0x8B) GZIPInputStream(raw, 64 * 1024) else raw
                parseXml(input)
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 流式解析，只保留 [现在-24h, 现在+48h] 的节目，节省内存 */
    private fun parseXml(input: InputStream): Parsed? {
        val now = Clock.now()
        val lower = now - 24 * 3600_000L
        val upper = now + 48 * 3600_000L
        val progs = HashMap<String, MutableList<Programme>>()
        val names = HashMap<String, String>()

        val p = Xml.newPullParser()
        p.setInput(input, null)
        var inChannel = false
        var curChannel = ""
        var inProgramme = false
        var skip = false
        var pChannel = ""
        var pStart = 0L
        var pStop = 0L
        var title: String? = null
        var collecting = false
        val text = StringBuilder()

        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "channel" -> { inChannel = true; curChannel = epgNorm(p.getAttributeValue(null, "id") ?: "") }
                    "display-name" -> if (inChannel) { collecting = true; text.setLength(0) }
                    "programme" -> {
                        inProgramme = true
                        title = null
                        pChannel = epgNorm(p.getAttributeValue(null, "channel") ?: "")
                        val s = parseTime(p.getAttributeValue(null, "start") ?: "")
                        val e = parseTime(p.getAttributeValue(null, "stop") ?: "")
                        if (s != null && e != null) {
                            pStart = s; pStop = e
                            skip = e < lower || s > upper
                        } else skip = true
                    }
                    "title" -> if (inProgramme && !skip && title == null) { collecting = true; text.setLength(0) }
                }
                XmlPullParser.TEXT -> if (collecting) text.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) {
                    "display-name" -> if (inChannel && collecting) {
                        collecting = false
                        val k = epgNorm(text.toString())
                        if (k.isNotEmpty()) names[k] = curChannel
                    }
                    "title" -> if (collecting) { collecting = false; title = text.toString().trim() }
                    "channel" -> inChannel = false
                    "programme" -> {
                        if (!skip) progs.getOrPut(pChannel) { ArrayList() }.add(Programme(pStart, pStop, title ?: ""))
                        inProgramme = false
                        skip = false
                    }
                }
            }
            ev = p.next()
        }
        if (progs.isEmpty()) return null
        val sorted = HashMap<String, List<Programme>>(progs.size)
        for ((k, v) in progs) sorted[k] = v.sortedBy { it.start }
        return Parsed(sorted, names)
    }

    companion object {
        /** “20261008082500 +0800” → 毫秒时间戳（手写解析，比 SimpleDateFormat 快很多） */
        fun parseTime(s: String): Long? {
            if (s.length < 14) return null
            fun num(a: Int, n: Int): Int? {
                var v = 0
                for (i in a until a + n) {
                    val c = s[i] - '0'
                    if (c < 0 || c > 9) return null
                    v = v * 10 + c
                }
                return v
            }
            val y = num(0, 4) ?: return null
            val mo = num(4, 2) ?: return null
            val d = num(6, 2) ?: return null
            val h = num(8, 2) ?: return null
            val mi = num(10, 2) ?: return null
            val sec = num(12, 2) ?: return null
            var offset = 0
            var signIdx = -1
            for (i in 14 until s.length) if (s[i] == '+' || s[i] == '-') { signIdx = i; break }
            if (signIdx >= 0 && s.length >= signIdx + 5) {
                val oh = num(signIdx + 1, 2)
                val om = num(signIdx + 3, 2)
                if (oh != null && om != null) offset = (oh * 3600 + om * 60) * (if (s[signIdx] == '-') -1 else 1)
            }
            val days = daysFromCivil(y, mo, d)
            val ts = days * 86400L + h * 3600L + mi * 60L + sec - offset
            return ts * 1000L
        }

        private fun daysFromCivil(y0: Int, m: Int, d: Int): Long {
            val y = if (m <= 2) y0 - 1 else y0
            val era = (if (y >= 0) y else y - 399) / 400
            val yoe = y - era * 400
            val doy = (153 * (m + (if (m > 2) -3 else 9)) + 2) / 5 + d - 1
            val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
            return era * 146097L + doe - 719468L
        }
    }
}
