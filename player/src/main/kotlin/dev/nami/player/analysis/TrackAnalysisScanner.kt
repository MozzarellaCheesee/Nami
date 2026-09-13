package dev.nami.player.analysis

import dev.nami.core.model.TrackId
import dev.nami.domain.LibraryRepository
import dev.nami.domain.ServerAudioRepository
import dev.nami.player.waveform.WaveformScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * BPM, тональность и форма волны для трека - один раз и навсегда.
 *
 * Откуда считать, решает наличие файла, а не вид трека:
 * - у серверного трека локального файла нет вовсе, и посчитать самим нечем. Раньше это и было
 *   причиной, по которой у таких треков анализ не появлялся никогда: [BpmKeyAnalyzer] получал
 *   путь вида `nami-server://42`, молча возвращал null, и следующее проигрывание начинало всё
 *   заново. Теперь расчёт заказывается серверу (`POST /api/tracks/{id}/analyze`).
 * - у локального файла считаем на устройстве, в сеть не ходим.
 *
 * Сервер спрашивается первым и для локального файла тоже: если он этот трек уже считал, взять
 * готовое дешевле, чем декодировать сто мегабайт на телефоне.
 *
 * Результат пишется на трек, а не в кеш по пути: путь меняется, когда скачанный серверный трек
 * становится локальным, и посчитанное сервером иначе терялось бы при каждом таком переезде.
 */
@Singleton
class TrackAnalysisScanner @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val serverAudioRepository: ServerAudioRepository,
) {
    // Трек может прийти сюда сразу с двух сторон: со старта воспроизведения и с окончания
    // скачивания. Без замка оба запустили бы полный декод одного и того же файла.
    private val inFlight = mutableSetOf<String>()
    private val guard = Mutex()

    suspend fun scanIfMissing(trackId: TrackId) {
        val track = libraryRepository.track(trackId).first() ?: return
        // Все три значения пишутся вместе. Требование "все на месте", а не "хоть одно", не даёт
        // застрять навсегда в состоянии, когда одна из оценок когда-то не удалась.
        if (track.bpm != null && track.musicalKey != null && track.waveform != null) return

        guard.withLock { if (!inFlight.add(trackId.value)) return }
        try {
            val server = serverAudioRepository
                .takeIf { it.isServerActive() }
                ?.requestServerAnalysis(track.artistName, track.title, track.durationMs)

            var bpm = server?.bpm
            var key = server?.musicalKey
            var bars = server?.waveform

            // На сервере трека может не быть, или ffmpeg там мог не справиться. Тогда считаем
            // сами - но только если есть что декодировать.
            val localFile = File(track.path).takeIf { it.isAbsolute && it.exists() }
            if (localFile != null && (bpm == null || key == null)) {
                val local = withContext(Dispatchers.Default) { BpmKeyAnalyzer.scan(track.path) }
                bpm = bpm ?: local.bpm
                key = key ?: local.musicalKey
            }
            if (localFile != null && bars == null) {
                bars = withContext(Dispatchers.Default) { WaveformScanner.scan(track.path) }
            }

            if (bpm != null || key != null) libraryRepository.setTrackBpmKey(trackId, bpm, key)
            // Пустой список тоже сохраняем: он означает "пробовали, не вышло" и избавляет от
            // повторного полного декода при каждом следующем запуске трека.
            if (bars != null) libraryRepository.setTrackWaveform(trackId, bars)
        } finally {
            guard.withLock { inFlight -= trackId.value }
        }
    }
}
