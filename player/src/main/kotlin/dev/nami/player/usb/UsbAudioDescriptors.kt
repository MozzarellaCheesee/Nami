package dev.nami.player.usb

/** П.md §10 «свой UAC2-драйвер» - разбор дескрипторов USB Audio Class.
 *
 * ЧТО ЗДЕСЬ ЕСТЬ: полный разбор конфигурационного блока USB-устройства (то, что отдаёт
 * UsbDeviceConnection.getRawDescriptors()) - интерфейсы AudioControl/AudioStreaming, версия класса
 * (UAC1 и UAC2), все альтернативные настройки с их форматами (число каналов, разрядность, размер
 * слота), изохронные конечные точки и, для UAC2, идентификаторы источников тактирования.
 *
 * ЧЕГО ЗДЕСЬ НЕТ И НЕ БУДЕТ БЕЗ NDK: собственно вывода звука мимо AudioTrack. Причина не в объёме
 * работы, а в платформе: публичный USB API Android умеет только bulk, control и interrupt
 * (UsbDeviceConnection.bulkTransfer/controlTransfer, UsbRequest.queue - последний по документации
 * только для bulk и interrupt). Изохронной передачи, а это ЕДИНСТВЕННЫЙ режим, которым UAC
 * передаёт PCM, в Java-API Android нет вообще. Обойти это можно только забрав
 * UsbDeviceConnection.getFileDescriptor() и подавая URB типа ISO через ioctl USBDEVFS_SUBMITURB
 * из нативного кода - то есть это отдельный NDK-модуль плюс покристальные обходы (XMOS/Savitech/
 * ESS), которые невозможно написать вслепую, без самого железа на столе.
 *
 * Поэтому здесь честно сделана та часть, которая реализуема и проверяема целиком: определение
 * того, что устройство умеет. Разбор чистый (байты на входе, данные на выходе), поэтому покрыт
 * тестами на реальных раскладках дескрипторов. Запрос частот у UAC2-устройства живёт в
 * [UsbAudioProbe] - он требует control-передачи, которая как раз доступна. */
object UsbAudioDescriptors {

    fun parse(raw: ByteArray): List<UsbAudioStreamingAlt> {
        val result = mutableListOf<UsbAudioStreamingAlt>()
        var offset = 0

        // Текущий разбираемый AudioStreaming-интерфейс: дескрипторы идут плоским списком, и всё,
        // что встречается после интерфейсного дескриптора, относится к нему до следующего такого.
        var current: Builder? = null
        // Номер AudioControl-интерфейса и найденные источники тактирования - они объявлены в
        // AC-интерфейсе, а нужны для запроса частот у AS-альтернативы (см. UsbAudioProbe).
        var controlInterfaceNumber = -1
        val clockIds = mutableListOf<Int>()

        fun flush() {
            current?.let { if (it.format != null) result.add(it.build(controlInterfaceNumber, clockIds.toList())) }
            current = null
        }

        while (offset + 1 < raw.size) {
            val length = raw[offset].toInt() and 0xFF
            val type = raw[offset + 1].toInt() and 0xFF
            // Нулевая длина - битый блок; без этой проверки цикл встал бы намертво.
            if (length < 2 || offset + length > raw.size) break

            when (type) {
                DESC_INTERFACE -> {
                    flush()
                    val interfaceClass = raw.u8(offset + 5)
                    val subClass = raw.u8(offset + 6)
                    val protocol = raw.u8(offset + 7)
                    if (interfaceClass == CLASS_AUDIO) {
                        when (subClass) {
                            SUBCLASS_AUDIOCONTROL -> controlInterfaceNumber = raw.u8(offset + 2)
                            SUBCLASS_AUDIOSTREAMING -> current = Builder(
                                interfaceNumber = raw.u8(offset + 2),
                                alternateSetting = raw.u8(offset + 3),
                                version = if (protocol == PROTOCOL_UAC2) UacVersion.UAC2 else UacVersion.UAC1,
                            )
                        }
                    }
                }

                DESC_CS_INTERFACE -> {
                    val subtype = raw.u8(offset + 2)
                    // Источник тактирования объявлен в AC-интерфейсе - запоминаем независимо от
                    // того, какой AS-интерфейс сейчас разбирается.
                    if (subtype == AC_CLOCK_SOURCE && current == null && length >= 8) {
                        clockIds.add(raw.u8(offset + 3))
                    }
                    val builder = current
                    if (builder != null) {
                        when (subtype) {
                            AS_GENERAL -> if (builder.version == UacVersion.UAC2 && length >= 16) {
                                // У UAC2 число каналов лежит в AS_GENERAL, а не в FORMAT_TYPE.
                                builder.channels = raw.u8(offset + 10)
                            }
                            AS_FORMAT_TYPE -> builder.format = parseFormatType(raw, offset, length, builder)
                        }
                    }
                }

                DESC_ENDPOINT -> {
                    val builder = current
                    if (builder != null && length >= 7) {
                        val attributes = raw.u8(offset + 3)
                        if (attributes and ENDPOINT_TYPE_MASK == ENDPOINT_TYPE_ISOCHRONOUS) {
                            builder.endpointAddress = raw.u8(offset + 2)
                            builder.maxPacketSize = raw.u16(offset + 4)
                            builder.intervalFrames = raw.u8(offset + 6)
                        }
                    }
                }
            }
            offset += length
        }
        flush()
        return result
    }

    private fun parseFormatType(raw: ByteArray, offset: Int, length: Int, builder: Builder): UsbAudioFormat? {
        // bFormatType != 1 - это FORMAT_TYPE_II/III (сжатые потоки, AC-3 и подобное). Для вывода
        // PCM они бесполезны, и притворяться, что мы их понимаем, хуже, чем пропустить.
        if (length < 4 || raw.u8(offset + 3) != FORMAT_TYPE_I) return null

        return when (builder.version) {
            UacVersion.UAC2 -> {
                if (length < 6) return null
                UsbAudioFormat(
                    channels = builder.channels,
                    subslotSizeBytes = raw.u8(offset + 4),
                    bitResolution = raw.u8(offset + 5),
                    // У UAC2 частоты в дескрипторе не лежат вообще - они спрашиваются у источника
                    // тактирования control-запросом, см. UsbAudioProbe.
                    sampleRatesHz = emptyList(),
                    continuousRateRange = null,
                )
            }
            UacVersion.UAC1 -> {
                if (length < 8) return null
                val channels = raw.u8(offset + 4)
                val subslot = raw.u8(offset + 5)
                val bits = raw.u8(offset + 6)
                val rateType = raw.u8(offset + 7)
                var rates = emptyList<Int>()
                var range: IntRange? = null
                if (rateType == 0) {
                    // Непрерывный диапазон: две записи по три байта - минимум и максимум.
                    if (length >= 14) range = raw.u24(offset + 8)..raw.u24(offset + 11)
                } else {
                    rates = (0 until rateType).mapNotNull { i ->
                        val at = offset + 8 + i * 3
                        if (at + 2 < offset + length) raw.u24(at) else null
                    }
                }
                UsbAudioFormat(
                    channels = channels,
                    subslotSizeBytes = subslot,
                    bitResolution = bits,
                    sampleRatesHz = rates,
                    continuousRateRange = range,
                )
            }
        }
    }

    private class Builder(
        val interfaceNumber: Int,
        val alternateSetting: Int,
        val version: UacVersion,
    ) {
        var channels: Int = 0
        var format: UsbAudioFormat? = null
        var endpointAddress: Int? = null
        var maxPacketSize: Int? = null
        var intervalFrames: Int = 0

        fun build(controlInterfaceNumber: Int, clockIds: List<Int>) = UsbAudioStreamingAlt(
            interfaceNumber = interfaceNumber,
            alternateSetting = alternateSetting,
            version = version,
            format = format!!,
            endpointAddress = endpointAddress,
            maxPacketSizeBytes = maxPacketSize,
            intervalFrames = intervalFrames,
            controlInterfaceNumber = controlInterfaceNumber,
            clockIds = clockIds,
        )
    }

    // Дескрипторы USB - беззнаковые little-endian; в Kotlin Byte знаковый, поэтому маскируем.
    private fun ByteArray.u8(at: Int) = this[at].toInt() and 0xFF
    private fun ByteArray.u16(at: Int) = u8(at) or (u8(at + 1) shl 8)
    private fun ByteArray.u24(at: Int) = u8(at) or (u8(at + 1) shl 8) or (u8(at + 2) shl 16)

    private const val DESC_INTERFACE = 0x04
    private const val DESC_ENDPOINT = 0x05
    private const val DESC_CS_INTERFACE = 0x24
    private const val CLASS_AUDIO = 0x01
    private const val SUBCLASS_AUDIOCONTROL = 0x01
    private const val SUBCLASS_AUDIOSTREAMING = 0x02
    private const val PROTOCOL_UAC2 = 0x20
    private const val AS_GENERAL = 0x01
    private const val AS_FORMAT_TYPE = 0x02
    private const val AC_CLOCK_SOURCE = 0x0A
    private const val FORMAT_TYPE_I = 0x01
    private const val ENDPOINT_TYPE_MASK = 0x03
    private const val ENDPOINT_TYPE_ISOCHRONOUS = 0x01
}

enum class UacVersion { UAC1, UAC2 }

/** Формат одной альтернативной настройки AudioStreaming-интерфейса. */
data class UsbAudioFormat(
    val channels: Int,
    /** Байт на отсчёт в потоке. У 24-битного звука это часто 4, а не 3 - разрядность и размер
     * слота разные вещи, и путать их значит поехать по всему потоку. */
    val subslotSizeBytes: Int,
    val bitResolution: Int,
    /** UAC1: перечисленные частоты. UAC2: всегда пусто, частоты спрашиваются у часов. */
    val sampleRatesHz: List<Int>,
    /** UAC1 с непрерывным диапазоном частот вместо списка. */
    val continuousRateRange: IntRange?,
)

data class UsbAudioStreamingAlt(
    val interfaceNumber: Int,
    val alternateSetting: Int,
    val version: UacVersion,
    val format: UsbAudioFormat,
    /** null - у альтернативы нет изохронной точки, то есть это «тихая» alt 0, которой устройство
     * освобождает полосу шины, когда звук не идёт. */
    val endpointAddress: Int?,
    val maxPacketSizeBytes: Int?,
    val intervalFrames: Int,
    val controlInterfaceNumber: Int,
    val clockIds: List<Int>,
)
