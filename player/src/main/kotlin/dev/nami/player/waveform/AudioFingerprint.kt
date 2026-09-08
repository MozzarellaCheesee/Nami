package dev.nami.player.waveform

/** П.md §23.19 "поиск дублей по звуку" - компактный 64-битный хеш RMS-огибающей трека.
 *
 * Своего декодера здесь нет намеренно: [WaveformScanner] уже прогоняет файл через
 * MediaExtractor/MediaCodec и сводит его к 120 значениям RMS по времени - ровно та огибающая,
 * которая нужна. Второй почти такой же цикл декодирования ради тех же чисел был бы копией
 * сотни строк, которая начнёт расходиться с оригиналом на первом же исправлении.
 *
 * Хеш строится на ЗНАКАХ разностей соседних точек, а не на самих уровнях: огибающая нормируется
 * к собственному максимуму трека, поэтому разная громкость, битрейт и формат одного и того же
 * исполнения дают почти одинаковую форму, а абсолютные значения - нет. Две записи разных
 * исполнений той же песни (живьём/студия) разойдутся по форме и не совпадут - это ожидаемо:
 * ищутся дубли файлов, а не каверы. */
object AudioFingerprint {

    /** Столько бит в отпечатке - ровно Long, чтобы хранить одной колонкой и сравнивать одним
     * xor, без массивов и своей сериализации. */
    const val BITS = 64

    /** Порог совпадения по Хэммингу. 6 бит из 64 - примерно 10% формы: перекодирование в другой
     * битрейт двигает несколько границ корзин, а разные треки расходятся куда сильнее. */
    const val MATCH_THRESHOLD = 6

    /** Null на любой ошибке декодирования (как и у [WaveformScanner]) - вызывающий просто
     * оставляет трек без отпечатка и сравнивает его по метаданным, как раньше. */
    fun compute(path: String): Long? {
        val bars = WaveformScanner.scan(path) ?: return null
        return fromEnvelope(bars)
    }

    /** Отдельно от [compute], чтобы логика сворачивания огибающей в биты тестировалась без
     * Android-декодера. */
    fun fromEnvelope(bars: List<Float>): Long? {
        if (bars.size < BITS + 1) return null
        var fingerprint = 0L
        // BITS+1 равномерно взятых точек огибающей дают BITS разностей между соседями.
        val step = (bars.size - 1).toDouble() / BITS
        for (bit in 0 until BITS) {
            val current = bars[(bit * step).toInt()]
            val next = bars[((bit + 1) * step).toInt()]
            if (next > current) fingerprint = fingerprint or (1L shl bit)
        }
        return fingerprint
    }

    /** Сколько бит различается. java.lang.Long.bitCount - одна инструкция на большинстве машин,
     * своего цикла по битам не нужно. */
    fun distance(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    fun matches(a: Long, b: Long): Boolean = distance(a, b) <= MATCH_THRESHOLD
}
