package dev.nami.data.search

object FtsQueryBuilder {
    fun build(text: String): String? {
        val tokens = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return null
        return tokens.joinToString(" ") { token ->
            val escaped = token.replace("\"", "\"\"")
            "\"$escaped\"*"
        }
    }
}
