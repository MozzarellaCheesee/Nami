package dev.nami.data.search

data class ParsedSearchQuery(val text: String, val format: String?, val year: Int?)

object SearchQueryParser {
    private val formatRegex = Regex("""format:(\S+)""")
    private val yearRegex = Regex("""year:(\d{4})""")

    fun parse(raw: String): ParsedSearchQuery {
        var remaining = raw
        val format = formatRegex.find(remaining)?.groupValues?.get(1)?.lowercase()
        remaining = formatRegex.replace(remaining, "")
        val year = yearRegex.find(remaining)?.groupValues?.get(1)?.toIntOrNull()
        remaining = yearRegex.replace(remaining, "")
        return ParsedSearchQuery(text = remaining.trim().replace(Regex("\\s+"), " "), format = format, year = year)
    }
}
