package com.cncat.maotv.net

import com.cncat.maotv.core.ApiError
import com.cncat.maotv.core.FavError
import com.cncat.maotv.core.RemoteConfig
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

private val trailingComma = Regex(",\\s*([}\\]])")

fun decodeText(data: ByteArray): String {
    try {
        return Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(data)).toString()
    } catch (e: CharacterCodingException) {
        // 不是 UTF-8：按 GB18030 再试
    }
    return try {
        String(data, Charset.forName("GB18030"))
    } catch (e: Exception) {
        String(data, Charsets.UTF_8)
    }
}

object ApiClient {
    const val ENDPOINT = "https://maotv.cncat.net/tvlist/tvandroid.php"

    /**
     * 用 Token 访问 tvandroid.php（阻塞，调用方放 IO 线程）。
     * 正确：Content-Type: application/json，解析出 m3ulist 与 epg；
     * 错误：Content-Type: text/plain，正文就是服务器给出的理由。
     */
    fun validate(token: String): RemoteConfig {
        val url = ENDPOINT.toHttpUrl().newBuilder().addQueryParameter("token", token).build()
        val req = Request.Builder().url(url).header("Cache-Control", "no-cache").build()
        try {
            Http.client.newCall(req).execute().use { resp ->
                val data = resp.body?.bytes() ?: ByteArray(0)
                val ct = (resp.header("Content-Type") ?: "").lowercase()
                if (ct.contains("application/json")) return parseConfig(data)
                var reason = decodeText(data).trim()
                if (reason.isEmpty()) reason = "未知错误（HTTP ${resp.code}）"
                throw ApiError.Server(reason)
            }
        } catch (e: ApiError) {
            throw e
        } catch (e: Exception) {
            throw ApiError.Network(e.message ?: e.javaClass.simpleName)
        }
    }

    /** 下载 M3U 文本（阻塞） */
    fun fetchText(url: String): String {
        val req = try {
            Request.Builder().url(url).header("Cache-Control", "no-cache").build()
        } catch (e: Exception) {
            throw ApiError.BadConfig("频道列表地址无效")
        }
        try {
            Http.client.newCall(req).execute().use { resp ->
                val data = resp.body?.bytes() ?: ByteArray(0)
                if (!resp.isSuccessful) {
                    val body = decodeText(data).trim()
                    throw ApiError.Server(if (body.isEmpty()) "获取频道列表失败（HTTP ${resp.code}）" else body)
                }
                return decodeText(data)
            }
        } catch (e: ApiError) {
            throw e
        } catch (e: Exception) {
            throw ApiError.Network(e.message ?: e.javaClass.simpleName)
        }
    }

    /** 服务器示例 JSON 末尾可能带多余逗号，先去掉再解析；仍失败则用正则直接取两个地址。 */
    private fun parseConfig(data: ByteArray): RemoteConfig {
        val raw = decodeText(data)
        val cleaned = raw.replace(trailingComma, "$1")
        var m3u: String? = null
        var epg: String? = null
        try {
            val configs = JSONObject(cleaned).optJSONObject("configs")
            if (configs != null) {
                m3u = configs.optString("m3ulist").ifEmpty { null }
                epg = configs.optString("epg").ifEmpty { null }
            }
        } catch (e: Exception) {
            // 走正则兜底
        }
        if (m3u == null) {
            m3u = firstMatch("\"m3ulist\"\\s*:\\s*\"([^\"]+)\"", raw)
            epg = epg ?: firstMatch("\"epg\"\\s*:\\s*\"([^\"]+)\"", raw)
        }
        val m3uStr = m3u?.trim()
        if (m3uStr.isNullOrEmpty()) throw ApiError.BadConfig("服务器返回的配置中没有可用的频道列表地址")
        return RemoteConfig(m3uStr, epg?.trim()?.ifEmpty { null })
    }

    private fun firstMatch(pattern: String, text: String): String? =
        Regex(pattern).find(text)?.groupValues?.getOrNull(1)
}

/** 云收藏（user_fav.php，只能用 GET，返回 JSON） */
object FavoriteApi {
    private const val ENDPOINT = "https://maotv.cncat.net/tvlist/user_fav.php"

    /** 通用调用（阻塞）。成功（success = true）时返回 data；失败抛出 FavError（msg 就是要给用户看的原因）。 */
    fun call(token: String, action: String? = null, requestId: String? = null, sortOrder: Int? = null): Any? {
        val b = ENDPOINT.toHttpUrl().newBuilder().addQueryParameter("token", token)
        if (action != null) b.addQueryParameter("action", action)
        if (requestId != null) b.addQueryParameter("requestID", requestId)
        if (sortOrder != null) b.addQueryParameter("sort_order", sortOrder.toString())
        val req = Request.Builder().url(b.build()).header("Cache-Control", "no-cache").build()

        val data: ByteArray
        val status: Int
        try {
            Http.client.newCall(req).execute().use { resp ->
                data = resp.body?.bytes() ?: ByteArray(0)
                status = resp.code
            }
        } catch (e: Exception) {
            throw FavError(e.message ?: e.javaClass.simpleName, true)
        }
        val text = decodeText(data).trim()
        val cleaned = text.replace(trailingComma, "$1")
        val obj = try {
            JSONObject(cleaned)
        } catch (e: Exception) {
            val brief = if (text.isEmpty()) "服务器没有返回内容" else text.take(80)
            throw FavError("服务器返回无法识别（HTTP $status）：$brief", false)
        }
        val message = obj.optString("message", "")
        val ok = when (val s = obj.opt("success")) {
            is Boolean -> s
            is Number -> s.toInt() != 0
            is String -> s == "true" || s == "1"
            else -> false
        }
        if (!ok) throw FavError(message.ifEmpty { "未知错误（HTTP $status）" }, false)
        return if (obj.has("data") && !obj.isNull("data")) obj.get("data") else null
    }

    /** 查询收藏列表：按服务器排序返回频道 ID（字符串） */
    fun list(token: String): List<String> {
        val d = call(token, action = "list") as? JSONArray ?: return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until d.length()) {
            val v = d.opt(i)
            val s = when (v) {
                is String -> v.trim()
                is Number -> v.toString().removeSuffix(".0")
                else -> ""
            }
            if (s.isNotEmpty()) out.add(s)
        }
        return out
    }
}
