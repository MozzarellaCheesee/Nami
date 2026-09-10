package dev.nami.data.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.nami.data.BackupRepositoryImpl
import dev.nami.data.LocalShareRepositoryImpl
import dev.nami.data.DictionaryRepositoryImpl
import dev.nami.data.LibraryRepositoryImpl
import dev.nami.data.LyricsRepositoryImpl
import dev.nami.data.AppSettingsRepository
import dev.nami.data.LoopsRepositoryImpl
import dev.nami.data.MomentsRepositoryImpl
import dev.nami.data.PlaylistRepositoryImpl
import dev.nami.data.SyncRepositoryImpl
import dev.nami.data.TagRepositoryImpl
import dev.nami.data.TrashRepositoryImpl
import dev.nami.data.VocabularyRepositoryImpl
import dev.nami.data.networkimport.NetworkImportRepositoryImpl
import dev.nami.data.search.SearchRepositoryImpl
import dev.nami.domain.BackupRepository
import dev.nami.domain.LocalShareRepository
import dev.nami.domain.DictionaryRepository
import dev.nami.domain.LibraryRepository
import dev.nami.domain.LyricsRepository
import dev.nami.domain.LoopsRepository
import dev.nami.domain.MomentsRepository
import dev.nami.domain.NetworkImportRepository
import dev.nami.domain.PlaylistRepository
import dev.nami.domain.SearchRepository
import dev.nami.domain.SettingsRepository
import dev.nami.domain.SyncRepository
import dev.nami.domain.TagRepository
import dev.nami.domain.TrashRepository
import dev.nami.domain.VocabularyRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {
    @Binds
    @Singleton
    abstract fun bindLibraryRepository(impl: LibraryRepositoryImpl): LibraryRepository

    @Binds
    @Singleton
    abstract fun bindBackupRepository(impl: BackupRepositoryImpl): BackupRepository

    @Binds
    @Singleton
    abstract fun bindLocalShareRepository(impl: LocalShareRepositoryImpl): LocalShareRepository

    @Binds
    @Singleton
    abstract fun bindLyricsRepository(impl: LyricsRepositoryImpl): LyricsRepository

    @Binds
    @Singleton
    abstract fun bindNetworkImportRepository(impl: NetworkImportRepositoryImpl): NetworkImportRepository

    @Binds
    @Singleton
    abstract fun bindSearchRepository(impl: SearchRepositoryImpl): SearchRepository

    @Binds
    @Singleton
    abstract fun bindPlaylistRepository(impl: PlaylistRepositoryImpl): PlaylistRepository

    @Binds
    @Singleton
    abstract fun bindTrashRepository(impl: TrashRepositoryImpl): TrashRepository

    @Binds
    @Singleton
    abstract fun bindDictionaryRepository(impl: DictionaryRepositoryImpl): DictionaryRepository

    @Binds
    @Singleton
    abstract fun bindVocabularyRepository(impl: VocabularyRepositoryImpl): VocabularyRepository

    @Binds
    @Singleton
    abstract fun bindSettingsRepository(impl: AppSettingsRepository): SettingsRepository

    @Binds
    @Singleton
    abstract fun bindMomentsRepository(impl: MomentsRepositoryImpl): MomentsRepository

    @Binds
    @Singleton
    abstract fun bindLoopsRepository(impl: LoopsRepositoryImpl): LoopsRepository

    @Binds
    @Singleton
    abstract fun bindTagRepository(impl: TagRepositoryImpl): TagRepository

    @Binds
    @Singleton
    abstract fun bindServerAudioRepository(
        impl: dev.nami.data.ServerAudioRepositoryImpl,
    ): dev.nami.domain.ServerAudioRepository

    @Binds
    @Singleton
    abstract fun bindSyncRepository(impl: SyncRepositoryImpl): SyncRepository
}
