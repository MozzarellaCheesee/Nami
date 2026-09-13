package dev.nami.player.analysis

import dev.nami.core.model.TrackId
import dev.nami.domain.LibraryRepository
import dev.nami.domain.ServerAudioRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * BPM/тональность (План.md §3), кешируются на треке раз и навсегда - повторно не считаются.
 * Раньше жило приватным методом в PlaybackService и запускалось только при начале
 * воспроизведения, поэтому у только что скачанного (и ещё не сыгранного) трека анализ
 * не появлялся сразу. Вынесено сюда, чтобы им мог пользоваться и PlaybackService, и код
 * скачивания серверных треков (data-модуль от player не зависит, поэтому вызов идёт из
 * feature-слоя, который на player уже ссылается).
 */
@Singleton
class BpmKeyScanner @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val serverAudioRepository: ServerAudioRepository,
) {
    suspend fun scanIfMissing(trackId: TrackId) {
        val track = libraryRepository.track(trackId).first() ?: return
        // Оба поля пишутся всегда вместе - требование "оба присутствуют", а не "хотя бы одно",
        // не даёт застрять навсегда в состоянии, когда одна из оценок когда-то не удалась.
        if (track.bpm != null && track.musicalKey != null) return
        val server =
            serverAudioRepository.serverAnalysis(track.artistName, track.title, track.durationMs)
        val bpm: Float?
        val key: String?
        if (server?.bpm != null || server?.musicalKey != null) {
            bpm = server.bpm
            key = server.musicalKey
        } else {
            val result = withContext(Dispatchers.Default) {
                BpmKeyAnalyzer.scan(track.path)
            }
            bpm = result.bpm
            key = result.musicalKey
        }
        if (bpm != null || key != null) {
            libraryRepository.setTrackBpmKey(trackId, bpm, key)
        }
    }
}
