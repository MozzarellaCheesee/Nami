package dev.nami.player.output

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import dev.nami.domain.OutputDeviceType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Live "which output route is active right now" for the per-device profiles feature (Этап 4,
 * План.md §16/§20). Same simplification the existing BitPerfectUsbController/BluetoothCodecReader
 * already use: Android has no public "give me the currently routed device" query that works
 * before an AudioTrack exists, so this picks the highest-priority CONNECTED output device instead
 * of the literally-routed one - USB DAC > Bluetooth > wired > speaker, on the reasoning that a
 * USB DAC or BT headset being connected at all is a much stronger signal than a wired jack that
 * might just be a charging accessory's audio pins.
 *
 * Registers one AudioDeviceCallback for its whole lifetime - construct once per service/screen
 * (PlaybackService owns one, экран Аудиотракта - свой) and call [release] when done. */
class OutputDeviceDetector(private val context: Context) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val _current = MutableStateFlow(OutputDeviceType.SPEAKER)
    val current: StateFlow<OutputDeviceType> = _current

    /** Ключ КОНКРЕТНОГО подключённого устройства ("BLUETOOTH:WF-1000XM5"), а не только типа
     * маршрута - под него и хранится свой EQ-профиль, см. [deviceKey]. */
    private val _currentKey = MutableStateFlow(deviceKey(OutputDeviceType.SPEAKER, null))
    val currentKey: StateFlow<String> = _currentKey

    /** Человекочитаемое имя того же устройства - для экрана EQ ("Профиль для WF-1000XM5"). */
    private val _currentName = MutableStateFlow<String?>(null)
    val currentName: StateFlow<String?> = _currentName

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refresh()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refresh()
    }

    private fun refresh() {
        val device = detectDevice()
        val type = device.toType()
        val name = device?.productNameOrNull()
        _current.value = type
        _currentKey.value = deviceKey(type, name)
        _currentName.value = name
    }

    init {
        refresh()
        audioManager?.registerAudioDeviceCallback(callback, null)
    }

    fun release() {
        audioManager?.unregisterAudioDeviceCallback(callback)
    }

    /** Само устройство, а не только его тип: имя нужно, чтобы отличить одни наушники от других.
     * Приоритет тот же, что описан выше. */
    private fun detectDevice(): AudioDeviceInfo? {
        val devices = audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS) ?: return null
        return devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
            ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
            ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES }
    }

    private fun AudioDeviceInfo?.toType(): OutputDeviceType = when (this?.type) {
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> OutputDeviceType.USB_DAC
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> OutputDeviceType.BLUETOOTH
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> OutputDeviceType.WIRED
        else -> OutputDeviceType.SPEAKER
    }

    private fun AudioDeviceInfo.productNameOrNull(): String? = productName?.toString()?.trim()?.takeIf { it.isNotEmpty() }

    companion object {
        /** Идентификатор устройства для хранения профиля.
         *
         * ponytail: имя устройства (AudioDeviceInfo.productName), а не Bluetooth-MAC и не USB
         * VID/PID. MAC требует BLUETOOTH_CONNECT и с Android 12 отдаётся не всем, а VID/PID
         * пришлось бы тянуть отдельным перебором UsbManager и сопоставлять с аудиомаршрутом по
         * догадке. Имя приходит вместе с самим маршрутом, бесплатно и без разрешений; цена -
         * двое одинаковых наушников будут делить один профиль. Апгрейд, если это когда-то
         * помешает: добавить MAC как уточнение к ключу там, где разрешение уже выдано.
         *
         * Тип входит в ключ, чтобы одноимённая гарнитура по проводу и по Bluetooth не смешивалась
         * (по проводу у неё нет ни своего кодека, ни своей АЧХ усилителя). */
        fun deviceKey(type: OutputDeviceType, productName: String?): String =
            if (productName.isNullOrBlank()) type.name else "${type.name}:$productName"
    }
}
