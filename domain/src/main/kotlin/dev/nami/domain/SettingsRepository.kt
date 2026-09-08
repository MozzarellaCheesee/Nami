package dev.nami.domain

import kotlinx.coroutines.flow.StateFlow

/** Which output route is currently active - drives per-device profiles (below). Priority when
 * more than one is physically connected: USB_DAC > BLUETOOTH > WIRED > SPEAKER (a USB DAC or BT
 * headset being connected is a much stronger signal of "this is what's playing" than a wired jack
 * that might just be a charging cable's audio pins). */
enum class OutputDeviceType { WIRED, BLUETOOTH, USB_DAC, SPEAKER }

/** План.md §22.14 "Гибкий shuffle". TRUE_RANDOM is a flat random permutation (already inherently
 * "no repeats until the cycle ends" - it reorders the existing queue once, it doesn't resample
 * with replacement). WEIGHTED_BY_STALENESS biases toward tracks that haven't played in a while
 * (Track.lastPlayed) so a shuffle surfaces forgotten tracks more often than ones on repeat.
 * Rating-weighted isn't here yet - Track has no rating field in this codebase. */
enum class ShuffleMode { TRUE_RANDOM, WEIGHTED_BY_STALENESS }

/** Per-device profile from План.md §16/§20: its own EQ and a volume ceiling, applied automatically
 * when the routed output changes. Crossfeed/ReplayGain-mode aren't per-profile here - crossfeed
 * doesn't exist as a processor in this codebase yet, and ReplayGain mode is a single global
 * on/off, not something that plausibly differs per output device. */
data class OutputProfile(val eqGainsDb: List<Float>, val volumeLimitPercent: Int) {
    companion object {
        val IDENTITY = OutputProfile(eqGainsDb = List(9) { 0f }, volumeLimitPercent = 100)
    }
}

/** План.md §22.11 "Сессии" - a named bundle of settings applied in one tap ("Учёба"/"Дорога"/
 * "Сон", or the user's own). Deliberately doesn't snapshot the queue itself (that needs real
 * playlist infra to restore reliably) - covers what the plan explicitly calls out as the other
 * half: "свой EQ, громкость и таймером". Applying one is orchestrated by whatever screen owns
 * both this repository and PlayerRepository (the sleep timer lives on that one, not here). */
data class Session(
    val name: String,
    val eqGainsDb: List<Float>,
    val crossfadeEnabled: Boolean,
    /** Null = don't touch/start a sleep timer when this session is applied. */
    val sleepTimerMinutes: Int?,
    /** Раньше сессия не запоминала перемешку/зацикливание вообще - "Дорога" и "Дома" на деле
     * часто отличаются именно этим, не только EQ. */
    val shuffleEnabled: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
)

/** App-wide preferences (SharedPreferences-backed) - interface lives in :domain so feature
 * modules that need a setting (e.g. feature:player gating karaoke) don't have to depend on
 * :data directly. */
interface SettingsRepository {
    val autoOpenPlayer: StateFlow<Boolean>
    fun setAutoOpenPlayer(value: Boolean)

    val hideSystemBars: StateFlow<Boolean>
    fun setHideSystemBars(value: Boolean)

    /** Word-level karaoke highlight sweep - off by default (best-effort timing, not always
     * correct), opt-in from Settings. */
    val karaokeEnabled: StateFlow<Boolean>
    fun setKaraokeEnabled(value: Boolean)

    /** Study mode (Beta): translation hidden per line until tapped, quiz entry point in the
     * vocabulary screen. Off by default, same reasoning as karaoke - opt-in, not fully polished. */
    val studyModeEnabled: StateFlow<Boolean>
    fun setStudyModeEnabled(value: Boolean)

    /** Local file path to a user-picked .ttf/.otf for the lyrics screen, copied into app storage
     * (a content:// pick isn't a stable path). Null = system default font. */
    val lyricsFontPath: StateFlow<String?>
    fun setLyricsFontPath(path: String?)

    /** Группа E "свой шрифт интерфейса" - тот же механизм что lyricsFontPath, но применяется
     * глобально через NamiTheme's typography, не только на экране лирики. */
    val uiFontPath: StateFlow<String?>
    fun setUiFontPath(path: String?)

    /** Группа E "настраиваемые жесты" - см. GestureAction. */
    val doubleTapArtworkAction: StateFlow<GestureAction>
    fun setDoubleTapArtworkAction(action: GestureAction)

    /** Этап 4's parametric EQ (Beta) - off by default: it sits directly in the path of every
     * second of audio the app plays, so a subtle DSP bug means "everything sounds wrong" rather
     * than "one screen is broken". Bass/mid/treble in dB, ±12 typical range. */
    val eqEnabled: StateFlow<Boolean>
    fun setEqEnabled(value: Boolean)

    /** One gain per band, in ParametricEqAudioProcessor.BAND_FREQS_HZ order (9 bands: 63Hz..16kHz). */
    val eqBandGains: StateFlow<List<Float>>
    fun setEqBandGains(gainsDb: List<Float>)

    /** Bit-perfect USB output (Этап 10, Beta) - Android 14+'s AudioMixerAttributes API only,
     * requires vendor HAL support most devices don't have; off by default and silently falls
     * back to the normal mixed path when the device/DAC can't actually do it. */
    val bitPerfectUsbEnabled: StateFlow<Boolean>
    fun setBitPerfectUsbEnabled(value: Boolean)

    /** ReplayGain-lite (Этап 4, Beta) - RMS-loudness normalization, not true EBU R128. Off by
     * default, same "sits in every second of audio" reasoning as EQ. */
    val replayGainEnabled: StateFlow<Boolean>
    fun setReplayGainEnabled(value: Boolean)

    /** TPDF dither before the sink's own bit-depth truncation (Этап 4, Beta). Off by default. */
    val ditherEnabled: StateFlow<Boolean>
    fun setDitherEnabled(value: Boolean)

    /** Fade-out/fade-in "soft cut" between tracks (Этап 4, Beta) - not a true overlapping mix,
     * see CrossfadeController's own doc for why. Off by default. */
    val crossfadeEnabled: StateFlow<Boolean>
    fun setCrossfadeEnabled(value: Boolean)

    /** Этап 6's "умный кроссфейд" (План.md §22.9, Beta) - only takes effect while
     * [crossfadeEnabled] is also on. Skips the crossfade for a track that ends abruptly (loud
     * right up to a hard cut) instead of chopping its ending early; BPM-matching (the other half
     * of "уместно" from the plan) isn't implemented - see TrackEndingAnalyzer's own doc. */
    val smartCrossfadeEnabled: StateFlow<Boolean>
    fun setSmartCrossfadeEnabled(value: Boolean)

    /** "Усиление воспроизведения" - flat library-wide boost (0/3/6 dB), independent of
     * per-track ReplayGain. */
    val playbackGainDb: StateFlow<Float>
    fun setPlaybackGainDb(value: Float)

    /** Hi-Fi (Beta) - "shortest path" output: while it's on, the custom DSP AudioSink is never
     * built at all, so EQ/ReplayGain/dither/усиление are bypassed and decoded samples reach
     * AudioTrack untouched by this app. It cannot promise bit-perfect (AudioFlinger still mixes
     * and may resample - only the separate bit-perfect USB path can skip that, and only on
     * hardware that supports it); what it does promise is that Nami itself adds nothing. Off by
     * default so the DSP toggles keep working as-is unless the user asks for the direct path. */
    val hiFiEnabled: StateFlow<Boolean>
    fun setHiFiEnabled(value: Boolean)

    /** Now Playing's "night mode" pill - a warmer, dimmer ambient backdrop for late-night
     * listening (not a separate app-wide theme; scoped to that one screen's own blurred-artwork
     * background). Persisted so it's remembered across sessions like every other toggle here. */
    val nightModeEnabled: StateFlow<Boolean>
    fun setNightModeEnabled(value: Boolean)

    /** Дизайн.md "Режим AMOLED" - чистый #000000 вместо ink-900, поверх тёмной темы. */
    val amoledEnabled: StateFlow<Boolean>
    fun setAmoledEnabled(value: Boolean)

    /** Этап 4's "профили по устройству вывода" (Beta) - off by default, same reasoning as EQ:
     * auto-switching gains/volume the instant a route changes is exactly the kind of thing that
     * needs to be opt-in, not something that surprises a user mid-listen. */
    val outputProfilesEnabled: StateFlow<Boolean>
    fun setOutputProfilesEnabled(value: Boolean)

    /** One [OutputProfile] per [OutputDeviceType], defaulting to [OutputProfile.IDENTITY] (flat
     * EQ, no volume limit) until the user edits one. */
    val outputProfiles: StateFlow<Map<OutputDeviceType, OutputProfile>>
    fun setOutputProfile(type: OutputDeviceType, profile: OutputProfile)

    /** STANDS4 lyrics fallback credentials - each user's own (Settings -> Лирика), not a key
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

    /** "Умное возобновление" (План.md §22.10) needs to survive the process actually dying, not
     * just the app being backgrounded - a paused, non-foreground PlaybackService is killable by
     * Android at any time, wiping ExoPlayer's whole in-memory queue. Persisted here so a cold
     * start can restore the WHOLE queue (not just the one playing track) and seek to the exact
     * position (never auto-plays) if the pause was recent enough. [lastPlaybackQueueTrackIds] is
     * every track id in the queue's own order; empty means "nothing to restore". */
    val lastPlaybackQueueTrackIds: StateFlow<List<String>>
    val lastPlaybackQueueIndex: StateFlow<Int>
    val lastPlaybackPositionMs: StateFlow<Long>
    val lastPlaybackPausedAt: StateFlow<Long>
    fun setLastPlayback(queueTrackIds: List<String>, queueIndex: Int, positionMs: Long, pausedAt: Long)

    /** П.md §2 "Режим наблюдения за папкой" - SAF tree URIs to keep re-scanning. No true
     * background inotify-style watch (Android has none for SAF trees) - rescanned on cold start
     * and via a manual "Обновить" action instead. */
    val watchedFolders: StateFlow<List<String>>
    fun addWatchedFolder(treeUri: String)
    fun removeWatchedFolder(treeUri: String)

    /** DeepL API key for lyrics translation (Settings -> Лирика) - each user's own free-tier
     * key (500k chars/month), not shared across installs. Blank means "not configured", the
     * on-device MLKit translator (worse quality, esp. JA->RU, but keyless/offline) is used
     * instead - see LyricsRepositoryImpl.translateToRussian. */
    val deeplApiKey: StateFlow<String>
    fun setDeeplApiKey(value: String)

    /** См. [ShuffleMode]. */
    val shuffleMode: StateFlow<ShuffleMode>
    fun setShuffleMode(mode: ShuffleMode)

    /** См. [Session]. */
    val sessions: StateFlow<List<Session>>
    fun saveSession(session: Session)
    fun deleteSession(name: String)

    /** Имя последней применённой сессии - для виджета "Сессии" (группа E), у которого иначе нет
     * способа показать, какая пилюля сейчас "активна" (нажатие меняет только EQ/кроссфейд
     * настройки, само по себе не отслеживается как состояние). Null пока ни одна не применена. */
    val lastAppliedSessionName: StateFlow<String?>
    fun setLastAppliedSessionName(name: String?)

    /** П.md §23.23 "Скробблинг" - ListenBrainz только (свой user-токен вставляется в Настройках,
     * никакого OAuth-приложения/api_key не нужно - Last.fm требует зарегистрированное приложение
     * с api_key+api_secret, которых у этого проекта нет и заводить их - отдельное решение).
     * Off по умолчанию: null-токен уже фактически выключает отправку, но отдельный тумблер
     * позволяет придержать без стирания токена. */
    val scrobblingEnabled: StateFlow<Boolean>
    fun setScrobblingEnabled(value: Boolean)
    val listenBrainzToken: StateFlow<String?>
    fun setListenBrainzToken(token: String?)

    /** П.md §14 "Главный экран - конструктор" - см. HomeBlock.kt. Порядок списка = порядок
     * отображения. */
    val homeBlocks: StateFlow<List<HomeBlockConfig>>
    fun setHomeBlocks(blocks: List<HomeBlockConfig>)

    /** П.md §17 "Now Playing - конструктор макета". Всё, что настраивается, лежит НИЖЕ обложки:
     * сам HorizontalPager с его gesture/анимационной синхронизацией (см. NowPlayingScreen.kt,
     * история фиксов там - десятки коммитов) не трогается ни одной из этих настроек.
     *
     * Видимость строки техинфо и видимость каждой из двух кнопок ряда управления по отдельности -
     * перемешать и зациклить настраиваются независимо, раньше это был один общий переключатель. */
    val nowPlayingShowTechInfo: StateFlow<Boolean>
    fun setNowPlayingShowTechInfo(value: Boolean)
    val nowPlayingShowShuffle: StateFlow<Boolean>
    fun setNowPlayingShowShuffle(value: Boolean)
    val nowPlayingShowRepeat: StateFlow<Boolean>
    fun setNowPlayingShowRepeat(value: Boolean)

    /** "Порядок блоков" из §17 - список секций ниже обложки в порядке показа, см.
     * [NowPlayingBlock]. Неизвестные имена при чтении отбрасываются, пропавшие дописываются в
     * конец - тот же приём, что у [homeBlocks]. */
    val nowPlayingBlockOrder: StateFlow<List<NowPlayingBlock>>
    fun setNowPlayingBlockOrder(order: List<NowPlayingBlock>)

    /** "Размер обложки" из §17, сделанный единственным безопасным способом: меняется только
     * боковой отступ пейджера (peekDp), из которого считается ширина страницы. Сама логика
     * HorizontalPager - драг, фling, автопродвижение - не знает о константе ничего, поэтому
     * компактный вариант не может её сломать. true = обложка меньше, соседние шире выглядывают. */
    val nowPlayingCompactCover: StateFlow<Boolean>
    fun setNowPlayingCompactCover(value: Boolean)

    /** "Форма прогресс-бара" из §17 - линия вместо волны (см. LineScrubber). Линейный вариант
     * не рисует метки моментов и не ловит долгий тап, это осознанное упрощение. */
    val nowPlayingLineProgress: StateFlow<Boolean>
    fun setNowPlayingLineProgress(value: Boolean)

    /** П.md §26 "Редактор темы" - цветовые токены только (см. NamiColors.EDITABLE_TOKENS doc для
     * того, чего в редакторе пока нет: форма/плотность/типографика/прозрачность/пресеты/
     * автопереключение/экспорт). Ключ - имя токена (NamiColors.TOKEN_*), значение - hex-строка. */
    val themeColorOverrides: StateFlow<Map<String, String>>
    fun setThemeColorOverride(token: String, hex: String?)
    fun resetThemeColors()

    /** П.md §26 "Форма" - радиус скругления в dp по токенам NamiRadius.TOKEN_* (карточки/кнопки/
     * шиты). Тот же JSON-в-SharedPreferences, что у цветов, только значение целое, а не hex. */
    val themeShapeOverrides: StateFlow<Map<String, Int>>
    fun setThemeShapeOverride(token: String, dp: Int?)

    /** П.md §26 "Плотность" - общий множитель вертикального ритма (NamiDensity.COMPACT/NORMAL/
     * SPACIOUS). Применён только к высоте строки списка треков, см. doc NamiDensity. */
    val themeDensityScale: StateFlow<Float>
    fun setThemeDensityScale(value: Float)

    /** П.md §26 "масштаб шрифта" - множитель размеров MaterialTheme.typography (NamiTypeScale.
     * SCALES), поверх системного масштаба, а не вместо него. Гарнитура настраивается отдельно,
     * через uiFontPath - это именно размер. */
    val themeFontScale: StateFlow<Float>
    fun setThemeFontScale(value: Float)

    /** П.md §26 "отдельный переключатель без размытия для слабых устройств". true = как было,
     * размытие рисуется (см. NamiEffects/namiBlur). Не сбрасывается вместе с темой: это про
     * железо устройства, а не про внешний вид. */
    val blurEnabled: StateFlow<Boolean>
    fun setBlurEnabled(value: Boolean)

    /** П.md §26 "Автопереключение", самое простое правило из списка: после 23:00 и до 6 утра
     * включать AMOLED-режим, днём возвращать как было. Проверяется на старте экрана, а не живым
     * таймером - честное упрощение, см. MainActivity. */
    val autoNightAmoled: StateFlow<Boolean>
    fun setAutoNightAmoled(value: Boolean)

    /** Сброс формы, плотности и масштаба текста к базовой теме - отдельно от [resetThemeColors],
     * чтобы кнопка "сбросить всё" в редакторе не смешивала разделы в одну необратимую операцию. */
    fun resetThemeShapeAndDensity()

    /** П.md §13 "Таб-бар настраиваемый" - весь список из восьми вкладок в порядке показа, с
     * флагом включения (см. [BottomTabConfig]). Хранится и читается ровно как [homeBlocks].
     * Ограничение 3-5 включённых держит UI конструктора, репозиторий значение не правит. */
    val bottomTabs: StateFlow<List<BottomTabConfig>>
    fun setBottomTabs(tabs: List<BottomTabConfig>)

    /** §13 "может скрыть подписи" - иконки без текста. */
    val bottomTabLabelsHidden: StateFlow<Boolean>
    fun setBottomTabLabelsHidden(value: Boolean)

    /** §13 "действия свайпов настраиваются" - свайп вбок по мини-плееру. Осмысленных значений
     * ровно два: SKIP_NEXT (как было - листание пейджера меняет трек) и NONE (пейджер не ловит
     * горизонтальный драг). Остальные [GestureAction] здесь бессмысленны: свайп двусторонний, а
     * "лайк влево и лайк вправо" - не действие. Свайп вверх (полный плеер) не настраивается: это
     * единственный способ раскрыть плеер жестом. */
    val miniPlayerSideSwipeAction: StateFlow<GestureAction>
    fun setMiniPlayerSideSwipeAction(action: GestureAction)

    /** П.md §17 "5 готовых пресетов макета". Только индикатор "что выбрали последним" - источник
     * правды по-прежнему сами переключатели (см. [NowPlayingLayoutPreset]). */
    val nowPlayingLayoutPreset: StateFlow<NowPlayingLayoutPreset>
    fun setNowPlayingLayoutPreset(preset: NowPlayingLayoutPreset)

    /** П.md §9 "кроссфид для наушников" - см. CrossfeedAudioProcessor. Выключен по умолчанию,
     * как и остальные DSP-тумблеры Аудиотракта. */
    val crossfeedEnabled: StateFlow<Boolean>
    fun setCrossfeedEnabled(value: Boolean)

    /** П.md §11 "Тест устройства" - готовая строка результата последнего прогона (человекочитаемая
     * таблица частот). Хранится как есть: она только показывается пользователю, автоматический
     * выбор частоты на её основе не сделан. */
    val deviceAudioProfile: StateFlow<String?>
    fun setDeviceAudioProfile(value: String?)

    companion object {
        const val STANDS4_DAILY_LIMIT = 100
    }
}
