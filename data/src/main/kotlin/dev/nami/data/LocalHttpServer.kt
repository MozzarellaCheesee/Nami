package dev.nami.data

import android.util.Log
import dev.nami.core.model.Track
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
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
 * отдельном потоке (локальная сеть, запросов единицы одновременно). */
class LocalHttpServer(
    private val port: Int,
    private val deviceName: String,
    private val trackByIdBlocking: (String) -> Track?,
    private val nowPlayingJsonBlocking: () -> JSONObject?,
    private val dropTrackBlocking: () -> Track?,
    private val manifestJsonBlocking: () -> JSONObject,
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
                // Drain headers - none of the four routes below need them.
                while (true) {
                    val line = input.readLine()
                    if (line.isNullOrEmpty()) break
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
                    path == "/drop" -> serveTrack(output, dropTrackBlocking())
                    path.startsWith("/track/") -> serveTrack(output, trackByIdBlocking(path.removePrefix("/track/")))
                    else -> writeStatus(output, 404)
                }
            } catch (e: Exception) {
                Log.w(TAG, "request failed: ${e.message}")
            }
        }
    }

    private fun serveTrack(output: OutputStream, track: Track?) {
        if (track == null) {
            writeStatus(output, 404)
            return
        }
        val file = File(track.path)
        if (!file.exists()) {
            writeStatus(output, 404)
            return
        }
        val fileName = file.name
        val header = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: application/octet-stream\r\n" +
            "Content-Length: ${file.length()}\r\n" +
            "X-Original-Filename: $fileName\r\n" +
            "Connection: close\r\n\r\n"
        output.write(header.toByteArray(Charsets.UTF_8))
        file.inputStream().use { it.copyTo(output) }
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

/** Small helper so callers building [nowPlayingJsonBlocking]-style blocking closures can run a
 * suspend repository call from the plain background thread the server handles requests on. */
fun <T> blocking(block: suspend () -> T): T = runBlocking { block() }
