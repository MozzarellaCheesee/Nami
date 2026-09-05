package dev.nami.data.search

object FtsQueryBuilder {
    fun build(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null

        val tokens = if (trimmed.contains("\"")) {
            listOf(trimmed)
        } else {
            trimmed.split(Regex("\\s+")).filter { it.isNotBlank() }
        }

        if (tokens.isEmpty()) return null
        return tokens.joinToString(" ") { token ->
            val escaped = token.replace("\"", "\"\"")
            "\"$escaped\"*"
        }
    }
}
