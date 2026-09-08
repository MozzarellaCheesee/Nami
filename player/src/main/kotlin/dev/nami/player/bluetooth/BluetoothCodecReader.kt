package dev.nami.player.bluetooth

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

/** Этап 4's Bluetooth output badge for the Аудиотракт screen's "Вывод" node. The actual codec
 * name (SBC/aptX/LDAC/...) lives behind BluetoothA2dp.getCodecStatus(), a @SystemApi hidden method
 * a regular app can't call without being a privileged system app - not reachable from here, so
 * this only reports the real, publicly-visible fact: whether output is currently routed to a
 * Bluetooth A2DP device at all, via AudioManager (no runtime permission needed for that much). */
object BluetoothCodecReader {

    fun isBluetoothOutputActive(context: Context): Boolean {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP && it.isSink }
    }
}
