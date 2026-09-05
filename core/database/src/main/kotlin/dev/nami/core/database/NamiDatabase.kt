package dev.nami.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import dev.nami.core.database.dao.AlbumDao
import dev.nami.core.database.dao.ArtistDao
import dev.nami.core.database.dao.PlaylistDao
import dev.nami.core.database.dao.PlaylistTrackDao
import dev.nami.core.database.dao.SearchDao
import dev.nami.core.database.dao.TrackDao
import dev.nami.core.database.entity.AlbumEntity
import dev.nami.core.database.entity.ArtistEntity
import dev.nami.core.database.entity.PlaylistEntity
import dev.nami.core.database.entity.PlaylistTrackEntity
import dev.nami.core.database.entity.TrackEntity

@Database(
    entities = [
        TrackEntity::class, ArtistEntity::class, AlbumEntity::class,
        PlaylistEntity::class, PlaylistTrackEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
abstract class NamiDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun artistDao(): ArtistDao
    abstract fun albumDao(): AlbumDao
    abstract fun searchDao(): SearchDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun playlistTrackDao(): PlaylistTrackDao
}
