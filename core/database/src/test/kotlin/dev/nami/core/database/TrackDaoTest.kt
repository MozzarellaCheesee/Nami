package dev.nami.core.database

import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.nami.core.database.dao.TrackDao
import dev.nami.core.database.entity.TrackEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@RunWith(RobolectricTestRunner::class)
class TrackDaoTest {
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

    private suspend fun loadFirstPage(source: PagingSource<Int, TrackDao.TrackWithArtwork>) =
        (source.load(PagingSource.LoadParams.Refresh(key = null, loadSize = 20, placeholdersEnabled = false))
            as PagingSource.LoadResult.Page)

    @Test
    fun `insertAll then findByPath returns inserted track`() = runTest {
        val track = TrackEntity(
            id = "t1", title = "Window View", artistId = null, albumId = null,
            trackNo = null, discNo = null, durationMs = 180_000,
            path = "/music/t1.flac", format = "flac", sizeBytes = 40_000_000,
            dateAdded = 1000L, lastPlayed = null, playCount = 0,
        )
        db.trackDao().insertAll(listOf(track))

        val found = db.trackDao().findByPath("/music/t1.flac")

        assertNotNull(found)
        assertEquals("t1", found.id)
    }

    @Test
    fun `insertAll ignores duplicate path on conflict`() = runTest {
        val track = TrackEntity(
            id = "t1", title = "A", artistId = null, albumId = null,
            trackNo = null, discNo = null, durationMs = 1000,
            path = "/music/dup.flac", format = "flac", sizeBytes = 1,
            dateAdded = 1, lastPlayed = null, playCount = 0,
        )
        val duplicate = track.copy(id = "t2", title = "B")

        db.trackDao().insertAll(listOf(track))
        db.trackDao().insertAll(listOf(duplicate))

        assertEquals(1, db.trackDao().count())
    }

    @Test
    fun `insertAll persists genre and findByPath returns it`() = runTest {
        val track = TrackEntity(
            id = "t1", title = "Window View", artistId = null, albumId = null,
            trackNo = null, discNo = null, durationMs = 180_000,
            path = "/music/genre.flac", format = "flac", sizeBytes = 1,
            dateAdded = 1, lastPlayed = null, playCount = 0, genre = "J-Rock",
        )
        db.trackDao().insertAll(listOf(track))

        val found = db.trackDao().findByPath("/music/genre.flac")

        assertEquals("J-Rock", found?.genre)
    }

    @Test
    fun `setDeletedAt hides track from pagingSource and shows it in trashedTracksFlow`() = runTest {
        db.trackDao().insertAll(listOf(
            TrackEntity(
                id = "t1", title = "Song", artistId = null, albumId = null, trackNo = null,
                discNo = null, durationMs = 1000, path = "/music/t1.flac", format = "flac",
                sizeBytes = 100, dateAdded = 1000, lastPlayed = null, playCount = 0,
            ),
        ))
        db.trackDao().setDeletedAt("t1", deletedAt = 2000, path = "/trash/t1.flac")

        val trashed = db.trackDao().trashedTracksFlow().first()
        assertEquals(1, trashed.size)
        assertEquals("/trash/t1.flac", trashed[0].path)
        assertEquals(2000L, trashed[0].deletedAt)

        val page = loadFirstPage(db.trackDao().pagingSource())
        assertEquals(emptyList(), page.data.map { it.track.id })
    }

    @Test
    fun `setDeletedAt with null restores the track`() = runTest {
        db.trackDao().insertAll(listOf(
            TrackEntity(
                id = "t1", title = "Song", artistId = null, albumId = null, trackNo = null,
                discNo = null, durationMs = 1000, path = "/music/t1.flac", format = "flac",
                sizeBytes = 100, dateAdded = 1000, lastPlayed = null, playCount = 0,
            ),
        ))
        db.trackDao().setDeletedAt("t1", deletedAt = 2000, path = "/trash/t1.flac")
        db.trackDao().setDeletedAt("t1", deletedAt = null, path = "/music/t1.flac")

        assertEquals(0, db.trackDao().trashedTracksFlow().first().size)
        assertEquals("/music/t1.flac", db.trackDao().findById("t1")?.path)
    }
}
