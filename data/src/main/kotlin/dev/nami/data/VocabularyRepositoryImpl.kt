package dev.nami.data

import dev.nami.core.database.dao.VocabularyDao
import dev.nami.core.database.entity.VocabularyEntity
import dev.nami.domain.VocabularyRepository
import dev.nami.domain.VocabularyWord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class VocabularyRepositoryImpl @Inject constructor(
    private val dao: VocabularyDao,
) : VocabularyRepository {

    override fun words(): Flow<List<VocabularyWord>> =
        dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override suspend fun add(word: String, reading: String, meaning: String, contextLine: String, trackTitle: String) {
        dao.insert(
            VocabularyEntity(
                word = word,
                reading = reading,
                meaning = meaning,
                contextLine = contextLine,
                trackTitle = trackTitle,
                addedAt = System.currentTimeMillis(),
            ),
        )
    }

    override suspend fun remove(id: Long) {
        dao.delete(id)
    }

    override suspend fun exportCsv(): String {
        val header = "word,reading,meaning,line,track"
        val rows = dao.observeAllSnapshot().map { w ->
            listOf(w.word, w.reading, w.meaning, w.contextLine, w.trackTitle).joinToString(",") { csvEscape(it) }
        }
        return (listOf(header) + rows).joinToString("\n")
    }

    private fun csvEscape(value: String): String =
        if (value.contains(',') || value.contains('"') || value.contains('\n')) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }

    private fun VocabularyEntity.toDomain() = VocabularyWord(id, word, reading, meaning, contextLine, trackTitle, addedAt)
}
