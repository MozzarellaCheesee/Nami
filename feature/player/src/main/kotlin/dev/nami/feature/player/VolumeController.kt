package dev.nami.feature.player

import android.content.Context
import android.media.AudioManager

/** Thin wrapper over the system media (STREAM_MUSIC) volume for the Now Playing overflow sheet's
 * slider - reads/writes the same volume the hardware buttons control. No live-sync with hardware
 * button presses while the sheet is open (would need a ContentObserver/BroadcastReceiver) - the
 * sheet is short-lived and re-reads on every open, so this is the cheap version.
 * ponytail: add a receiver for ACTION_VOLUME_CHANGED if the sheet ever needs to stay open across
 * a hardware button press. */
class VolumeController(context: Context) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    val max: Int get() = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    val current: Int get() = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

    fun set(value: Int) {
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, value.coerceIn(0, max), 0)
    }
}
