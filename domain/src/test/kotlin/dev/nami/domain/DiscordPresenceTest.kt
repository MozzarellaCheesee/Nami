package dev.nami.domain

import dev.nami.core.model.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiscordPresenceTest {
    private val track = QueueTrack(TrackId("1"), "Трек 🎵", "Исполнитель")
    private val playing = PlaybackState.Playing(track.id, 15000, 210000, true)
    private fun presence(state: PlaybackState = playing, now: Long = 1_000_000) =
        discordPresence(state, track, DiscordListeningMode.SOLO, true, now)

    @Test fun `pause stop and mismatched queue clear activity`() {
        assertNull(presence(PlaybackState.Idle))
        assertNull(presence(playing.copy(isPlaying = false)))
        assertNull(presence(playing.copy(trackId = TrackId("other"))))
        assertNull(discordPresence(playing, null, DiscordListeningMode.SOLO, true, 1_000_000))
    }

    @Test fun `elapsed playback keeps timestamps and seek changes them`() {
        assertEquals(985L, presence()!!.startSeconds)
        assertEquals(1195L, presence()!!.endSeconds)
        assertEquals(presence(), presence(playing.copy(positionMs = 16000), 1_001_000))
        assertEquals(940L, presence(playing.copy(positionMs = 60000))!!.startSeconds)
        assertNull(presence(playing.copy(durationMs = 0))!!.endSeconds)
    }

    @Test fun `real modes have stable priority and do not expose room secrets`() {
        val jam = JamSession("SECRET", true, emptyList(), null, 0, 0, null)
        assertEquals(DiscordListeningMode.JAM_HOST, discordListeningMode(jam, true, true, true, true))
        assertEquals(DiscordListeningMode.JAM_GUEST, discordListeningMode(jam.copy(isHost = false), true, false, false, false))
        assertEquals(DiscordListeningMode.TOGETHER_GUEST, discordListeningMode(jam, false, true, true, true))
        assertEquals(DiscordListeningMode.TOGETHER_HOST, discordListeningMode(null, false, false, true, true))
        assertEquals(DiscordListeningMode.DROP, discordListeningMode(null, false, false, false, true))
        assertEquals(DiscordListeningMode.SOLO, discordListeningMode(null, false, false, false, false))
        val activity = discordPresence(playing, track, DiscordListeningMode.JAM_HOST, true, 1_000_000)!!
        assertEquals("Исполнитель · Джем · ведущий", activity.description)
        assertFalse(activity.description.contains(jam.code))
        assertEquals("Исполнитель", discordPresence(playing, track, DiscordListeningMode.JAM_HOST, false, 1_000_000)!!.description)
    }

    @Test fun `application ID validation rejects tokens signs Unicode and overflow`() {
        assertTrue(validDiscordApplicationId("123456789012345678"))
        assertTrue(validDiscordApplicationId("18446744073709551615"))
        listOf("", "1", "00000000000000000", "18446744073709551616", "+12345678901234567",
            "１２３４５６７８９０１２３４５６７８", "mfa.not-an-application-id").forEach {
            assertFalse(validDiscordApplicationId(it), it)
        }
    }
}
