package dev.nami.domain

/** П.md §14 "Главный экран - конструктор" - блоки, которые пользователь включает/отключает и
 * переставляет. Все девять блоков плана здесь; порядок enum роли не играет, порядок показа
 * хранится в списке настроек (см. [DEFAULT_HOME_BLOCKS]). */
enum class HomeBlockType {
    CONTINUE_LISTENING,
    RECENTLY_ADDED,
    TOP_WEEK,
    RANDOM_ALBUM,
    FORGOTTEN,
    QUICK_TAGS,
    BOOKMARKED_PLAYLISTS,
    STATS_TODAY,
    NEW_IMPORT,
    /** Группа G "сеть" - подключиться к "слушать вместе" или принять предложенный трек с
     * найденного рядом устройства без захода в Настройки. В отличие от остальных восьми блоков
     * не разовый снимок при входе на экран, а живой (запускает NSD-автопоиск, пока блок включён
     * и экран открыт). Включён по умолчанию (в отличие от первой версии) - пользователь явно
     * попросил, чтобы раздача/сессия рядом распознавалась сама по факту открытой главной, без
     * ручного включения; поиск всё равно идёт только пока сам экран реально на переднем плане
     * (см. DisposableEffect в HomeScreen), не постоянно в фоне. */
    NEARBY_NETWORK,
}

data class HomeBlockConfig(val type: HomeBlockType, val enabled: Boolean)

/** Порядок в списке = порядок отображения на экране. */
val DEFAULT_HOME_BLOCKS = listOf(
    HomeBlockConfig(HomeBlockType.CONTINUE_LISTENING, true),
    HomeBlockConfig(HomeBlockType.NEW_IMPORT, true),
    HomeBlockConfig(HomeBlockType.RECENTLY_ADDED, true),
    HomeBlockConfig(HomeBlockType.QUICK_TAGS, true),
    HomeBlockConfig(HomeBlockType.TOP_WEEK, true),
    HomeBlockConfig(HomeBlockType.BOOKMARKED_PLAYLISTS, true),
    HomeBlockConfig(HomeBlockType.RANDOM_ALBUM, true),
    HomeBlockConfig(HomeBlockType.FORGOTTEN, true),
    HomeBlockConfig(HomeBlockType.STATS_TODAY, true),
    HomeBlockConfig(HomeBlockType.NEARBY_NETWORK, true),
)
