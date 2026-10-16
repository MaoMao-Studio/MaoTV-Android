package com.cncat.maotv.net

import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import javax.net.ssl.SSLException

/**
 * 服务器身份探测：GET connect_test.php?n=<随机数>，用内置公钥验证服务器返回的 RSA-SHA256 签名。
 * 签名内容："MAOTV-CONNECT-V1\n<nonce>\n<ts>"。时间和用户 IP 都不参与“通过/不通过”的判断。
 * （只能挡住假冒的服务器，挡不住把请求原样转发给真服务器的中转式中间人。）
 */
object ServerProbe {
    private const val URL = "https://maotv.cncat.net/tvlist/connect_test.php"
    private const val LABEL = "MAOTV-CONNECT-V1"

    private val PUBLIC_KEY_B64 = listOf(
        "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAvczIBNOvZ/ET8mIfrqLV",
        "deMW/u/trAksNKhfqn7dzNmVmo58ZhYcs0A0WSkEVz1Frrhg675tSsl/jx00/Zhk",
        "gaLeUz1YVZcWDRbPP7u0DhWJsbmAuTOs6OnAn9qaPVky/I8LsN7dubjRWDIIxTLV",
        "y126onKEsUihZaZYOAOz05dOviBkfu/IBC64m1MhhwAEdQwwYkwt+zJViTBLdhHm",
        "+aDGcw96L5ji2+zgJYmuFSSCp7OYncOVoX1H4TXMZlK6iJw0Ki0PD16blzw1zXCh",
        "6Hc6WNFSojaBY0cB5w8V8LZysThBAanwLyVPXizMYWbm9QXLKx/Z2dGzZR9QbW4C",
        "gwIDAQAB"
    ).joinToString("")

    sealed class Result {
        /** 验证通过。serverTimeMs = 服务器时间（毫秒），用来校正盒子时钟 */
        data class Verified(val serverTimeMs: Long) : Result()
        /** 证书类错误（严格模式下） */
        data class SslError(val reason: String) : Result()
        /** 网络不通 / 超时 / 返回内容不对 / 签名不对 */
        data class Failed(val reason: String) : Result()
    }

    private fun newNonce(): String {
        val b = ByteArray(16)
        SecureRandom().nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    /** 用给定的客户端探测一次（阻塞，调用方放到 IO 线程） */
    fun probe(client: OkHttpClient): Result {
        val nonce = newNonce()
        val req = Request.Builder().url("$URL?n=$nonce")
            .header("Cache-Control", "no-cache").build()
        var body = ""
        try {
            client.newCall(req).execute().use { resp ->
                body = resp.body?.string() ?: ""
                if (!resp.isSuccessful) return Result.Failed("HTTP ${resp.code}")
            }
        } catch (e: SSLException) {
            return Result.SslError(e.message ?: "SSL 错误")
        } catch (e: java.security.cert.CertificateException) {
            return Result.SslError(e.message ?: "证书错误")
        } catch (e: Exception) {
            return Result.Failed(e.message ?: e.javaClass.simpleName)
        }
        return try {
            val obj = JSONObject(body)
            if (obj.optString("nonce") != nonce) return Result.Failed("随机数不一致")
            val ts = obj.optLong("ts", -1L)
            if (ts < 0) return Result.Failed("缺少时间戳")
            val sig = Base64.decode(obj.optString("sig"), Base64.DEFAULT)
            val pub = KeyFactory.getInstance("RSA")
                .generatePublic(X509EncodedKeySpec(Base64.decode(PUBLIC_KEY_B64, Base64.DEFAULT)))
            val s = Signature.getInstance("SHA256withRSA")
            s.initVerify(pub)
            s.update("$LABEL\n$nonce\n$ts".toByteArray(Charsets.UTF_8))
            if (s.verify(sig)) Result.Verified(ts * 1000L) else Result.Failed("签名不正确")
        } catch (e: Exception) {
            Result.Failed("返回内容无法识别")
        }
    }
}
