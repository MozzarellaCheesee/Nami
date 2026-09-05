package dev.nami.core.database

import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.nami.core.database.dao.PlaylistDao
import dev.nami.core.database.entity.PlaylistEntity
import dev.nami.core.database.entity.PlaylistTrackEntity
import dev.nami.core.database.entity.TrackEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
class PlaylistDaoTest {
    private lateinit var db: NamiDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            NamiDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private fun trackFixture(id: String) = TrackEntity(
        id = id, title = id, artistId = null, albumId = null, trackNo = null, discNo = null,
        durationMs = 1000, path = "/music/$id.flac", format = "flac", sizeBytes = 1,
        dateAdded = 1, lastPlayed = null, playCount = 0, genre = null,
    )

    private suspend fun loadFirstPage(source: PagingSource<Int, PlaylistDao.PlaylistListRow>) =
        (source.load(PagingSource.LoadParams.Refresh(key = null, loadSize = 20, placeholdersEnabled = false))
            as PagingSource.LoadResult.Page)

    @Test
    fun `pagingSource joins track count and orders by createdAt desc`() = runTest {
        db.playlistDao().insert(PlaylistEntity(id = "p1", name = "Doujin", coverPath = null, createdAt = 1))
        db.playlistDao().insert(PlaylistEntity(id = "p2", name = "Night Drive", coverPath = null, createdAt = 2))
        db.trackDao().insertAll(listOf(trackFixture("t1"), trackFixture("t2")))
        db.playlistTrackDao().insert(PlaylistTrackEntity("p1", "t1", 0, 1))
        db.playlistTrackDao().insert(PlaylistTrackEntity("p1", "t2", 1, 2))

        val page = loadFirstPage(db.playlistDao().pagingSource())

        assertEquals(listOf("p2", "p1"), page.data.map { it.id })
        assertEquals(2, page.data.first { it.id == "p1" }.trackCount)
        assertEquals(0, page.data.first { it.id == "p2" }.trackCount)
    }

    @Test
    fun `insert into playlist_tracks is idempotent for the same track`() = runTest {
        db.playlistDao().insert(PlaylistEntity(id = "p1", name = "Doujin", coverPath = null, createdAt = 1))
        db.trackDao().insertAll(listOf(trackFixture("t1")))

        db.playlistTrackDao().insert(PlaylistTrackEntity("p1", "t1", 0, 1))
        db.playlistTrackDao().insert(PlaylistTrackEntity("p1", "t1", 1, 2))

        val tracks = db.playlistTrackDao().tracksInPlaylist("p1")
        assertEquals(1, tracks.size)
    }

    @Test
    fun `tracksInPlaylist orders by position`() = runTest {
        db.playlistDao().insert(PlaylistEntity(id = "p1", name = "Doujin", coverPath = null, createdAt = 1))
        db.trackDao().insertAll(listOf(trackFixture("t1"), trackFixture("t2")))
        db.playlistTrackDao().insert(PlaylistTrackEntity("p1", "t2", 0, 1))
        db.playlistTrackDao().insert(PlaylistTrackEntity("p1", "t1", 1, 2))

        val tracks = db.playlistTrackDao().tracksInPlaylist("p1")

        assertEquals(listOf("t2", "t1"), tracks.map { it.id })
    }

    @Test
    fun `remove deletes exactly one playlist-track pair`() = runTest {
        db.playlistDao().insert(PlaylistEntity(id = "p1", name = "Doujin", coverPath = null, createdAt = 1))
        db.trackDao().insertAll(listOf(trackFixture("t1"), trackFixture("t2")))
        db.playlistTrackDao().insert(PlaylistTrackEntity("p1", "t1", 0, 1))
        db.playlistTrackDao().insert(PlaylistTrackEntity("p1", "t2", 1, 2))

        db.playlistTrackDao().remove("p1", "t1")

        val tracks = db.playlistTrackDao().tracksInPlaylist("p1")
        assertEquals(listOf("t2"), tracks.map { it.id })
    }

    @Test
    fun `deleting a playlist cascades to its playlist_tracks rows`() = runTest {
        db.playlistDao().insert(PlaylistEntity(id = "p1", name = "Doujin", coverPath = null, createdAt = 1))
        db.trackDao().insertAll(listOf(trackFixture("t1")))
        db.playlistTrackDao().insert(PlaylistTrackEntity("p1", "t1", 0, 1))

        db.playlistDao().hardDelete("p1")

        assertEquals(emptyList(), db.playlistTrackDao().tracksInPlaylist("p1"))
        assertNull(db.playlistDao().findById("p1"))
    }

    @Test
    fun `nextPosition returns 0 for an empty playlist and increments after inserts`() = runTest {
        db.playlistDao().insert(PlaylistEntity(id = "p1", name = "Doujin", coverPath = null, createdAt = 1))
        assertEquals(0, db.playlistTrackDao().nextPosition("p1"))

        db.trackDao().insertAll(listOf(trackFixture("t1")))
        db.playlistTrackDao().insert(PlaylistTrackEntity("p1", "t1", 0, 1))

        assertEquals(1, db.playlistTrackDao().nextPosition("p1"))
    }

    @Test
    fun `softDelete hides playlist from pagingSource and trashedPlaylistsFlow shows it`() = runTest {
        db.playlistDao().insert(PlaylistEntity(id = "p1", name = "Mix", coverPath = null, createdAt = 1000))
        db.playlistDao().softDelete("p1", deletedAt = 2000)

        val trashed = db.playlistDao().trashedPlaylistsFlow().first()
        assertEquals(1, trashed.size)
        assertEquals("p1", trashed[0].id)

        val page = loadFirstPage(db.playlistDao().pagingSource())
        assertEquals(emptyList(), page.data.map { it.id })
    }

    @Test
    fun `restore clears deletedAt`() = runTest {
        db.playlistDao().insert(PlaylistEntity(id = "p1", name = "Mix", coverPath = null, createdAt = 1000))
        db.playlistDao().softDelete("p1", deletedAt = 2000)
        db.playlistDao().restore("p1")

        assertNull(db.playlistDao().findById("p1")?.deletedAt)
    }

    @Test
    fun `soft-deleted track disappears from tracksInPlaylistFlow`() = runTest {
        db.playlistDao().insert(PlaylistEntity(id = "p1", name = "Doujin", coverPath = null, createdAt = 1))
        db.trackDao().insertAll(listOf(trackFixture("t1"), trackFixture("t2")))
        db.playlistTrackDao().insert(PlaylistTrackEntity("p1", "t1", 0, 1))
        db.playlistTrackDao().insert(PlaylistTrackEntity("p1", "t2", 1, 2))

        db.trackDao().setDeletedAt("t1", deletedAt = 2000, path = "/trash/t1.flac")

        val tracks = db.playlistTrackDao().tracksInPlaylistFlow("p1").first()
        assertEquals(listOf("t2"), tracks.map { it.id })
    }

    @Test
    fun `hardDelete removes the row permanently`() = runTest {
        db.playlistDao().insert(PlaylistEntity(id = "p1", name = "Mix", coverPath = null, createdAt = 1000))
        db.playlistDao().hardDelete("p1")

        assertNull(db.playlistDao().findById("p1"))
    }
}
