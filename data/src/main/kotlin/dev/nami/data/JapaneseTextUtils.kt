package dev.nami.data

/** Lines that mix Japanese with a large chunk of Latin text (this song's lyrics do, e.g.
 * "I'll be born again 燃える") break both Kuromoji (an IPADIC-only tokenizer with no English
 * dictionary -- falls back to garbage per-character tokens) and ML Kit's JA->RU translator (fed
 * a source language it isn't actually all in). Rather than try to handle code-switching
 * properly, lines that are mostly Latin are left alone by both features -- annotated/translated
 * only when there's enough actual Japanese in them to be worth it and safe to process. */
internal fun isJapaneseDominant(text: String): Boolean {
    val japanese = text.count { it in '぀'..'ヿ' || it in '一'..'鿿' }
    val latin = text.count { it in 'A'..'Z' || it in 'a'..'z' }
    return japanese > latin
}
