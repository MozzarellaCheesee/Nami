package dev.nami.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.nami.domain.OutputDeviceType
import dev.nami.domain.OutputProfile
import dev.nami.domain.Session
import dev.nami.domain.SettingsRepository
import dev.nami.domain.ShuffleMode
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

private const val PREFS_NAME = "nami_settings"
private const val KEY_AUTO_OPEN_PLAYER = "auto_open_player"
private const val KEY_HIDE_SYSTEM_BARS = "hide_system_bars"
private const val KEY_KARAOKE_ENABLED = "karaoke_enabled"
private const val KEY_STUDY_MODE_ENABLED = "study_mode_enabled"
private const val KEY_LYRICS_FONT_PATH = "lyrics_font_path"
private const val KEY_UI_FONT_PATH = "ui_font_path"
private const val KEY_DOUBLE_TAP_ARTWORK_ACTION = "double_tap_artwork_action"
private const val KEY_EQ_ENABLED = "eq_enabled"
private const val KEY_EQ_BAND_GAINS = "eq_band_gains" // CSV, 9 floats, BAND_FREQS_HZ order
private const val EQ_BAND_COUNT = 9
private const val KEY_BIT_PERFECT_USB_ENABLED = "bit_perfect_usb_enabled"
private const val KEY_REPLAY_GAIN_ENABLED = "replay_gain_enabled"
private const val KEY_DITHER_ENABLED = "dither_enabled"
private const val KEY_CROSSFADE_ENABLED = "crossfade_enabled"
private const val KEY_SMART_CROSSFADE_ENABLED = "smart_crossfade_enabled"
private const val KEY_PLAYBACK_GAIN_DB = "playback_gain_db"
private const val KEY_HIFI_ENABLED = "hifi_enabled"
private const val KEY_NIGHT_MODE_ENABLED = "night_mode_enabled"
private const val KEY_AMOLED_ENABLED = "amoled_enabled"
private const val KEY_STANDS4_UID = "stands4_uid"
private const val KEY_STANDS4_TOKEN = "stands4_token"
private const val KEY_DEEPL_API_KEY = "deepl_api_key"
private const val KEY_STANDS4_REQUEST_COUNT = "stands4_request_count"
private const val KEY_STANDS4_REQUEST_DATE = "stands4_request_date" // yyyy-MM-dd, device-local
private const val KEY_LAST_PLAYBACK_QUEUE = "last_playback_queue"
private const val KEY_WATCHED_FOLDERS = "watched_folders"
private const val KEY_LAST_PLAYBACK_QUEUE_INDEX = "last_playback_queue_index"
private const val KEY_LAST_PLAYBACK_POSITION_MS = "last_playback_position_ms"
private const val KEY_LAST_PLAYBACK_PAUSED_AT = "last_playback_paused_at"
private const val KEY_SHUFFLE_MODE = "shuffle_mode"
private const val KEY_SESSIONS = "sessions" // JSON array, see AppSettingsRepository.readSessions
private const val KEY_LAST_APPLIED_SESSION = "last_applied_session"
private const val KEY_OUTPUT_PROFILES_ENABLED = "output_profiles_enabled"
private const val KEY_SCROBBLING_ENABLED = "scrobbling_enabled"
private const val KEY_LISTENBRAINZ_TOKEN = "listenbrainz_token"
private const val KEY_HOME_BLOCKS = "home_blocks" // JSON array [{type, enabled}], see readHomeBlocks
// One "<CSV of 9 gains>|<volumeLimitPercent>" string per device type.
private fun outputProfileKey(type: OutputDeviceType) = "output_profile_${type.name}"

@Singleton
class AppSettingsRepository @Inject constructor(@ApplicationContext context: Context) : SettingsRepository {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Default OFF: tapping a track starts playback without jumping to Now Playing, per the
    // explicit request this setting exists for - opening the full player is opt-in.
    private val _autoOpenPlayer = MutableStateFlow(prefs.getBoolean(KEY_AUTO_OPEN_PLAYER, false))
    override val autoOpenPlayer: StateFlow<Boolean> = _autoOpenPlayer

    override fun setAutoOpenPlayer(value: Boolean) {
        prefs.edit { putBoolean(KEY_AUTO_OPEN_PLAYER, value) }
        _autoOpenPlayer.value = value
    }

    // Default ON: status/nav bar hidden (immersive), matching the reference mocks' edge-to-edge
    // look - an explicit opt-out for anyone who wants the system bars back.
    private val _hideSystemBars = MutableStateFlow(prefs.getBoolean(KEY_HIDE_SYSTEM_BARS, true))
    override val hideSystemBars: StateFlow<Boolean> = _hideSystemBars

    override fun setHideSystemBars(value: Boolean) {
        prefs.edit { putBoolean(KEY_HIDE_SYSTEM_BARS, value) }
        _hideSystemBars.value = value
    }

    // Default OFF: the word-level karaoke sweep is a best-effort estimate (or a Whisper pass the
    // user has to run themselves) rather than always-correct timing, so it doesn't turn on
    // uninvited - opt-in from Settings.
    private val _karaokeEnabled = MutableStateFlow(prefs.getBoolean(KEY_KARAOKE_ENABLED, false))
    override val karaokeEnabled: StateFlow<Boolean> = _karaokeEnabled

    override fun setKaraokeEnabled(value: Boolean) {
        prefs.edit { putBoolean(KEY_KARAOKE_ENABLED, value) }
        _karaokeEnabled.value = value
    }

    private val _studyModeEnabled = MutableStateFlow(prefs.getBoolean(KEY_STUDY_MODE_ENABLED, false))
    override val studyModeEnabled: StateFlow<Boolean> = _studyModeEnabled

    override fun setStudyModeEnabled(value: Boolean) {
        prefs.edit { putBoolean(KEY_STUDY_MODE_ENABLED, value) }
        _studyModeEnabled.value = value
    }

    private val _lyricsFontPath = MutableStateFlow(prefs.getString(KEY_LYRICS_FONT_PATH, null))
    override val lyricsFontPath: StateFlow<String?> = _lyricsFontPath

    override fun setLyricsFontPath(path: String?) {
        prefs.edit { putString(KEY_LYRICS_FONT_PATH, path) }
        _lyricsFontPath.value = path
    }

    private val _uiFontPath = MutableStateFlow(prefs.getString(KEY_UI_FONT_PATH, null))
    override val uiFontPath: StateFlow<String?> = _uiFontPath

    override fun setUiFontPath(path: String?) {
        prefs.edit { putString(KEY_UI_FONT_PATH, path) }
        _uiFontPath.value = path
    }

    private val _doubleTapArtworkAction = MutableStateFlow(
        prefs.getString(KEY_DOUBLE_TAP_ARTWORK_ACTION, null)
            ?.let { runCatching { dev.nami.domain.GestureAction.valueOf(it) }.getOrNull() }
            ?: dev.nami.domain.GestureAction.NONE,
    )
    override val doubleTapArtworkAction: StateFlow<dev.nami.domain.GestureAction> = _doubleTapArtworkAction

    override fun setDoubleTapArtworkAction(action: dev.nami.domain.GestureAction) {
        prefs.edit { putString(KEY_DOUBLE_TAP_ARTWORK_ACTION, action.name) }
        _doubleTapArtworkAction.value = action
    }

    private val _eqEnabled = MutableStateFlow(prefs.getBoolean(KEY_EQ_ENABLED, false))
    override val eqEnabled: StateFlow<Boolean> = _eqEnabled

    override fun setEqEnabled(value: Boolean) {
        prefs.edit { putBoolean(KEY_EQ_ENABLED, value) }
        _eqEnabled.value = value
    }

    private val _eqBandGains = MutableStateFlow(readEqBandGains())
    override val eqBandGains: StateFlow<List<Float>> = _eqBandGains

    override fun setEqBandGains(gainsDb: List<Float>) {
        require(gainsDb.size == EQ_BAND_COUNT) { "expected $EQ_BAND_COUNT gains, got ${gainsDb.size}" }
        prefs.edit { putString(KEY_EQ_BAND_GAINS, gainsDb.joinToString(",")) }
        _eqBandGains.value = gainsDb
    }

    private fun readEqBandGains(): List<Float> {
        val csv = prefs.getString(KEY_EQ_BAND_GAINS, null) ?: return List(EQ_BAND_COUNT) { 0f }
        val parsed = csv.split(",").mapNotNull { it.toFloatOrNull() }
        return if (parsed.size == EQ_BAND_COUNT) parsed else List(EQ_BAND_COUNT) { 0f }
    }

    private val _bitPerfectUsbEnabled = MutableStateFlow(prefs.getBoolean(KEY_BIT_PERFECT_USB_ENABLED, false))
    override val bitPerfectUsbEnabled: StateFlow<Boolean> = _bitPerfectUsbEnabled

    override fun setBitPerfectUsbEnabled(value: Boolean) {
        prefs.edit { putBoolean(KEY_BIT_PERFECT_USB_ENABLED, value) }
        _bitPerfectUsbEnabled.value = value
    }

    private val _replayGainEnabled = MutableStateFlow(prefs.getBoolean(KEY_REPLAY_GAIN_ENABLED, false))
    override val replayGainEnabled: StateFlow<Boolean> = _replayGainEnabled

    override fun setReplayGainEnabled(value: Boolean) {
        prefs.edit { putBoolean(KEY_REPLAY_GAIN_ENABLED, value) }
        _replayGainEnabled.value = value
    }

    private val _ditherEnabled = MutableStateFlow(prefs.getBoolean(KEY_DITHER_ENABLED, false))
    override val ditherEnabled: StateFlow<Boolean> = _ditherEnabled

    override fun setDitherEnabled(value: Boolean) {
        prefs.edit { putBoolean(KEY_DITHER_ENABLED, value) }
        _ditherEnabled.value = value
    }

    private val _crossfadeEnabled = MutableStateFlow(prefs.getBoolean(KEY_CROSSFADE_ENABLED, false))
    override val crossfadeEnabled: StateFlow<Boolean> = _crossfadeEnabled

    override fun setCrossfadeEnabled(value: Boolean) {
        prefs.edit { putBoolean(KEY_CROSSFADE_ENABLED, value) }
        _crossfadeEnabled.value = value
    }

    private val _smartCrossfadeEnabled = MutableStateFlow(prefs.getBoolean(KEY_SMART_CROSSFADE_ENABLED, false))
    override val smartCrossfadeEnabled: StateFlow<Boolean> = _smartCrossfadeEnabled

    override fun setSmartCrossfadeEnabled(value: Boolean) {
        prefs.edit { putBoolean(KEY_SMART_CROSSFADE_ENABLED, value) }
        _smartCrossfadeEnabled.value = value
    }

    private val _playbackGainDb = MutableStateFlow(prefs.getFloat(KEY_PLAYBACK_GAIN_DB, 0f))
    override val playbackGainDb: StateFlow<Float> = _playbackGainDb

    override fun setPlaybackGainDb(value: Float) {
        prefs.edit { putFloat(KEY_PLAYBACK_GAIN_DB, value) }
        _playbackGainDb.value = value
    }

    // Default OFF: turning it on silently disables whatever DSP the user already had enabled, so
    // it has to be a deliberate choice rather than something they wake up in.
    private val _hiFiEnabled = MutableStateFlow(prefs.getBoolean(KEY_HIFI_ENABLED, false))
    override val hiFiEnabled: StateFlow<Boolean> = _hiFiEnabled

    override fun setHiFiEnabled(value: Boolean) {
        prefs.edit { putBoolean(KEY_HIFI_ENABLED, value) }
        _hiFiEnabled.value = value
    }

    private val _nightModeEnabled = MutableStateFlow(prefs.getBoolean(KEY_NIGHT_MODE_ENABLED, false))
    override val nightModeEnabled: StateFlow<Boolean> = _nightModeEnabled

    override fun setNightModeEnabled(value: Boolean) {
        prefs.edit { putBoolean(KEY_NIGHT_MODE_ENABLED, value) }
        _nightModeEnabled.value = value
    }

    private val _amoledEnabled = MutableStateFlow(prefs.getBoolean(KEY_AMOLED_ENABLED, false))
    override val amoledEnabled: StateFlow<Boolean> = _amoledEnabled

    override fun setAmoledEnabled(value: Boolean) {
        prefs.edit { putBoolean(KEY_AMOLED_ENABLED, value) }
        _amoledEnabled.value = value
    }

    private val _outputProfilesEnabled = MutableStateFlow(prefs.getBoolean(KEY_OUTPUT_PROFILES_ENABLED, false))
    override val outputProfilesEnabled: StateFlow<Boolean> = _outputProfilesEnabled

    override fun setOutputProfilesEnabled(value: Boolean) {
        prefs.edit { putBoolean(KEY_OUTPUT_PROFILES_ENABLED, value) }
        _outputProfilesEnabled.value = value
    }

    private val _outputProfiles = MutableStateFlow(
        OutputDeviceType.entries.associateWith { readOutputProfile(it) },
    )
    override val outputProfiles: StateFlow<Map<OutputDeviceType, OutputProfile>> = _outputProfiles

    override fun setOutputProfile(type: OutputDeviceType, profile: OutputProfile) {
        require(profile.eqGainsDb.size == EQ_BAND_COUNT) { "expected $EQ_BAND_COUNT gains, got ${profile.eqGainsDb.size}" }
        val serialized = profile.eqGainsDb.joinToString(",") + "|" + profile.volumeLimitPercent
        prefs.edit { putString(outputProfileKey(type), serialized) }
        _outputProfiles.value = _outputProfiles.value + (type to profile)
    }

    private fun readOutputProfile(type: OutputDeviceType): OutputProfile {
        val raw = prefs.getString(outputProfileKey(type), null) ?: return OutputProfile.IDENTITY
        val parts = raw.split("|")
        val gains = parts.getOrNull(0)?.split(",")?.mapNotNull { it.toFloatOrNull() }
        val limit = parts.getOrNull(1)?.toIntOrNull()
        return if (gains?.size == EQ_BAND_COUNT && limit != null) {
            OutputProfile(gains, limit)
        } else {
            OutputProfile.IDENTITY
        }
    }

    private val _stands4Uid = MutableStateFlow(prefs.getString(KEY_STANDS4_UID, "") ?: "")
    override val stands4Uid: StateFlow<String> = _stands4Uid

    override fun setStands4Uid(value: String) {
        prefs.edit { putString(KEY_STANDS4_UID, value) }
        _stands4Uid.value = value
    }

    private val _stands4Token = MutableStateFlow(prefs.getString(KEY_STANDS4_TOKEN, "") ?: "")
    override val stands4Token: StateFlow<String> = _stands4Token

    override fun setStands4Token(value: String) {
        prefs.edit { putString(KEY_STANDS4_TOKEN, value) }
        _stands4Token.value = value
    }

    private val _deeplApiKey = MutableStateFlow(prefs.getString(KEY_DEEPL_API_KEY, "") ?: "")
    override val deeplApiKey: StateFlow<String> = _deeplApiKey

    override fun setDeeplApiKey(value: String) {
        prefs.edit { putString(KEY_DEEPL_API_KEY, value) }
        _deeplApiKey.value = value
    }

    // Comma-joined track ids - they're UUID-shaped (no commas of their own), same "plain
    // delimited string" pattern already used elsewhere in this class, no JSON needed for a flat list.
    private val _lastPlaybackQueueTrackIds = MutableStateFlow(
        prefs.getString(KEY_LAST_PLAYBACK_QUEUE, "")?.split(",")?.filter { it.isNotBlank() } ?: emptyList(),
    )
    override val lastPlaybackQueueTrackIds: StateFlow<List<String>> = _lastPlaybackQueueTrackIds

    private val _lastPlaybackQueueIndex = MutableStateFlow(prefs.getInt(KEY_LAST_PLAYBACK_QUEUE_INDEX, 0))
    override val lastPlaybackQueueIndex: StateFlow<Int> = _lastPlaybackQueueIndex

    private val _lastPlaybackPositionMs = MutableStateFlow(prefs.getLong(KEY_LAST_PLAYBACK_POSITION_MS, 0L))
    override val lastPlaybackPositionMs: StateFlow<Long> = _lastPlaybackPositionMs

    private val _lastPlaybackPausedAt = MutableStateFlow(prefs.getLong(KEY_LAST_PLAYBACK_PAUSED_AT, 0L))
    override val lastPlaybackPausedAt: StateFlow<Long> = _lastPlaybackPausedAt

    // П.md §2 "Режим наблюдения за папкой" - SAF tree URIs the user asked to keep in sync.
    private val _watchedFolders = MutableStateFlow(
        prefs.getString(KEY_WATCHED_FOLDERS, "")?.split(",")?.filter { it.isNotBlank() } ?: emptyList(),
    )
    override val watchedFolders: StateFlow<List<String>> = _watchedFolders

    override fun addWatchedFolder(treeUri: String) {
        val updated = (_watchedFolders.value + treeUri).distinct()
        prefs.edit { putString(KEY_WATCHED_FOLDERS, updated.joinToString(",")) }
        _watchedFolders.value = updated
    }

    override fun removeWatchedFolder(treeUri: String) {
        val updated = _watchedFolders.value - treeUri
        prefs.edit { putString(KEY_WATCHED_FOLDERS, updated.joinToString(",")) }
        _watchedFolders.value = updated
    }

    override fun setLastPlayback(queueTrackIds: List<String>, queueIndex: Int, positionMs: Long, pausedAt: Long) {
        prefs.edit {
            putString(KEY_LAST_PLAYBACK_QUEUE, queueTrackIds.joinToString(","))
            putInt(KEY_LAST_PLAYBACK_QUEUE_INDEX, queueIndex)
            putLong(KEY_LAST_PLAYBACK_POSITION_MS, positionMs)
            putLong(KEY_LAST_PLAYBACK_PAUSED_AT, pausedAt)
        }
        _lastPlaybackQueueTrackIds.value = queueTrackIds
        _lastPlaybackQueueIndex.value = queueIndex
        _lastPlaybackPositionMs.value = positionMs
        _lastPlaybackPausedAt.value = pausedAt
    }

    private fun today(): String = java.time.LocalDate.now().toString()

    private val _stands4RequestsToday = MutableStateFlow(
        if (prefs.getString(KEY_STANDS4_REQUEST_DATE, null) == today()) {
            prefs.getInt(KEY_STANDS4_REQUEST_COUNT, 0)
        } else {
            0
        },
    )
    override val stands4RequestsToday: StateFlow<Int> = _stands4RequestsToday

    override fun recordStands4Request() {
        val isNewDay = prefs.getString(KEY_STANDS4_REQUEST_DATE, null) != today()
        val newCount = if (isNewDay) 1 else prefs.getInt(KEY_STANDS4_REQUEST_COUNT, 0) + 1
        prefs.edit {
            putString(KEY_STANDS4_REQUEST_DATE, today())
            putInt(KEY_STANDS4_REQUEST_COUNT, newCount)
        }
        _stands4RequestsToday.value = newCount
    }

    private val _shuffleMode = MutableStateFlow(
        prefs.getString(KEY_SHUFFLE_MODE, null)?.let { runCatching { ShuffleMode.valueOf(it) }.getOrNull() }
            ?: ShuffleMode.TRUE_RANDOM,
    )
    override val shuffleMode: StateFlow<ShuffleMode> = _shuffleMode

    override fun setShuffleMode(mode: ShuffleMode) {
        prefs.edit { putString(KEY_SHUFFLE_MODE, mode.name) }
        _shuffleMode.value = mode
    }

    private val _sessions = MutableStateFlow(readSessions())
    override val sessions: StateFlow<List<Session>> = _sessions

    override fun saveSession(session: Session) {
        val updated = _sessions.value.filterNot { it.name == session.name } + session
        writeSessions(updated)
    }

    override fun deleteSession(name: String) {
        writeSessions(_sessions.value.filterNot { it.name == name })
    }

    private val _lastAppliedSessionName = MutableStateFlow(prefs.getString(KEY_LAST_APPLIED_SESSION, null))
    override val lastAppliedSessionName: StateFlow<String?> = _lastAppliedSessionName

    override fun setLastAppliedSessionName(name: String?) {
        prefs.edit { putString(KEY_LAST_APPLIED_SESSION, name) }
        _lastAppliedSessionName.value = name
    }

    private val _scrobblingEnabled = MutableStateFlow(prefs.getBoolean(KEY_SCROBBLING_ENABLED, false))
    override val scrobblingEnabled: StateFlow<Boolean> = _scrobblingEnabled
    override fun setScrobblingEnabled(value: Boolean) {
        prefs.edit { putBoolean(KEY_SCROBBLING_ENABLED, value) }
        _scrobblingEnabled.value = value
    }

    private val _listenBrainzToken = MutableStateFlow(prefs.getString(KEY_LISTENBRAINZ_TOKEN, null))
    override val listenBrainzToken: StateFlow<String?> = _listenBrainzToken
    override fun setListenBrainzToken(token: String?) {
        val trimmed = token?.trim()?.takeIf { it.isNotEmpty() }
        prefs.edit { putString(KEY_LISTENBRAINZ_TOKEN, trimmed) }
        _listenBrainzToken.value = trimmed
    }

    private val _homeBlocks = MutableStateFlow(readHomeBlocks())
    override val homeBlocks: StateFlow<List<dev.nami.domain.HomeBlockConfig>> = _homeBlocks
    override fun setHomeBlocks(blocks: List<dev.nami.domain.HomeBlockConfig>) {
        val array = JSONArray()
        blocks.forEach { array.put(JSONObject().put("type", it.type.name).put("enabled", it.enabled)) }
        prefs.edit { putString(KEY_HOME_BLOCKS, array.toString()) }
        _homeBlocks.value = blocks
    }

    private fun readHomeBlocks(): List<dev.nami.domain.HomeBlockConfig> {
        val raw = prefs.getString(KEY_HOME_BLOCKS, null) ?: return dev.nami.domain.DEFAULT_HOME_BLOCKS
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val obj = array.optJSONObject(i) ?: return@mapNotNull null
                val type = runCatching { dev.nami.domain.HomeBlockType.valueOf(obj.optString("type")) }.getOrNull() ?: return@mapNotNull null
                dev.nami.domain.HomeBlockConfig(type, obj.optBoolean("enabled", true))
            }
        }.getOrDefault(dev.nami.domain.DEFAULT_HOME_BLOCKS)
    }

    private fun writeSessions(sessions: List<Session>) {
        val array = JSONArray()
        sessions.forEach { session ->
            array.put(
                JSONObject().apply {
                    put("name", session.name)
                    put("eqGainsDb", JSONArray(session.eqGainsDb))
                    put("crossfadeEnabled", session.crossfadeEnabled)
                    put("sleepTimerMinutes", session.sleepTimerMinutes ?: JSONObject.NULL)
                    put("shuffleEnabled", session.shuffleEnabled)
                    put("repeatMode", session.repeatMode.name)
                },
            )
        }
        prefs.edit { putString(KEY_SESSIONS, array.toString()) }
        _sessions.value = sessions
    }

    private fun readSessions(): List<Session> {
        val raw = prefs.getString(KEY_SESSIONS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val obj = array.optJSONObject(i) ?: return@mapNotNull null
                val gains = obj.optJSONArray("eqGainsDb") ?: return@mapNotNull null
                Session(
                    name = obj.optString("name"),
                    eqGainsDb = (0 until gains.length()).map { gains.optDouble(it).toFloat() },
                    crossfadeEnabled = obj.optBoolean("crossfadeEnabled"),
                    sleepTimerMinutes = obj.opt("sleepTimerMinutes")?.takeIf { it != JSONObject.NULL } as? Int,
                    shuffleEnabled = obj.optBoolean("shuffleEnabled"),
                    repeatMode = runCatching { dev.nami.domain.RepeatMode.valueOf(obj.optString("repeatMode")) }.getOrDefault(dev.nami.domain.RepeatMode.OFF),
                )
            }
        }.getOrDefault(emptyList())
    }
}
