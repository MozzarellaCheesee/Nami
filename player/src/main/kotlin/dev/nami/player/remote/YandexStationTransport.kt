package dev.nami.player.remote

import android.content.Context
import dev.nami.domain.SettingsRepository
import org.json.JSONObject

/**
 * Яндекс Станция (Beta) поверх локального протокола Glagol - см. [GlagolClient] про источник
 * формата команд и про то, почему проверка сертификата выключена.
 *
 * Станция скачивает файл сама, поэтому раздаётся он тем же [dev.nami.player.LocalHttpServer], что
 * и для Cast/DLNA/AirPlay. Ссылка обязана быть с расширением файла - без него станция молча
 * игнорирует директиву (см. extensionOf в RemoteCastController и обработку /track в LocalHttpServer).
 */
class YandexStationTransport(
    override val device: RemoteDevice,
    private val oauthToken: String,
) : RemoteTransport {

    private var client: GlagolClient? = null
    /** Последняя отправленная директива воспроизведения. Нужна для возобновления: на локальный
     * поток станция отвечает на "play" не всегда, референс в этом случае шлёт audio_play заново. */
    private var lastStream: JSONObject? = null
    private var lastPositionMs = 0L

    private fun connected(): GlagolClient = client ?: run {
        val deviceId = device.extras["deviceId"] ?: error("${device.name}: станция не сообщила deviceId")
        val platform = device.extras["platform"].orEmpty()
        val token = fetchGlagolDeviceToken(oauthToken, deviceId, platform)
        GlagolClient(device.host, device.port, token).also {
            it.connect()
            client = it
        }
    }

    override suspend fun load(url: String, title: String, artist: String?, mime: String, durationMs: Long) {
        val stream = JSONObject()
            .put("url", url)
            // Кодек станция определяет по содержимому, format обязан быть лишь допустимым
            // значением перечисления - при неизвестном она отбрасывает директиву, не сходив по
            // ссылке. "Track" вместо "FmRadio" даёт полосу прогресса и имя исполнителя.
            .put("format", "MP3")
            .put("type", "Track")
        val metadata = JSONObject().put("title", title)
        if (!artist.isNullOrBlank()) metadata.put("subtitle", artist)
        val payload = JSONObject().put("stream", stream).put("metadata", metadata)
        lastStream = payload
        lastPositionMs = 0
        connected().send(externalCommand("audio_play", payload))
    }

    override suspend fun play() {
        val client = connected()
        val stream = lastStream
        if (stream != null) {
            // ponytail: возобновление локального потока - это повторная audio_play с offset_ms.
            // Потолок: станция перезапрашивает файл целиком, то есть пауза длиннее пары секунд
            // стоит нового HTTP-запроса. Дешевле, чем держать своё состояние на её стороне.
            stream.getJSONObject("stream").put("offset_ms", lastPositionMs)
            client.send(externalCommand("audio_play", stream))
        } else {
            client.send(JSONObject().put("command", "play"))
        }
    }

    /** У Glagol пауза называется stop - "выключить" в этом протоколе отдельной команды не имеет. */
    override suspend fun pause() {
        lastPositionMs = client?.progressMs ?: lastPositionMs
        connected().send(JSONObject().put("command", "stop"))
    }

    override suspend fun seek(positionMs: Long) {
        lastPositionMs = positionMs
        connected().send(JSONObject().put("command", "rewind").put("position", positionMs / 1000))
    }

    override suspend fun stop() {
        runCatching { client?.send(JSONObject().put("command", "stop")) }
        client?.close()
        client = null
        lastStream = null
    }

    override suspend fun positionMs(): Long? = client?.progressMs?.also { lastPositionMs = it }

    override suspend fun setVolume(volume: Float) {
        // Станция округляет громкость до десятых - отдаём сразу в её шкале 0..1.
        connected().send(
            JSONObject().put("command", "setVolume").put("volume", Math.round(volume.coerceIn(0f, 1f) * 10) / 10.0),
        )
    }

    override fun release() {
        client?.close()
        client = null
    }
}

/** Создаётся только при включённом тумблере "Яндекс Станция (Beta)" - выключенный тумблер
 * означает, что mDNS на `_yandexio._tcp` не запускается вообще. */
class YandexStationDiscovery(
    private val context: Context,
    private val settings: SettingsRepository,
) : RemoteDiscovery {
    override val kind = RemoteKind.YANDEX

    override suspend fun search(): List<RemoteDevice> =
        nsdScan(context, "_yandexio._tcp").mapNotNull { entry ->
            val deviceId = entry.attributes["deviceId"] ?: return@mapNotNull null
            RemoteDevice(
                id = "yandex:$deviceId",
                name = entry.name,
                kind = RemoteKind.YANDEX,
                host = entry.host,
                port = entry.port,
                extras = mapOf("deviceId" to deviceId, "platform" to entry.attributes["platform"].orEmpty()),
            )
        }

    override fun transportFor(device: RemoteDevice): RemoteTransport {
        val token = settings.yandexOAuthToken.value
        check(!token.isNullOrBlank()) { "Войдите в Яндекс ID в настройках плеера" }
        return YandexStationTransport(device, token)
    }
}
