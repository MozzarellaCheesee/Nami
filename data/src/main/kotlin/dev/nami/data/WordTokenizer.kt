package dev.nami.data

import com.atilika.kuromoji.ipadic.Tokenizer
import dev.nami.core.model.WordToken

/** Single source of truth for splitting a lyric line into tappable words - used both for the
 * furigana ruby-text layout and for dictionary lookup, so the two always agree on where one word
 * ends and the next begins (previously furigana used its own bracket-string parsing while a tap
 * feature would have needed its own segmentation - two different splits of the same line is how
 * ruby text and tap targets end up misaligned). */
object WordTokenizer {
    private val tokenizer by lazy { Tokenizer() }
    private val kanjiRegex = Regex("[一-鿿]")

    fun tokenize(line: String): List<WordToken> {
        if (line.isBlank()) return emptyList()
        return tokenizer.tokenize(line).map { token ->
            val surface = token.surface
            val reading = token.reading
            val hiragana = if (reading != null && reading != "*") katakanaToHiragana(reading) else surface
            // baseForm is "*" for tokens that don't conjugate (particles, nouns already in
            // dictionary form) - surface is already the dictionary form there.
            val baseForm = token.baseForm?.takeIf { it != "*" } ?: surface
            WordToken(
                surface = surface,
                baseForm = baseForm,
                readingHiragana = hiragana,
                hasKanji = kanjiRegex.containsMatchIn(surface),
            )
        }
    }

    private fun katakanaToHiragana(katakana: String): String =
        katakana.map { ch -> if (ch in 'ァ'..'ヶ') ch - 0x60 else ch }.joinToString("")
}
