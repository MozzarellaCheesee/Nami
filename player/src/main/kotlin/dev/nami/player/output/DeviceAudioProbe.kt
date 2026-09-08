package dev.nami.player.output

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build

/** П.md §11 "Тест устройства" - что текущий выход реально принимает.
 *
 * Проверяем постройкой настоящего [AudioTrack], а не через getMinBufferSize: последний отвечает
 * за то, что формат в принципе описуем, и бодро возвращает размер буфера для комбинаций, на
 * которых конструктор потом падает. Построенный трек с STATE_INITIALIZED - единственный ответ,
 * которому можно верить, потому что это ровно тот объект, через который пойдёт звук.
 *
 * Честно про то, чего этот тест НЕ показывает. AudioTrack описывает вход микшера Android, а не
 * то, что уедет в железо: AudioFlinger молча пересчитает 192 кГц в 48, если выход работает на 48,
 * и трек всё равно построится. То есть «поддерживается» здесь значит «приложение может это
 * отправить», а не «ЦАП получит это без пересчёта». Настоящую частоту выхода без bit-perfect
 * (см. BitPerfectUsbController) Android приложению не сообщает вовсе.
 *
 * Поэтому результат и хранится просто строкой для показа пользователю: автоматически выбирать по
 * нему частоту было бы решением на основе данных, которые этого не выдерживают. */
object DeviceAudioProbe {

    private val SAMPLE_RATES = intArrayOf(44100, 48000, 88200, 96000, 176400, 192000)

    /** Человекочитаемая таблица - ровно то, что уходит в SettingsRepository.deviceAudioProfile. */
    fun probe(): String {
        val encodings = buildList {
            add("16 бит" to AudioFormat.ENCODING_PCM_16BIT)
            add("float32" to AudioFormat.ENCODING_PCM_FLOAT)
            // 24- и 32-битные целочисленные кодировки появились только в Android 12.
            if (Build.VERSION.SDK_INT >= 31) {
                add("24 бит" to AudioFormat.ENCODING_PCM_24BIT_PACKED)
                add("32 бит" to AudioFormat.ENCODING_PCM_32BIT)
            }
        }

        val lines = SAMPLE_RATES.map { rate ->
            val supported = encodings.filter { (_, encoding) -> canOpen(rate, encoding) }.map { it.first }
            val khz = if (rate % 1000 == 0) "${rate / 1000}" else String.format("%.1f", rate / 1000f)
            if (supported.isEmpty()) "$khz кГц - нет" else "$khz кГц - ${supported.joinToString(", ")}"
        }
        return lines.joinToString("\n")
    }

    private fun canOpen(sampleRate: Int, encoding: Int): Boolean {
        val channelMask = AudioFormat.CHANNEL_OUT_STEREO
        val minBuffer = try {
            AudioTrack.getMinBufferSize(sampleRate, channelMask, encoding)
        } catch (e: Exception) {
            return false
        }
        if (minBuffer <= 0) return false

        var track: AudioTrack? = null
        return try {
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(encoding)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelMask)
                        .build(),
                )
                .setBufferSizeInBytes(minBuffer)
                .build()
            track.state == AudioTrack.STATE_INITIALIZED
        } catch (e: Exception) {
            // Неподдерживаемая комбинация - это именно исключение из конструктора, а не код
            // возврата, так что ловить обязательно: иначе тест падал бы на первой же дырке.
            false
        } finally {
            // Каждый успешный трек держит канал микшера. Не освободив, следующие проверки в этом
            // же прогоне начали бы падать по исчерпанию ресурсов, а не по неподдерживаемости.
            try {
                track?.release()
            } catch (e: Exception) {
                // release() на недостроенном треке - не повод ронять тест.
            }
        }
    }
}
