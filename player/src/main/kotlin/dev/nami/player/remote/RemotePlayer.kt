package dev.nami.player.remote

import android.os.Looper
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "RemotePlayer"

/**
 * Приёмник (DLNA-телевизор, Apple TV, Яндекс Станция) в виде обычного [Player] - ровно та же
 * роль, что у `CastPlayer` из media3-cast для Chromecast. Благодаря этому "играем на телефоне /
 * играем на телевизоре" остаётся подменой плеера у MediaSession, а уведомление, виджеты, Now
 * Playing и MediaController продолжают работать без единой правки: они разговаривают с сессией.
 *
 * ВАЖНО, ожидаемое поведение, не баг (то же самое, что уже написано в CastController): пока идёт
 * трансляция, весь DSP-тракт Nami - эквалайзер, ReplayGain, dither, кроссфид, свёртка, кроссфейд,
 * bit-perfect - не даёт эффекта. Файл декодирует и выводит сам приёмник, телефон только раздаёт
 * его по HTTP. Физически иначе быть не может. По той же причине не работает CUE-нарезка: приёмник
 * играет файл целиком.
 *
 * ponytail: очередь ведём сами, а состояние приёмника опрашиваем раз в секунду (DLNA умеет
 * GetPositionInfo, AirPlay - /scrub, Станция шлёт статус сама). Потолок - секундная зернистость
 * прогресса и переход на следующий трек по достижении длительности, а не по событию "трек
 * кончился" (его в этих протоколах либо нет, либо оно ненадёжно). Если понадобится точнее -
 * подписываться на UPnP LastChange-события вместо опроса.
 */
@UnstableApi
class RemotePlayer(
    private val transport: RemoteTransport,
    private val urlForItem: (MediaItem) -> String?,
    private val mimeForItem: (MediaItem) -> String,
    private val durationMsForItem: (MediaItem) -> Long,
    /** Приёмник отключился сам (сеть, выключили телевизор) - владелец возвращает локальный плеер. */
    private val onDisconnected: () -> Unit,
) : SimpleBasePlayer(Looper.getMainLooper()) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var ticker: Job? = null

    private var items: List<MediaItem> = emptyList()
    private var index = 0
    private var positionMs = 0L
    private var playWhenReadyValue = false
    private var state = Player.STATE_IDLE
    private var volume = 1f
    private var released = false

    private val commands = Player.Commands.Builder()
        .addAll(
            COMMAND_PLAY_PAUSE,
            COMMAND_PREPARE,
            COMMAND_STOP,
            COMMAND_SEEK_TO_DEFAULT_POSITION,
            COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            COMMAND_SEEK_TO_PREVIOUS,
            COMMAND_SEEK_TO_NEXT,
            COMMAND_SEEK_TO_MEDIA_ITEM,
            COMMAND_SET_MEDIA_ITEM,
            COMMAND_CHANGE_MEDIA_ITEMS,
            COMMAND_GET_CURRENT_MEDIA_ITEM,
            COMMAND_GET_TIMELINE,
            COMMAND_GET_METADATA,
            COMMAND_SET_VOLUME,
            COMMAND_RELEASE,
        )
        .build()

    override fun getState(): State {
        val playlist = items.mapIndexed { i, item ->
            MediaItemData.Builder(item.mediaId.ifEmpty { "item-$i" })
                .setMediaItem(item)
                .setMediaMetadata(item.mediaMetadata)
                .setDurationUs(durationMsForItem(item).takeIf { it > 0 }?.times(1000) ?: androidx.media3.common.C.TIME_UNSET)
                .setIsSeekable(true)
                .setIsDynamic(false)
                .build()
        }
        return State.Builder()
            .setAvailableCommands(commands)
            .setPlaybackState(state)
            .setPlayWhenReady(playWhenReadyValue, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaylist(playlist)
            .setCurrentMediaItemIndex(index.coerceIn(0, maxOf(0, playlist.size - 1)))
            .setContentPositionMs(positionMs)
            .setVolume(volume)
            .build()
    }

    /** Точка входа владельца: отдать очередь и позицию, снятые с локального плеера. */
    fun startWith(queue: List<MediaItem>, startIndex: Int, startPositionMs: Long) {
        items = queue
        index = startIndex.coerceIn(0, maxOf(0, queue.size - 1))
        positionMs = startPositionMs
        playWhenReadyValue = true
        state = Player.STATE_BUFFERING
        invalidateState()
        loadCurrent(seekTo = startPositionMs)
    }

    override fun handleSetMediaItems(mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
        items = mediaItems.toList()
        index = if (startIndex == androidx.media3.common.C.INDEX_UNSET) 0 else startIndex
        positionMs = if (startPositionMs == androidx.media3.common.C.TIME_UNSET) 0L else startPositionMs
        loadCurrent(seekTo = positionMs)
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        if (state == Player.STATE_IDLE) state = Player.STATE_BUFFERING
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        playWhenReadyValue = playWhenReady
        run { if (playWhenReady) transport.play() else transport.pause() }
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val target = if (positionMs == androidx.media3.common.C.TIME_UNSET) 0L else positionMs
        if (mediaItemIndex != index) {
            index = mediaItemIndex.coerceIn(0, maxOf(0, items.size - 1))
            this.positionMs = target
            loadCurrent(seekTo = target)
        } else {
            this.positionMs = target
            run { transport.seek(target) }
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        playWhenReadyValue = false
        state = Player.STATE_IDLE
        stopTicker()
        run { transport.stop() }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetVolume(volume: Float): ListenableFuture<*> {
        this.volume = volume
        run { transport.setVolume(volume) }
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        released = true
        stopTicker()
        // Приёмник надо явно остановить: иначе телевизор продолжит играть уже закрытый нами файл.
        run { transport.stop() }
        transport.release()
        scope.cancel()
        return Futures.immediateVoidFuture()
    }

    private fun loadCurrent(seekTo: Long) {
        val item = items.getOrNull(index)
        if (item == null) {
            state = Player.STATE_ENDED
            invalidateState()
            return
        }
        val url = urlForItem(item)
        if (url == null) {
            Log.w(TAG, "нет URL для ${item.mediaId} - пропускаем трек")
            advance()
            return
        }
        state = Player.STATE_BUFFERING
        invalidateState()
        scope.launch {
            val ok = runCatching {
                withContext(Dispatchers.IO) {
                    transport.load(
                        url = url,
                        title = item.mediaMetadata.title?.toString() ?: "NAMI",
                        artist = item.mediaMetadata.artist?.toString(),
                        mime = mimeForItem(item),
                        durationMs = durationMsForItem(item),
                    )
                    if (seekTo > 0) transport.seek(seekTo)
                    if (playWhenReadyValue) transport.play()
                }
            }.onFailure { Log.w(TAG, "load упал: ${it.message}") }.isSuccess
            if (!ok) {
                // Молча замереть хуже, чем вернуть звук на телефон: пользователь иначе видит
                // "играет" и тишину в комнате.
                onDisconnected()
                return@launch
            }
            positionMs = seekTo
            state = Player.STATE_READY
            invalidateState()
            startTicker()
        }
    }

    private fun advance() {
        if (index + 1 < items.size) {
            index++
            positionMs = 0
            loadCurrent(seekTo = 0)
        } else {
            playWhenReadyValue = false
            state = Player.STATE_ENDED
            stopTicker()
            invalidateState()
        }
    }

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (!released) {
                delay(1000)
                if (!playWhenReadyValue || state != Player.STATE_READY) continue
                val reported = runCatching { withContext(Dispatchers.IO) { transport.positionMs() } }.getOrNull()
                positionMs = reported ?: (positionMs + 1000)
                val duration = items.getOrNull(index)?.let(durationMsForItem) ?: 0L
                // 1500 мс запаса: приёмники отдают позицию с секундной точностью и почти никогда
                // не доезжают ровно до длительности - без запаса очередь вставала на последней секунде.
                if (duration > 0 && positionMs >= duration - 1500) {
                    advance()
                } else {
                    invalidateState()
                }
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    /** Выстрелить командой на приёмник и забыть: media3 ждёт от handle* только "принято", а
     * ошибка сети на паузе не должна ронять UI - её увидит следующий опрос позиции. */
    private fun run(block: suspend RemoteTransport.() -> Unit) {
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { transport.block() } }
                .onFailure { Log.w(TAG, "команда приёмнику не прошла: ${it.message}") }
            invalidateState()
        }
    }
}

/** Заголовки/DIDL-Lite приёмников хотят конкретный MIME, "как-нибудь" не работает. */
internal fun mimeForFileName(fileName: String?): String =
    when (fileName?.substringAfterLast('.', "")?.lowercase()) {
        "mp3" -> "audio/mpeg"
        "m4a", "mp4", "aac", "alac" -> "audio/mp4"
        "flac" -> "audio/flac"
        "wav" -> "audio/wav"
        "ogg", "oga", "opus" -> "audio/ogg"
        else -> "audio/mpeg"
    }

