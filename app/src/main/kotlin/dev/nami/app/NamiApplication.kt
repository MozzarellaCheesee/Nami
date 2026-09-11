package dev.nami.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import dev.nami.app.widget.updateAllNamiWidgets
import dev.nami.domain.PlaybackState
import dev.nami.domain.PlayerRepository
import dev.nami.domain.TrashRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class NamiApplication : Application() {

    @Inject lateinit var trashRepository: TrashRepository
    @Inject lateinit var playerRepository: PlayerRepository

    override fun onCreate() {
        super.onCreate()
        // ponytail: fire-and-forget startup sweep, not a scheduled job — see the plan's
        // "purge mechanism" note for why WorkManager is out of scope for now.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { trashRepository.purgeExpired() }
        }
        // Держим все виджеты рабочего стола в актуальном состоянии при любых изменениях
        // воспроизведения, очереди, shuffle из приложения, уведомлений, Bluetooth или таймера сна.
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            combine(playerRepository.state, playerRepository.queue, playerRepository.shuffleEnabled) { state, queue, shuffle ->
                Triple((state as? PlaybackState.Playing)?.isPlaying, queue.nowPlaying?.id, shuffle)
            }
                .distinctUntilChanged()
                .collect {
                    runCatching { updateAllNamiWidgets(this@NamiApplication) }
                }
        }
    }
}
