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

    @Query("UPDATE playlists SET name = :name, updatedAt = :updatedAt WHERE id = :id")
    suspend fun rename(id: String, name: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE playlists SET coverPath = :coverPath WHERE id = :id")
    suspend fun setCoverPath(id: String, coverPath: String)

    @Query("UPDATE playlists SET deletedAt = :deletedAt WHERE id = :id")
    suspend fun softDelete(id: String, deletedAt: Long)

    @Query("UPDATE playlists SET deletedAt = NULL WHERE id = :id")
    suspend fun restore(id: String)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun hardDelete(id: String)

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun findById(id: String): PlaylistEntity?

    /** Snapshot of every non-deleted playlist for export/backup - see BackupRepository. */
    @Query("SELECT * FROM playlists WHERE deletedAt IS NULL")
    suspend fun allRaw(): List<PlaylistEntity>

    @Query("SELECT * FROM playlists")
    suspend fun allForSync(): List<PlaylistEntity>

    @Query("SELECT * FROM playlists WHERE id = :id")
    fun findByIdFlow(id: String): Flow<PlaylistEntity?>

    /** The one Liked-songs playlist, if it's ever been created (see PlaylistRepositoryImpl.
     * ensureLikedPlaylist) - null before the user's first like. */
    @Query("SELECT * FROM playlists WHERE isLiked = 1 AND deletedAt IS NULL LIMIT 1")
    suspend fun findLikedPlaylist(): PlaylistEntity?

    @Query(
        """
        SELECT playlists.id AS id, playlists.name AS name, playlists.coverPath AS coverPath,
               playlists.isLiked AS isLiked, playlists.isSmart AS isSmart,
               COUNT(playlist_tracks.trackId) AS trackCount
        FROM playlists
        LEFT JOIN playlist_tracks ON playlists.id = playlist_tracks.playlistId
        WHERE playlists.deletedAt IS NULL
        GROUP BY playlists.id
        ORDER BY playlists.isLiked DESC, playlists.createdAt DESC
        """,
    )
    fun pagingSource(): PagingSource<Int, PlaylistListRow>

    @Query(
        """
        SELECT playlists.id AS id, playlists.name AS name, playlists.coverPath AS coverPath,
               playlists.isLiked AS isLiked, playlists.isSmart AS isSmart,
               COUNT(playlist_tracks.trackId) AS trackCount
        FROM playlists
        LEFT JOIN playlist_tracks ON playlists.id = playlist_tracks.playlistId
        WHERE playlists.deletedAt IS NULL
        GROUP BY playlists.id
        ORDER BY playlists.isLiked DESC, playlists.createdAt DESC
        LIMIT :limit
        """,
    )
    suspend fun recent(limit: Int): List<PlaylistListRow>

    @Query(
        """
        SELECT playlists.id AS id, playlists.name AS name, playlists.coverPath AS coverPath,
               playlists.deletedAt AS deletedAt, playlists.isLiked AS isLiked,
               playlists.isSmart AS isSmart, COUNT(playlist_tracks.trackId) AS trackCount
        FROM playlists
        LEFT JOIN playlist_tracks ON playlists.id = playlist_tracks.playlistId
        WHERE playlists.deletedAt IS NOT NULL
        GROUP BY playlists.id
        ORDER BY playlists.deletedAt DESC
        """,
    )
    fun trashedPlaylistsFlow(): Flow<List<PlaylistListRow>>

    @Query("SELECT deletedAt FROM playlists WHERE id = :id")
    suspend fun deletedAtOf(id: String): Long?

    @Query("UPDATE playlists SET smartQueryJson = :queryJson WHERE id = :id")
    suspend fun updateSmartQuery(id: String, queryJson: String)

    /** П.md §20 - свои настройки воспроизведения на плейлист. Null в любом поле означает
     * "не навязывать", поэтому пишутся все три разом: частичное обновление потребовало бы
     * отличать "не задано" от "не меняй", а разницы в поведении между ними нет. */
    @Query(
        "UPDATE playlists SET eqGainsCsv = :eqGainsCsv, crossfadeEnabled = :crossfadeEnabled, " +
            "shuffleOnStart = :shuffleOnStart WHERE id = :id",
    )
    suspend fun updatePlaybackSettings(
        id: String,
        eqGainsCsv: String?,
        crossfadeEnabled: Boolean?,
        shuffleOnStart: Boolean?,
    )

    data class PlaylistListRow(
        val id: String,
        val name: String,
        val coverPath: String?,
        val trackCount: Int,
        val deletedAt: Long? = null,
        val isLiked: Boolean = false,
        val isSmart: Boolean = false,
    )
}
