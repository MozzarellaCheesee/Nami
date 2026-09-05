package dev.nami.player

import androidx.media3.common.Player
import dev.nami.core.model.TrackId
import dev.nami.domain.PlaybackState
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackStateMapperTest {
    @Test
    fun `maps playing player to Playing state`() {
        val state = toPlaybackState(
            trackId = TrackId("t1"),
            positionMs = 5000,
            durationMs = 180_000,
            playbackState = Player.STATE_READY,
            playWhenReady = true,
        )

        assertEquals(
            PlaybackState.Playing(TrackId("t1"), 5000, 180_000, isPlaying = true),
            state,
        )
    }

    @Test
    fun `maps idle player to Idle state`() {
        val state = toPlaybackState(
            trackId = null,
            positionMs = 0,
            durationMs = 0,
            playbackState = Player.STATE_IDLE,
            playWhenReady = false,
        )

        assertEquals(PlaybackState.Idle, state)
    }
}
