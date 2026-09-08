package dev.nami.player.usb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Разбор дескрипторов - чистая функция над байтами, поэтому проверяется целиком, без железа.
 * Раскладки собраны по спецификациям USB Audio 1.0/2.0 в том виде, в каком их реально отдают
 * ЦАПы: интерфейс, за ним класс-специфичные дескрипторы, за ними конечная точка. */
class UsbAudioDescriptorsTest {

    private fun interfaceDescriptor(number: Int, alt: Int, subClass: Int, protocol: Int, endpoints: Int) =
        byteArrayOf(9, 0x04, number.toByte(), alt.toByte(), endpoints.toByte(), 0x01, subClass.toByte(), protocol.toByte(), 0)

    private fun isoEndpoint(address: Int, maxPacket: Int, interval: Int) = byteArrayOf(
        7, 0x05, address.toByte(),
        0x01, // изохронная
        (maxPacket and 0xFF).toByte(), (maxPacket shr 8).toByte(),
        interval.toByte(),
    )

    @Test
    fun `UAC1 - перечисленные частоты, каналы и разрядность`() {
        val raw = interfaceDescriptor(number = 1, alt = 1, subClass = 0x02, protocol = 0x00, endpoints = 1) +
            // AS_GENERAL (UAC1)
            byteArrayOf(7, 0x24, 0x01, 0x01, 0, 0x01, 0x00) +
            // FORMAT_TYPE_I: 2 канала, слот 2 байта, 16 бит, две частоты - 44100 и 48000
            byteArrayOf(
                14, 0x24, 0x02, 0x01, 2, 2, 16, 2,
                0x44.toByte(), 0xAC.toByte(), 0x00, // 44100
                0x80.toByte(), 0xBB.toByte(), 0x00, // 48000
            ) +
            isoEndpoint(address = 0x01, maxPacket = 192, interval = 1)

        val alts = UsbAudioDescriptors.parse(raw)
        assertEquals(1, alts.size)
        val alt = alts[0]
        assertEquals(UacVersion.UAC1, alt.version)
        assertEquals(2, alt.format.channels)
        assertEquals(16, alt.format.bitResolution)
        assertEquals(2, alt.format.subslotSizeBytes)
        assertEquals(listOf(44100, 48000), alt.format.sampleRatesHz)
        assertEquals(0x01, alt.endpointAddress)
        assertEquals(192, alt.maxPacketSizeBytes)
    }

    @Test
    fun `UAC1 - непрерывный диапазон частот вместо списка`() {
        val raw = interfaceDescriptor(1, 1, 0x02, 0x00, 1) +
            byteArrayOf(7, 0x24, 0x01, 0x01, 0, 0x01, 0x00) +
            byteArrayOf(
                14, 0x24, 0x02, 0x01, 2, 3, 24, 0, // bSamFreqType = 0 - диапазон
                0x44.toByte(), 0xAC.toByte(), 0x00, // min 44100
                0x00, 0x77, 0x01, // max 96000
            ) +
            isoEndpoint(0x01, 288, 1)

        val alt = UsbAudioDescriptors.parse(raw).single()
        assertTrue(alt.format.sampleRatesHz.isEmpty())
        assertEquals(44100..96000, alt.format.continuousRateRange)
        assertEquals(24, alt.format.bitResolution)
        // Разрядность и размер слота - разные вещи; 24 бита часто едут в 3- или 4-байтном слоте.
        assertEquals(3, alt.format.subslotSizeBytes)
    }

    @Test
    fun `UAC2 - каналы из AS_GENERAL, частоты в дескрипторе не лежат`() {
        val raw =
            // AudioControl с источником тактирования - его id нужен для запроса частот.
            interfaceDescriptor(number = 0, alt = 0, subClass = 0x01, protocol = 0x20, endpoints = 0) +
                byteArrayOf(8, 0x24, 0x0A, 0x05, 0x03, 0x07, 0x00, 0x00) + // CLOCK_SOURCE, bClockID = 5
                interfaceDescriptor(number = 1, alt = 1, subClass = 0x02, protocol = 0x20, endpoints = 1) +
                // AS_GENERAL (UAC2): bNrChannels на смещении 10
                byteArrayOf(16, 0x24, 0x01, 0x01, 0x00, 0x01, 0x01, 0, 0, 0, 2, 0x03, 0, 0, 0, 0) +
                // FORMAT_TYPE_I (UAC2): слот 4 байта, 32 бита
                byteArrayOf(6, 0x24, 0x02, 0x01, 4, 32) +
                isoEndpoint(address = 0x03, maxPacket = 1024, interval = 1)

        val alt = UsbAudioDescriptors.parse(raw).single()
        assertEquals(UacVersion.UAC2, alt.version)
        assertEquals(2, alt.format.channels)
        assertEquals(32, alt.format.bitResolution)
        assertEquals(4, alt.format.subslotSizeBytes)
        assertTrue(alt.format.sampleRatesHz.isEmpty(), "UAC2 не перечисляет частоты в дескрипторе")
        assertEquals(0, alt.controlInterfaceNumber)
        assertEquals(listOf(5), alt.clockIds)
    }

    @Test
    fun `alt 0 без изохронной точки не считается выходом`() {
        // Устройства объявляют alt 0 без точки, чтобы освободить полосу шины в простое.
        val raw = interfaceDescriptor(1, 0, 0x02, 0x00, 0) +
            byteArrayOf(7, 0x24, 0x01, 0x01, 0, 0x01, 0x00) +
            byteArrayOf(11, 0x24, 0x02, 0x01, 2, 2, 16, 1, 0x44.toByte(), 0xAC.toByte(), 0x00)

        val alt = UsbAudioDescriptors.parse(raw).single()
        assertNull(alt.endpointAddress)
    }

    @Test
    fun `не-аудио интерфейсы игнорируются`() {
        // HID-интерфейс (класс 3) - у составных ЦАПов с кнопками громкости он всегда есть.
        val raw = byteArrayOf(9, 0x04, 2, 0, 1, 0x03, 0x00, 0x00, 0) +
            byteArrayOf(7, 0x05, 0x83.toByte(), 0x03, 8, 0, 10)
        assertTrue(UsbAudioDescriptors.parse(raw).isEmpty())
    }

    @Test
    fun `битые дескрипторы не зацикливают разбор`() {
        // Нулевая длина - если её не отсечь, цикл никогда не сдвинется.
        assertTrue(UsbAudioDescriptors.parse(byteArrayOf(0, 0x04, 1, 0)).isEmpty())
        // Длина больше остатка блока.
        assertTrue(UsbAudioDescriptors.parse(byteArrayOf(9, 0x04, 1)).isEmpty())
        assertTrue(UsbAudioDescriptors.parse(ByteArray(0)).isEmpty())
    }

    @Test
    fun `сжатые форматы пропускаются`() {
        // FORMAT_TYPE_II - это не PCM, выдавать его за поддерживаемый выход нельзя.
        val raw = interfaceDescriptor(1, 1, 0x02, 0x00, 1) +
            byteArrayOf(7, 0x24, 0x01, 0x01, 0, 0x01, 0x00) +
            byteArrayOf(9, 0x24, 0x02, 0x02, 0, 0, 0, 0, 0) +
            isoEndpoint(0x01, 192, 1)
        assertTrue(UsbAudioDescriptors.parse(raw).isEmpty())
    }
}
