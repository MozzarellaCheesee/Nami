package dev.nami.player

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.media3.common.AudioAttributes as Media3AudioAttributes
import dev.nami.domain.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/** Этап 10 B: bit-perfect USB output via Android 14's `AudioMixerAttributes` --
 * `MIXER_BEHAVIOR_BIT_PERFECT` skips AudioFlinger's mixing/resampling/volume entirely for a
 * matching USB device, at the cost of every other in-app effect (EQ, ReplayGain, crossfade) also
 * being bypassed, per План.md's own warning. Requires a vendor HAL flag most phones don't ship,
 * so this silently no-ops (stays on the normal mixed path) on anything that can't actually do it
 * - there is no UI feedback for "didn't work" beyond that, matching the honest framing that this
 * is opportunistic, not guaranteed, hardware support.
 *
 * Also where Этап 10's other two pieces stop: DoP (DSD-over-PCM bit-packing, see
 * dev.nami.player.dsd.DopEncoder) exists as a standalone, unit-tested encoder but isn't wired to
 * a real DSD decode path (no .dsf/.dff parser in this codebase yet - that's a separate,
 * unstarted piece, not silently faked here).
 *
 * Про свой UAC2-драйвер (План.md's own "самая тяжёлая часть проекта"): разбор дескрипторов и
 * определение форматов устройства сделаны и покрыты тестами - см. dev.nami.player.usb. Самого
 * вывода звука мимо AudioTrack там нет, и упирается это не в объём работы, а в платформу:
 * изохронной передачи, которой USB Audio передаёт PCM, в публичном API Android нет вовсе
 * (доступны только bulk/control/interrupt). Подробности и путь обхода через NDK - в доке
 * UsbAudioDescriptors.
 */
class BitPerfectUsbController(
    private val context: Context,
    settingsRepository: SettingsRepository,
    scope: CoroutineScope,
) {
    init {
        settingsRepository.bitPerfectUsbEnabled
            .onEach { enabled -> if (enabled) tryEnable() else disable() }
            .launchIn(scope)
    }

    private fun tryEnable() {
        if (Build.VERSION.SDK_INT >= 34) enableBitPerfect()
        // Below API 34, AudioMixerAttributes doesn't exist - the setting stays on (so it takes
        // effect if the device is later updated) but has no effect here.
    }

    @RequiresApi(34)
    private fun enableBitPerfect() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val usbDevice = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
            ?: return

        val bitPerfectAttrs = try {
            audioManager.getSupportedMixerAttributes(usbDevice).firstOrNull {
                it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT
            }
        } catch (e: Exception) {
            // Reflects real vendor sightings of half-implemented AIDL Audio HALs (План.md's own
            // "известны случаи полного отсутствия звука") - fail closed, never crash playback
            // over an optional hi-res path.
            null
        } ?: return

        try {
            val usage = Media3AudioAttributes.DEFAULT.usage
            val nativeAttrs = android.media.AudioAttributes.Builder().setUsage(usage).build()
            audioManager.setPreferredMixerAttributes(nativeAttrs, usbDevice, bitPerfectAttrs)
        } catch (e: Exception) {
            // Same reasoning: an unsupported/broken vendor implementation degrades to normal
            // mixed output, it does not take music down with it.
        }
    }

    private fun disable() {
        if (Build.VERSION.SDK_INT < 34) return
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val usbDevice = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
            ?: return
        try {
            val usage = Media3AudioAttributes.DEFAULT.usage
            val nativeAttrs = android.media.AudioAttributes.Builder().setUsage(usage).build()
            // Mandatory per План.md: never leave a preferred mixer attribute set once the app
            // isn't using it, or the user loses system sounds on that USB output entirely.
            audioManager.clearPreferredMixerAttributes(nativeAttrs, usbDevice)
        } catch (e: Exception) {
            // Nothing to fall back to here; the worst case is a leftover preference the user
            // clears themselves by unplugging/replugging the device.
        }
    }
}
