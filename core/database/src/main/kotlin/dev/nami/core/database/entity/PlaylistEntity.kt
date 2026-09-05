package dev.nami.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val coverPath: String?,
    val createdAt: Long,
    val deletedAt: Long? = null,
)
