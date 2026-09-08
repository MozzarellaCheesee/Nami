package dev.nami.player.remote

import android.util.Log
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

private const val TAG = "Glagol"

/**
 * Клиент локального протокола Яндекс Станции (Glagol). Официального SDK нет: протокол
 * реверс-инженерен сообществом, формат команд сверен с AlexxIT/YandexStation
 * (custom_components/yandex_station/core/yandex_glagol.py и yandex_station.py).
 *
 * Своя реализация WebSocket на голых сокетах - тот же подход, что у LocalHttpServer: нужны ровно
 * текстовые кадры в обе стороны, а ради них тянуть OkHttp (единственная альтернатива - в Android
 * SDK WebSocket-клиента нет) в проект, который весь построен на java.net, несоразмерно.
 *
 * Проверка сертификата станции выключена намеренно и осознанно: станция предъявляет
 * самоподписанный сертификат на свой же локальный IP, проверить его нечем и не у кого - ровно так
 * же поступает референсная реализация (ssl=False). Соединение при этом всё равно шифруется, а
 * доверять тут можно только тому, что устройство ответило на нашем же device_id в локальной сети.
 */
class GlagolClient(
    private val host: String,
    private val port: Int,
    private val deviceToken: String,
) {
    private var socket: SSLSocket? = null
    private var output: OutputStream? = null
    private var reader: Thread? = null

    /** Последний прогресс, который станция прислала сама (она шлёт статус раз в секунду при
     * воспроизведении) - опрашивать её отдельным запросом не нужно и нечем. */
    @Volatile var progressMs: Long? = null
        private set

    fun connect() {
        val ssl = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(TrustAnyCertificate), SecureRandom())
        }
        val s = ssl.socketFactory.createSocket(host, port) as SSLSocket
        s.soTimeout = 0
        s.startHandshake()
        val input = BufferedInputStream(s.inputStream)
        val out = s.outputStream

        val key = ByteArray(16).also { SecureRandom().nextBytes(it) }
        out.write(
            (
                "GET / HTTP/1.1\r\n" +
                    "Host: $host:$port\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Key: ${Base64.getEncoder().encodeToString(key)}\r\n" +
                    "Sec-WebSocket-Version: 13\r\n\r\n"
                ).toByteArray(Charsets.ISO_8859_1),
        )
        out.flush()
        val status = readHeaders(input)
        check(status.startsWith("HTTP/1.1 101")) { "станция не приняла WebSocket: $status" }

        socket = s
        output = out
        reader = Thread({ readLoop(input) }, "Glagol-reader").apply { isDaemon = true; start() }
    }

    fun close() {
        runCatching { socket?.close() }
        socket = null
        output = null
        reader = null
    }

    /** Одна команда протокола. [payload] - то, что в референсе кладут в поле "payload". */
    fun send(payload: JSONObject) {
        val out = output ?: error("нет соединения со станцией")
        val message = JSONObject()
            .put("conversationToken", deviceToken)
            .put("id", java.util.UUID.randomUUID().toString())
            .put("payload", payload)
            .put("sentTime", System.currentTimeMillis())
        synchronized(out) {
            writeTextFrame(out, message.toString())
        }
    }

    private fun readHeaders(input: InputStream): String {
        val line = StringBuilder()
        val all = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) break
            line.append(b.toChar())
            if (line.endsWith("\r\n")) {
                if (line.length == 2) break
                all.append(line)
                line.setLength(0)
            }
        }
        return all.toString()
    }

    private fun readLoop(input: InputStream) {
        try {
            while (socket != null) {
                val text = readTextFrame(input) ?: break
                val state = runCatching { JSONObject(text).optJSONObject("state") }.getOrNull() ?: continue
                val player = state.optJSONObject("playerState") ?: continue
                // progress приходит в секундах с плавающей точкой.
                progressMs = (player.optDouble("progress", -1.0) * 1000).toLong().takeIf { it >= 0 }
            }
        } catch (e: Exception) {
            Log.i(TAG, "соединение со станцией закрылось: ${e.message}")
        }
    }
}

/** Короткоживущий токен конкретной станции. Бросает с человекочитаемым текстом - экран показывает
 * его как есть, потому что "не удалось" без причины тут бесполезно: причин ровно две, протухший
 * OAuth-токен и Яндекс, который прикрыл неофициальный эндпоинт. */
fun fetchGlagolDeviceToken(oauthToken: String, deviceId: String, platform: String): String {
    val url = URL("https://quasar.yandex.net/glagol/token?device_id=$deviceId&platform=$platform")
    val connection = (url.openConnection() as HttpURLConnection).apply {
        setRequestProperty("Authorization", "OAuth $oauthToken")
        connectTimeout = 8000
        readTimeout = 8000
    }
    try {
        val code = connection.responseCode
        val body = if (code in 200..299) {
            connection.inputStream.bufferedReader().use { it.readText() }
        } else {
            connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
        }
        if (code == 401 || code == 403) error("Яндекс ID отклонил токен - войдите заново")
        if (code == 429) error("Яндекс временно ограничил выдачу токенов станции, попробуйте позже")
        check(code in 200..299) { "Яндекс ответил $code на запрос токена станции" }
        val json = JSONObject(body)
        check(json.optString("status") == "ok") { "Яндекс не выдал токен станции: $body" }
        return json.getString("token")
    } finally {
        connection.disconnect()
    }
}

/** Кодирование того самого `externalCommandBypass`: имя директивы в поле 1, JSON-полезная нагрузка
 * в поле 2, всё это protobuf и потом base64. Полноценный protobuf для двух строковых полей не
 * нужен - обе записи это wire type 2 (LEN). Формат взят из protobuf.py/utils.py референса. */
internal fun externalCommand(name: String, payload: JSONObject?): JSONObject = JSONObject()
    .put("command", "externalCommandBypass")
    .put("data", Base64.getEncoder().encodeToString(encodeExternalCommand(name, payload?.toString())))

/** Сама упаковка, без JSON вокруг - вынесена отдельно, чтобы её можно было проверить тестом. */
internal fun encodeExternalCommand(name: String, payloadJson: String?): ByteArray {
    val bytes = mutableListOf<Byte>()
    fun lenField(field: Int, value: String) {
        bytes += ((field shl 3) or 2).toByte()
        val data = value.toByteArray(Charsets.UTF_8)
        var length = data.size
        while (length >= 0x80) {
            bytes += ((length and 0x7F) or 0x80).toByte()
            length = length shr 7
        }
        bytes += length.toByte()
        data.forEach { bytes += it }
    }
    lenField(1, name)
    if (payloadJson != null) lenField(2, payloadJson)
    return bytes.toByteArray()
}

private object TrustAnyCertificate : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

private fun writeTextFrame(out: OutputStream, text: String) {
    val payload = text.toByteArray(Charsets.UTF_8)
    out.write(0x81) // FIN + opcode 1 (текст)
    // Бит маски обязателен для кадров клиент -> сервер, иначе сервер обязан разорвать соединение.
    when {
        payload.size < 126 -> out.write(0x80 or payload.size)
        payload.size <= 0xFFFF -> {
            out.write(0x80 or 126)
            out.write((payload.size shr 8) and 0xFF)
            out.write(payload.size and 0xFF)
        }
        else -> {
            out.write(0x80 or 127)
            for (shift in 56 downTo 0 step 8) out.write((payload.size.toLong() shr shift).toInt() and 0xFF)
        }
    }
    val mask = ByteArray(4).also { SecureRandom().nextBytes(it) }
    out.write(mask)
    out.write(ByteArray(payload.size) { (payload[it].toInt() xor mask[it % 4].toInt()).toByte() })
    out.flush()
}

/** null - соединение закрыто. Кадры не-текст (ping/pong/close/двоичные) пропускаем: станция шлёт
 * только текст, а ping от неё закрытие соединения не требует. */
private fun readTextFrame(input: InputStream): String? {
    while (true) {
        val b0 = input.read()
        if (b0 < 0) return null
        val opcode = b0 and 0x0F
        val b1 = input.read()
        if (b1 < 0) return null
        val masked = (b1 and 0x80) != 0
        var length = (b1 and 0x7F).toLong()
        if (length == 126L) {
            length = ((input.read() shl 8) or input.read()).toLong()
        } else if (length == 127L) {
            length = 0
            repeat(8) { length = (length shl 8) or input.read().toLong() }
        }
        val mask = if (masked) ByteArray(4).also { readFully(input, it) } else null
        val payload = ByteArray(length.toInt()).also { readFully(input, it) }
        if (mask != null) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
        if (opcode == 8) return null
        if (opcode == 1) return String(payload, Charsets.UTF_8)
    }
}

private fun readFully(input: InputStream, buffer: ByteArray) {
    var read = 0
    while (read < buffer.size) {
        val n = input.read(buffer, read, buffer.size - read)
        if (n < 0) throw java.io.EOFException()
        read += n
    }
}
