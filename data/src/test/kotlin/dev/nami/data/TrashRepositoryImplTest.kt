package dev.nami.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.nami.core.database.NamiDatabase
import dev.nami.core.database.entity.PlaylistEntity
import dev.nami.core.database.entity.TrackEntity
import dev.nami.core.model.PlaylistId
import dev.nami.core.model.TrackId
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class TrashRepositoryImplTest {
    private lateinit var db: NamiDatabase
    private lateinit var context: Context
    private lateinit var trashFileStore: TrashFileStore
    private lateinit var repo: TrashRepositoryImpl

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, NamiDatabase::class.java)
            .allowMainThreadQueries().build()
        trashFileStore = TrashFileStore(context)
        repo = TrashRepositoryImpl(db.trackDao(), db.playlistDao(), trashFileStore)
    }

    @After
    fun tearDown() = db.close()

    private fun trashedTrackFixture(id: String, path: String, deletedAt: Long) = TrackEntity(
        id = id, title = id, artistId = null, albumId = null, trackNo = null, discNo = null,
        durationMs = 1000, path = path, format = "flac", sizeBytes = 1,
        dateAdded = 1, lastPlayed = null, playCount = 0, genre = null, deletedAt = deletedAt,
    )

    @Test
    fun `restoreTrack moves file back and clears deletedAt`() = runTest {
        val trashDir = File(context.filesDir, "trash").apply { mkdirs() }
        val trashedFile = File(trashDir, "t1.flac").apply { writeText("audio-bytes") }
        db.trackDao().insertAll(listOf(trashedTrackFixture("t1", trashedFile.path, deletedAt = 1L)))

        repo.restoreTrack(TrackId("t1"))

        val restored = db.trackDao().findById("t1")
        assertNull(restored?.deletedAt)
        assertEquals(File(context.filesDir, "music/t1.flac").path, restored?.path)
        assertTrue(File(restored!!.path).exists())
        assertTrue(!trashedFile.exists())
    }

    @Test
    fun `deleteTrackForever removes the row and the file`() = runTest {
        val trashDir = File(context.filesDir, "trash").apply { mkdirs() }
        val trashedFile = File(trashDir, "t1.flac").apply { writeText("audio-bytes") }
        db.trackDao().insertAll(listOf(trashedTrackFixture("t1", trashedFile.path, deletedAt = 1L)))

        repo.deleteTrackForever(TrackId("t1"))

        assertNull(db.trackDao().findById("t1"))
        assertTrue(!trashedFile.exists())
    }

    @Test
    fun `purgeExpired removes only rows older than 30 days`() = runTest {
        val trashDir = File(context.filesDir, "trash").apply { mkdirs() }
        val oldFile = File(trashDir, "old.flac").apply { writeText("old") }
        val recentFile = File(trashDir, "recent.flac").apply { writeText("recent") }
        val now = System.currentTimeMillis()
        val thirtyOneDaysAgo = now - 31L * 24 * 60 * 60 * 1000
        val oneDayAgo = now - 1L * 24 * 60 * 60 * 1000
        db.trackDao().insertAll(
            listOf(
                trashedTrackFixture("old", oldFile.path, deletedAt = thirtyOneDaysAgo),
                trashedTrackFixture("recent", recentFile.path, deletedAt = oneDayAgo),
            ),
        )
        db.playlistDao().insert(PlaylistEntity(id = "old-pl", name = "Old", coverPath = null, createdAt = 1))
        db.playlistDao().insert(PlaylistEntity(id = "recent-pl", name = "Recent", coverPath = null, createdAt = 1))
        db.playlistDao().softDelete("old-pl", deletedAt = thirtyOneDaysAgo)
        db.playlistDao().softDelete("recent-pl", deletedAt = oneDayAgo)

        repo.purgeExpired()

        assertNull(db.trackDao().findById("old"))
        assertTrue(db.trackDao().findById("recent") != null)
        assertNull(db.playlistDao().findById("old-pl"))
        assertTrue(db.playlistDao().findById("recent-pl") != null)
    }
}
