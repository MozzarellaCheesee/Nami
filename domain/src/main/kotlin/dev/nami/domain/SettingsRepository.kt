package dev.nami.domain

import kotlinx.coroutines.flow.StateFlow

/** Which output route is currently active -- drives per-device profiles (below). Priority when
 * more than one is physically connected: USB_DAC > BLUETOOTH > WIRED > SPEAKER (a USB DAC or BT
 * headset being connected is a much stronger signal of "this is what's playing" than a wired jack
 * that might just be a charging cable's audio pins). */
enum class OutputDeviceType { WIRED, BLUETOOTH, USB_DAC, SPEAKER }

/** План.md §22.14 "Гибкий shuffle". TRUE_RANDOM is a flat random permutation (already inherently
 * "no repeats until the cycle ends" -- it reorders the existing queue once, it doesn't resample
 * with replacement). WEIGHTED_BY_STALENESS biases toward tracks that haven't played in a while
 * (Track.lastPlayed) so a shuffle surfaces forgotten tracks more often than ones on repeat.
 * Rating-weighted isn't here yet -- Track has no rating field in this codebase. */
enum class ShuffleMode { TRUE_RANDOM, WEIGHTED_BY_STALENESS }

/** Per-device profile from План.md §16/§20: its own EQ and a volume ceiling, applied automatically
 * when the routed output changes. Crossfeed/ReplayGain-mode aren't per-profile here -- crossfeed
 * doesn't exist as a processor in this codebase yet, and ReplayGain mode is a single global
 * on/off, not something that plausibly differs per output device. */
data class OutputProfile(val eqGainsDb: List<Float>, val volumeLimitPercent: Int) {
    companion object {
        val IDENTITY = OutputProfile(eqGainsDb = List(9) { 0f }, volumeLimitPercent = 100)
    }
}

/** План.md §22.11 "Сессии" -- a named bundle of settings applied in one tap ("Учёба"/"Дорога"/
 * "Сон", or the user's own). Deliberately doesn't snapshot the queue itself (that needs real
 * playlist infra to restore reliably) -- covers what the plan explicitly calls out as the other
 * half: "свой EQ, громкость и таймером". Applying one is orchestrated by whatever screen owns
 * both this repository and PlayerRepository (the sleep timer lives on that one, not here). */
data class Session(
    val name: String,
    val eqGainsDb: List<Float>,
    val crossfadeEnabled: Boolean,
    /** Null = don't touch/start a sleep timer when this session is applied. */
    val sleepTimerMinutes: Int?,
)

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

    /** Этап 6's "умный кроссфейд" (План.md §22.9, Beta) -- only takes effect while
     * [crossfadeEnabled] is also on. Skips the crossfade for a track that ends abruptly (loud
     * right up to a hard cut) instead of chopping its ending early; BPM-matching (the other half
     * of "уместно" from the plan) isn't implemented -- see TrackEndingAnalyzer's own doc. */
    val smartCrossfadeEnabled: StateFlow<Boolean>
    fun setSmartCrossfadeEnabled(value: Boolean)

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

    /** Now Playing's "night mode" pill -- a warmer, dimmer ambient backdrop for late-night
     * listening (not a separate app-wide theme; scoped to that one screen's own blurred-artwork
     * background). Persisted so it's remembered across sessions like every other toggle here. */
    val nightModeEnabled: StateFlow<Boolean>
    fun setNightModeEnabled(value: Boolean)

    /** Этап 4's "профили по устройству вывода" (Beta) -- off by default, same reasoning as EQ:
     * auto-switching gains/volume the instant a route changes is exactly the kind of thing that
     * needs to be opt-in, not something that surprises a user mid-listen. */
    val outputProfilesEnabled: StateFlow<Boolean>
    fun setOutputProfilesEnabled(value: Boolean)

    /** One [OutputProfile] per [OutputDeviceType], defaulting to [OutputProfile.IDENTITY] (flat
     * EQ, no volume limit) until the user edits one. */
    val outputProfiles: StateFlow<Map<OutputDeviceType, OutputProfile>>
    fun setOutputProfile(type: OutputDeviceType, profile: OutputProfile)

    /** STANDS4 lyrics fallback credentials -- each user's own (Settings -> Лирика), not a key
     * shared across every install: the free tier is 100 requests/day per account. Blank means
     * "not configured", the fallback silently no-ops (LRCLIB keeps working regardless). */
    val stands4Uid: StateFlow<String>
    fun setStands4Uid(value: String)
    val stands4Token: StateFlow<String>
    fun setStands4Token(value: String)

    /** How many STANDS4 requests have gone out today (device-local calendar day), and the fixed
     * free-tier ceiling. Every actual HTTP call increments this (hit or miss both count against
     * STANDS4's own quota) via [recordStands4Request]; resets automatically the first time either
     * is read/written on a new day. */
    val stands4RequestsToday: StateFlow<Int>
    fun recordStands4Request()

    /** См. [ShuffleMode]. */
    val shuffleMode: StateFlow<ShuffleMode>
    fun setShuffleMode(mode: ShuffleMode)

    /** См. [Session]. */
    val sessions: StateFlow<List<Session>>
    fun saveSession(session: Session)
    fun deleteSession(name: String)

    companion object {
        const val STANDS4_DAILY_LIMIT = 100
    }
}
