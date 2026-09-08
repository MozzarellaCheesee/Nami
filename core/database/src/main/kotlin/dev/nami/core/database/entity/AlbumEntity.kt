package dev.nami.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "albums",
    foreignKeys = [
        ForeignKey(
            entity = ArtistEntity::class,
            parentColumns = ["id"],
            childColumns = ["artistId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("artistId")],
)
data class AlbumEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artistId: String?,
    val year: Int?,
    val artworkPath: String?,
    val isSingle: Boolean = false,
    /** Real album-level trash (План.md's 30-day corzина) - previously "deleting an album" only
     * soft-deleted its tracks and relied on an EXISTS(non-deleted track) check to hide the album
     * row, which silently failed to hide the album whenever that per-track loop didn't finish for
     * every track (one file move failure, one already-missing file) - some tracks stayed
     * non-deleted, the EXISTS check kept finding them, and the album never disappeared. This flag
     * is the album's own lifecycle, independent of its tracks' individual state. */
    val deletedAt: Long? = null,
)
