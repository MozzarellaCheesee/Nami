package dev.nami.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import dev.nami.core.database.dao.AlbumDao
import dev.nami.core.database.dao.ArtistDao
import dev.nami.core.database.dao.LoopDao
import dev.nami.core.database.dao.MomentDao
import dev.nami.core.database.dao.PlaylistDao
import dev.nami.core.database.dao.PlaylistTrackDao
import dev.nami.core.database.dao.SearchDao
import dev.nami.core.database.dao.TrackDao
import dev.nami.core.database.dao.VocabularyDao
import dev.nami.core.database.entity.AlbumArtistCrossRef
import dev.nami.core.database.entity.AlbumEntity
import dev.nami.core.database.entity.ArtistEntity
import dev.nami.core.database.entity.LoopEntity
import dev.nami.core.database.entity.MomentEntity
import dev.nami.core.database.entity.PlaylistEntity
import dev.nami.core.database.entity.PlaylistTrackEntity
import dev.nami.core.database.entity.TrackEntity
import dev.nami.core.database.entity.VocabularyEntity

@Database(
    entities = [
        TrackEntity::class, ArtistEntity::class, AlbumEntity::class,
        PlaylistEntity::class, PlaylistTrackEntity::class, AlbumArtistCrossRef::class,
        VocabularyEntity::class, MomentEntity::class, LoopEntity::class,
    ],
    version = 21,
    exportSchema = true,
)
abstract class NamiDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun artistDao(): ArtistDao
    abstract fun albumDao(): AlbumDao
    abstract fun searchDao(): SearchDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun playlistTrackDao(): PlaylistTrackDao
    abstract fun vocabularyDao(): VocabularyDao
    abstract fun momentDao(): MomentDao
    abstract fun loopDao(): LoopDao
}
