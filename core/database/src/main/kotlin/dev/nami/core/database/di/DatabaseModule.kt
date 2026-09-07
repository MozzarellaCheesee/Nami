package dev.nami.core.database.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.nami.core.database.CREATE_SEARCH_INDEX_SQL
import dev.nami.core.database.NamiDatabase
import dev.nami.core.database.dao.AlbumDao
import dev.nami.core.database.dao.ArtistDao
import dev.nami.core.database.dao.PlaylistDao
import dev.nami.core.database.dao.PlaylistTrackDao
import dev.nami.core.database.dao.SearchDao
import dev.nami.core.database.dao.TrackDao
import dev.nami.core.database.dao.VocabularyDao
import dev.nami.core.database.migration.MIGRATION_1_2
import dev.nami.core.database.migration.MIGRATION_2_3
import dev.nami.core.database.migration.MIGRATION_3_4
import dev.nami.core.database.migration.MIGRATION_4_5
import dev.nami.core.database.migration.MIGRATION_5_6
import dev.nami.core.database.migration.MIGRATION_6_7
import dev.nami.core.database.migration.MIGRATION_7_8
import dev.nami.core.database.migration.MIGRATION_8_9
import dev.nami.core.database.migration.MIGRATION_9_10
import dev.nami.core.database.migration.MIGRATION_10_11
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): NamiDatabase =
        Room.databaseBuilder(context, NamiDatabase::class.java, "nami.db")
            .openHelperFactory(RequerySQLiteOpenHelperFactory())
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11)
            .addCallback(
                object : RoomDatabase.Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        super.onCreate(db)
                        db.execSQL(CREATE_SEARCH_INDEX_SQL)
                    }
                },
            )
            .build()

    @Provides
    fun provideTrackDao(db: NamiDatabase): TrackDao = db.trackDao()

    @Provides
    fun provideArtistDao(db: NamiDatabase): ArtistDao = db.artistDao()

    @Provides
    fun provideAlbumDao(db: NamiDatabase): AlbumDao = db.albumDao()

    @Provides
    fun provideSearchDao(db: NamiDatabase): SearchDao = db.searchDao()

    @Provides
    fun providePlaylistDao(db: NamiDatabase): PlaylistDao = db.playlistDao()

    @Provides
    fun providePlaylistTrackDao(db: NamiDatabase): PlaylistTrackDao = db.playlistTrackDao()

    @Provides
    fun provideVocabularyDao(db: NamiDatabase): VocabularyDao = db.vocabularyDao()
}
