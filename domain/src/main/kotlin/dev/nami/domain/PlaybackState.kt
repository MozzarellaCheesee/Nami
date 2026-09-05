package dev.nami.domain

import dev.nami.core.model.TrackId

sealed interface PlaybackState {
    data object Idle : PlaybackState

    data class Playing(
        val trackId: TrackId,
        val positionMs: Long,
        val durationMs: Long,
        val isPlaying: Boolean,
    ) : PlaybackState
}
