package dev.nami.player.output

import android.content.Context
import android.media.AudioManager

/** The one publicly-readable fact about the device's own output chain: the rate AudioFlinger's
 * mixer actually runs at. Everything above it (whether a given track then gets resampled) is
 * arithmetic on that plus the file's own sample rate - which is why the Аудиотракт screen can
 * finally say something true in its "Ресемплинг" row instead of a hardcoded "нет".
 *
 * PROPERTY_OUTPUT_SAMPLE_RATE is a hint for the *primary* output and can be wrong or absent for a
 * USB/Bluetooth route; null means "we don't know", and the UI says exactly that. */
object AudioOutputInfo {

    fun outputSampleRateHz(context: Context): Int? {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return null
        return audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()
    }
}
