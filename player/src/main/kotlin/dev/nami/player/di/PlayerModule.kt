package dev.nami.player.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.nami.domain.DjRepository
import dev.nami.domain.PlayerRepository
import dev.nami.player.DjRepositoryImpl
import dev.nami.player.PlayerRepositoryImpl
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class PlayerModule {
    @Binds
    @Singleton
    abstract fun bindPlayerRepository(impl: PlayerRepositoryImpl): PlayerRepository

    // Not @Singleton -- see DjRepositoryImpl's doc, one instance per DjViewModel, released with it.
    @Binds
    abstract fun bindDjRepository(impl: DjRepositoryImpl): DjRepository
}
