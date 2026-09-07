package dev.nami.domain

import kotlinx.coroutines.flow.StateFlow

/** App-wide preferences (SharedPreferences-backed) -- interface lives in :domain so feature
 * modules that need a setting (e.g. feature:player gating karaoke) don't have to depend on
 * :data directly. */
interface SettingsRepository {
    val autoOpenPlayer: StateFlow<Boolean>
    fun setAutoOpenPlayer(value: Boolean)

    val hideSystemBars: StateFlow<Boolean>
    fun setHideSystemBars(value: Boolean)

    /** Word-level karaoke highlight sweep -- off by default (best-effort timing, not always
     * correct), opt-in from Settings. */
    val karaokeEnabled: StateFlow<Boolean>
    fun setKaraokeEnabled(value: Boolean)

    /** Study mode (Beta): translation hidden per line until tapped, quiz entry point in the
     * vocabulary screen. Off by default, same reasoning as karaoke -- opt-in, not fully polished. */
    val studyModeEnabled: StateFlow<Boolean>
    fun setStudyModeEnabled(value: Boolean)

    /** Local file path to a user-picked .ttf/.otf for the lyrics screen, copied into app storage
     * (a content:// pick isn't a stable path). Null = system default font. */
    val lyricsFontPath: StateFlow<String?>
    fun setLyricsFontPath(path: String?)

    /** Этап 4's parametric EQ (Beta) -- off by default: it sits directly in the path of every
     * second of audio the app plays, so a subtle DSP bug means "everything sounds wrong" rather
     * than "one screen is broken". Bass/mid/treble in dB, ±12 typical range. */
    val eqEnabled: StateFlow<Boolean>
    fun setEqEnabled(value: Boolean)

    val eqBassDb: StateFlow<Float>
    val eqMidDb: StateFlow<Float>
    val eqTrebleDb: StateFlow<Float>
    fun setEqGains(bassDb: Float, midDb: Float, trebleDb: Float)

    /** Bit-perfect USB output (Этап 10, Beta) -- Android 14+'s AudioMixerAttributes API only,
     * requires vendor HAL support most devices don't have; off by default and silently falls
     * back to the normal mixed path when the device/DAC can't actually do it. */
    val bitPerfectUsbEnabled: StateFlow<Boolean>
    fun setBitPerfectUsbEnabled(value: Boolean)
}
