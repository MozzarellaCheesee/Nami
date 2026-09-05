package dev.nami.core.database

import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.nami.core.database.dao.AlbumDao
import dev.nami.core.database.entity.AlbumEntity
import dev.nami.core.database.entity.ArtistEntity
import dev.nami.core.database.entity.TrackEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class LibraryBrowsingDaoTest {
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

    private suspend fun loadFirstPage(source: PagingSource<Int, AlbumDao.AlbumListRow>) =
        source.load(
            PagingSource.LoadParams.Refresh(key = null, loadSize = 20, placeholdersEnabled = false),
        ) as PagingSource.LoadResult.Page

    @Test
    fun `album pagingSource joins artist name`() = runTest {
        db.artistDao().insert(ArtistEntity(id = "a1", name = "Farewell225", sortName = "Farewell225"))
        db.albumDao().insert(
            AlbumEntity(id = "al1", title = "Doujin Compilation", artistId = "a1", year = 2023, artworkPath = null),
        )

        val page = loadFirstPage(db.albumDao().pagingSource())

        assertEquals(1, page.data.size)
        assertEquals("Doujin Compilation", page.data[0].title)
        assertEquals("Farewell225", page.data[0].artistName)
    }

    @Test
    fun `album pagingSource tolerates album with no artist`() = runTest {
        db.albumDao().insert(
            AlbumEntity(id = "al2", title = "Unknown Artist Album", artistId = null, year = null, artworkPath = null),
        )

        val page = loadFirstPage(db.albumDao().pagingSource())

        assertEquals("Unknown Artist Album", page.data[0].title)
        assertEquals(null, page.data[0].artistName)
    }

    @Test
    fun `tracksForAlbum orders by disc then track number`() = runTest {
        db.albumDao().insert(AlbumEntity(id = "al1", title = "Album 1", artistId = null, year = null, artworkPath = null))
        db.albumDao().insert(AlbumEntity(id = "al2", title = "Album 2", artistId = null, year = null, artworkPath = null))
        db.trackDao().insertAll(
            listOf(
                trackFixture(id = "t2", albumId = "al1", discNo = 1, trackNo = 2),
                trackFixture(id = "t1", albumId = "al1", discNo = 1, trackNo = 1),
                trackFixture(id = "t3", albumId = "al1", discNo = 2, trackNo = 1),
                trackFixture(id = "other", albumId = "al2", discNo = 1, trackNo = 1),
            ),
        )

        val tracks = db.trackDao().tracksForAlbum("al1")

        assertEquals(listOf("t1", "t2", "t3"), tracks.map { it.id })
    }

    @Test
    fun `tracksForArtist returns only that artist's tracks`() = runTest {
        db.artistDao().insert(ArtistEntity(id = "a1", name = "Artist 1", sortName = "Artist 1"))
        db.artistDao().insert(ArtistEntity(id = "a2", name = "Artist 2", sortName = "Artist 2"))
        db.trackDao().insertAll(
            listOf(
                trackFixture(id = "t1", artistId = "a1"),
                trackFixture(id = "t2", artistId = "a2"),
            ),
        )

        val tracks = db.trackDao().tracksForArtist("a1")

        assertEquals(listOf("t1"), tracks.map { it.id })
    }

    private fun trackFixture(
        id: String,
        albumId: String? = null,
        artistId: String? = null,
        discNo: Int? = null,
        trackNo: Int? = null,
    ) = TrackEntity(
        id = id, title = id, artistId = artistId, albumId = albumId,
        trackNo = trackNo, discNo = discNo, durationMs = 1000,
        path = "/music/$id.flac", format = "flac", sizeBytes = 1,
        dateAdded = 1, lastPlayed = null, playCount = 0, genre = null,
    )
}
