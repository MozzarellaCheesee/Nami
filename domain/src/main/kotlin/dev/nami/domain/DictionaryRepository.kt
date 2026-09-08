package dev.nami.domain

import dev.nami.core.model.DictionaryEntry

/** JMdict (the standard open Japanese-English dictionary), bundled whole - every entry, not a
 * cut-down subset - as a read-only SQLite asset. Looked up by dictionary/base form (see
 * [WordToken.baseForm][dev.nami.core.model.WordToken]), not the conjugated surface form. */
interface DictionaryRepository {
    suspend fun lookup(word: String): List<DictionaryEntry>
}
