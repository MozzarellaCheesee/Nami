package dev.nami.domain

/**
 * П.md §17 "5 готовых пресетов макета плюс конструктор". Новой системы макета под ними нет и не
 * заводится: пресет - это готовый набор значений уже существующих независимых переключателей
 * (компактная обложка, линия/волна, техинфо, shuffle/repeat, порядок блоков). Применение пресета
 * = разовая установка всех этих значений, дальше пользователь правит их по одному как обычно, а
 * сохранённый пресет остаётся просто подсветкой "с чего начали".
 *
 * CUSTOM - состояние "ни один пресет не применяли" (или применили и уже что-то поменяли вручную;
 * мы это не отслеживаем, см. выше - осознанное упрощение).
 */
enum class NowPlayingLayoutPreset {
    CUSTOM,
    CLASSIC,
    BIG_COVER,
    COMPACT,
    LYRICS_FIRST,
    MINIMAL,
}

/** Значения переключателей Now Playing одним пакетом - ровно то, что применяет пресет. */
data class NowPlayingLayout(
    val compactCover: Boolean,
    val lineProgress: Boolean,
    val showTechInfo: Boolean,
    val showShuffle: Boolean,
    val showRepeat: Boolean,
    val blockOrder: List<NowPlayingBlock>,
)

/** Раскладка каждого пресета. CUSTOM сюда не входит - его "применить" нельзя. */
fun layoutOf(preset: NowPlayingLayoutPreset): NowPlayingLayout = when (preset) {
    // Дефолты, с которыми экран жил до появления настроек.
    NowPlayingLayoutPreset.CUSTOM, NowPlayingLayoutPreset.CLASSIC -> NowPlayingLayout(
        compactCover = false,
        lineProgress = false,
        showTechInfo = true,
        showShuffle = true,
        showRepeat = true,
        blockOrder = DEFAULT_NOW_PLAYING_BLOCKS,
    )
    // Обложка во всю ширину, под ней минимум отвлекающего.
    NowPlayingLayoutPreset.BIG_COVER -> NowPlayingLayout(
        compactCover = false,
        lineProgress = true,
        showTechInfo = false,
        showShuffle = false,
        showRepeat = false,
        blockOrder = DEFAULT_NOW_PLAYING_BLOCKS,
    )
    // Обложка меньше, зато весь транспорт сразу под рукой.
    NowPlayingLayoutPreset.COMPACT -> NowPlayingLayout(
        compactCover = true,
        lineProgress = true,
        showTechInfo = false,
        showShuffle = true,
        showRepeat = true,
        blockOrder = listOf(
            NowPlayingBlock.TITLE_ARTIST,
            NowPlayingBlock.TRANSPORT,
            NowPlayingBlock.PROGRESS,
            NowPlayingBlock.PILLS,
            NowPlayingBlock.TECH_INFO,
        ),
    )
    // "Лирика-первая" в честном виде: кнопка "Текст" живёт в ряду пилюль, поэтому пресет поднимает
    // этот ряд сразу под название. Отдельного блока лирики на экране нет - она открывается своим
    // оверлеем, встроить её в порядок блоков значило бы переписывать экран.
    NowPlayingLayoutPreset.LYRICS_FIRST -> NowPlayingLayout(
        compactCover = true,
        lineProgress = true,
        showTechInfo = false,
        showShuffle = false,
        showRepeat = false,
        blockOrder = listOf(
            NowPlayingBlock.TITLE_ARTIST,
            NowPlayingBlock.PILLS,
            NowPlayingBlock.PROGRESS,
            NowPlayingBlock.TRANSPORT,
            NowPlayingBlock.TECH_INFO,
        ),
    )
    // Только обложка, название и перемотка.
    NowPlayingLayoutPreset.MINIMAL -> NowPlayingLayout(
        compactCover = false,
        lineProgress = true,
        showTechInfo = false,
        showShuffle = false,
        showRepeat = false,
        blockOrder = listOf(
            NowPlayingBlock.TITLE_ARTIST,
            NowPlayingBlock.PROGRESS,
            NowPlayingBlock.TRANSPORT,
            NowPlayingBlock.PILLS,
            NowPlayingBlock.TECH_INFO,
        ),
    )
}
