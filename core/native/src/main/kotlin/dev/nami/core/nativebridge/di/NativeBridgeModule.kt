package dev.nami.core.nativebridge.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.nami.core.nativebridge.TagReaderNativeBridge
import dev.nami.domain.NativeBridge
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class NativeBridgeModule {
    @Binds
    @Singleton
    abstract fun bindNativeBridge(impl: TagReaderNativeBridge): NativeBridge
}
