package dev.nami.player.usb

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager

/** П.md §10 - что подключённый USB-ЦАП умеет на самом деле, по его собственным дескрипторам.
 *
 * Границы честно описаны в [UsbAudioDescriptors]: изохронной передачи в публичном API Android
 * нет, поэтому здесь определение возможностей, а не вывод звука. Зато определение настоящее -
 * данные берутся у самого устройства, а не у микшера Android (сравни с
 * dev.nami.player.output.DeviceAudioProbe, который показывает вход микшера).
 *
 * Требует разрешения пользователя на доступ к устройству (UsbManager.requestPermission): без него
 * openDevice вернёт null, и мы честно скажем, что не смогли открыть, а не покажем пустой список
 * как «ничего не поддерживает». */
class UsbAudioProbe(private val context: Context) {

    /** Все подключённые устройства с интерфейсом класса Audio. */
    fun audioDevices(): List<UsbDevice> {
        val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return emptyList()
        return manager.deviceList.values.filter { device ->
            (0 until device.interfaceCount).any { device.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_AUDIO }
        }
    }

    /** Человекочитаемое описание возможностей устройства - то же назначение, что у
     * DeviceAudioProbe.probe(), но по данным самого ЦАПа. */
    fun describe(device: UsbDevice): String {
        val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
            ?: return "USB недоступен"
        if (!manager.hasPermission(device)) return "Нет разрешения на доступ к устройству"
        val connection = try {
            manager.openDevice(device)
        } catch (e: Exception) {
            null
        } ?: return "Не удалось открыть устройство"

        return try {
            val alts = UsbAudioDescriptors.parse(connection.rawDescriptors ?: ByteArray(0))
                // alt 0 без изохронной точки - служебная «тишина», показывать её пользователю
                // как поддерживаемый формат было бы враньём.
                .filter { it.endpointAddress != null }
                // Только выход: вход - это микрофонная часть составного устройства.
                .filter { (it.endpointAddress!! and 0x80) == 0 }
            if (alts.isEmpty()) return "Аудиовыход в дескрипторах не найден"

            alts.joinToString("\n") { alt ->
                val rates = when {
                    alt.format.sampleRatesHz.isNotEmpty() -> alt.format.sampleRatesHz.joinToString(", ") { formatHz(it) }
                    alt.format.continuousRateRange != null ->
                        "${formatHz(alt.format.continuousRateRange.first)}-${formatHz(alt.format.continuousRateRange.last)}"
                    else -> queryUac2Rates(connection, alt).takeIf { it.isNotEmpty() }
                        ?.joinToString(", ") { formatHz(it) }
                        ?: "частоты не сообщены"
                }
                "${alt.version.name}, ${alt.format.channels} кан., ${alt.format.bitResolution} бит: $rates"
            }
        } catch (e: Exception) {
            "Ошибка разбора дескрипторов"
        } finally {
            try {
                connection.close()
            } catch (e: Exception) {
                // Закрытие уже закрытого соединения не повод ронять экран настроек.
            }
        }
    }

    /** UAC2 не перечисляет частоты в дескрипторе - их спрашивают у источника тактирования
     * запросом RANGE. Это control-передача, а она в Android доступна, поэтому кусок работает
     * по-настоящему, в отличие от самого вывода звука. */
    private fun queryUac2Rates(
        connection: android.hardware.usb.UsbDeviceConnection,
        alt: UsbAudioStreamingAlt,
    ): List<Int> {
        if (alt.version != UacVersion.UAC2) return emptyList()
        for (clockId in alt.clockIds) {
            val buffer = ByteArray(RANGE_BUFFER_BYTES)
            val read = try {
                connection.controlTransfer(
                    REQUEST_TYPE_CLASS_INTERFACE_IN,
                    REQUEST_RANGE,
                    CS_SAM_FREQ_CONTROL shl 8,
                    (clockId shl 8) or alt.controlInterfaceNumber,
                    buffer,
                    buffer.size,
                    CONTROL_TIMEOUT_MS,
                )
            } catch (e: Exception) {
                -1
            }
            if (read < 2) continue

            // Ответ: wNumSubRanges, затем тройки (dMIN, dMAX, dRES) по 4 байта.
            val subRanges = (buffer[0].toInt() and 0xFF) or ((buffer[1].toInt() and 0xFF) shl 8)
            val rates = mutableListOf<Int>()
            for (i in 0 until subRanges) {
                val at = 2 + i * 12
                if (at + 8 > read) break
                val min = buffer.readLe32(at)
                val max = buffer.readLe32(at + 4)
                // Подавляющее большинство ЦАПов отдают дискретные частоты как min == max.
                rates.add(min)
                if (max != min) rates.add(max)
            }
            if (rates.isNotEmpty()) return rates.distinct().sorted()
        }
        return emptyList()
    }

    private fun ByteArray.readLe32(at: Int): Int =
        (this[at].toInt() and 0xFF) or
            ((this[at + 1].toInt() and 0xFF) shl 8) or
            ((this[at + 2].toInt() and 0xFF) shl 16) or
            ((this[at + 3].toInt() and 0xFF) shl 24)

    private fun formatHz(hz: Int): String =
        if (hz % 1000 == 0) "${hz / 1000} кГц" else String.format("%.1f кГц", hz / 1000f)

    private companion object {
        /** device-to-host | class | interface */
        const val REQUEST_TYPE_CLASS_INTERFACE_IN = 0xA1
        const val REQUEST_RANGE = 0x02
        const val CS_SAM_FREQ_CONTROL = 0x01
        const val CONTROL_TIMEOUT_MS = 500
        /** Хватает на 21 поддиапазон - реальные ЦАПы отдают до десятка. */
        const val RANGE_BUFFER_BYTES = 256
    }
}
