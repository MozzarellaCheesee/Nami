package dev.nami.player.remote

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL

/** Порт AirPlay-приёмника, фиксирован протоколом. */
private const val AIRPLAY_PORT = 7000

/**
 * AirPlay (Beta). Что здесь реально сделано и, главное, чего здесь НЕТ:
 *
 * Сделано - HTTP-профиль AirPlay v1 (порт 7000, обнаружение mDNS `_airplay._tcp`): POST /play с
 * Content-Location, /rate, /stop, /scrub. Его понимает Apple TV. Реализовано вручную, потому что
 * найденная планом библиотека `open-airplay` (github.com/openairplay/open-airplay) артефактом не
 * публикуется вообще: это один файл AirPlay.java со сборкой на Ant, ни Maven Central, ни JitPack.
 * Тянуть её исходником ради четырёх HTTP-запросов дороже, чем написать те же четыре запроса -
 * протокольная часть, которую она даёт, целиком помещается в этот файл.
 *
 * НЕ сделано и не будет здесь - RAOP/RTSP (AirPlay-аудио): HomePod, AirPort Express и AirPlay-
 * колонки говорят ТОЛЬКО на нём и по этому HTTP-профилю не отзовутся. RAOP - это RTSP-сессия с
 * шифрованием, ключом Apple и упаковкой звука в ALAC-пакеты; отдельный по объёму труд, который
 * план прямо запрещает начинать походя. Итог для пользователя: Apple TV работает, AirPlay-колонки
 * не поддерживаются.
 *
 * Приёмник скачивает файл сам, поэтому раздаёт его тот же LocalHttpServer, что и для Cast/DLNA.
 */
class AirPlayTransport(
    override val device: RemoteDevice,
) : RemoteTransport {

    private val base = "http://${device.host}:${device.port.takeIf { it > 0 } ?: AIRPLAY_PORT}"
    private var durationSec = 0.0

    override suspend fun load(url: String, title: String, artist: String?, mime: String, durationMs: Long) {
        durationSec = durationMs / 1000.0
        // text/parameters - формат, который требует именно этот эндпоинт: не JSON и не форма.
        val body = "Content-Location: $url\r\nStart-Position: 0\r\n"
        val code = post("/play", body, "text/parameters")
        check(code in 200..299) { "${device.name} ответил $code на /play" }
    }

    override suspend fun play() { post("/rate?value=1") }

    override suspend fun pause() { post("/rate?value=0") }

    override suspend fun seek(positionMs: Long) { post("/scrub?position=${positionMs / 1000.0}") }

    override suspend fun stop() { post("/stop") }

    override suspend fun positionMs(): Long? = runCatching {
        // GET /scrub отдаёт "duration: N\nposition: M" - позиция вторым полем.
        get("/scrub")
            ?.lineSequence()
            ?.firstOrNull { it.startsWith("position:") }
            ?.substringAfter(':')?.trim()?.toDoubleOrNull()
            ?.let { (it * 1000).toLong() }
    }.getOrNull()

    private fun post(path: String, body: String? = null, contentType: String? = null): Int =
        open(path, "POST").run {
            if (body != null) {
                contentType?.let { setRequestProperty("Content-Type", it) }
                doOutput = true
                outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = responseCode
            disconnect()
            code
        }

    private fun get(path: String): String? = open(path, "GET").run {
        val text = if (responseCode in 200..299) inputStream.bufferedReader().use { it.readText() } else null
        disconnect()
        text
    }

    private fun open(path: String, method: String): HttpURLConnection =
        (URL("$base$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 4000
            readTimeout = 4000
            // Apple TV отвечает 403 на запросы без своего User-Agent - это единственная "защита"
            // в этом профиле протокола.
            setRequestProperty("User-Agent", "MediaControl/1.0")
        }
}

/** Обнаружение Apple TV. Создаётся только при включённом тумблере "устройства Apple (Beta)" -
 * выключенный тумблер означает, что сеть на AirPlay не сканируется вовсе. */
class AirPlayDiscovery(private val context: Context) : RemoteDiscovery {
    override val kind = RemoteKind.AIRPLAY

    override suspend fun search(): List<RemoteDevice> =
        nsdScan(context, "_airplay._tcp").map { entry ->
            RemoteDevice(
                id = "airplay:${entry.host}:${entry.port}",
                name = entry.name,
                kind = RemoteKind.AIRPLAY,
                host = entry.host,
                port = entry.port,
            )
        }

    override fun transportFor(device: RemoteDevice): RemoteTransport = AirPlayTransport(device)
}
