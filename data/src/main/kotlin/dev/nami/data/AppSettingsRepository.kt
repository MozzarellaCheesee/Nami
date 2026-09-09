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

/** Дефолт темы для СВЕЖЕЙ установки - те же значения, что у пресета "Бумага" в app/ThemeIo.kt
 * (:data не может зависеть от :app, а тащить :core:designsystem только ради 12 hex-строк того
 * не стоит - если пресет там поменяется, эту копию тоже нужно поправить). Тёмная "Тушь" (пустой
 * override) остаётся дефолтом для уже существующих установок - это различает
 * [readThemeColorOverrides] через [SharedPreferences.getAll], не отдельный флаг: пустой файл
 * настроек бывает только на реально первом запуске, апдейт с прошлой версии SharedPreferences не
 * очищает, так что у любого, кто уже пользовался приложением, там уже что-то накопилось. */
private val FRESH_INSTALL_THEME_COLORS = mapOf(
    "Ink900" to "#F5F1E8",
    "Ink800" to "#EBE6DA",
    "Ink700" to "#E0DACC",
    "Ink600" to "#CFC7B5",
    "Ink500" to "#B4AB98",
    "Paper100" to "#1A1A18",
    "Paper70" to "#4A4844",
    "Paper40" to "#7A756B",
    "Shu" to "#C24A34",
    "Ai" to "#3E6098",
    "Kin" to "#8A6D12",
    "Wakaba" to "#3E7A44",
)
private const val KEY_AUTO_OPEN_PLAYER = "auto_open_player"
private const val KEY_HIDE_SYSTEM_BARS = "hide_system_bars"
private const val KEY_KARAOKE_ENABLED = "karaoke_enabled"
private const val KEY_STUDY_MODE_ENABLED = "study_mode_enabled"
private const val KEY_LYRICS_FONT_PATH = "lyrics_font_path"
private const val KEY_UI_FONT_PATH = "ui_font_path"
private const val KEY_UI_CJK_FONT_PATH = "ui_cjk_font_path"
private const val KEY_LYRICS_CJK_FONT_PATH = "lyrics_cjk_font_path"
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
// Онбординг "отключите оптимизацию батареи" (План.md Часть X, "убийцы фоновых процессов"):
// показываем один раз, дальше только вручную из Настроек. Флаг отдельный от самого разрешения -
// система может вернуть "оптимизируется" и после отказа пользователя, а долбить его каждый старт
// нельзя.
private const val KEY_BATTERY_HINT_SHOWN = "battery_hint_shown"
private const val KEY_SESSIONS = "sessions" // JSON array, see AppSettingsRepository.readSessions
private const val KEY_LAST_APPLIED_SESSION = "last_applied_session"
private const val KEY_OUTPUT_PROFILES_ENABLED = "output_profiles_enabled"
private const val KEY_SCROBBLING_ENABLED = "scrobbling_enabled"
private const val KEY_AIRPLAY_ENABLED = "airplay_enabled"
private const val KEY_YANDEX_STATION_ENABLED = "yandex_station_enabled"
private const val KEY_YANDEX_OAUTH_TOKEN = "yandex_oauth_token"
private const val KEY_YANDEX_CLIENT_ID = "yandex_client_id"
private const val KEY_JAMENDO_CLIENT_ID = "jamendo_client_id"
private const val KEY_SOUNDCLOUD_CLIENT_ID = "soundcloud_client_id"
private const val KEY_LISTENBRAINZ_TOKEN = "listenbrainz_token"
private const val KEY_HOME_BLOCKS = "home_blocks" // JSON array [{type, enabled}], see readHomeBlocks
private const val KEY_NOW_PLAYING_SHOW_TECH_INFO = "now_playing_show_tech_info"
// Устаревший общий ключ на обе кнопки - остался только как дефолт для двух ключей ниже.
private const val KEY_NOW_PLAYING_SHOW_SHUFFLE_REPEAT = "now_playing_show_shuffle_repeat"
private const val KEY_NOW_PLAYING_SHOW_SHUFFLE = "now_playing_show_shuffle"
private const val KEY_NOW_PLAYING_SHOW_REPEAT = "now_playing_show_repeat"
private const val KEY_NOW_PLAYING_BLOCK_ORDER = "now_playing_block_order" // CSV имён NowPlayingBlock
private const val KEY_THEME_COLOR_OVERRIDES = "theme_color_overrides" // JSON object {token: hex}
private const val KEY_NOW_PLAYING_COMPACT_COVER = "now_playing_compact_cover"
private const val KEY_NOW_PLAYING_LINE_PROGRESS = "now_playing_line_progress"
private const val KEY_THEME_SHAPE_OVERRIDES = "theme_shape_overrides" // JSON object {token: dp}
private const val KEY_THEME_DENSITY_SCALE = "theme_density_scale"
private const val KEY_THEME_FONT_SCALE = "theme_font_scale"
private const val KEY_BLUR_ENABLED = "blur_enabled"
private const val KEY_BOTTOM_TABS = "bottom_tabs" // JSON array [{tab, enabled}], см. readBottomTabs
private const val KEY_DEFAULT_START_SCREEN = "default_start_screen"
private const val KEY_NOW_PLAYING_MORE_ITEMS = "now_playing_more_items" // JSON array [{item, section, accent}]
private const val KEY_BOTTOM_TAB_LABELS_HIDDEN = "bottom_tab_labels_hidden"
private const val KEY_MINI_PLAYER_SIDE_SWIPE = "mini_player_side_swipe_action"
private const val KEY_NOW_PLAYING_LAYOUT_PRESET = "now_playing_layout_preset"
private const val KEY_CROSSFEED_ENABLED = "crossfeed_enabled"
private const val KEY_DEVICE_AUDIO_PROFILE = "device_audio_profile"
private const val KEY_CONVOLUTION_ENABLED = "convolution_enabled"
private const val KEY_CONVOLUTION_IR_PATH = "convolution_ir_path"
private const val KEY_AUTO_NIGHT_AMOLED = "auto_night_amoled"
// One "<CSV of 9 gains>|<volumeLimitPercent>" string per device type.
private fun outputProfileKey(type: OutputDeviceType) = "output_profile_${type.name}"

@Singleton
class AppSettingsRepository @Inject constructor(@ApplicationContext context: Context) : SettingsRepository {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Отдельное шифрованное хранилище - только для чужих секретов (OAuth-токен Яндекс ID).
     * Остальные настройки там не нужны: это обычные пользовательские предпочтения, а каждый
     * доступ к EncryptedSharedPreferences идёт через Keystore и стоит дороже.
     *
     * runCatching: на части прошивок Keystore ломается (сброс ключа после смены пароля экрана
     * блокировки, кривые AOSP-сборки) и EncryptedSharedPreferences бросает при создании. Тогда
     * откатываемся на обычный файл: потерять возможность войти в Яндекс ID хуже, чем хранить
     * токен как остальные настройки, а сам файл всё равно лежит в приватной песочнице приложения. */
    private val securePrefs: SharedPreferences = runCatching {
        androidx.security.crypto.EncryptedSharedPreferences.create(
            context,
            "nami_secure",
            androidx.security.crypto.MasterKey.Builder(context)
                .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM)
                .build(),
            androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrElse { context.getSharedPreferences("nami_secure_plain", Context.MODE_PRIVATE) }

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

    private val _uiCjkFontPath = MutableStateFlow(prefs.getString(KEY_UI_CJK_FONT_PATH, null))
    override val uiCjkFontPath: StateFlow<String?> = _uiCjkFontPath

    override fun setUiCjkFontPath(path: String?) {
        prefs.edit { putString(KEY_UI_CJK_FONT_PATH, path) }
        _uiCjkFontPath.value = path
    }

    private val _lyricsCjkFontPath = MutableStateFlow(prefs.getString(KEY_LYRICS_CJK_FONT_PATH, null))
    override val lyricsCjkFontPath: StateFlow<String?> = _lyricsCjkFontPath

    override fun setLyricsCjkFontPath(path: String?) {
        prefs.edit { putString(KEY_LYRICS_CJK_FONT_PATH, path) }
        _lyricsCjkFontPath.value = path
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

    private val _airPlayEnabled = MutableStateFlow(prefs.getBoolean(KEY_AIRPLAY_ENABLED, false))
    override val airPlayEnabled: StateFlow<Boolean> = _airPlayEnabled
    override fun setAirPlayEnabled(value: Boolean) {
        prefs.edit { putBoolean(KEY_AIRPLAY_ENABLED, value) }
        _airPlayEnabled.value = value
    }

    private val _yandexStationEnabled = MutableStateFlow(prefs.getBoolean(KEY_YANDEX_STATION_ENABLED, false))
    override val yandexStationEnabled: StateFlow<Boolean> = _yandexStationEnabled
    override fun setYandexStationEnabled(value: Boolean) {
        prefs.edit { putBoolean(KEY_YANDEX_STATION_ENABLED, value) }
        _yandexStationEnabled.value = value
    }

    private val _yandexOAuthToken = MutableStateFlow(securePrefs.getString(KEY_YANDEX_OAUTH_TOKEN, null))
    override val yandexOAuthToken: StateFlow<String?> = _yandexOAuthToken
    override fun setYandexOAuthToken(token: String?) {
        val trimmed = token?.trim()?.takeIf { it.isNotEmpty() }
        securePrefs.edit { putString(KEY_YANDEX_OAUTH_TOKEN, trimmed) }
        _yandexOAuthToken.value = trimmed
    }

    private val _yandexClientId = MutableStateFlow(prefs.getString(KEY_YANDEX_CLIENT_ID, null))
    override val yandexClientId: StateFlow<String?> = _yandexClientId
    override fun setYandexClientId(value: String?) {
        val trimmed = value?.trim()?.takeIf { it.isNotEmpty() }
        prefs.edit { putString(KEY_YANDEX_CLIENT_ID, trimmed) }
        _yandexClientId.value = trimmed
    }

    private val _jamendoClientId = MutableStateFlow(prefs.getString(KEY_JAMENDO_CLIENT_ID, null))
    override val jamendoClientId: StateFlow<String?> = _jamendoClientId
    override fun setJamendoClientId(value: String?) {
        val trimmed = value?.trim()?.takeIf { it.isNotEmpty() }
        prefs.edit { putString(KEY_JAMENDO_CLIENT_ID, trimmed) }
        _jamendoClientId.value = trimmed
    }

    private val _soundCloudClientId = MutableStateFlow(prefs.getString(KEY_SOUNDCLOUD_CLIENT_ID, null))
    override val soundCloudClientId: StateFlow<String?> = _soundCloudClientId
    override fun setSoundCloudClientId(value: String?) {
        val trimmed = value?.trim()?.takeIf { it.isNotEmpty() }
        prefs.edit { putString(KEY_SOUNDCLOUD_CLIENT_ID, trimmed) }
        _soundCloudClientId.value = trimmed
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

    private val _nowPlayingShowTechInfo = MutableStateFlow(prefs.getBoolean(KEY_NOW_PLAYING_SHOW_TECH_INFO, true))
    override val nowPlayingShowTechInfo: StateFlow<Boolean> = _nowPlayingShowTechInfo
    override fun setNowPlayingShowTechInfo(value: Boolean) {
        prefs.edit { putBoolean(KEY_NOW_PLAYING_SHOW_TECH_INFO, value) }
        _nowPlayingShowTechInfo.value = value
    }

    // Раньше на обе кнопки был один переключатель - его значение и становится дефолтом для
    // каждой из двух новых, чтобы у тех, кто их выключил, они не появились обратно.
    private val legacyShowShuffleRepeat = prefs.getBoolean(KEY_NOW_PLAYING_SHOW_SHUFFLE_REPEAT, true)

    private val _nowPlayingShowShuffle = MutableStateFlow(prefs.getBoolean(KEY_NOW_PLAYING_SHOW_SHUFFLE, legacyShowShuffleRepeat))
    override val nowPlayingShowShuffle: StateFlow<Boolean> = _nowPlayingShowShuffle
    override fun setNowPlayingShowShuffle(value: Boolean) {
        prefs.edit { putBoolean(KEY_NOW_PLAYING_SHOW_SHUFFLE, value) }
        _nowPlayingShowShuffle.value = value
    }

    private val _nowPlayingShowRepeat = MutableStateFlow(prefs.getBoolean(KEY_NOW_PLAYING_SHOW_REPEAT, legacyShowShuffleRepeat))
    override val nowPlayingShowRepeat: StateFlow<Boolean> = _nowPlayingShowRepeat
    override fun setNowPlayingShowRepeat(value: Boolean) {
        prefs.edit { putBoolean(KEY_NOW_PLAYING_SHOW_REPEAT, value) }
        _nowPlayingShowRepeat.value = value
    }

    private val _nowPlayingBlockOrder = MutableStateFlow(readNowPlayingBlockOrder())
    override val nowPlayingBlockOrder: StateFlow<List<dev.nami.domain.NowPlayingBlock>> = _nowPlayingBlockOrder
    override fun setNowPlayingBlockOrder(order: List<dev.nami.domain.NowPlayingBlock>) {
        prefs.edit { putString(KEY_NOW_PLAYING_BLOCK_ORDER, order.joinToString(",") { it.name }) }
        _nowPlayingBlockOrder.value = order
    }

    /** Имена через запятую, а не JSON: это просто список без полей. Неизвестное имя (блок из
     * будущей версии, откат назад) отбрасывается, пропавший блок дописывается в конец - иначе у
     * тех, кто уже трогал порядок, новые блоки не появились бы никогда. */
    private fun readNowPlayingBlockOrder(): List<dev.nami.domain.NowPlayingBlock> {
        val raw = prefs.getString(KEY_NOW_PLAYING_BLOCK_ORDER, null) ?: return dev.nami.domain.DEFAULT_NOW_PLAYING_BLOCKS
        val saved = raw.split(",")
            .mapNotNull { name -> runCatching { dev.nami.domain.NowPlayingBlock.valueOf(name.trim()) }.getOrNull() }
            .distinct()
        return saved + dev.nami.domain.DEFAULT_NOW_PLAYING_BLOCKS.filter { it !in saved }
    }

    private val _nowPlayingCompactCover = MutableStateFlow(prefs.getBoolean(KEY_NOW_PLAYING_COMPACT_COVER, false))
    override val nowPlayingCompactCover: StateFlow<Boolean> = _nowPlayingCompactCover
    override fun setNowPlayingCompactCover(value: Boolean) {
        prefs.edit { putBoolean(KEY_NOW_PLAYING_COMPACT_COVER, value) }
        _nowPlayingCompactCover.value = value
    }

    private val _nowPlayingLineProgress = MutableStateFlow(prefs.getBoolean(KEY_NOW_PLAYING_LINE_PROGRESS, false))
    override val nowPlayingLineProgress: StateFlow<Boolean> = _nowPlayingLineProgress
    override fun setNowPlayingLineProgress(value: Boolean) {
        prefs.edit { putBoolean(KEY_NOW_PLAYING_LINE_PROGRESS, value) }
        _nowPlayingLineProgress.value = value
    }

    private val _defaultStartScreen = MutableStateFlow(
        prefs.getString(KEY_DEFAULT_START_SCREEN, null)
            ?.let { runCatching { dev.nami.domain.BottomTab.valueOf(it) }.getOrNull() }
            ?: dev.nami.domain.BottomTab.LIBRARY,
    )
    override val defaultStartScreen: StateFlow<dev.nami.domain.BottomTab> = _defaultStartScreen
    override fun setDefaultStartScreen(tab: dev.nami.domain.BottomTab) {
        prefs.edit { putString(KEY_DEFAULT_START_SCREEN, tab.name) }
        _defaultStartScreen.value = tab
    }

    private val _bottomTabs = MutableStateFlow(readBottomTabs())
    override val bottomTabs: StateFlow<List<dev.nami.domain.BottomTabConfig>> = _bottomTabs
    override fun setBottomTabs(tabs: List<dev.nami.domain.BottomTabConfig>) {
        val array = JSONArray()
        tabs.forEach { array.put(JSONObject().put("tab", it.tab.name).put("enabled", it.enabled)) }
        prefs.edit { putString(KEY_BOTTOM_TABS, array.toString()) }
        _bottomTabs.value = tabs
    }

    /** Тот же приём, что у [readHomeBlocks]: неизвестные имена отбрасываются, появившиеся в новой
     * версии вкладки дописываются в конец (выключенными, как в дефолте). */
    private fun readBottomTabs(): List<dev.nami.domain.BottomTabConfig> {
        val raw = prefs.getString(KEY_BOTTOM_TABS, null) ?: return dev.nami.domain.DEFAULT_BOTTOM_TABS
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val obj = array.optJSONObject(i) ?: return@mapNotNull null
                val tab = runCatching { dev.nami.domain.BottomTab.valueOf(obj.optString("tab")) }.getOrNull() ?: return@mapNotNull null
                dev.nami.domain.BottomTabConfig(tab, obj.optBoolean("enabled", false))
            }
        }.getOrDefault(dev.nami.domain.DEFAULT_BOTTOM_TABS)
            .let { saved -> saved + dev.nami.domain.DEFAULT_BOTTOM_TABS.filter { d -> saved.none { it.tab == d.tab } } }
    }

    private val _nowPlayingMoreItems = MutableStateFlow(readNowPlayingMoreItems())
    override val nowPlayingMoreItems: StateFlow<List<dev.nami.domain.NowPlayingMoreConfig>> = _nowPlayingMoreItems
    override fun setNowPlayingMoreItems(items: List<dev.nami.domain.NowPlayingMoreConfig>) {
        val array = JSONArray()
        items.forEach {
            array.put(
                JSONObject()
                    .put("item", it.item.name)
                    .put("section", it.section.name)
                    .put("accent", it.accent.name),
            )
        }
        prefs.edit { putString(KEY_NOW_PLAYING_MORE_ITEMS, array.toString()) }
        _nowPlayingMoreItems.value = items
    }

    /** Тот же приём, что у [readBottomTabs]: неизвестные id отбрасываются, появившиеся в новой
     * версии пункты дописываются в конец со своими дефолтными секцией и цветом. */
    private fun readNowPlayingMoreItems(): List<dev.nami.domain.NowPlayingMoreConfig> {
        val raw = prefs.getString(KEY_NOW_PLAYING_MORE_ITEMS, null) ?: return dev.nami.domain.DEFAULT_NOW_PLAYING_MORE_ITEMS
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val obj = array.optJSONObject(i) ?: return@mapNotNull null
                val item = runCatching { dev.nami.domain.NowPlayingMoreItem.valueOf(obj.optString("item")) }.getOrNull()
                    ?: return@mapNotNull null
                val section = runCatching { dev.nami.domain.NowPlayingMoreSection.valueOf(obj.optString("section")) }.getOrNull()
                    ?: dev.nami.domain.NowPlayingMoreSection.LIST
                val accent = runCatching { dev.nami.domain.NowPlayingMoreAccent.valueOf(obj.optString("accent")) }.getOrNull()
                    ?: dev.nami.domain.NowPlayingMoreAccent.NEUTRAL
                dev.nami.domain.NowPlayingMoreConfig(item, section, accent)
            }
        }.getOrDefault(dev.nami.domain.DEFAULT_NOW_PLAYING_MORE_ITEMS)
            .let { saved -> saved + dev.nami.domain.DEFAULT_NOW_PLAYING_MORE_ITEMS.filter { d -> saved.none { it.item == d.item } } }
    }

    private val _bottomTabLabelsHidden = MutableStateFlow(prefs.getBoolean(KEY_BOTTOM_TAB_LABELS_HIDDEN, false))
    override val bottomTabLabelsHidden: StateFlow<Boolean> = _bottomTabLabelsHidden
    override fun setBottomTabLabelsHidden(value: Boolean) {
        prefs.edit { putBoolean(KEY_BOTTOM_TAB_LABELS_HIDDEN, value) }
        _bottomTabLabelsHidden.value = value
    }

    private val _miniPlayerSideSwipeAction = MutableStateFlow(
        prefs.getString(KEY_MINI_PLAYER_SIDE_SWIPE, null)
            ?.let { runCatching { dev.nami.domain.GestureAction.valueOf(it) }.getOrNull() }
            ?: dev.nami.domain.GestureAction.SKIP_NEXT,
    )
    override val miniPlayerSideSwipeAction: StateFlow<dev.nami.domain.GestureAction> = _miniPlayerSideSwipeAction
    override fun setMiniPlayerSideSwipeAction(action: dev.nami.domain.GestureAction) {
        prefs.edit { putString(KEY_MINI_PLAYER_SIDE_SWIPE, action.name) }
        _miniPlayerSideSwipeAction.value = action
    }

    private val _nowPlayingLayoutPreset = MutableStateFlow(
        prefs.getString(KEY_NOW_PLAYING_LAYOUT_PRESET, null)
            ?.let { runCatching { dev.nami.domain.NowPlayingLayoutPreset.valueOf(it) }.getOrNull() }
            ?: dev.nami.domain.NowPlayingLayoutPreset.CUSTOM,
    )
    override val nowPlayingLayoutPreset: StateFlow<dev.nami.domain.NowPlayingLayoutPreset> = _nowPlayingLayoutPreset
    override fun setNowPlayingLayoutPreset(preset: dev.nami.domain.NowPlayingLayoutPreset) {
        prefs.edit { putString(KEY_NOW_PLAYING_LAYOUT_PRESET, preset.name) }
        _nowPlayingLayoutPreset.value = preset
    }

    private val _crossfeedEnabled = MutableStateFlow(prefs.getBoolean(KEY_CROSSFEED_ENABLED, false))
    override val crossfeedEnabled: StateFlow<Boolean> = _crossfeedEnabled
    override fun setCrossfeedEnabled(value: Boolean) {
        prefs.edit { putBoolean(KEY_CROSSFEED_ENABLED, value) }
        _crossfeedEnabled.value = value
    }

    private val _deviceAudioProfile = MutableStateFlow(prefs.getString(KEY_DEVICE_AUDIO_PROFILE, null))
    override val deviceAudioProfile: StateFlow<String?> = _deviceAudioProfile
    override fun setDeviceAudioProfile(value: String?) {
        prefs.edit { putString(KEY_DEVICE_AUDIO_PROFILE, value) }
        _deviceAudioProfile.value = value
    }

    private val _convolutionEnabled = MutableStateFlow(prefs.getBoolean(KEY_CONVOLUTION_ENABLED, false))
    override val convolutionEnabled: StateFlow<Boolean> = _convolutionEnabled
    override fun setConvolutionEnabled(value: Boolean) {
        prefs.edit { putBoolean(KEY_CONVOLUTION_ENABLED, value) }
        _convolutionEnabled.value = value
    }

    private val _convolutionIrPath = MutableStateFlow(prefs.getString(KEY_CONVOLUTION_IR_PATH, null))
    override val convolutionIrPath: StateFlow<String?> = _convolutionIrPath
    override fun setConvolutionIrPath(value: String?) {
        prefs.edit { putString(KEY_CONVOLUTION_IR_PATH, value) }
        _convolutionIrPath.value = value
    }

    private val _themeColorOverrides = MutableStateFlow(readThemeColorOverrides())
    override val themeColorOverrides: StateFlow<Map<String, String>> = _themeColorOverrides
    override fun setThemeColorOverride(token: String, hex: String?) {
        val updated = if (hex != null) _themeColorOverrides.value + (token to hex) else _themeColorOverrides.value - token
        writeThemeColorOverrides(updated)
    }
    override fun resetThemeColors() = writeThemeColorOverrides(emptyMap())

    private fun writeThemeColorOverrides(overrides: Map<String, String>) {
        val obj = JSONObject()
        overrides.forEach { (token, hex) -> obj.put(token, hex) }
        prefs.edit { putString(KEY_THEME_COLOR_OVERRIDES, obj.toString()) }
        _themeColorOverrides.value = overrides
    }

    private val _themeShapeOverrides = MutableStateFlow(readThemeShapeOverrides())
    override val themeShapeOverrides: StateFlow<Map<String, Int>> = _themeShapeOverrides
    override fun setThemeShapeOverride(token: String, dp: Int?) {
        val updated = if (dp != null) _themeShapeOverrides.value + (token to dp) else _themeShapeOverrides.value - token
        writeThemeShapeOverrides(updated)
    }

    private val _themeDensityScale = MutableStateFlow(prefs.getFloat(KEY_THEME_DENSITY_SCALE, 1f))
    override val themeDensityScale: StateFlow<Float> = _themeDensityScale
    override fun setThemeDensityScale(value: Float) {
        prefs.edit { putFloat(KEY_THEME_DENSITY_SCALE, value) }
        _themeDensityScale.value = value
    }

    private val _themeFontScale = MutableStateFlow(prefs.getFloat(KEY_THEME_FONT_SCALE, 1f))
    override val themeFontScale: StateFlow<Float> = _themeFontScale
    override fun setThemeFontScale(value: Float) {
        prefs.edit { putFloat(KEY_THEME_FONT_SCALE, value) }
        _themeFontScale.value = value
    }

    // По умолчанию размытие включено - выключатель для слабых устройств, а не наоборот.
    private val _blurEnabled = MutableStateFlow(prefs.getBoolean(KEY_BLUR_ENABLED, true))
    override val blurEnabled: StateFlow<Boolean> = _blurEnabled
    override fun setBlurEnabled(value: Boolean) {
        prefs.edit { putBoolean(KEY_BLUR_ENABLED, value) }
        _blurEnabled.value = value
    }

    private val _autoNightAmoled = MutableStateFlow(prefs.getBoolean(KEY_AUTO_NIGHT_AMOLED, false))
    override val autoNightAmoled: StateFlow<Boolean> = _autoNightAmoled
    override fun setAutoNightAmoled(value: Boolean) {
        prefs.edit { putBoolean(KEY_AUTO_NIGHT_AMOLED, value) }
        _autoNightAmoled.value = value
    }

    override fun resetThemeShapeAndDensity() {
        writeThemeShapeOverrides(emptyMap())
        setThemeDensityScale(1f)
        setThemeFontScale(1f)
    }

    private fun writeThemeShapeOverrides(overrides: Map<String, Int>) {
        val obj = JSONObject()
        overrides.forEach { (token, dp) -> obj.put(token, dp) }
        prefs.edit { putString(KEY_THEME_SHAPE_OVERRIDES, obj.toString()) }
        _themeShapeOverrides.value = overrides
    }

    private fun readThemeShapeOverrides(): Map<String, Int> {
        val raw = prefs.getString(KEY_THEME_SHAPE_OVERRIDES, null) ?: return emptyMap()
        return runCatching {
            val obj = JSONObject(raw)
            obj.keys().asSequence().associateWith { obj.getInt(it) }
        }.getOrDefault(emptyMap())
    }

    private fun readThemeColorOverrides(): Map<String, String> {
        val raw = prefs.getString(KEY_THEME_COLOR_OVERRIDES, null)
        if (raw == null && prefs.all.isEmpty()) {
            // Реально первый запуск (см. комментарий у FRESH_INSTALL_THEME_COLORS) - пишем
            // сразу, не только возвращаем в памяти: иначе первое же изменение ЛЮБОЙ другой
            // настройки сделало бы prefs непустым, и на следующем запуске эта ветка перестала
            // бы срабатывать, молча откатив тему обратно на "Тушь".
            val obj = JSONObject()
            FRESH_INSTALL_THEME_COLORS.forEach { (token, hex) -> obj.put(token, hex) }
            prefs.edit { putString(KEY_THEME_COLOR_OVERRIDES, obj.toString()) }
            return FRESH_INSTALL_THEME_COLORS
        }
        if (raw == null) return emptyMap()
        return runCatching {
            val obj = JSONObject(raw)
            obj.keys().asSequence().associateWith { obj.getString(it) }
        }.getOrDefault(emptyMap())
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
            // Блоки, появившиеся в новой версии, дописываются в конец сохранённого порядка -
            // иначе у тех, кто уже открывал конструктор, они не появились бы никогда.
            .let { saved -> saved + dev.nami.domain.DEFAULT_HOME_BLOCKS.filter { d -> saved.none { it.type == d.type } } }
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

    // Не в SettingsRepository (domain) - это чисто андроидная штука уровня app, интерфейсу
    // предметной области про оптимизацию батареи знать незачем.
    var batteryHintShown: Boolean
        get() = prefs.getBoolean(KEY_BATTERY_HINT_SHOWN, false)
        set(value) = prefs.edit { putBoolean(KEY_BATTERY_HINT_SHOWN, value) }

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
