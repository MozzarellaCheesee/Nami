package dev.nami.feature.player

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dev.nami.player.bluetooth.BluetoothCodecReader

/** Live version of [BluetoothCodecReader.isBluetoothOutputActive] -- that was a one-shot
 * `remember {}` read before, so connecting/disconnecting a Bluetooth device while the Аудиотракт
 * screen was already open never updated the badge without leaving and reopening the screen.
 * AudioManager's own AudioDeviceCallback fires on every output device add/remove (Bluetooth
 * included), so this just re-reads the same real fact whenever that happens. */
@Composable
fun rememberBluetoothOutputActive(): State<Boolean> {
    val context = LocalContext.current
    val state = remember { mutableStateOf(BluetoothCodecReader.isBluetoothOutputActive(context)) }
    DisposableEffect(context) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                state.value = BluetoothCodecReader.isBluetoothOutputActive(context)
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                state.value = BluetoothCodecReader.isBluetoothOutputActive(context)
            }
        }
        audioManager?.registerAudioDeviceCallback(callback, null)
        onDispose { audioManager?.unregisterAudioDeviceCallback(callback) }
    }
    return state
}
