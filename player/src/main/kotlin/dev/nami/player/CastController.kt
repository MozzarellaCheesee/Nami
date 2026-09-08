package dev.nami.player

import android.content.Context
import android.util.Log
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.google.android.gms.cast.framework.CastContext
import dev.nami.core.model.Track

private const val TAG = "CastController"

/** Отдельный от Wi-Fi Drop (47821) порт: раздача на телевизор и раздача на второй телефон могут
 * идти одновременно, один ServerSocket на двоих сделал бы их взаимоисключающими. */
private const val CAST_HTTP_PORT = 47822

/**
 * Chromecast. Про исключение из правила "никаких Google Play Services" - см. libs.versions.toml.
 *
 * Как это работает: [CastPlayer] из официального media3-cast - это такой же [Player], как локальный
 * ExoPlayer, поэтому переключение "играем на телефоне / играем на телевизоре" сводится к подмене
 * плеера у MediaSession ([onActivePlayerChanged]). Вся остальная система (MediaController в
 * PlayerRepositoryImpl, уведомление, виджеты, экран Now Playing) продолжает работать без единой
 * правки: она разговаривает с сессией, а не с конкретным плеером.
 *
 * ВАЖНО, ожидаемое поведение, не баг: пока идёт трансляция, весь DSP-тракт Nami (эквалайзер,
 * ReplayGain, dither, кроссфейд, bit-perfect) не даёт эффекта - файл декодирует и выводит сам
 * Chromecast, телефон его только раздаёт по HTTP. Физически иначе и быть не может. По той же
 * причине не работает и CUE-нарезка (ClippingConfiguration): приёмник играет файл целиком.
 *
 * Библиотека локальная, а Chromecast умеет только скачивать по сети - поэтому на время сессии
 * поднимается тот же [LocalHttpServer], что уже раздаёт треки для Wi-Fi Drop, и MediaItem'ам
 * подменяется uri на http://<ip телефона>/track/<id>. Формат отдаём как есть, без перекодирования:
 * что приёмник не умеет (обычно это ALAC, DSD, экзотические кодеки), то просто не заиграет.
 */
class CastController(
    private val context: Context,
    private val localPlayer: () -> ExoPlayer,
    private val trackByIdBlocking: (String) -> Track?,
    private val onActivePlayerChanged: (Player) -> Unit,
) {
    /** Пока true, владелец не должен сам переставлять плеер сессии (см. PlaybackService.swapPlayer). */
    var isCasting = false
        private set

    private var castPlayer: CastPlayer? = null
    private var server: LocalHttpServer? = null

    /** runCatching: на устройстве может не быть Google Play Services вообще (AOSP-прошивки,
     * китайские сборки) - тогда Cast просто недоступен, а плеер обязан работать как раньше. */
    fun start() {
        val castContext = runCatching { CastContext.getSharedInstance(context) }
            .onFailure { Log.i(TAG, "Cast недоступен: ${it.message}") }
            .getOrNull() ?: return
        val cast = CastPlayer(castContext)
        castPlayer = cast
        cast.setSessionAvailabilityListener(
            object : SessionAvailabilityListener {
                override fun onCastSessionAvailable() = beginCasting(cast)
                override fun onCastSessionUnavailable() = endCasting(cast)
            },
        )
    }

    fun release() {
        server?.stop()
        server = null
        // Сессии нужно вернуть локальный плеер до release(): иначе она осталась бы держать уже
        // освобождённый CastPlayer, а настоящий ExoPlayer никто бы не закрыл.
        if (isCasting) onActivePlayerChanged(localPlayer())
        castPlayer?.setSessionAvailabilityListener(null)
        castPlayer?.release()
        castPlayer = null
        isCasting = false
    }

    private fun beginCasting(cast: CastPlayer) {
        val local = localPlayer()
        val items = (0 until local.mediaItemCount).map { local.getMediaItemAt(it) }
        val index = local.currentMediaItemIndex.takeIf { it != C.INDEX_UNSET } ?: 0
        val position = local.currentPosition
        // Локальный звук глушим сразу: две копии одного трека (в комнате и из телефона) - это то,
        // что пользователь слышит как эхо, а не как "трансляцию".
        local.pause()

        val address = localIpAddress()
        server?.stop()
        val fresh = LocalHttpServer(port = CAST_HTTP_PORT, trackByIdBlocking = trackByIdBlocking)
        try {
            fresh.start()
        } catch (e: Exception) {
            Log.w(TAG, "не удалось поднять раздачу для Cast: ${e.message}")
            return
        }
        server = fresh

        isCasting = true
        if (items.isNotEmpty()) {
            cast.setMediaItems(items.map { it.forCast("http://$address:$CAST_HTTP_PORT") }, index, position)
            cast.playWhenReady = true
        }
        onActivePlayerChanged(cast)
    }

    private fun endCasting(cast: CastPlayer) {
        val index = cast.currentMediaItemIndex
        val position = cast.currentPosition
        isCasting = false
        server?.stop()
        server = null
        onActivePlayerChanged(localPlayer())
        // Позицию с телевизора переносим обратно на телефон, но НЕ возобновляем воспроизведение:
        // "отключился от телевизора" - это чаще "закончил слушать", чем "продолжаю в динамик".
        val local = localPlayer()
        if (index != C.INDEX_UNSET && index < local.mediaItemCount) local.seekTo(index, position)
        cast.clearMediaItems()
    }

    /** mimeType обязателен: DefaultMediaItemConverter внутри CastPlayer без него падает, а сам
     * приёмник по нему выбирает декодер. */
    private fun MediaItem.forCast(base: String): MediaItem = buildUpon()
        .setUri("$base/track/$mediaId")
        .setMimeType(castMimeType(localConfiguration?.uri?.lastPathSegment))
        .build()
}

private fun castMimeType(fileName: String?): String =
    when (fileName?.substringAfterLast('.', "")?.lowercase()) {
        "mp3" -> MimeTypes.AUDIO_MPEG
        "m4a", "mp4", "aac", "alac" -> MimeTypes.AUDIO_MP4
        "flac" -> MimeTypes.AUDIO_FLAC
        "wav" -> MimeTypes.AUDIO_WAV
        "ogg", "oga", "opus" -> MimeTypes.AUDIO_OGG
        else -> MimeTypes.AUDIO_UNKNOWN
    }
