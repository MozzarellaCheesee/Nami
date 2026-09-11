package dev.nami.player.usb

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Прямой транспорт USB Audio Class (UAC1/UAC2).
 *
 * Архитектура:
 * 1. Получение эксклюзивного доступа к устройству через [UsbManager.openDevice].
 * 2. Разбор дескрипторов аудиопотока через [UsbAudioDescriptors].
 * 3. Захват аудио-интерфейса ([UsbDeviceConnection.claimInterface]) и переключение
 *    на рабочую альтернативную настройку (Alternate Setting) с активной Isochronous OUT точкой.
 * 4. Управление частотой дискретизации UAC2 через control transfer (CS_SAM_FREQ_CONTROL).
 * 5. Изохронный вывод PCM-сэмплов через файловый дескриптор USBDEVFS (ioctl USBDEVFS_SUBMITURB).
 */
class UsbAudioTransport(
    private val context: Context,
    private val device: UsbDevice,
) {
    companion object {
        private const val TAG = "UsbAudioTransport"

        // USBDEVFS ioctl коды для Linux / Android
        const val USBDEVFS_SETINTERFACE = 0x80085504.toInt()
        const val USBDEVFS_SUBMITURB = 0x8038550a.toInt()
        const val USBDEVFS_REAPURB = 0x4008550c.toInt()
        const val USBDEVFS_REAPURBNDELAY = 0x4008550d.toInt()
        const val USBDEVFS_CLAIMINTERFACE = 0x8004550f.toInt()
        const val USBDEVFS_RELEASEINTERFACE = 0x80045510.toInt()

        const val USBDEVFS_URB_TYPE_ISO = 0
        const val USBDEVFS_URB_ISO_ASAP = 0x02
    }

    private var connection: UsbDeviceConnection? = null
    private var streamingAlt: UsbAudioStreamingAlt? = null
    private var claimedInterface: UsbInterface? = null
    private var nativeFd: Int = -1

    /** Статус готовности прямого изохронного транспорта */
    var isInitialized: Boolean = false
        private set

    /** Открывает устройство и инициализирует дескрипторы */
    fun open(): Boolean {
        val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return false
        if (!manager.hasPermission(device)) {
            Log.w(TAG, "Нет разрешения USB для устройства ${device.deviceName}")
            return false
        }

        val conn = manager.openDevice(device) ?: run {
            Log.w(TAG, "Не удалось открыть UsbDeviceConnection")
            return false
        }
        connection = conn
        nativeFd = conn.fileDescriptor

        val raw = conn.rawDescriptors ?: ByteArray(0)
        val alts = UsbAudioDescriptors.parse(raw)
            .filter { it.endpointAddress != null }
            .filter { (it.endpointAddress!! and 0x80) == 0 } // Только OUT

        if (alts.isEmpty()) {
            Log.w(TAG, "В дескрипторах не найдено подходящих альтернативных настроек AudioStreaming OUT")
            close()
            return false
        }

        // Выбираем лучшую альтернативу (предпочитаем UAC2, 24/32 бит стерео)
        streamingAlt = alts.maxByOrNull { alt ->
            (if (alt.version == UacVersion.UAC2) 1000 else 0) +
            alt.format.bitResolution * 10 +
            alt.format.channels
        }

        val alt = streamingAlt ?: run {
            close()
            return false
        }

        // Захватываем интерфейс
        val iface = (0 until device.interfaceCount)
            .map { device.getInterface(it) }
            .firstOrNull { it.id == alt.interfaceNumber }

        if (iface != null) {
            conn.claimInterface(iface, true)
            claimedInterface = iface
        }

        // Переключаем alternate setting через control transfer (SET_INTERFACE)
        val setAltSuccess = conn.controlTransfer(
            0x01, // USB_RECIP_INTERFACE | USB_TYPE_STANDARD
            0x0B, // SET_INTERFACE
            alt.alternateSetting,
            alt.interfaceNumber,
            null,
            0,
            1000,
        ) >= 0

        Log.d(TAG, "Переключение на Alt ${alt.alternateSetting}, результат: $setAltSuccess, FD: $nativeFd")
        isInitialized = true
        return true
    }

    /**
     * Задаёт частоту дискретизации для ЦАПа через UAC2 control transfer (RANGE / CUR).
     */
    fun setSampleRate(sampleRateHz: Int): Boolean {
        val conn = connection ?: return false
        val alt = streamingAlt ?: return false

        if (alt.version == UacVersion.UAC2) {
            for (clockId in alt.clockIds) {
                val data = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(sampleRateHz).array()
                val res = conn.controlTransfer(
                    0x21, // CS request OUT: Host to Device, Class, Interface
                    0x01, // CUR
                    0x01 shl 8, // CS_SAM_FREQ_CONTROL
                    (clockId shl 8) or alt.controlInterfaceNumber,
                    data,
                    data.size,
                    1000,
                )
                if (res >= 0) {
                    Log.d(TAG, "Частота UAC2 успешно установлена: $sampleRateHz Гц (Clock ID: $clockId)")
                    return true
                }
            }
        }
        return false
    }

    /**
     * Возвращает дескриптор текущего активного потока
     */
    fun activeStreamFormat(): UsbAudioStreamingAlt? = streamingAlt

    /**
     * Возвращает нативный файловый дескриптор usbdevfs для ioctl вызовов
     */
    fun fileDescriptor(): Int = nativeFd

    /**
     * Закрывает соединение и освобождает ресурсы
     */
    fun close() {
        try {
            claimedInterface?.let { connection?.releaseInterface(it) }
            connection?.close()
        } catch (_: Exception) {}
        claimedInterface = null
        connection = null
        streamingAlt = null
        nativeFd = -1
        isInitialized = false
    }
}
