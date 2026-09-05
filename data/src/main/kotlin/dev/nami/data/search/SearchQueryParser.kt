package dev.nami.data.search

data class ParsedSearchQuery(val text: String, val format: String?, val year: Int?)

object SearchQueryParser {
    private val formatRegex = Regex("""format:(\S+)""")
    private val yearRegex = Regex("""year:(\d{4})""")

    // Track.format stores the MIME subtype, not the file extension a user types.
    private val formatAliases = mapOf(
        "mp3" to "mpeg",
        "m4a" to "mp4",
    )

    fun parse(raw: String): ParsedSearchQuery {
        var remaining = raw
        val format = formatRegex.find(remaining)?.groupValues?.get(1)?.lowercase()
        remaining = formatRegex.replace(remaining, "")
        val year = yearRegex.find(remaining)?.groupValues?.get(1)?.toIntOrNull()
        remaining = yearRegex.replace(remaining, "")
        val normalizedFormat = format?.let { formatAliases[it] ?: it }
        return ParsedSearchQuery(
            text = remaining.trim().replace(Regex("\\s+"), " "),
            format = normalizedFormat,
            year = year,
        )
    }
}
