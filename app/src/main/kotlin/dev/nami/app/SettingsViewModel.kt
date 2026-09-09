package dev.nami.app

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.nami.data.AppSettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appSettingsRepository: AppSettingsRepository,
    playerRepository: dev.nami.domain.PlayerRepository,
) : ViewModel() {

    /** Только для пресета темы "Из обложки" (П.md §26) - экрану настроек нужен один путь к
     * картинке текущего трека, а не весь плеер. */
    val nowPlayingArtworkPath: StateFlow<String?> = playerRepository.queue
        .map { it.nowPlaying?.artworkPath }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, null)

    val autoOpenPlayer: StateFlow<Boolean> = appSettingsRepository.autoOpenPlayer
    val hideSystemBars: StateFlow<Boolean> = appSettingsRepository.hideSystemBars
    val karaokeEnabled: StateFlow<Boolean> = appSettingsRepository.karaokeEnabled
    val studyModeEnabled: StateFlow<Boolean> = appSettingsRepository.studyModeEnabled
    val lyricsFontPath: StateFlow<String?> = appSettingsRepository.lyricsFontPath
    val stands4Uid: StateFlow<String> = appSettingsRepository.stands4Uid
    val stands4Token: StateFlow<String> = appSettingsRepository.stands4Token
    val stands4RequestsToday: StateFlow<Int> = appSettingsRepository.stands4RequestsToday
    val deeplApiKey: StateFlow<String> = appSettingsRepository.deeplApiKey
    val shuffleMode: StateFlow<dev.nami.domain.ShuffleMode> = appSettingsRepository.shuffleMode
    val amoledEnabled: StateFlow<Boolean> = appSettingsRepository.amoledEnabled
    val uiFontPath: StateFlow<String?> = appSettingsRepository.uiFontPath
    val doubleTapArtworkAction: StateFlow<dev.nami.domain.GestureAction> = appSettingsRepository.doubleTapArtworkAction
    val scrobblingEnabled: StateFlow<Boolean> = appSettingsRepository.scrobblingEnabled
    val airPlayEnabled: StateFlow<Boolean> = appSettingsRepository.airPlayEnabled
    fun setAirPlayEnabled(value: Boolean) = appSettingsRepository.setAirPlayEnabled(value)
    val yandexStationEnabled: StateFlow<Boolean> = appSettingsRepository.yandexStationEnabled
    fun setYandexStationEnabled(value: Boolean) = appSettingsRepository.setYandexStationEnabled(value)
    val yandexOAuthToken: StateFlow<String?> = appSettingsRepository.yandexOAuthToken
    fun setYandexOAuthToken(token: String?) = appSettingsRepository.setYandexOAuthToken(token)
    val yandexClientId: StateFlow<String?> = appSettingsRepository.yandexClientId
    fun setYandexClientId(value: String?) = appSettingsRepository.setYandexClientId(value)
    val jamendoClientId: StateFlow<String?> = appSettingsRepository.jamendoClientId
    fun setJamendoClientId(value: String?) = appSettingsRepository.setJamendoClientId(value)
    val soundCloudClientId: StateFlow<String?> = appSettingsRepository.soundCloudClientId
    fun setSoundCloudClientId(value: String?) = appSettingsRepository.setSoundCloudClientId(value)
    val listenBrainzToken: StateFlow<String?> = appSettingsRepository.listenBrainzToken
    val nowPlayingShowTechInfo: StateFlow<Boolean> = appSettingsRepository.nowPlayingShowTechInfo
    val nowPlayingShowShuffle: StateFlow<Boolean> = appSettingsRepository.nowPlayingShowShuffle
    val nowPlayingShowRepeat: StateFlow<Boolean> = appSettingsRepository.nowPlayingShowRepeat
    val nowPlayingBlockOrder: StateFlow<List<dev.nami.domain.NowPlayingBlock>> = appSettingsRepository.nowPlayingBlockOrder
    val nowPlayingCompactCover: StateFlow<Boolean> = appSettingsRepository.nowPlayingCompactCover
    val nowPlayingLineProgress: StateFlow<Boolean> = appSettingsRepository.nowPlayingLineProgress
    val themeColorOverrides: StateFlow<Map<String, String>> = appSettingsRepository.themeColorOverrides
    val themeShapeOverrides: StateFlow<Map<String, Int>> = appSettingsRepository.themeShapeOverrides
    val themeDensityScale: StateFlow<Float> = appSettingsRepository.themeDensityScale
    val themeFontScale: StateFlow<Float> = appSettingsRepository.themeFontScale
    val blurEnabled: StateFlow<Boolean> = appSettingsRepository.blurEnabled
    val autoNightAmoled: StateFlow<Boolean> = appSettingsRepository.autoNightAmoled
    val bottomTabs: StateFlow<List<dev.nami.domain.BottomTabConfig>> = appSettingsRepository.bottomTabs
    val bottomTabLabelsHidden: StateFlow<Boolean> = appSettingsRepository.bottomTabLabelsHidden
    val defaultStartScreen: StateFlow<dev.nami.domain.BottomTab> = appSettingsRepository.defaultStartScreen
    val miniPlayerSideSwipeAction: StateFlow<dev.nami.domain.GestureAction> = appSettingsRepository.miniPlayerSideSwipeAction
    val nowPlayingLayoutPreset: StateFlow<dev.nami.domain.NowPlayingLayoutPreset> = appSettingsRepository.nowPlayingLayoutPreset

    fun setBottomTabs(tabs: List<dev.nami.domain.BottomTabConfig>) {
        appSettingsRepository.setBottomTabs(tabs)
    }

    fun setDefaultStartScreen(tab: dev.nami.domain.BottomTab) {
        appSettingsRepository.setDefaultStartScreen(tab)
    }

    val nowPlayingMoreItems: StateFlow<List<dev.nami.domain.NowPlayingMoreConfig>> =
        appSettingsRepository.nowPlayingMoreItems

    fun setNowPlayingMoreItems(items: List<dev.nami.domain.NowPlayingMoreConfig>) {
        appSettingsRepository.setNowPlayingMoreItems(items)
    }

    fun setBottomTabLabelsHidden(value: Boolean) {
        appSettingsRepository.setBottomTabLabelsHidden(value)
    }

    fun setMiniPlayerSideSwipeAction(action: dev.nami.domain.GestureAction) {
        appSettingsRepository.setMiniPlayerSideSwipeAction(action)
    }

    /** П.md §17 "5 готовых пресетов макета": пресет не новая сущность в рантайме, а разовая
     * запись набора значений в те же переключатели, что пользователь и так крутит вручную. */
    fun applyNowPlayingPreset(preset: dev.nami.domain.NowPlayingLayoutPreset) {
        val layout = dev.nami.domain.layoutOf(preset)
        appSettingsRepository.setNowPlayingCompactCover(layout.compactCover)
        appSettingsRepository.setNowPlayingLineProgress(layout.lineProgress)
        appSettingsRepository.setNowPlayingShowTechInfo(layout.showTechInfo)
        appSettingsRepository.setNowPlayingShowShuffle(layout.showShuffle)
        appSettingsRepository.setNowPlayingShowRepeat(layout.showRepeat)
        appSettingsRepository.setNowPlayingBlockOrder(layout.blockOrder)
        appSettingsRepository.setNowPlayingLayoutPreset(preset)
    }

    fun setAutoOpenPlayer(value: Boolean) {
        appSettingsRepository.setAutoOpenPlayer(value)
    }

    fun setHideSystemBars(value: Boolean) {
        appSettingsRepository.setHideSystemBars(value)
    }

    fun setKaraokeEnabled(value: Boolean) {
        appSettingsRepository.setKaraokeEnabled(value)
    }

    fun setStudyModeEnabled(value: Boolean) {
        appSettingsRepository.setStudyModeEnabled(value)
    }

    /** [uri] is a content:// pick - not a stable path, so the font file is copied into app
     * storage once and only the local copy's path is kept. */
    fun pickLyricsFont(uri: Uri) {
        viewModelScope.launch {
            val extension = context.contentResolver.getType(uri)?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "ttf"
            val dest = File(context.filesDir, "fonts/lyrics.$extension")
            val copied = withContext(Dispatchers.IO) {
                dest.parentFile?.mkdirs()
                context.contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                } != null
            }
            if (copied) appSettingsRepository.setLyricsFontPath(dest.path)
        }
    }

    fun clearLyricsFont() {
        appSettingsRepository.setLyricsFontPath(null)
    }

    fun setStands4Uid(value: String) {
        appSettingsRepository.setStands4Uid(value)
    }

    fun setStands4Token(value: String) {
        appSettingsRepository.setStands4Token(value)
    }

    fun setDeeplApiKey(value: String) {
        appSettingsRepository.setDeeplApiKey(value)
    }

    fun setShuffleMode(mode: dev.nami.domain.ShuffleMode) {
        appSettingsRepository.setShuffleMode(mode)
    }

    fun setAmoledEnabled(value: Boolean) {
        appSettingsRepository.setAmoledEnabled(value)
    }

    /** Группа E "свой шрифт интерфейса" - тот же copy-once-to-local-storage приём что
     * pickLyricsFont, т.к. [uri] не стабилен между запусками. */
    fun pickUiFont(uri: Uri) {
        viewModelScope.launch {
            val extension = context.contentResolver.getType(uri)?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "ttf"
            val dest = File(context.filesDir, "fonts/ui.$extension")
            val copied = withContext(Dispatchers.IO) {
                dest.parentFile?.mkdirs()
                context.contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                } != null
            }
            if (copied) appSettingsRepository.setUiFontPath(dest.path)
        }
    }

    /** Встроенный шрифт из assets. Копируется в те же fonts/ в filesDir, что и свой файл, - так
     * весь путь загрузки (Font(File) в MainActivity/лирике) остаётся ровно один, а по имени файла
     * видно, какой именно набор выбран. */
    fun pickBundledUiFont(font: BundledFont) = copyBundledFont(font, "ui", appSettingsRepository::setUiFontPath)

    fun pickBundledLyricsFont(font: BundledFont) = copyBundledFont(font, "lyrics", appSettingsRepository::setLyricsFontPath)

    private fun copyBundledFont(font: BundledFont, prefix: String, apply: (String) -> Unit) {
        viewModelScope.launch {
            val dest = File(context.filesDir, "fonts/" + bundledFontFileName(font.key, prefix))
            val copied = withContext(Dispatchers.IO) {
                runCatching {
                    dest.parentFile?.mkdirs()
                    context.assets.open(font.assetPath).use { input ->
                        dest.outputStream().use { output -> input.copyTo(output) }
                    }
                }.isSuccess
            }
            if (copied) apply(dest.path)
        }
    }

    fun clearUiFont() {
        appSettingsRepository.setUiFontPath(null)
    }

    fun setDoubleTapArtworkAction(action: dev.nami.domain.GestureAction) {
        appSettingsRepository.setDoubleTapArtworkAction(action)
    }

    fun setScrobblingEnabled(value: Boolean) {
        appSettingsRepository.setScrobblingEnabled(value)
    }

    fun setListenBrainzToken(token: String) {
        appSettingsRepository.setListenBrainzToken(token)
    }

    fun setNowPlayingShowTechInfo(value: Boolean) {
        appSettingsRepository.setNowPlayingShowTechInfo(value)
    }

    fun setNowPlayingShowShuffle(value: Boolean) {
        appSettingsRepository.setNowPlayingShowShuffle(value)
    }

    fun setNowPlayingShowRepeat(value: Boolean) {
        appSettingsRepository.setNowPlayingShowRepeat(value)
    }

    fun setNowPlayingBlockOrder(order: List<dev.nami.domain.NowPlayingBlock>) {
        appSettingsRepository.setNowPlayingBlockOrder(order)
    }

    fun setNowPlayingCompactCover(value: Boolean) {
        appSettingsRepository.setNowPlayingCompactCover(value)
    }

    fun setNowPlayingLineProgress(value: Boolean) {
        appSettingsRepository.setNowPlayingLineProgress(value)
    }

    fun setThemeColorOverride(token: String, hex: String?) {
        appSettingsRepository.setThemeColorOverride(token, hex)
    }

    fun resetThemeColors() {
        appSettingsRepository.resetThemeColors()
    }

    fun setThemeShapeOverride(token: String, dp: Int?) {
        appSettingsRepository.setThemeShapeOverride(token, dp)
    }

    fun setThemeDensityScale(value: Float) {
        appSettingsRepository.setThemeDensityScale(value)
    }

    fun setThemeFontScale(value: Float) {
        appSettingsRepository.setThemeFontScale(value)
    }

    fun setBlurEnabled(value: Boolean) {
        appSettingsRepository.setBlurEnabled(value)
    }

    fun setAutoNightAmoled(value: Boolean) {
        appSettingsRepository.setAutoNightAmoled(value)
    }

    fun resetThemeShapeAndDensity() {
        appSettingsRepository.resetThemeShapeAndDensity()
    }

    /** Результат последнего импорта/экспорта темы для тоста, null - показывать нечего. */
    private val _themeIoMessage = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val themeIoMessage: StateFlow<String?> = _themeIoMessage
    fun themeIoMessageShown() {
        _themeIoMessage.value = null
    }

    /** П.md §26 "Экспорт и импорт .json" - экспорт в выбранное системным пикером место, тот же
     * ACTION_CREATE_DOCUMENT, что у экспорта плейлиста. Импорт по ссылке и по QR из плана не
     * сделан: файла достаточно, а загрузка тем из сети - отдельный разговор про доверие. */
    fun exportTheme(uri: Uri) {
        viewModelScope.launch {
            val json = encodeThemeFile(
                ThemeFile(
                    colors = appSettingsRepository.themeColorOverrides.value,
                    shape = appSettingsRepository.themeShapeOverrides.value,
                    densityScale = appSettingsRepository.themeDensityScale.value,
                    fontScale = appSettingsRepository.themeFontScale.value,
                ),
            )
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) } != null
                }.getOrDefault(false)
            }
            _themeIoMessage.value = if (ok) "Тема сохранена" else "Не удалось сохранить тему"
        }
    }

    fun importTheme(uri: Uri) {
        viewModelScope.launch {
            val raw = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }.getOrNull()
            }
            val parsed = raw?.let(::parseThemeFile)
            if (parsed == null) {
                _themeIoMessage.value = "Это не файл темы"
                return@launch
            }
            // Заменяем набор целиком, а не дополняем текущий: импортированная тема должна
            // выглядеть так же, как у того, кто её прислал, а не смешаться с чужими правками.
            appSettingsRepository.resetThemeColors()
            parsed.colors.forEach { (token, hex) -> appSettingsRepository.setThemeColorOverride(token, hex) }
            appSettingsRepository.resetThemeShapeAndDensity()
            parsed.shape.forEach { (token, dp) -> appSettingsRepository.setThemeShapeOverride(token, dp) }
            parsed.densityScale?.let(appSettingsRepository::setThemeDensityScale)
            parsed.fontScale?.let(appSettingsRepository::setThemeFontScale)
            _themeIoMessage.value = "Тема применена"
        }
    }

    /** П.md §26 "Галерея тем" - применить пресет целиком: сначала снимаем все текущие цветовые
     * оверрайды, потом кладём набор пресета. Пустой набор = "Тушь", базовая тема как есть. */
    fun applyThemePreset(preset: ThemePreset) {
        appSettingsRepository.resetThemeColors()
        preset.colors.forEach { (token, hex) -> appSettingsRepository.setThemeColorOverride(token, hex) }
    }
}
