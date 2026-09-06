package dev.nami.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** One word a user tapped in the lyrics screen and saved -- kept with the line/track it came
 * from (План.md's "свой словарик": "слова из песен с контекстной строкой и ссылкой на трек"). */
@Entity(tableName = "vocabulary")
data class VocabularyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val word: String,
    val reading: String,
    val meaning: String,
    val contextLine: String,
    val trackTitle: String,
    val addedAt: Long,
)
