package dev.nami.domain

/**
 * П.md §13 "Таб-бар настраиваемый" - вкладки, из которых пользователь собирает нижнюю панель.
 * [route] обязан совпадать со строкой роута в NamiNavHost: панель ничего не знает про навигацию,
 * она отдаёт наверх ровно эту строку.
 *
 * Вкладки "Теги" в списке нет честно: отдельного экрана тегов в приложении не существует (теги
 * живут внутри карточки трека и блока "Быстрые теги" на главной), а заводить его ради одной
 * вкладки - отдельная задача. Остальные семь плюс Главная - это ровно те восемь, что есть.
 */
enum class BottomTab(val route: String) {
    HOME("home"),
    LIBRARY("library"),
    SEARCH("search"),
    PLAYLISTS("playlists"),
    SETTINGS("settings"),
    STATS("stats"),
    VOCABULARY("vocabulary"),
    FOLDERS("watched_folders"),
    JAM("jam"),
}

data class BottomTabConfig(val tab: BottomTab, val enabled: Boolean)

/** Сколько вкладок разрешено включить одновременно (§13 "количество 3-5"). */
const val MIN_BOTTOM_TABS = 3
const val MAX_BOTTOM_TABS = 5

/** Порядок в списке = порядок в панели. Дефолт - те же пять вкладок, что были захардкожены. */
val DEFAULT_BOTTOM_TABS = listOf(
    BottomTabConfig(BottomTab.HOME, true),
    BottomTabConfig(BottomTab.LIBRARY, true),
    BottomTabConfig(BottomTab.SEARCH, true),
    BottomTabConfig(BottomTab.PLAYLISTS, true),
    BottomTabConfig(BottomTab.SETTINGS, true),
    BottomTabConfig(BottomTab.STATS, false),
    BottomTabConfig(BottomTab.VOCABULARY, false),
    BottomTabConfig(BottomTab.FOLDERS, false),
    BottomTabConfig(BottomTab.JAM, false),
)
