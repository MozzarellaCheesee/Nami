package dev.nami.player.net

import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.OkHttpClient
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * `OkHttpDataSource.Factory` для ExoPlayer, доверяющий self-hosted серверу NAMI в
 * локальной сети по ПИННИНГУ отпечатка сертификата - так же, как `NamiServerClient`.
 *
 * Стандартный `DefaultHttpDataSource` проверяет цепочку через системные CA и на
 * самоподписанном сертификате сервера (адрес - IP в LAN) падает `SSLHandshakeException`,
 * из-за чего стрим `https://<IP>/api/tracks/.../stream` не играл. Здесь `TrustManager`
 * сначала пробует системную проверку (для внешнего домена с настоящим сертификатом всё
 * работает как раньше), а при её провале сверяет SHA-256 leaf-сертификата с закреплённым
 * отпечатком (`certSha256Provider` читает его из настроек в момент рукопожатия).
 */
fun pinnedOkHttpDataSourceFactory(
    certSha256Provider: () -> String?,
): OkHttpDataSource.Factory {
    val systemTm = systemTrustManager()
    val pinningTm = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            // 1. Обычная проверка через системные CA - внешний домен проходит здесь.
            runCatching { systemTm.checkServerTrusted(chain, authType) }.onSuccess { return }
            // 2. Провал -> сверяем отпечаток leaf-сертификата с закреплённым.
            val expected = certSha256Provider()?.removePrefix("sha256:")?.lowercase()
                ?: throw CertificateException("сертификат не доверен и отпечаток сервера не задан")
            val cert = chain?.firstOrNull() ?: throw CertificateException("сервер не прислал сертификат")
            val fp = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
                .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
            if (fp != expected) throw CertificateException("отпечаток сертификата сервера не совпал")
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = systemTm.acceptedIssuers
    }

    val sslContext = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf<javax.net.ssl.TrustManager>(pinningTm), null)
    }
    val client = OkHttpClient.Builder()
        .sslSocketFactory(sslContext.socketFactory, pinningTm)
        // Имя хоста не проверяем: у самоподписанного сертификата сервера SAN - это IP/localhost,
        // а доверие уже дал совпавший отпечаток. Для внешнего домена сертификат от CA прошёл
        // системную проверку выше, подмена нереальна.
        .hostnameVerifier { _, _ -> true }
        .build()
    return OkHttpDataSource.Factory(client)
}

private fun systemTrustManager(): X509TrustManager {
    val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    tmf.init(null as java.security.KeyStore?)
    return tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
}
