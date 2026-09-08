package dev.nami.player

import android.content.Context
import android.net.wifi.WifiManager
import android.text.format.Formatter
import android.util.Log
import dev.nami.core.model.Track
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket

private const val TAG = "LocalHttpServer"

/** Группа G "сеть" - минимальный HTTP/1.1 сервер на голых сокетах, GET-only. Ни одна из
 * стандартных Java HTTP-сереверных API (com.sun.net.httpserver) не входит в Android SDK, а
 * тянуть Ktor/NanoHTTPD ради четырёх ручек - overkill. Один accept-поток, обработка запроса в
 * отдельном потоке (локальная сеть, запросов единицы одновременно).
 *
 * Лежит в :player, а не в :data (где живёт его первый потребитель, Wi-Fi Drop): Cast тоже раздаёт
 * им локальные файлы на телевизор, а :data зависит от :player, не наоборот - обратная зависимость
 * дала бы цикл. Все ручки кроме /track опциональны, Cast использует только её. */
class LocalHttpServer(
    private val port: Int,
    private val trackByIdBlocking: (String) -> Track?,
    private val deviceName: String = "NAMI",
    private val nowPlayingJsonBlocking: () -> JSONObject? = { null },
    private val dropTrackBlocking: () -> Track? = { null },
    private val manifestJsonBlocking: () -> JSONObject = { JSONObject() },
) {
    private var serverSocket: ServerSocket? = null

    fun start() {
        val socket = ServerSocket(port)
        serverSocket = socket
        Thread({
            while (!socket.isClosed) {
                try {
                    val client = socket.accept()
                    Thread({ handleClient(client) }, "LocalHttpServer-client").start()
                } catch (e: IOException) {
                    break // socket closed via stop()
                }
            }
        }, "LocalHttpServer-accept").apply { isDaemon = true }.start()
    }

    fun stop() {
        try {
            serverSocket?.close()
        } catch (e: IOException) {
            // Already closed - fine.
        }
        serverSocket = null
    }

    private fun handleClient(socket: Socket) {
        socket.use { s ->
            try {
                val input = BufferedReader(InputStreamReader(s.getInputStream()))
                val requestLine = input.readLine() ?: return
                // Из заголовков нужен только Range (Chromecast качает файл кусками и без 206 не
                // умеет перематывать); остальные ручки заголовков не читают, но поток дочитать надо.
                var range: String? = null
                while (true) {
                    val line = input.readLine()
                    if (line.isNullOrEmpty()) break
                    if (line.startsWith("Range:", ignoreCase = true)) range = line.substringAfter(':').trim()
                }
                val path = requestLine.split(" ").getOrNull(1) ?: return
                val output = s.getOutputStream()
                when {
                    path == "/info" -> writeJson(output, JSONObject().put("name", deviceName))
                    path == "/nowplaying" -> {
                        val json = nowPlayingJsonBlocking()
                        if (json != null) writeJson(output, json) else writeStatus(output, 204)
                    }
                    path == "/manifest" -> writeJson(output, manifestJsonBlocking())
                    path == "/drop" -> serveTrack(output, dropTrackBlocking(), range)
                    path.startsWith("/track/") -> serveTrack(output, trackByIdBlocking(path.removePrefix("/track/")), range)
                    else -> writeStatus(output, 404)
                }
            } catch (e: Exception) {
                Log.w(TAG, "request failed: ${e.message}")
            }
        }
    }

    private fun serveTrack(output: OutputStream, track: Track?, range: String?) {
        if (track == null) {
            writeStatus(output, 404)
            return
        }
        val file = File(track.path)
        if (!file.exists()) {
            writeStatus(output, 404)
            return
        }
        val total = file.length()
        val requested = parseByteRange(range, total)
        val partial = requested != null
        val from = requested?.first ?: 0L
        val to = requested?.last ?: (total - 1)
        val length = to - from + 1
        val header = buildString {
            append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
            append("Content-Type: application/octet-stream\r\n")
            append("Accept-Ranges: bytes\r\n")
            append("Content-Length: $length\r\n")
            if (partial) append("Content-Range: bytes $from-$to/$total\r\n")
            append("X-Original-Filename: ${file.name}\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(header.toByteArray(Charsets.UTF_8))
        file.inputStream().use { stream ->
            stream.skip(from)
            val buffer = ByteArray(64 * 1024)
            var left = length
            while (left > 0) {
                val read = stream.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                if (read <= 0) break
                output.write(buffer, 0, read)
                left -= read
            }
        }
        output.flush()
    }

    private fun writeJson(output: OutputStream, json: JSONObject) {
        val body = json.toString().toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n\r\n"
        output.write(header.toByteArray(Charsets.UTF_8))
        output.write(body)
        output.flush()
    }

    private fun writeStatus(output: OutputStream, code: Int) {
        val text = if (code == 404) "Not Found" else if (code == 204) "No Content" else "Error"
        output.write("HTTP/1.1 $code $text\r\nConnection: close\r\n\r\n".toByteArray(Charsets.UTF_8))
        output.flush()
    }
}

/** "bytes=NNN-" и "bytes=NNN-MMM" (единственные формы, которые шлют Chromecast и браузеры) в
 * готовые границы включительно. null означает "отдать файл целиком, 200" - в том числе для всего,
 * что не разобралось, и для Wi-Fi Drop, который Range вообще не шлёт. Multipart-диапазоны
 * ("bytes=0-99,200-299") не поддерживаем: их не запрашивает ни один реальный клиент этих ручек. */
internal fun parseByteRange(header: String?, total: Long): LongRange? {
    if (header == null || total <= 0) return null
    val spec = header.substringAfter("bytes=", "").ifEmpty { return null }
    val from = spec.substringBefore('-').toLongOrNull() ?: return null
    if (from < 0 || from >= total) return null
    val to = spec.substringAfter('-', "").toLongOrNull()?.coerceAtMost(total - 1) ?: (total - 1)
    if (to < from) return null
    return from..to
}

/** Адрес телефона в текущей Wi-Fi сети - то, что должен позвать другой участник ЛВС (телевизор с
 * Chromecast, второй телефон). "0.0.0.0" означает "Wi-Fi нет", раздавать нечего. */
fun localIpAddress(context: Context): String {
    val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    val ipInt = wifiManager?.connectionInfo?.ipAddress ?: 0
    return if (ipInt != 0) Formatter.formatIpAddress(ipInt) else "0.0.0.0"
}
