package dev.nami.player.replaygain

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat

/** Сканер ReplayGain по настоящему ITU-R BS.1770-4 / EBU R128 - K-взвешивание, стробирование и
 * true-peak с 4-кратной передискретизацией (сам алгоритм в [R128Loudness], здесь только декод).
 *
 * Раньше это был простой RMS против -18 dBFS: без K-взвешивания (тихая и громкая части трека
 * считались одинаково значимыми независимо от того, в каких они частотах), без стробирования
 * (паузы занижали измерение) и без true-peak, из-за чего после применения gain реален межсемпловый
 * клиппинг. Контракт остался тот же: Float? в дБ, null при любой неудаче декодирования,
 * результат кэшируется в Track.replayGainDb.
 *
 * Значения, посчитанные старым алгоритмом, обнуляются один раз миграцией базы (Migrations.kt) -
 * пересчёт происходит лениво при следующем воспроизведении трека, как и первый скан. */
object ReplayGainScanner {

    /** Диапазон gain ограничен ±12 дБ - как и у EQ: что-то большее скорее артефакт скана, чем
     * реальная разница сведения. */
    private const val MAX_ABS_GAIN_DB = 12f

    fun scan(path: String): Float? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(path)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            val format = extractor.getTrackFormat(trackIndex)
            extractor.selectTrack(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            var meter: R128Loudness? = null
            var scratch = FloatArray(0)
            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex) ?: continue
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                if (outputIndex >= 0) {
                    if (bufferInfo.size > 0) {
                        val outputBuffer = codec.getOutputBuffer(outputIndex)
                        if (outputBuffer != null) {
                            // Формат берём у самого кодека, а не у экстрактора: частота и число
                            // каналов на выходе декодера могут отличаться (HE-AAC SBR удваивает
                            // частоту), а K-взвешивающий фильтр считается именно под неё.
                            if (meter == null) {
                                val outFormat = codec.outputFormat
                                val rate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                                val channels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                                if (rate <= 0 || channels <= 0) return null
                                meter = R128Loudness(rate, channels)
                            }
                            // ponytail: PCM 16-bit only - это то, что MediaCodec на Android отдаёт
                            // по умолчанию; точности 16 бит для измерения громкости хватает с
                            // огромным запасом (шум квантования на ~96 дБ ниже сигнала).
                            val shortBuffer = outputBuffer.asShortBuffer()
                            val count = shortBuffer.remaining()
                            if (scratch.size < count) scratch = FloatArray(count)
                            for (i in 0 until count) scratch[i] = shortBuffer.get() / 32768f
                            meter.feed(scratch, count)
                        }
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }
                }
            }
            codec.stop()
            codec.release()

            val measured = meter ?: return null
            gainFor(measured.integratedLufs() ?: return null, measured.truePeakDbfs())
        } catch (e: Exception) {
            null
        } finally {
            extractor.release()
        }
    }

    /** Чистая часть решения (вынесена, чтобы её можно было проверить тестом): подгоняем громкость
     * под опорные -18 LUFS, но не даём true-peak после усиления перелезть через -1 dBTP - иначе
     * нормализация сама бы и создала межсемпловый клиппинг. */
    internal fun gainFor(integratedLufs: Double, truePeakDbtp: Double): Float {
        val wanted = R128Loudness.TARGET_LUFS - integratedLufs
        val headroom = R128Loudness.TRUE_PEAK_CEILING_DBTP - truePeakDbtp
        return minOf(wanted, headroom).toFloat().coerceIn(-MAX_ABS_GAIN_DB, MAX_ABS_GAIN_DB)
    }
}
