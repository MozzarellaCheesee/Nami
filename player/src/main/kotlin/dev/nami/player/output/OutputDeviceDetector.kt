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
 * Registers one AudioDeviceCallback for its whole lifetime - construct once per service/process
 * (PlaybackService owns one), not per screen. */
class OutputDeviceDetector(private val context: Context) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val _current = MutableStateFlow(detect())
    val current: StateFlow<OutputDeviceType> = _current

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            _current.value = detect()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            _current.value = detect()
        }
    }

    init {
        audioManager?.registerAudioDeviceCallback(callback, null)
    }

    fun release() {
        audioManager?.unregisterAudioDeviceCallback(callback)
    }

    private fun detect(): OutputDeviceType {
        val devices = audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS) ?: return OutputDeviceType.SPEAKER
        val types = devices.map { it.type }.toSet()
        return when {
            AudioDeviceInfo.TYPE_USB_DEVICE in types || AudioDeviceInfo.TYPE_USB_HEADSET in types -> OutputDeviceType.USB_DAC
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP in types -> OutputDeviceType.BLUETOOTH
            AudioDeviceInfo.TYPE_WIRED_HEADSET in types || AudioDeviceInfo.TYPE_WIRED_HEADPHONES in types -> OutputDeviceType.WIRED
            else -> OutputDeviceType.SPEAKER
        }
    }
}
