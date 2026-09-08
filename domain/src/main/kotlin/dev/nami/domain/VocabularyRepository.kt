package dev.nami.domain

import kotlinx.coroutines.flow.Flow

data class VocabularyWord(
    val id: Long = 0,
    val word: String,
    val reading: String,
    val meaning: String,
    val contextLine: String,
    val trackTitle: String,
    val addedAt: Long,
)

/** "Свой словарик" from План.md's lyrics screen section - words tapped while reading lyrics,
 * kept with the line/track they came from, exportable to Anki. */
interface VocabularyRepository {
    fun words(): Flow<List<VocabularyWord>>
    suspend fun add(word: String, reading: String, meaning: String, contextLine: String, trackTitle: String)
    suspend fun remove(id: Long)

    /** CSV, one row per word: word,reading,meaning,line,track - Anki's plain-CSV import maps
     * these directly to note fields, no .apkg packaging needed. */
    suspend fun exportCsv(): String
}
