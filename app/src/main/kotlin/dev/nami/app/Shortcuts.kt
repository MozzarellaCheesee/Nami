package dev.nami.app

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.nami.core.model.TrackId
import dev.nami.data.AppSettingsRepository
import dev.nami.domain.LibraryRepository
import dev.nami.domain.PlayableTrack
import dev.nami.domain.PlayerRepository
import dev.nami.domain.PlaylistRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Какой ярлык нажали - MainActivity получает его как extra, дальше см. [ShortcutsViewModel.handle]. */
const val EXTRA_SHORTCUT_ID = "dev.nami.app.SHORTCUT_ID"

private const val ID_RESUME = "resume"
private const val ID_RANDOM = "random"
private const val ID_SEARCH = "search"
private const val ID_PLAYLIST = "playlist"

/** Не маршрут NavHost'а: Now Playing живёт оверлеем над текущим экраном (см. showNowPlaying в
 * NamiNavHost), а не отдельным destination'ом - это сигнал открыть тот оверлей. */
const val SHORTCUT_TARGET_NOW_PLAYING = "now_playing"

/** План.md §28 "Ярлыки приложения (долгий тап по иконке)" - динамические, а не статические в
 * манифесте: подписи и цель зависят от истории (последний трек, последний плейлист).
 *
 * Обновляются один раз за запуск приложения (см. вызов [refresh] в NamiNavHost). Живое обновление
 * по ходу прослушивания было бы избыточно - лаунчер всё равно перечитывает ярлыки лениво, а не
 * мгновенно. */
@HiltViewModel
class ShortcutsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playerRepository: PlayerRepository,
    private val libraryRepository: LibraryRepository,
    private val playlistRepository: PlaylistRepository,
    private val settingsRepository: AppSettingsRepository,
) : ViewModel() {

    fun refresh() {
        viewModelScope.launch {
            val lastTrackTitle = runCatching { lastPlayedTrackTitle() }.getOrNull()
            val lastPlaylist = runCatching { playlistRepository.recentPlaylists(1).firstOrNull() }.getOrNull()

            val shortcuts = buildList {
                add(
                    shortcut(
                        id = ID_RESUME,
                        short = "Продолжить",
                        long = lastTrackTitle ?: "Продолжить прослушивание",
                        icon = R.drawable.ic_shortcut_resume,
                    ),
                )
                add(
                    shortcut(
                        id = ID_RANDOM,
                        short = "Случайный трек",
                        long = "Случайный трек из библиотеки",
                        icon = R.drawable.ic_shortcut_random,
                    ),
                )
                add(shortcut(id = ID_SEARCH, short = "Поиск", long = "Поиск по библиотеке", icon = R.drawable.ic_shortcut_search))
                // Плейлиста может не быть вовсе - тогда ярлыков просто три, а не четвёртый,
                // ведущий в никуда.
                lastPlaylist?.let {
                    add(
                        shortcut(
                            id = "$ID_PLAYLIST:${it.id.value}",
                            short = it.name,
                            long = "Плейлист «${it.name}»",
                            icon = R.drawable.ic_shortcut_playlist,
                        ),
                    )
                }
            }
            // setDynamicShortcuts, а не add: список каждый раз пересобирается целиком, иначе
            // ярлык на удалённый плейлист остался бы висеть навсегда.
            runCatching { ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts) }
        }
    }

    /** Возвращает, куда перейти после нажатия ярлыка: маршрут NavHost'а,
     * [SHORTCUT_TARGET_NOW_PLAYING] или null, если переход не нужен. Воспроизведение (если ярлык
     * его подразумевает) запускается прямо здесь, не дожидаясь навигации. */
    fun handle(shortcutId: String): String? = when {
        shortcutId == ID_RESUME -> {
            viewModelScope.launch {
                // PlayerRepositoryImpl на холодном старте сам восстанавливает последнюю очередь
                // (на паузе) - остаётся снять её с паузы. Если уже играет, toggle() не трогаем.
                playerRepository.awaitReady()
                if (playerRepository.queue.value.nowPlaying != null && !isPlayingNow()) {
                    playerRepository.toggle()
                }
            }
            SHORTCUT_TARGET_NOW_PLAYING
        }
        shortcutId == ID_RANDOM -> {
            viewModelScope.launch {
                val track = libraryRepository.allTracksOrdered().randomOrNull() ?: return@launch
                playerRepository.play(
                    listOf(
                        PlayableTrack(
                            id = track.id,
                            title = track.title,
                            artistName = track.artistName,
                            path = track.path,
                            artworkPath = track.albumArtworkPath,
                            format = track.format,
                            durationMs = track.durationMs,
                            cueStartMs = track.cueStartMs,
                            cueEndMs = track.cueEndMs,
                        ),
                    ),
                    startIndex = 0,
                )
            }
            SHORTCUT_TARGET_NOW_PLAYING
        }
        shortcutId == ID_SEARCH -> "search"
        shortcutId.startsWith("$ID_PLAYLIST:") -> "playlist/${shortcutId.substringAfter(':')}"
        else -> null
    }

    private fun isPlayingNow(): Boolean =
        (playerRepository.state.value as? dev.nami.domain.PlaybackState.Playing)?.isPlaying == true

    private suspend fun lastPlayedTrackTitle(): String? {
        val ids = settingsRepository.lastPlaybackQueueTrackIds.value
        val id = ids.getOrNull(settingsRepository.lastPlaybackQueueIndex.value) ?: return null
        return libraryRepository.track(TrackId(id)).first()?.title
    }

    private fun shortcut(id: String, short: String, long: String, icon: Int): ShortcutInfoCompat =
        ShortcutInfoCompat.Builder(context, id)
            .setShortLabel(short)
            .setLongLabel(long)
            .setIcon(IconCompat.createWithResource(context, icon))
            .setIntent(
                Intent(Intent.ACTION_VIEW).apply {
                    setClassName(context.packageName, "dev.nami.app.MainActivity")
                    putExtra(EXTRA_SHORTCUT_ID, id)
                },
            )
            .build()
}
