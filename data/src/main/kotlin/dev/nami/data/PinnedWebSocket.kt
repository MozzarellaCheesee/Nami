package dev.nami.data

import okhttp3.OkHttpClient
import java.net.URI
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * OkHttp-клиент для сокета с пиннингом отпечатка сертификата.
 *
 * Пиннинг применяется ТОЛЬКО к адресу-IP: в локальной сети сертификат самоподписанный, и
 * доверять ему можно лишь по заранее известному отпечатку. У внешнего домена сертификат обычный,
 * от центра сертификации, и подменять проверку там нельзя - это ослабило бы защиту, а не усилило.
 *
 * Живёт отдельным файлом, потому что нужен двум местам сразу - Jam-сессии и слушателю изменений
 * библиотеки. Две копии одного пиннинга разошлись бы при первой же правке, а расхождение здесь
 * означает дыру в проверке сертификата, а не косметику.
 */
internal fun pinnedWebSocketClient(wsBase: String, certSha256: String?): OkHttpClient {
    val builder = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        // Ноль, а не большое значение: сокет молчит сколько угодно между событиями, и любой
        // таймаут чтения рвал бы живое соединение. Обрыв ловится пингом ниже.
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(30, TimeUnit.SECONDS)

    val host = runCatching {
        val probe = if (wsBase.contains("://")) wsBase else "ws://$wsBase"
        URI(probe.replace(Regex("^wss?"), "http")).host
    }.getOrNull().orEmpty()

    val isTls = wsBase.startsWith("wss://", ignoreCase = true) || wsBase.startsWith("https://", ignoreCase = true)
    if (isTls && certSha256 != null && hostIsIpLiteralForPinning(host)) {
        val tm = pinnedTrustManagerFor(certSha256)
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf<javax.net.ssl.TrustManager>(tm), null) }
        builder.sslSocketFactory(ssl.socketFactory, tm)
        builder.hostnameVerifier { _, _ -> true }
    }
    return builder.build()
}

internal fun hostIsIpLiteralForPinning(host: String): Boolean {
    val clean = host.trim().removePrefix("[").removeSuffix("]")
    return clean.matches(Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")) || clean.contains(':')
}

internal fun pinnedTrustManagerFor(expected: String): X509TrustManager {
    val want = expected.removePrefix("sha256:").lowercase()
    return object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val cert = chain?.firstOrNull() ?: throw CertificateException("сервер не прислал сертификат")
            val fingerprint = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
                .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
            if (fingerprint != want) throw CertificateException("отпечаток сертификата сервера не совпал")
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }
}
