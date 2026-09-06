package dev.nami.data.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.nami.data.LibraryRepositoryImpl
import dev.nami.data.LyricsRepositoryImpl
import dev.nami.data.PlaylistRepositoryImpl
import dev.nami.data.TrashRepositoryImpl
import dev.nami.data.search.SearchRepositoryImpl
import dev.nami.domain.LibraryRepository
import dev.nami.domain.LyricsRepository
import dev.nami.domain.PlaylistRepository
import dev.nami.domain.SearchRepository
import dev.nami.domain.TrashRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {
    @Binds
    @Singleton
    abstract fun bindLibraryRepository(impl: LibraryRepositoryImpl): LibraryRepository

    @Binds
    @Singleton
    abstract fun bindLyricsRepository(impl: LyricsRepositoryImpl): LyricsRepository

    @Binds
    @Singleton
    abstract fun bindSearchRepository(impl: SearchRepositoryImpl): SearchRepository

    @Binds
    @Singleton
    abstract fun bindPlaylistRepository(impl: PlaylistRepositoryImpl): PlaylistRepository

    @Binds
    @Singleton
    abstract fun bindTrashRepository(impl: TrashRepositoryImpl): TrashRepository
}
