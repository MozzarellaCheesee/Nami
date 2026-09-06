package dev.nami.data

import com.atilika.kuromoji.ipadic.Tokenizer

/** Hepburn romanization of every token's reading (not just kanji-bearing ones, unlike
 * [FuriganaGenerator] -- a romaji line needs the WHOLE line spelled out, not just the kanji
 * parts). Tokens are space-separated for readability, matching how romaji lyrics are
 * conventionally written (word-by-word), rather than one unbroken run of letters. */
object RomajiGenerator {
    private val tokenizer by lazy { Tokenizer() }

    // Longest-match-first: multi-kana digraphs (きゃ, しゃ, ちゃ...) have to be checked before
    // their single-kana components or they'd romanize as two separate morae.
    private val digraphs = linkedMapOf(
        "きゃ" to "kya", "きゅ" to "kyu", "きょ" to "kyo",
        "しゃ" to "sha", "しゅ" to "shu", "しょ" to "sho",
        "ちゃ" to "cha", "ちゅ" to "chu", "ちょ" to "cho",
        "にゃ" to "nya", "にゅ" to "nyu", "にょ" to "nyo",
        "ひゃ" to "hya", "ひゅ" to "hyu", "ひょ" to "hyo",
        "みゃ" to "mya", "みゅ" to "myu", "みょ" to "myo",
        "りゃ" to "rya", "りゅ" to "ryu", "りょ" to "ryo",
        "ぎゃ" to "gya", "ぎゅ" to "gyu", "ぎょ" to "gyo",
        "じゃ" to "ja", "じゅ" to "ju", "じょ" to "jo",
        "びゃ" to "bya", "びゅ" to "byu", "びょ" to "byo",
        "ぴゃ" to "pya", "ぴゅ" to "pyu", "ぴょ" to "pyo",
    )
    private val monographs = linkedMapOf(
        "あ" to "a", "い" to "i", "う" to "u", "え" to "e", "お" to "o",
        "か" to "ka", "き" to "ki", "く" to "ku", "け" to "ke", "こ" to "ko",
        "さ" to "sa", "し" to "shi", "す" to "su", "せ" to "se", "そ" to "so",
        "た" to "ta", "ち" to "chi", "つ" to "tsu", "て" to "te", "と" to "to",
        "な" to "na", "に" to "ni", "ぬ" to "nu", "ね" to "ne", "の" to "no",
        "は" to "ha", "ひ" to "hi", "ふ" to "fu", "へ" to "he", "ほ" to "ho",
        "ま" to "ma", "み" to "mi", "む" to "mu", "め" to "me", "も" to "mo",
        "や" to "ya", "ゆ" to "yu", "よ" to "yo",
        "ら" to "ra", "り" to "ri", "る" to "ru", "れ" to "re", "ろ" to "ro",
        "わ" to "wa", "を" to "o", "ん" to "n",
        "が" to "ga", "ぎ" to "gi", "ぐ" to "gu", "げ" to "ge", "ご" to "go",
        "ざ" to "za", "じ" to "ji", "ず" to "zu", "ぜ" to "ze", "ぞ" to "zo",
        "だ" to "da", "ぢ" to "ji", "づ" to "zu", "で" to "de", "ど" to "do",
        "ば" to "ba", "び" to "bi", "ぶ" to "bu", "べ" to "be", "ぼ" to "bo",
        "ぱ" to "pa", "ぴ" to "pi", "ぷ" to "pu", "ぺ" to "pe", "ぽ" to "po",
        "ー" to "-",
    )

    fun generate(lines: List<String>): List<String> = lines.map { generateLine(it) }

    private fun generateLine(line: String): String {
        if (line.isBlank() || !isJapaneseDominant(line)) return line
        return tokenizer.tokenize(line).joinToString(" ") { token ->
            val reading = token.reading
            if (reading == null || reading == "*") token.surface else romanize(katakanaToHiragana(reading))
        }.trim()
    }

    private fun katakanaToHiragana(katakana: String): String =
        katakana.map { ch -> if (ch in 'ァ'..'ヶ') ch - 0x60 else ch }.joinToString("")

    private fun romanize(hiragana: String): String {
        val result = StringBuilder()
        var i = 0
        while (i < hiragana.length) {
            // Small つ doubles the following consonant (がっこう -> gakkou) instead of romanizing
            // as its own mora.
            if (hiragana[i] == 'っ' && i + 1 < hiragana.length) {
                val next = romanizeAt(hiragana, i + 1)
                if (next != null && next.text.isNotEmpty()) {
                    result.append(next.text[0])
                    i++
                    continue
                }
            }
            val match = romanizeAt(hiragana, i)
            if (match != null) {
                result.append(match.text)
                i += match.consumed
            } else {
                result.append(hiragana[i])
                i++
            }
        }
        return result.toString()
    }

    private data class Romanized(val text: String, val consumed: Int)

    private fun romanizeAt(hiragana: String, index: Int): Romanized? {
        if (index + 1 < hiragana.length) {
            digraphs[hiragana.substring(index, index + 2)]?.let { return Romanized(it, 2) }
        }
        monographs[hiragana[index].toString()]?.let { return Romanized(it, 1) }
        return null
    }
}
