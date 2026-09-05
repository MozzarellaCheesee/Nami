package dev.nami.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.nami.core.database.entity.TrackEntity
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
}
