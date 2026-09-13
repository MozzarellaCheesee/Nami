package dev.nami.data

import dev.nami.core.model.AlbumId
import dev.nami.core.model.Track
import dev.nami.core.model.TrackId
import dev.nami.domain.ServerTrackMeta
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ServerTrackPairingTest {

    private fun local(
        id: String,
        title: String,
        artist: String? = "Артист",
        albumId: String? = "album-1",
        serverTrackId: Long? = null,
    ) = Track(
        id = TrackId(id),
        title = title,
        artistId = null,
        albumId = albumId?.let(::AlbumId),
        durationMs = 200_000,
        path = "/музыка/$id.flac",
        format = "flac",
        sizeBytes = 1,
        dateAdded = 0,
        artistName = artist,
        serverTrackId = serverTrackId,
    )

    private fun server(id: Long, title: String, artist: String = "Артист", album: String? = "Альбом") =
        ServerTrackMeta(id = id, title = title, artist = artist, album = album, durationMs = 200_000)

    private fun pair(serverTracks: List<ServerTrackMeta>, localTracks: List<Track>) =
        pairServerTracksWithLocal(
            serverTracks = serverTracks,
            localTracks = localTracks,
            albumTitleOf = { "Альбом" },
            primaryArtist = { it },
        )

    @Test
    fun `unlinked track is matched by metadata`() {
        val localTrack = local("t1", "Песня")
        val result = pair(listOf(server(42, "Песня")), listOf(localTrack))
        assertEquals(localTrack, result.single().second)
    }

    @Test
    fun `a rename on another device still finds the linked local file`() {
        // Ровно тот случай, ради которого связь и заводится: название на сервере уже другое,
        // сопоставление по метаданным его не найдёт, и без связи рядом с собственным файлом
        // появилось бы зеркало.
        val localTrack = local("t1", "Старое название", serverTrackId = 42)
        val result = pair(listOf(server(42, "Новое название")), listOf(localTrack))
        assertEquals(localTrack, result.single().second)
    }

    @Test
    fun `a track the device does not have stays unmatched`() {
        val result = pair(listOf(server(7, "Чужая песня")), listOf(local("t1", "Своя песня")))
        assertNull(result.single().second)
    }

    @Test
    fun `one local file cannot be claimed by two server tracks`() {
        // Иначе оба серверных трека сочли бы себя уже имеющимися локально, и второй пропал бы
        // из библиотеки совсем - ни зеркала, ни своего файла.
        val localTrack = local("t1", "Песня")
        val result = pair(listOf(server(1, "Песня"), server(2, "Песня")), listOf(localTrack))
        assertEquals(localTrack, result[0].second)
        assertNull(result[1].second)
    }

    @Test
    fun `a file linked to another server track is not matched by metadata`() {
        val linkedElsewhere = local("t1", "Песня", serverTrackId = 99)
        val result = pair(listOf(server(42, "Песня")), listOf(linkedElsewhere))
        assertNull(result.single().second)
    }
}
