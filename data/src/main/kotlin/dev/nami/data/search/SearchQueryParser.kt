package dev.nami.data.search

/** План.md §21 "операторы поиска". [text] - то, что осталось после вырезания всех операторов,
 * оно и уходит в FTS5; остальные поля применяются как дополнительный фильтр поверх результата.
 *
 * Только format/year живут в самом FTS-индексе, всё прочее (bpm, рейтинг, дата добавления,
 * наличие .lrc) - постфильтр по строкам треков: доиндексировать эти поля во fts5 ради
 * операторов, которыми пользуются раз в месяц, дороже, чем добрать их запросом по id. */
data class ParsedSearchQuery(
    val text: String,
    val format: String?,
    val year: Int?,
    val bpmFrom: Float? = null,
    val bpmTo: Float? = null,
    /** rating:>3 / rating:<5 / rating:4 - минимум, максимум и точное значение соответственно. */
    val ratingMin: Int? = null,
    val ratingMax: Int? = null,
    val ratingExact: Int? = null,
    /** added:<7d - добавлено за последние N дней; added:>30d - добавлено раньше, чем N дней назад. */
    val addedWithinDays: Int? = null,
    val addedOlderThanDays: Int? = null,
    /** no-lyrics:true - только треки без .lrc; false - только с .lrc. */
    val noLyrics: Boolean? = null,
    /** lyrics:слово - поиск по строкам лирики. Отдельным оператором, а не всегда: лирика лежит
     * .lrc-файлами рядом с треками, индекса по ним нет, и сканировать всю библиотеку на каждый
     * ввод буквы нельзя. */
    val lyrics: String? = null,
)

object SearchQueryParser {
    private val formatRegex = Regex("""format:(\S+)""")
    private val yearRegex = Regex("""year:(\d{4})""")
    private val bpmRegex = Regex("""bpm:(\d+(?:\.\d+)?)(?:-(\d+(?:\.\d+)?))?""")
    private val ratingRegex = Regex("""rating:([<>]?)(\d)""")
    private val addedRegex = Regex("""added:([<>]?)(\d+)d""")
    private val noLyricsRegex = Regex("""no-lyrics:(true|false)""")
    private val lyricsRegex = Regex("""lyrics:(\S+)""")

    // Track.format stores the MIME subtype, not the file extension a user types.
    private val formatAliases = mapOf(
        "mp3" to "mpeg",
        "m4a" to "mp4",
    )

    fun parse(raw: String): ParsedSearchQuery {
        var remaining = raw
        fun <T> take(regex: Regex, block: (MatchResult) -> T): T? {
            val match = regex.find(remaining) ?: return null
            remaining = remaining.removeRange(match.range)
            return block(match)
        }

        val format = take(formatRegex) { it.groupValues[1].lowercase() }
        val year = take(yearRegex) { it.groupValues[1].toIntOrNull() }
        var bpmFrom: Float? = null
        var bpmTo: Float? = null
        take(bpmRegex) { match ->
            bpmFrom = match.groupValues[1].toFloatOrNull()
            // bpm:120 без диапазона - точное значение, ловим с допуском ±1, иначе float никогда
            // не совпадёт с целым, которое ввёл пользователь.
            bpmTo = match.groupValues[2].toFloatOrNull() ?: bpmFrom?.plus(1f)
            if (match.groupValues[2].isEmpty()) bpmFrom = bpmFrom?.minus(1f)
        }
        var ratingMin: Int? = null
        var ratingMax: Int? = null
        var ratingExact: Int? = null
        take(ratingRegex) { match ->
            val value = match.groupValues[2].toInt()
            when (match.groupValues[1]) {
                ">" -> ratingMin = value + 1
                "<" -> ratingMax = value - 1
                else -> ratingExact = value
            }
        }
        var addedWithinDays: Int? = null
        var addedOlderThanDays: Int? = null
        take(addedRegex) { match ->
            val days = match.groupValues[2].toInt()
            // added:7d без знака читается как "за последние 7 дней" - самая ожидаемая трактовка.
            if (match.groupValues[1] == ">") addedOlderThanDays = days else addedWithinDays = days
        }
        val noLyrics = take(noLyricsRegex) { it.groupValues[1] == "true" }
        val lyrics = take(lyricsRegex) { it.groupValues[1] }

        return ParsedSearchQuery(
            text = remaining.trim().replace(Regex("\\s+"), " "),
            format = format?.let { formatAliases[it] ?: it },
            year = year,
            bpmFrom = bpmFrom,
            bpmTo = bpmTo,
            ratingMin = ratingMin,
            ratingMax = ratingMax,
            ratingExact = ratingExact,
            addedWithinDays = addedWithinDays,
            addedOlderThanDays = addedOlderThanDays,
            noLyrics = noLyrics,
            lyrics = lyrics,
        )
    }
}

/** Есть ли в запросе хоть один оператор, который умеет отфильтровать только треки (альбомы и
 * артисты под него не подходят по определению). */
val ParsedSearchQuery.hasTrackOnlyFilters: Boolean
    get() = bpmFrom != null || ratingMin != null || ratingMax != null || ratingExact != null ||
        addedWithinDays != null || addedOlderThanDays != null || noLyrics != null || lyrics != null
