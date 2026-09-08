package dev.nami.app.tile

import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dagger.hilt.android.AndroidEntryPoint
import dev.nami.app.R
import dev.nami.domain.PlaybackState
import dev.nami.domain.PlayerRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Общий на процесс, а не на сервис: система убивает TileService почти сразу после того как
 * шторка закрылась, и тап (awaitReady() + toggle(), это заметно дольше нуля) успевал отмениться
 * вместе с сервисом - плитка выглядела как "нажал, ничего не произошло". Подписка на состояние -
 * наоборот, живёт ровно пока сервис слушает (см. onStartListening/onStopListening): с ACTIVE_TILE
 * в манифесте это окно открывает PlayerRepositoryImpl.startTileNudges на реальную смену состояния,
 * а не только пока шторка на экране. */
internal val tileClickScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

/** План.md §28 "Плитка быстрых настроек" - играть/пауза прямо из шторки. Ничего не играет?
 * Тап всё равно работает: PlayerRepositoryImpl на холодном старте сам восстанавливает последнюю
 * очередь (на паузе), так что toggle() после awaitReady() продолжит её. */
@AndroidEntryPoint
class PlayPauseTileService : TileService() {

    @Inject lateinit var playerRepository: PlayerRepository

    private var listenJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        listenJob = tileClickScope.launch {
            playerRepository.awaitReady()
            combine(playerRepository.state, playerRepository.queue) { state, queue ->
                ((state as? PlaybackState.Playing)?.isPlaying == true) to queue.nowPlaying
            }.collect { (isPlaying, nowPlaying) ->
                val tile = qsTile ?: return@collect
                tile.state = if (isPlaying) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                tile.icon = Icon.createWithResource(
                    this@PlayPauseTileService,
                    if (isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = nowPlaying?.title
                }
                tile.updateTile()
            }
        }
    }

    override fun onStopListening() {
        listenJob?.cancel()
        listenJob = null
        super.onStopListening()
    }

    override fun onClick() {
        tileClickScope.launch {
            playerRepository.awaitReady()
            playerRepository.toggle()
        }
    }
}
