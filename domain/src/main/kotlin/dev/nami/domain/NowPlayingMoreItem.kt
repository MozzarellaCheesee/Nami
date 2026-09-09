package dev.nami.domain

/**
 * Пункты меню "Ещё" на Now Playing. Стабильные id вместо подписи-строки: подпись контекстная и
 * переименовывается, а порядок/секция/цвет должны переживать переименование.
 *
 * Часть пунктов существует только в контексте (нет трека - нет "Информации о треке", нет artistId -
 * нет "Открыть исполнителя"). Конфиг хранит настройки для ВСЕХ id, а экран всё так же фильтрует
 * недоступные в моменте.
 */
enum class NowPlayingMoreItem {
    CAST,
    SHARE_CARD,
    RADIO,
    ADD_TO_PLAYLIST,
    DRIVE_MODE,
    AUDIO_TRACT,
    SLEEP_TIMER,
    SHARE_OVER_NETWORK,
    LISTEN_TOGETHER,
    SHARE,
    MOMENTS,
    TRACK_INFO,
    OPEN_ARTIST,
    OPEN_ALBUM,
    PLAYER_SETTINGS,
    THEME_EDITOR,
    ALL_SETTINGS,
}

/** Куда пункт попадает в листе. HIDDEN - пользователь спрятал его совсем. */
enum class NowPlayingMoreSection { GRID, LIST, HIDDEN }

/** Акцент ячейки сетки - выбор из уже существующей палитры приложения, не произвольный цвет.
 * NEUTRAL = как было у неизвестных подписей (Paper100). Пунктам списка цвет не нужен. */
enum class NowPlayingMoreAccent { NEUTRAL, AI, SHU, WAKABA, KIN }

data class NowPlayingMoreConfig(
    val item: NowPlayingMoreItem,
    val section: NowPlayingMoreSection,
    val accent: NowPlayingMoreAccent = NowPlayingMoreAccent.NEUTRAL,
)

/** Порядок в списке = порядок показа внутри своей секции. Дефолт повторяет то, что было
 * захардкожено в NowPlayingScreen/gridCellAccent, чтобы после обновления меню выглядело как раньше. */
val DEFAULT_NOW_PLAYING_MORE_ITEMS = listOf(
    NowPlayingMoreConfig(NowPlayingMoreItem.CAST, NowPlayingMoreSection.GRID, NowPlayingMoreAccent.AI),
    NowPlayingMoreConfig(NowPlayingMoreItem.SHARE_CARD, NowPlayingMoreSection.GRID, NowPlayingMoreAccent.AI),
    NowPlayingMoreConfig(NowPlayingMoreItem.RADIO, NowPlayingMoreSection.GRID, NowPlayingMoreAccent.SHU),
    NowPlayingMoreConfig(NowPlayingMoreItem.ADD_TO_PLAYLIST, NowPlayingMoreSection.GRID, NowPlayingMoreAccent.WAKABA),
    NowPlayingMoreConfig(NowPlayingMoreItem.DRIVE_MODE, NowPlayingMoreSection.GRID, NowPlayingMoreAccent.KIN),
    NowPlayingMoreConfig(NowPlayingMoreItem.AUDIO_TRACT, NowPlayingMoreSection.GRID, NowPlayingMoreAccent.AI),
    NowPlayingMoreConfig(NowPlayingMoreItem.SLEEP_TIMER, NowPlayingMoreSection.GRID, NowPlayingMoreAccent.KIN),
    NowPlayingMoreConfig(NowPlayingMoreItem.SHARE_OVER_NETWORK, NowPlayingMoreSection.GRID, NowPlayingMoreAccent.NEUTRAL),
    NowPlayingMoreConfig(NowPlayingMoreItem.LISTEN_TOGETHER, NowPlayingMoreSection.GRID, NowPlayingMoreAccent.NEUTRAL),
    NowPlayingMoreConfig(NowPlayingMoreItem.SHARE, NowPlayingMoreSection.LIST),
    NowPlayingMoreConfig(NowPlayingMoreItem.MOMENTS, NowPlayingMoreSection.LIST),
    NowPlayingMoreConfig(NowPlayingMoreItem.TRACK_INFO, NowPlayingMoreSection.LIST),
    NowPlayingMoreConfig(NowPlayingMoreItem.OPEN_ARTIST, NowPlayingMoreSection.LIST),
    NowPlayingMoreConfig(NowPlayingMoreItem.OPEN_ALBUM, NowPlayingMoreSection.LIST),
    NowPlayingMoreConfig(NowPlayingMoreItem.PLAYER_SETTINGS, NowPlayingMoreSection.LIST),
    NowPlayingMoreConfig(NowPlayingMoreItem.THEME_EDITOR, NowPlayingMoreSection.LIST),
    NowPlayingMoreConfig(NowPlayingMoreItem.ALL_SETTINGS, NowPlayingMoreSection.LIST),
)
