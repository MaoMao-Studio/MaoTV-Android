package com.cncat.maotv.core

/** 一个频道（来自 M3U） */
data class Channel(
    val id: Int,               // 在 M3U 中的顺序
    val number: Int,           // 台号（加载后统一重新编为 1..N）
    val name: String,
    val group: String,
    val logo: String?,
    val url: String,
    val tvgId: String,
    val tvgName: String,
    /** 云端频道 ID（M3U 里的 tv-id）；收藏接口的 requestID 就是它，不是台号 */
    val tvId: String,
)

/** 左侧分类（我的收藏 / 全部频道 / 各分组） */
data class Category(val id: String, val title: String, val icon: String) {
    companion object {
        const val FAVORITES_ID = "__fav__"
        const val ALL_ID = "__all__"
    }
}

/** 一条节目 */
data class Programme(val start: Long, val stop: Long, val title: String)   // 毫秒时间戳

/** 音轨 / 字幕轨条目 */
data class TrackItem(val id: String, val name: String, val isOn: Boolean)

data class DiagRow(val label: String, val value: String)
data class DiagSection(val title: String, val rows: List<DiagRow>)

data class RemoteConfig(val m3u: String, val epg: String?)

sealed class ApiError(message: String) : Exception(message) {
    /** 网络不通 / 超时 */
    class Network(m: String) : ApiError("无法连接服务器：$m")
    /** 服务器明确拒绝（Content-Type 为 text/plain，正文是理由） */
    class Server(m: String) : ApiError("服务器返回：$m")
    /** 返回了 JSON 但内容不可用 */
    class BadConfig(m: String) : ApiError(m)

    val isRejection: Boolean get() = this is Server
}

class FavError(val msg: String, val isNetwork: Boolean) : Exception(msg)
