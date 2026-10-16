package com.cncat.maotv.net

import android.content.Context
import com.cncat.maotv.R
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * 全应用共用的 HTTP 客户端。
 *  - 严格模式：系统根证书 + 内置的 ISRG Root X1（旧盒子系统里没有它，Let's Encrypt 证书会被误判无效）
 *  - 忽略证书模式：设置里“忽略SSL证书”打开时使用，任何证书都放行
 * 播放器（Media3 的 OkHttpDataSource）通过 [callFactory] 取客户端，设置一改立即生效。
 */
object Http {
    @Volatile var ignoreSsl: Boolean = false

    private lateinit var appContext: Context

    /** 内置根证书 + 系统根证书的合并信任管理器 */
    private val strictTm: X509TrustManager by lazy { buildCompositeTrustManager() }

    private val trustAllTm = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun buildCompositeTrustManager(): X509TrustManager {
        // 系统的
        val sysFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        sysFactory.init(null as KeyStore?)
        val sys = sysFactory.trustManagers.filterIsInstance<X509TrustManager>().first()

        // 内置的 ISRG Root X1
        val bundled: X509TrustManager? = try {
            val cf = CertificateFactory.getInstance("X.509")
            val bytes = appContext.resources.openRawResource(R.raw.isrg_root_x1).use { it.readBytes() }
            val cert = cf.generateCertificate(ByteArrayInputStream(bytes)) as X509Certificate
            val ks = KeyStore.getInstance(KeyStore.getDefaultType())
            ks.load(null, null)
            ks.setCertificateEntry("isrg-root-x1", cert)
            val f = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            f.init(ks)
            f.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
        } catch (e: Exception) {
            null
        }

        return object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                sys.checkClientTrusted(chain, authType)
            }

            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                try {
                    sys.checkServerTrusted(chain, authType)
                } catch (e: java.security.cert.CertificateException) {
                    val b = bundled ?: throw e
                    try {
                        b.checkServerTrusted(chain, authType)
                    } catch (e2: java.security.cert.CertificateException) {
                        throw e
                    }
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> =
                sys.acceptedIssuers + (bundled?.acceptedIssuers ?: emptyArray())
        }
    }

    private fun base(tm: X509TrustManager, trustAll: Boolean): OkHttpClient {
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf<TrustManager>(tm), null)
        val b = OkHttpClient.Builder()
            .sslSocketFactory(ctx.socketFactory, tm)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)      // 读取超时 20 秒（与 iOS 版一致）
            .writeTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
        if (trustAll) b.hostnameVerifier(HostnameVerifier { _, _ -> true })
        return b.build()
    }

    val strict: OkHttpClient by lazy { base(strictTm, false) }
    val trustAll: OkHttpClient by lazy { base(trustAllTm, true) }

    /** 当前设置对应的客户端 */
    val client: OkHttpClient get() = if (ignoreSsl) trustAll else strict

    /** 给 Media3 用：每次发请求时才决定用哪个客户端 */
    val callFactory: Call.Factory = Call.Factory { request: Request -> client.newCall(request) }
}
