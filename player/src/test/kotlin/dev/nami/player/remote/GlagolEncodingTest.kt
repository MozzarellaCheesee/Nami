package dev.nami.player.remote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Единственное место в клиенте Станции, где ошибка не видна ни в логах, ни в UI: неправильно
 * упакованную директиву станция молча выбрасывает. Отсюда - разбор кодировки обратно.
 */
class GlagolEncodingTest {

    @Test
    fun `имя директивы и полезная нагрузка кодируются полями 1 и 2`() {
        val encoded = encodeExternalCommand("audio_play", """{"url":"x"}""")
        val fields = decodeLenFields(encoded)
        assertEquals("audio_play", fields[1])
        assertEquals("""{"url":"x"}""", fields[2])
    }

    @Test
    fun `директива без нагрузки состоит из одного поля`() {
        val fields = decodeLenFields(encodeExternalCommand("sound_louder", null))
        assertEquals(mapOf(1 to "sound_louder"), fields)
    }

    @Test
    fun `длина больше 127 байт кодируется многобайтовым varint`() {
        val payload = "\"" + "a".repeat(300) + "\""
        val fields = decodeLenFields(encodeExternalCommand("audio_play", payload))
        assertEquals(payload, fields[2])
        // Один байт длины хватило бы только до 127 - иначе тест выше прошёл бы и на сломанном varint.
        assertTrue(payload.length > 127)
    }

    /** Минимальный разбор protobuf'а обратно: только wire type 2 (LEN), больше здесь не бывает. */
    private fun decodeLenFields(bytes: ByteArray): Map<Int, String> {
        val result = mutableMapOf<Int, String>()
        var i = 0
        while (i < bytes.size) {
            val tag = bytes[i].toInt() and 0xFF
            i++
            assertEquals(2, tag and 0b111, "ожидался wire type 2")
            var length = 0
            var shift = 0
            while (true) {
                val b = bytes[i].toInt() and 0xFF
                i++
                length = length or ((b and 0x7F) shl shift)
                if (b and 0x80 == 0) break
                shift += 7
            }
            result[tag shr 3] = String(bytes, i, length, Charsets.UTF_8)
            i += length
        }
        return result
    }
}
