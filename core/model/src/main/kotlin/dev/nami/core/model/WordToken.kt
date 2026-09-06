package dev.nami.core.model

/** One morphological token from a lyric line -- [baseForm] (dictionary/citation form) is what a
 * dictionary lookup should use, not [surface] (the conjugated form actually printed), since
 * JMdict headwords are dictionary forms ("食べる", not "食べた"). */
data class WordToken(
    val surface: String,
    val baseForm: String,
    val readingHiragana: String,
    val hasKanji: Boolean,
)

data class DictionaryEntry(
    val kanji: String?,
    val kana: String?,
    val partsOfSpeech: List<String>,
    val glosses: List<String>,
)
