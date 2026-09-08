package dev.nami.app.tile

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dagger.hilt.android.AndroidEntryPoint
import dev.nami.domain.PlayerRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Из тех же пресетов, что в SleepTimerSheet (15/30/45/60) - плитка не выбирает, а берёт
 * середину: у неё один жест, а не список. */
private const val DEFAULT_MINUTES = 30

/** План.md §28 "отдельная плитка таймера сна" - тап включает таймер на [DEFAULT_MINUTES] минут,
 * повторный тап выключает. Подзаголовок показывает сколько осталось. */
@AndroidEntryPoint
class SleepTimerTileService : TileService() {

    @Inject lateinit var playerRepository: PlayerRepository

    private var listenJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        listenJob = tileClickScope.launch {
            playerRepository.sleepTimerRemainingMs.collect { remainingMs ->
                val tile = qsTile ?: return@collect
                tile.state = if (remainingMs != null) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = remainingMs?.let { formatRemaining(it) } ?: "$DEFAULT_MINUTES мин"
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
            if (playerRepository.sleepTimerRemainingMs.value != null) {
                playerRepository.cancelSleepTimer()
            } else {
                playerRepository.startSleepTimer(DEFAULT_MINUTES * 60_000L)
            }
        }
    }
}

/** Секунды в подзаголовке плитки были бы шумом (она обновляется раз в секунду, а видна редко) -
 * округляем вверх до минуты, "1 мин" держится до самого конца. */
internal fun formatRemaining(ms: Long): String = "${(ms + 59_999L) / 60_000L} мин"
