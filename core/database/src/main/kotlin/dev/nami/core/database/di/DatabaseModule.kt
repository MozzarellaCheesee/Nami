package dev.nami.core.database.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.nami.core.database.NamiDatabase
import dev.nami.core.database.dao.AlbumDao
import dev.nami.core.database.dao.ArtistDao
import dev.nami.core.database.dao.TrackDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): NamiDatabase =
        Room.databaseBuilder(context, NamiDatabase::class.java, "nami.db")
            .addMigrations(dev.nami.core.database.migration.MIGRATION_1_2)
            .build()

    @Provides
    fun provideTrackDao(db: NamiDatabase): TrackDao = db.trackDao()

    @Provides
    fun provideArtistDao(db: NamiDatabase): ArtistDao = db.artistDao()

    @Provides
    fun provideAlbumDao(db: NamiDatabase): AlbumDao = db.albumDao()
}
