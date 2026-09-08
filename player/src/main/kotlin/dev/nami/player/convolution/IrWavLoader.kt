package dev.nami.player.convolution

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/** Импульсная характеристика, уже разобранная в сэмплы. [channels] - по массиву на канал, значения
 * нормализованы (см. [IrWavLoader]). */
data class ImpulseResponse(
    val sampleRateHz: Int,
    val channels: List<FloatArray>,
) {
    val lengthSamples: Int get() = channels.firstOrNull()?.size ?: 0
}

/** Разбор WAV с импульсом. Свой, а не через MediaExtractor, по двум причинам: IR почти всегда
 * лежит несжатым WAV (это формат, в котором их публикуют), а MediaExtractor пришлось бы гонять
 * асинхронно и он всё равно не отдаёт 24-битный и float-PCM без потерь - именно в них
 * распространяются приличные импульсы.
 *
 * Поддержано: RIFF/WAVE, PCM целочисленный 8/16/24/32 бита и IEEE float 32/64 бита, любое число
 * каналов. Не поддержано сознательно: RF64 (файлы >4 ГБ - для импульса бессмысленно), сжатые
 * кодеки внутри WAV (ADPCM/mp3-in-wav - для IR не встречаются), WAVE_FORMAT_EXTENSIBLE разбирается
 * только по под-формату в первых двух байтах GUID, чего хватает для всех реальных файлов.
 *
 * Файл читается целиком в память - осознанно: [MAX_SAMPLES_PER_CHANNEL] ограничивает импульс
 * десятью секундами на канал, а это верхняя граница даже для соборов. */
object IrWavLoader {

    /** Дальше десяти секунд IR не бывает, а вот кривой/битый заголовок легко объявляет длину в
     * гигабайты - это и потолок по памяти, и защита от такого файла. */
    const val MAX_SAMPLES_PER_CHANNEL = 10 * 192_000

    /** null - файл не WAV, битый или пустой. Молча: выбор IR это действие пользователя в
     * настройках, а не то, что должно ронять воспроизведение. Вызывающий показывает ошибку сам. */
    fun load(file: File): ImpulseResponse? = runCatching { parse(file.readBytes()) }.getOrNull()

    private fun parse(bytes: ByteArray): ImpulseResponse? {
        if (bytes.size < 44) return null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (buffer.int != RIFF) return null
        buffer.int // общий размер, не нужен - реальную длину знаем из bytes.size
        if (buffer.int != WAVE) return null

        var audioFormat = 0
        var channelCount = 0
        var sampleRate = 0
        var bitsPerSample = 0
        var dataOffset = -1
        var dataSize = 0

        // Чанки идут подряд и в произвольном порядке, между fmt и data может стоять что угодно
        // (LIST с описанием, cue, fact) - поэтому обходим их, а не читаем по фиксированным
        // смещениям, как позволяет «канонический» 44-байтный заголовок.
        while (buffer.remaining() >= 8) {
            val chunkId = buffer.int
            val chunkSize = buffer.int
            if (chunkSize < 0 || chunkSize > buffer.remaining()) {
                // Последний чанк с обрезанным/навравшим размером - для data это ещё пригодно,
                // берём сколько реально есть.
                if (chunkId == DATA) {
                    dataOffset = buffer.position()
                    dataSize = buffer.remaining()
                }
                break
            }
            val chunkStart = buffer.position()
            when (chunkId) {
                FMT -> {
                    audioFormat = buffer.short.toInt() and 0xFFFF
                    channelCount = buffer.short.toInt() and 0xFFFF
                    sampleRate = buffer.int
                    buffer.int // byte rate
                    buffer.short // block align
                    bitsPerSample = buffer.short.toInt() and 0xFFFF
                    // WAVE_FORMAT_EXTENSIBLE: настоящий формат лежит в начале GUID подформата.
                    if (audioFormat == FORMAT_EXTENSIBLE && chunkSize >= 40) {
                        buffer.position(chunkStart + 24)
                        audioFormat = buffer.short.toInt() and 0xFFFF
                    }
                }
                DATA -> {
                    dataOffset = chunkStart
                    dataSize = chunkSize
                }
            }
            // Чанки выровнены по чётной границе - нечётный размер добивается одним байтом.
            buffer.position(chunkStart + chunkSize + (chunkSize and 1))
        }

        if (dataOffset < 0 || channelCount <= 0 || sampleRate <= 0) return null
        val bytesPerSample = bitsPerSample / 8
        if (bytesPerSample <= 0) return null
        val frameSize = bytesPerSample * channelCount
        val frameCount = (dataSize / frameSize).coerceAtMost(MAX_SAMPLES_PER_CHANNEL)
        if (frameCount <= 0) return null

        val channels = List(channelCount) { FloatArray(frameCount) }
        val data = ByteBuffer.wrap(bytes, dataOffset, frameCount * frameSize).order(ByteOrder.LITTLE_ENDIAN)
        for (frame in 0 until frameCount) {
            for (channel in 0 until channelCount) {
                val value = when {
                    audioFormat == FORMAT_FLOAT && bitsPerSample == 32 -> data.float
                    audioFormat == FORMAT_FLOAT && bitsPerSample == 64 -> data.double.toFloat()
                    bitsPerSample == 8 -> ((data.get().toInt() and 0xFF) - 128) / 128f // 8-битный WAV беззнаковый
                    bitsPerSample == 16 -> data.short / 32768f
                    bitsPerSample == 24 -> {
                        val b0 = data.get().toInt() and 0xFF
                        val b1 = data.get().toInt() and 0xFF
                        val b2 = data.get().toInt()
                        // b2 со знаком - сдвиг влево на 16 сохраняет знак 24-битного числа.
                        ((b2 shl 16) or (b1 shl 8) or b0) / 8388608f
                    }
                    bitsPerSample == 32 -> data.int / 2147483648f
                    else -> return null
                }
                channels[channel][frame] = value
            }
        }
        return ImpulseResponse(sampleRate, channels).normalized()
    }

    /** Приводим импульс к единичной энергии. Без этого громкость после свёртки целиком зависит от
     * того, как автор нормализовал файл: один и тот же трек с двумя импульсами звучал бы то тише
     * на 20 дБ, то в клиппинге. Нормируем по суммарной энергии всех каналов сразу, а не поканально
     * - поканальная нормировка развалила бы стереобаланс несимметричного импульса. */
    private fun ImpulseResponse.normalized(): ImpulseResponse? {
        var energy = 0.0
        channels.forEach { channel -> channel.forEach { energy += it.toDouble() * it } }
        if (energy <= 0.0) return null // тишина вместо импульса - свёртка дала бы полную тишину
        val scale = (1.0 / sqrt(energy / channels.size)).toFloat()
        channels.forEach { channel ->
            for (i in channel.indices) channel[i] *= scale
        }
        return this
    }

    // 'RIFF', 'WAVE', 'fmt ', 'data' как little-endian int - буфер читается LE, поэтому байты
    // разложены в обратном порядке.
    private const val RIFF = 0x46464952
    private const val WAVE = 0x45564157
    private const val FMT = 0x20746D66
    private const val DATA = 0x61746164
    private const val FORMAT_FLOAT = 3
    private const val FORMAT_EXTENSIBLE = 0xFFFE
}
