package dev.nami.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** An album can have more than one artist (compilations, splits, features) - this is the join
 * table for that; [AlbumEntity.artistId] stays as the single "primary" artist (used for grouping
 * an artist's own discography and as the default when an album has exactly one artist), while
 * this table holds every artist actually credited on the album, including the primary one. */
@Entity(
    tableName = "album_artists",
    primaryKeys = ["albumId", "artistId"],
    foreignKeys = [
        ForeignKey(entity = AlbumEntity::class, parentColumns = ["id"], childColumns = ["albumId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ArtistEntity::class, parentColumns = ["id"], childColumns = ["artistId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("artistId")],
)
data class AlbumArtistCrossRef(val albumId: String, val artistId: String)
