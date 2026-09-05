package dev.nami.core.database.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.nami.core.database.entity.PlaylistTrackEntity
import dev.nami.core.database.entity.TrackEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistTrackDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entry: PlaylistTrackEntity)

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId AND trackId = :trackId")
    suspend fun remove(playlistId: String, trackId: String)

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM playlist_tracks WHERE playlistId = :playlistId")
    suspend fun nextPosition(playlistId: String): Int

    @Query(
        """
        SELECT tracks.* FROM playlist_tracks
        JOIN tracks ON playlist_tracks.trackId = tracks.id
        WHERE playlist_tracks.playlistId = :playlistId AND tracks.deletedAt IS NULL
        ORDER BY playlist_tracks.position ASC
        """,
    )
    suspend fun tracksInPlaylist(playlistId: String): List<TrackEntity>

    @Query(
        """
        SELECT tracks.*, COALESCE(albums.artworkPath, tracks.artworkPath) AS albumArtworkPath,
               artists.name AS artistName
        FROM playlist_tracks
        JOIN tracks ON playlist_tracks.trackId = tracks.id
        LEFT JOIN albums ON tracks.albumId = albums.id
        LEFT JOIN artists ON tracks.artistId = artists.id
        WHERE playlist_tracks.playlistId = :playlistId AND tracks.deletedAt IS NULL
        ORDER BY playlist_tracks.position ASC
        """,
    )
    fun tracksInPlaylistFlow(playlistId: String): Flow<List<TrackWithArtwork>>

    data class TrackWithArtwork(
        @Embedded val track: TrackEntity,
        val albumArtworkPath: String?,
        val artistName: String?,
    )
}
