package dev.nami.player.dsd

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Парсер контейнера Philips DSDIFF (.dff) — формат хранения 1-битного DSD-аудио.
 *
 * Спецификация DSDIFF (Direct Stream Digital Interchange File Format):
 * Контейнер на основе IFF (Big-Endian):
 * - Magic: "FRM8" (4 байта) + 8 байт размера файла + "DSD " (4 байта).
 * - Чанк "FVER": 4 байта размера + 4 байта версии формата (например, 0x01050000).
 * - Чанк "PROP": свойства потока (содержит подтип "SND "):
 *     - "FS  ": частота дискретизации (4 байта, Big-Endian, 2822400 для DSD64, 5644800 для DSD128 и т.д.).
 *     - "CHNL": количество каналов (2 байта Big-Endian, 2 для стерео).
 *     - "CMPR": тип сжатия (4 байта, "DSD " для несжатого 1-битного DSD).
 * - Чанк "DSD ": сырые DSD сэмплы. В несжатом DSDIFF биты семплов упакованы по 1 байту
 *   на канал поочерёдно (ch0 byte, ch1 byte, ch0 byte, ch1 byte, ...), MSB-first.
 *
 * Возвращает [DsfAudio] (с деинтерливингом по каналам), готовый для передачи в [DopEncoder].
 */
object DffParser {
    private const val HEADER_SIZE = 16 // "FRM8" + size (8) + "DSD "

    fun parse(bytes: ByteArray): DsfAudio? {
        if (bytes.size < 40) return null
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)

        // Проверяем корневой чанк FRM8 и маркер DSD
        if (!magicAt(bytes, 0, "FRM8")) return null
        if (!magicAt(bytes, 12, "DSD ")) return null

        var sampleRateHz = 0
        var channelCount = 0
        var compressionType = ""
        var dsdDataOffset = -1
        var dsdDataSize = 0L

        var offset = HEADER_SIZE
        while (offset + 12 <= bytes.size) {
            val chunkId = String(bytes, offset, 4, Charsets.US_ASCII)
            val chunkSize = buf.getLong(offset + 4)
            val chunkDataOffset = offset + 12

            if (chunkSize < 0 || chunkDataOffset + chunkSize > bytes.size) {
                // Если чанк выходит за границы, пробуем обработать оставшиеся данные
                if (chunkId == "DSD ") {
                    dsdDataOffset = chunkDataOffset
                    dsdDataSize = (bytes.size - chunkDataOffset).toLong()
                }
                break
            }

            when (chunkId) {
                "PROP" -> {
                    // Чанк PROP содержит 4 байта типа ("SND ") и вложенные чанки
                    if (chunkSize >= 4 && magicAt(bytes, chunkDataOffset, "SND ")) {
                        parsePropChunk(bytes, chunkDataOffset + 4, (chunkSize - 4).toInt(), buf) { fs, ch, cmpr ->
                            if (fs > 0) sampleRateHz = fs
                            if (ch > 0) channelCount = ch
                            if (cmpr.isNotEmpty()) compressionType = cmpr
                        }
                    }
                }
                "DSD " -> {
                    dsdDataOffset = chunkDataOffset
                    dsdDataSize = chunkSize
                }
            }

            // Выравнивание чанков IFF по 2 байтам
            val paddedSize = if (chunkSize % 2 != 0L) chunkSize + 1 else chunkSize
            offset = chunkDataOffset + paddedSize.toInt()
        }

        if (sampleRateHz <= 0 || channelCount <= 0 || dsdDataOffset < 0 || dsdDataSize <= 0) {
            return null
        }

        // Поддерживается только несжатый DSD ("DSD " или пустой/неуказанный тип)
        if (compressionType.isNotEmpty() && compressionType != "DSD ") {
            return null
        }

        val totalBytes = minOf(dsdDataSize, (bytes.size - dsdDataOffset).toLong()).toInt()
        val bytesPerChannel = totalBytes / channelCount
        if (bytesPerChannel <= 0) return null

        val channelData = List(channelCount) { ByteArray(bytesPerChannel) }

        // Деинтерливинг: в DFF байты каналов чередуются (ch0, ch1, ch0, ch1...)
        var srcPos = dsdDataOffset
        for (i in 0 until bytesPerChannel) {
            for (ch in 0 until channelCount) {
                channelData[ch][i] = bytes[srcPos++]
            }
        }

        return DsfAudio(
            sampleRateHz = sampleRateHz,
            channelCount = channelCount,
            dsdBytesPerChannel = channelData,
        )
    }

    private inline fun parsePropChunk(
        bytes: ByteArray,
        startOffset: Int,
        length: Int,
        buf: ByteBuffer,
        onPropertyFound: (sampleRate: Int, channels: Int, compression: String) -> Unit,
    ) {
        var offset = startOffset
        val end = startOffset + length
        var fs = 0
        var ch = 0
        var cmpr = ""

        while (offset + 12 <= end) {
            val subId = String(bytes, offset, 4, Charsets.US_ASCII)
            val subSize = buf.getLong(offset + 4)
            val subDataOffset = offset + 12

            when (subId) {
                "FS  " -> {
                    if (subSize >= 4 && subDataOffset + 4 <= end) {
                        fs = buf.getInt(subDataOffset)
                    }
                }
                "CHNL" -> {
                    if (subSize >= 2 && subDataOffset + 2 <= end) {
                        ch = buf.getShort(subDataOffset).toInt() and 0xFFFF
                    }
                }
                "CMPR" -> {
                    if (subSize >= 4 && subDataOffset + 4 <= end) {
                        cmpr = String(bytes, subDataOffset, 4, Charsets.US_ASCII)
                    }
                }
            }

            val paddedSize = if (subSize % 2 != 0L) subSize + 1 else subSize
            offset = subDataOffset + paddedSize.toInt()
        }

        onPropertyFound(fs, ch, cmpr)
    }

    private fun magicAt(bytes: ByteArray, offset: Int, magic: String): Boolean {
        if (offset + magic.length > bytes.size) return false
        for (i in magic.indices) {
            if (bytes[offset + i] != magic[i].code.toByte()) return false
        }
        return true
    }
}
