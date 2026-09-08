package dev.nami.player.remote

/** Экосистема приёмника. Google Cast здесь нет намеренно: его закрывает media3-cast со своим
 * готовым [androidx.media3.cast.CastPlayer] (см. CastController), переизобретать его через
 * [RemotePlayer] незачем. */
enum class RemoteKind {
    DLNA,
    /** Beta: неофициальный путь, включается тумблером в настройках. */
    AIRPLAY,
    /** Beta: неофициальный протокол Glagol, включается тумблером и требует входа в Яндекс ID. */
    YANDEX,
}

/** Найденный в локальной сети приёмник. [id] стабилен в пределах сессии поиска - по нему
 * дедуплицируются повторные ответы SSDP/mDNS. */
data class RemoteDevice(
    val id: String,
    val name: String,
    val kind: RemoteKind,
    val host: String = "",
    val port: Int = 0,
    /** Что нужно конкретному протоколу сверх адреса: у Станции - deviceId/platform из mDNS-TXT. */
    val extras: Map<String, String> = emptyMap(),
) {
    val isBeta: Boolean get() = kind != RemoteKind.DLNA
}

/**
 * Минимум, который умеет любой приёмник: взять URL и играть. Три реализации (DLNA/AirPlay/
 * Станция) - именно поэтому это интерфейс, а не одна конкретная реализация.
 *
 * Все методы вызываются из корутины на IO и вправе бросать - [RemotePlayer] ловит и переводит
 * в ошибку плеера, а не роняет процесс.
 */
interface RemoteTransport {
    val device: RemoteDevice

    /** Начать играть [url] с нуля. [mime] нужен DLNA (DIDL-Lite), остальным - для расширения. */
    suspend fun load(url: String, title: String, artist: String?, mime: String, durationMs: Long)

    suspend fun play()
    suspend fun pause()
    suspend fun seek(positionMs: Long)
    suspend fun stop()

    /** Позиция на приёмнике или null, если протокол её не отдаёт - тогда [RemotePlayer] считает
     * её сам по часам. */
    suspend fun positionMs(): Long? = null

    /** 0..1. По умолчанию нечего делать: у приёмника своя ручка громкости. */
    suspend fun setVolume(volume: Float) = Unit

    fun release() = Unit
}
