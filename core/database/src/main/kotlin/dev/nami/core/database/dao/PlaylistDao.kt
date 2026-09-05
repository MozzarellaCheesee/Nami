package dev.nami.core.database.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.nami.core.database.entity.PlaylistEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(playlist: PlaylistEntity)

    @Query("UPDATE playlists SET name = :name WHERE id = :id")
    suspend fun rename(id: String, name: String)

    @Query("UPDATE playlists SET coverPath = :coverPath WHERE id = :id")
    suspend fun setCoverPath(id: String, coverPath: String)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun findById(id: String): PlaylistEntity?

    @Query("SELECT * FROM playlists WHERE id = :id")
    fun findByIdFlow(id: String): Flow<PlaylistEntity?>

    @Query(
        """
        SELECT playlists.id AS id, playlists.name AS name, playlists.coverPath AS coverPath,
               COUNT(playlist_tracks.trackId) AS trackCount
        FROM playlists
        LEFT JOIN playlist_tracks ON playlists.id = playlist_tracks.playlistId
        GROUP BY playlists.id
        ORDER BY playlists.createdAt DESC
        """,
    )
    fun pagingSource(): PagingSource<Int, PlaylistListRow>

    data class PlaylistListRow(
        val id: String,
        val name: String,
        val coverPath: String?,
        val trackCount: Int,
    )
}
