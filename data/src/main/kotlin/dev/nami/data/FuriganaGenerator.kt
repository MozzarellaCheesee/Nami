package dev.nami.data

import com.atilika.kuromoji.ipadic.Tokenizer

/** Kuromoji is a pure-JVM Japanese morphological analyzer (no native/Rust build, unlike the
 * lindera path План.md describes) -- good enough for reading-based furigana even though it isn't
 * that specific tool. Runs entirely on-device, no network, no dictionary download beyond what's
 * bundled in the dependency jar.
 *
 * Output format: each line becomes a string of segments, kanji-bearing ones annotated as
 * "surface[hiragana]", everything else left as plain text -- e.g. "感[かん]じ" -- compact and
 * easy to re-split with a regex on render, and readable if ever inspected/edited by hand. */
object FuriganaGenerator {
    private val tokenizer by lazy { Tokenizer() }
    private val kanjiRegex = Regex("[一-鿿]")

    fun annotate(lines: List<String>): List<String> = lines.map { annotateLine(it) }

    private fun annotateLine(line: String): String {
        // A Latin-heavy line (English mixed into the lyrics) breaks Kuromoji's tokenizer --
        // IPADIC has no English dictionary, so it falls back to garbage per-character tokens and
        // the resulting ruby-text layout comes out visibly broken. Leave those lines alone.
        if (line.isBlank() || !isJapaneseDominant(line)) return line
        return tokenizer.tokenize(line).joinToString("") { token ->
            val surface = token.surface
            val reading = token.reading
            if (kanjiRegex.containsMatchIn(surface) && reading != null && reading != "*") {
                "$surface[${katakanaToHiragana(reading)}]"
            } else {
                surface
            }
        }
    }

    // Katakana and hiragana occupy parallel Unicode blocks a fixed 0x60 apart -- readings come
    // back as katakana, furigana is conventionally written in hiragana.
    private fun katakanaToHiragana(katakana: String): String =
        katakana.map { ch -> if (ch in 'ァ'..'ヶ') ch - 0x60 else ch }.joinToString("")
}
