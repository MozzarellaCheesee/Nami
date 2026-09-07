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

    /** One gain per band, in ParametricEqAudioProcessor.BAND_FREQS_HZ order (9 bands: 63Hz..16kHz). */
    val eqBandGains: StateFlow<List<Float>>
    fun setEqBandGains(gainsDb: List<Float>)

    /** Bit-perfect USB output (Этап 10, Beta) -- Android 14+'s AudioMixerAttributes API only,
     * requires vendor HAL support most devices don't have; off by default and silently falls
     * back to the normal mixed path when the device/DAC can't actually do it. */
    val bitPerfectUsbEnabled: StateFlow<Boolean>
    fun setBitPerfectUsbEnabled(value: Boolean)

    /** ReplayGain-lite (Этап 4, Beta) -- RMS-loudness normalization, not true EBU R128. Off by
     * default, same "sits in every second of audio" reasoning as EQ. */
    val replayGainEnabled: StateFlow<Boolean>
    fun setReplayGainEnabled(value: Boolean)

    /** TPDF dither before the sink's own bit-depth truncation (Этап 4, Beta). Off by default. */
    val ditherEnabled: StateFlow<Boolean>
    fun setDitherEnabled(value: Boolean)

    /** Fade-out/fade-in "soft cut" between tracks (Этап 4, Beta) -- not a true overlapping mix,
     * see CrossfadeController's own doc for why. Off by default. */
    val crossfadeEnabled: StateFlow<Boolean>
    fun setCrossfadeEnabled(value: Boolean)

    /** "Усиление воспроизведения" -- flat library-wide boost (0/3/6 dB), independent of
     * per-track ReplayGain. */
    val playbackGainDb: StateFlow<Float>
    fun setPlaybackGainDb(value: Float)

    /** Hi-Fi (Beta) -- "shortest path" output: while it's on, the custom DSP AudioSink is never
     * built at all, so EQ/ReplayGain/dither/усиление are bypassed and decoded samples reach
     * AudioTrack untouched by this app. It cannot promise bit-perfect (AudioFlinger still mixes
     * and may resample -- only the separate bit-perfect USB path can skip that, and only on
     * hardware that supports it); what it does promise is that Nami itself adds nothing. Off by
     * default so the DSP toggles keep working as-is unless the user asks for the direct path. */
    val hiFiEnabled: StateFlow<Boolean>
    fun setHiFiEnabled(value: Boolean)
}
