package dev.nami.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** П.md §3 "модель данных" - user-defined color-coded tags, separate from genre (one track can
 * carry several, unlike the single genre string). */
@Entity(tableName = "tags")
data class TagEntity(
    @PrimaryKey val id: String,
    val name: String,
    val colorArgb: Int,
)
