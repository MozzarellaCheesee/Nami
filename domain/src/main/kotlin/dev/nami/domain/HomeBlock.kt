package dev.nami.domain

/** П.md §14 "Главный экран - конструктор" - блоки, которые пользователь включает/отключает и
 * переставляет. Из девяти блоков плана реализовано шесть - те, для которых уже есть готовые
 * запросы в LibraryRepository (topTracks/recentAlbums/listeningSummary) без новой инфраструктуры.
 * Быстрые теги, плейлисты-закладки и "импорт (если есть новое)" не сделаны - каждому нужен свой
 * кусок инфраструктуры (paged-срез плейлистов, экран быстрых тегов, детект нового импорта),
 * отдельная задача. */
enum class HomeBlockType { CONTINUE_LISTENING, RECENTLY_ADDED, TOP_WEEK, RANDOM_ALBUM, FORGOTTEN, STATS_TODAY }

data class HomeBlockConfig(val type: HomeBlockType, val enabled: Boolean)

/** Порядок в списке = порядок отображения на экране. */
val DEFAULT_HOME_BLOCKS = listOf(
    HomeBlockConfig(HomeBlockType.CONTINUE_LISTENING, true),
    HomeBlockConfig(HomeBlockType.RECENTLY_ADDED, true),
    HomeBlockConfig(HomeBlockType.TOP_WEEK, true),
    HomeBlockConfig(HomeBlockType.RANDOM_ALBUM, true),
    HomeBlockConfig(HomeBlockType.FORGOTTEN, true),
    HomeBlockConfig(HomeBlockType.STATS_TODAY, true),
)
