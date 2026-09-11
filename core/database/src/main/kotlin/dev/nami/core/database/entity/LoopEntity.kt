package dev.nami.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A saved, named A-B loop (План.md §22.2 "выделил фрагмент, зациклил, сохранил под именем").
 * The live loop-while-playing state itself lives in PlayerRepository (activeLoop/setActiveLoop) --
 * this table is just the saved presets a user can come back to and re-activate later. */
@Entity(tableName = "loops")
data class LoopEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: String,
    val startMs: Long,
    val endMs: Long,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long = 0,
)
