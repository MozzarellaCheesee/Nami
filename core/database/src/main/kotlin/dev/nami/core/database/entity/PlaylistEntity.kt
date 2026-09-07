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
    /** The one system-managed "Любимые треки" playlist (Spotify-style Liked Songs) -- its own
     * heart-gradient cover instead of coverPath, name/cover locked, can't be deleted. At most one
     * row ever has this true (enforced by PlaylistRepositoryImpl.ensureLikedPlaylist's
     * find-or-create, not a DB constraint -- a unique partial index isn't worth it for a value
     * that's only ever written by one code path). */
    val isLiked: Boolean = false,
    /** П.md §20 "умные плейлисты" -- see core.model.Playlist's identical doc. */
    val isSmart: Boolean = false,
    val smartQueryJson: String? = null,
)
