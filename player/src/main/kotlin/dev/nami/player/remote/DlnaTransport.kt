package dev.nami.player.remote

import android.content.Context
import com.yinnho.upnpcast.CastOptions
import com.yinnho.upnpcast.DLNACast

/**
 * DLNA/UPnP - открытый стандарт без привязки к Google/Apple, покрывает большинство "обычных"
 * смарт-телевизоров и ТВ-приставок. Вся протокольная часть (SSDP-поиск, AVTransport и
 * RenderingControl по SOAP) - в библиотеке UPnPCast, живой Kotlin-замене заброшенного Cling.
 *
 * Файл раздаём своим [dev.nami.player.LocalHttpServer] (порт 47822, тот же, что у Cast), а не
 * встроенным в UPnPCast файловым сервером: второй сервер на те же файлы не нужен, а наш уже умеет
 * Range/206 и осмысленные имена.
 */
class DlnaTransport(
    context: Context,
    override val device: RemoteDevice,
    private val upnpDevice: DLNACast.Device,
) : RemoteTransport {

    init {
        // Идемпотентно: init() внутри библиотеки пересоздаёт движок, повторный вызов безопасен.
        DLNACast.init(context.applicationContext)
    }

    override suspend fun load(url: String, title: String, artist: String?, mime: String, durationMs: Long) {
        val ok = DLNACast.castToDevice(
            device = upnpDevice,
            url = url,
            title = listOfNotNull(artist, title).joinToString(" - "),
            // upnpClass обязателен: без него телевизор считает поток видео и рисует чёрный экран
            // вместо экрана "играет музыка".
            options = CastOptions(mimeType = mime, upnpClass = "object.item.audioItem.musicTrack"),
        )
        check(ok) { "приёмник ${device.name} отказался играть $url" }
        DLNACast.clearProgressCache()
    }

    override suspend fun play() { DLNACast.play() }

    override suspend fun pause() { DLNACast.pause() }

    override suspend fun seek(positionMs: Long) { DLNACast.seek(positionMs) }

    override suspend fun stop() { DLNACast.stop() }

    override suspend fun positionMs(): Long? = DLNACast.getProgress()?.first

    override suspend fun setVolume(volume: Float) {
        DLNACast.setVolume((volume.coerceIn(0f, 1f) * 100).toInt())
    }

    override fun release() = DLNACast.cleanup()
}
