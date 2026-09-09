package dev.nami.player.output

import android.media.AudioTrack
import androidx.media3.common.AudioAttributes
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import java.util.concurrent.Executors

/** Убирает паузу на гэплесс-границе между треками РАЗНОГО формата (другая частота дискретизации
 * или число каналов).
 *
 * Почему пауза вообще есть. `android.media.AudioTrack` создаётся один раз с фиксированными
 * sampleRate/channelMask/encoding, поменять их на лету нельзя. При смене формата
 * `DefaultAudioSink` обязан закрыть старый трек и построить новый, и строит он его синхронно на
 * потоке воспроизведения (байткод 1.5.0: `handleBuffer` -> `initializeAudioTrack` ->
 * `buildAudioTrack` -> `audioTrackProvider.getAudioTrack(...)`). Сам конструктор `AudioTrack`
 * ходит в AudioFlinger/HAL за новым выходным потоком - это и есть слышимая дырка. Предзагрузка
 * файла тут ни при чём: следующий трек ExoPlayer декодирует заранее, узкое место - трек, не данные.
 *
 * Что делает этот класс. `DefaultAudioSink.AudioTrackProvider` - единственная точка, где синк
 * получает `AudioTrack` (проверено по байткоду: другого места создания нет). Значит второй трек
 * можно держать наготове снаружи, не переписывая `AudioSink` целиком:
 *  - запоминаем конфигурации, которые синк реально просил (в них уже посчитан bufferSize - гадать
 *    его самим не надо);
 *  - как только их стало больше одной, в фоне заранее строим один запасной трек под ту
 *    конфигурацию, которая НЕ играет сейчас;
 *  - когда синк на границе просит трек - если запасной подходит, отдаём готовый, конструктор на
 *    потоке воспроизведения не выполняется.
 *
 * Симметрично вперёд и назад: провайдер не знает и не хочет знать направление перехода, он просто
 * держит горячей "другую" конфигурацию. Переход A -> B и возврат B -> A одинаково берут готовый
 * трек.
 *
 * Замена мгновенная, без кроссфейда: старый трек синк останавливает как обычно, новый начинает
 * писать сразу следующим буфером. Никакого смешивания двух треков тут нет и не появляется - этот
 * класс только убирает ожидание создания, а не меняет момент стыка.
 *
 * Регрессий на обычном пути (треки одного формата) быть не должно: пока конфигурация одна,
 * `warm` всегда null, ничего не строится и не держится, а `getAudioTrack` вырождается в вызов
 * `DEFAULT`. Прогрев идёт на своём потоке и никогда не держит блокировку во время конструктора,
 * так что поток воспроизведения на нём не залипает.
 *
 * ponytail: потолок - первая в сессии смена формата всё равно платит полную цену (конфигурацию
 * ещё не видели, прогревать было нечего), выигрыш начинается со второй. Предсказывать
 * конфигурацию из метаданных очереди можно, но там нужен bufferSize, который считает сам синк -
 * ради одного первого перехода не стоит.
 *
 * ponytail: запасной трек не переводится в play() заранее. Это сняло бы ещё и прогрев HAL, но
 * синк пишет в буфер и на паузе - предварительно запущенный трек тогда зазвучал бы во время
 * паузы. Если замеры покажут, что остаток паузы именно в первом write() - это следующий шаг. */
@UnstableApi
object WarmAudioTrackProvider : DefaultAudioSink.AudioTrackProvider {

    private const val TAG = "WarmAudioTrack"

    internal data class Key(
        val encoding: Int,
        val sampleRate: Int,
        val channelConfig: Int,
        val tunneling: Boolean,
        val offload: Boolean,
        val bufferSize: Int,
        val attributes: AudioAttributes,
        val sessionId: Int,
    )

    private val lock = Any()
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "nami-warm-audiotrack") }

    /** Конфигурации, которые синк уже просил, свежая - последняя. Держим короткий хвост: реальная
     * фонотека это две-три комбинации (44.1/48 стерео), длинная история тут не нужна. */
    private val seen = LinkedHashSet<Key>()

    /** Готовый, ещё ни разу не использованный трек - максимум один на весь процесс. */
    private var warmKey: Key? = null
    private var warmTrack: AudioTrack? = null

    /** Прогрев уже запланирован под эту конфигурацию - чтобы не строить одно и то же дважды. */
    private var pendingKey: Key? = null

    override fun getAudioTrack(
        config: AudioSink.AudioTrackConfig,
        attributes: AudioAttributes,
        sessionId: Int,
    ): AudioTrack {
        val key = key(config, attributes, sessionId)
        val warm = synchronized(lock) {
            observe(key)
            if (warmKey == key) {
                warmTrack.also { warmKey = null; warmTrack = null }
            } else {
                null
            }
        }
        // Трек мог быть создан давно и с тех пор не пережить, например, смену устройства вывода -
        // синк всё равно проверит getState(), но лучше не отдавать заведомо мёртвый.
        val usable = warm?.takeIf { it.state == AudioTrack.STATE_INITIALIZED }
        if (warm != null && usable == null) runCatching { warm.release() }
        scheduleWarm(key)
        return usable ?: DefaultAudioSink.AudioTrackProvider.DEFAULT.getAudioTrack(config, attributes, sessionId)
    }

    /** Запомнить конфигурацию как самую свежую, обрезав хвост истории. */
    internal fun observe(key: Key) {
        seen.remove(key)
        seen.add(key)
        while (seen.size > MAX_SEEN) seen.remove(seen.first())
    }

    /** Что греть при играющей [current]: самая свежая известная конфигурация, кроме неё самой.
     * null - пока формат один, греть нечего и незачем. */
    internal fun warmTarget(current: Key): Key? = seen.lastOrNull { it != current }

    /** Строит про запас трек под самую свежую конфигурацию, отличную от играющей сейчас. */
    private fun scheduleWarm(current: Key) {
        val target = synchronized(lock) {
            val candidate = warmTarget(current) ?: return
            if (warmKey == candidate || pendingKey == candidate) return
            pendingKey = candidate
            candidate
        }
        worker.execute {
            val built = runCatching {
                DefaultAudioSink.AudioTrackProvider.DEFAULT.getAudioTrack(
                    AudioSink.AudioTrackConfig(
                        target.encoding,
                        target.sampleRate,
                        target.channelConfig,
                        target.tunneling,
                        target.offload,
                        target.bufferSize,
                    ),
                    target.attributes,
                    target.sessionId,
                )
            }.getOrElse {
                Log.w(TAG, "не удалось прогреть AudioTrack под $target", it)
                null
            }?.takeIf { it.state == AudioTrack.STATE_INITIALIZED }
            val stale = synchronized(lock) {
                if (pendingKey == target) pendingKey = null
                val previous = warmTrack
                if (built != null) {
                    warmKey = target
                    warmTrack = built
                }
                previous
            }
            runCatching { stale?.release() }
        }
    }

    /** Отпустить запасной трек - он держит открытым выходной поток, а после остановки сервиса
     * держать его незачем. */
    fun release() {
        val stale = synchronized(lock) {
            seen.clear()
            pendingKey = null
            warmKey = null
            warmTrack.also { warmTrack = null }
        }
        runCatching { stale?.release() }
    }

    private fun key(config: AudioSink.AudioTrackConfig, attributes: AudioAttributes, sessionId: Int) =
        Key(
            encoding = config.encoding,
            sampleRate = config.sampleRate,
            channelConfig = config.channelConfig,
            tunneling = config.tunneling,
            offload = config.offload,
            bufferSize = config.bufferSize,
            attributes = attributes,
            sessionId = sessionId,
        )

    private const val MAX_SEEN = 4
}
