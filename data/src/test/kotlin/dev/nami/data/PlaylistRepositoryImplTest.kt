package dev.nami.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.nami.core.database.NamiDatabase
import dev.nami.core.database.entity.TrackEntity
import dev.nami.core.model.PlaylistId
import dev.nami.core.model.TrackId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class PlaylistRepositoryImplTest {
    private lateinit var db: NamiDatabase
    private lateinit var context: Context
    private lateinit var repo: PlaylistRepositoryImpl

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, NamiDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = PlaylistRepositoryImpl(context, db.playlistDao(), db.playlistTrackDao(), db.trackDao(), ArtworkStore(context))
    }

    @Test
    fun `deletePlaylist soft-deletes it out of pagingSource and into trashedPlaylistsFlow`() = runTest {
        val playlistId = repo.createPlaylist("Doujin")

        repo.deletePlaylist(playlistId)

        val trashed = db.playlistDao().trashedPlaylistsFlow().first()
        assertEquals(listOf(playlistId.value), trashed.map { it.id })
    }

    @After
    fun tearDown() = db.close()

    private fun trackFixture(id: String, path: String) = TrackEntity(
        id = id, title = id, artistId = null, albumId = null, trackNo = null, discNo = null,
        durationMs = 1000, path = path, format = "flac", sizeBytes = 1,
        dateAdded = 1, lastPlayed = null, playCount = 0, genre = null,
    )

    @Test
    fun `createPlaylist then addTrack then tracksInPlaylist returns the track`() = runTest {
        db.trackDao().insertAll(listOf(trackFixture("t1", "/music/t1.flac")))
        val playlistId = repo.createPlaylist("Doujin")

        repo.addTrack(playlistId, TrackId("t1"))
        val tracks = repo.tracksInPlaylist(playlistId).first()

        assertEquals(listOf("t1"), tracks.map { it.id.value })
    }

    @Test
    fun `addTrack is idempotent for the same track`() = runTest {
        db.trackDao().insertAll(listOf(trackFixture("t1", "/music/t1.flac")))
        val playlistId = repo.createPlaylist("Doujin")

        repo.addTrack(playlistId, TrackId("t1"))
        repo.addTrack(playlistId, TrackId("t1"))

        assertEquals(1, repo.tracksInPlaylist(playlistId).first().size)
    }

    @Test
    fun `removeTrack removes exactly that track`() = runTest {
        db.trackDao().insertAll(listOf(trackFixture("t1", "/music/t1.flac"), trackFixture("t2", "/music/t2.flac")))
        val playlistId = repo.createPlaylist("Doujin")
        repo.addTrack(playlistId, TrackId("t1"))
        repo.addTrack(playlistId, TrackId("t2"))

        repo.removeTrack(playlistId, TrackId("t1"))

        assertEquals(listOf("t2"), repo.tracksInPlaylist(playlistId).first().map { it.id.value })
    }

    @Test
    fun `exportM3u8 writes one path per line`() = runTest {
        db.trackDao().insertAll(listOf(trackFixture("t1", "/music/t1.flac"), trackFixture("t2", "/music/t2.flac")))
        val playlistId = repo.createPlaylist("Doujin")
        repo.addTrack(playlistId, TrackId("t1"))
        repo.addTrack(playlistId, TrackId("t2"))

        val destinationUri = android.net.Uri.parse("content://fake/export.m3u8")
        val captured = ByteArrayOutputStream()
        shadowOf(context.contentResolver).registerOutputStream(destinationUri, captured)

        repo.exportM3u8(playlistId, destinationUri.toString())

        val lines = captured.toString().lines().filter { it.isNotBlank() }
        assertTrue(lines.contains("/music/t1.flac"))
        assertTrue(lines.contains("/music/t2.flac"))
    }

    @Test
    fun `importM3u8 matches known paths and skips unknown ones`() = runTest {
        db.trackDao().insertAll(listOf(trackFixture("t1", "/music/t1.flac")))
        val sourceUri = android.net.Uri.parse("content://fake/import.m3u8")
        val content = "#EXTM3U\n/music/t1.flac\n/music/missing.flac\n"
        shadowOf(context.contentResolver).registerInputStream(sourceUri, content.byteInputStream())

        val result = repo.importM3u8(sourceUri.toString(), "Imported")

        assertEquals(1, result.matchedCount)
        assertEquals(1, result.skippedCount)
        assertEquals(listOf("t1"), repo.tracksInPlaylist(result.playlistId).first().map { it.id.value })
    }
}
