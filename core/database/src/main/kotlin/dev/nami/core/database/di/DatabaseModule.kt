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
import dev.nami.core.database.dao.LoopDao
import dev.nami.core.database.dao.MomentDao
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
import dev.nami.core.database.migration.MIGRATION_11_12
import dev.nami.core.database.migration.MIGRATION_12_13
import dev.nami.core.database.migration.MIGRATION_13_14
import dev.nami.core.database.migration.MIGRATION_14_15
import dev.nami.core.database.migration.MIGRATION_15_16
import dev.nami.core.database.migration.MIGRATION_16_17
import dev.nami.core.database.migration.MIGRATION_17_18
import dev.nami.core.database.migration.MIGRATION_18_19
import dev.nami.core.database.migration.MIGRATION_19_20
import dev.nami.core.database.migration.MIGRATION_20_21
import dev.nami.core.database.migration.MIGRATION_21_22
import dev.nami.core.database.migration.MIGRATION_22_23
import dev.nami.core.database.migration.MIGRATION_23_24
import dev.nami.core.database.migration.MIGRATION_24_25
import dev.nami.core.database.migration.MIGRATION_25_26
import dev.nami.core.database.migration.MIGRATION_26_27
import dev.nami.core.database.migration.MIGRATION_27_28
import dev.nami.core.database.migration.MIGRATION_28_29
import dev.nami.core.database.migration.MIGRATION_29_30
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
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23, MIGRATION_23_24, MIGRATION_24_25, MIGRATION_25_26, MIGRATION_26_27, MIGRATION_27_28, MIGRATION_28_29, MIGRATION_29_30)
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

    @Provides
    fun provideMomentDao(db: NamiDatabase): MomentDao = db.momentDao()

    @Provides
    fun provideLoopDao(db: NamiDatabase): LoopDao = db.loopDao()

    @Provides
    fun providePlayHistoryDao(db: NamiDatabase): dev.nami.core.database.dao.PlayHistoryDao = db.playHistoryDao()

    @Provides
    fun provideTagDao(db: NamiDatabase): dev.nami.core.database.dao.TagDao = db.tagDao()

    @Provides
    fun providePendingScrobbleDao(db: NamiDatabase): dev.nami.core.database.dao.PendingScrobbleDao = db.pendingScrobbleDao()
}
