package com.cncat.maotv.player

import android.util.Log
import com.cncat.maotv.net.Http
import java.util.Locale
import java.util.concurrent.TimeUnit

/** m3u8 主播放列表里的一条 #EXT-X-MEDIA（音轨 / 字幕轨） */
class MediaHint(val name: String, val lang: String) {
    /** 统一显示名：NAME，没有就用 LANGUAGE */
    val display: String get() = name.ifBlank { lang }
}

class M3u8Hints(val audio: List<MediaHint>, val subtitles: List<MediaHint>) {
    companion object {
        val EMPTY = M3u8Hints(emptyList(), emptyList())
        private const val TAG = "M3u8Hints"
        private val ATTR = Regex("([A-Z0-9-]+)=(\"[^\"]*\"|[^,]*)")

        /** 在后台线程读一次 m3u8（只有几 KB），解析 EXT-X-MEDIA 的书写顺序；不是 m3u8 或失败则不回调 */
        fun fetch(url: String, onDone: (M3u8Hints) -> Unit) {
            if (!url.contains(".m3u8", ignoreCase = true)) return
            Thread {
                try {
                    val call = Http.callFactory.newCall(okhttp3.Request.Builder().url(url).build())
                    call.timeout().timeout(8, TimeUnit.SECONDS)
                    val text = call.execute().use { r -> if (r.isSuccessful) r.body?.string().orEmpty() else "" }
                    val a = ArrayList<MediaHint>()
                    val s = ArrayList<MediaHint>()
                    for (line in text.lineSequence()) {
                        if (!line.startsWith("#EXT-X-MEDIA:")) continue
                        val attrs = ATTR.findAll(line.substringAfter(':')).associate { it.groupValues[1] to it.groupValues[2].trim('"') }
                        val h = MediaHint((attrs["NAME"] ?: "").trim(), (attrs["LANGUAGE"] ?: "").trim())
                        when (attrs["TYPE"]) {
                            "AUDIO" -> a.add(h)
                            "SUBTITLES", "CLOSED-CAPTIONS" -> s.add(h)
                        }
                    }
                    if (a.isNotEmpty() || s.isNotEmpty()) onDone(M3u8Hints(a, s))
                } catch (e: Throwable) {
                    Log.w(TAG, "读取 m3u8 轨道信息失败", e)
                }
            }.start()
        }
    }
}

/** 播放器给出的一条候选轨道（名字 / 语言可能为空） */
class TrackCand(val name: String, val lang: String)

/**
 * Media3 和 VLC 共用：把播放器给出的轨道按 m3u8 的 EXT-X-MEDIA 顺序重排，并统一命名（NAME，没有则 LANGUAGE），
 * 这样无论用哪个播放器，列表顺序和名字都一样，记住的选择也就通用。
 * 返回：按显示顺序排列的（候选下标, 统一名字或 null）。同名只保留第一条。
 */
object TrackArranger {
    private fun norm(s: String) = s.trim().lowercase(Locale.ROOT)

    fun arrange(c: List<TrackCand>, hints: List<MediaHint>): List<Pair<Int, String?>> {
        if (hints.isEmpty()) return dedupe(c, c.indices.map { it to null })
        val rest = c.indices.toMutableList()
        val slots = arrayOfNulls<Int>(hints.size)
        for (pass in 0..2) {
            for ((hi, h) in hints.withIndex()) {
                if (slots[hi] != null) continue
                val n = norm(h.name)
                val l = norm(h.lang)
                val hit = rest.firstOrNull {
                    val cn = norm(c[it].name)
                    val cl = norm(c[it].lang)
                    when (pass) {
                        0 -> (n.isNotEmpty() && cn == n) || (n.isEmpty() && l.isNotEmpty() && cl == l && cn.isEmpty())
                        1 -> n.isNotEmpty() && cn.contains(n)
                        else -> l.isNotEmpty() && (cl == l || cn.contains("[$l]"))
                    }
                }
                if (hit != null) { slots[hi] = hit; rest.remove(hit) }
            }
        }
        // 对不上名字 / 语言的：按先后顺序一一对应（轨道出现顺序默认和 m3u8 一致）
        for (hi in hints.indices) {
            if (slots[hi] == null && rest.isNotEmpty()) slots[hi] = rest.removeAt(0)
        }
        val out = ArrayList<Pair<Int, String?>>()
        for (hi in hints.indices) slots[hi]?.let { out.add(it to hints[hi].display) }
        for (i in rest) out.add(i to null)
        return dedupe(c, out)
    }

    /** 同名（统一名字，没有则原名）只保留第一条 */
    private fun dedupe(c: List<TrackCand>, list: List<Pair<Int, String?>>): List<Pair<Int, String?>> {
        val seen = HashSet<String>()
        return list.filter { (i, nm) ->
            val key = norm(nm ?: c[i].name)
            key.isEmpty() || seen.add(key)
        }
    }
}
