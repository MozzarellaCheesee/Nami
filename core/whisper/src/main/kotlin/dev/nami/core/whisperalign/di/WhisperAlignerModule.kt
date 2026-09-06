package dev.nami.core.whisperalign.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.nami.core.whisperalign.WhisperAlignerImpl
import dev.nami.domain.WhisperAligner
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class WhisperAlignerModule {
    @Binds
    @Singleton
    abstract fun bindWhisperAligner(impl: WhisperAlignerImpl): WhisperAligner
}
